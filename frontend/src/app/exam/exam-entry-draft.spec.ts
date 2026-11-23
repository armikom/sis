import { describe, it, expect } from 'vitest';
import type { QuestionDto } from '../core/api.models';
import { draftKey, draftSheet, makeDraft, parseDrafts, questionsKey, serializeDrafts, withDraft, type Drafts } from './exam-entry-draft';
import { emptySheet, updateCell } from './exam-entry.logic';

const choice = (id: number, position: number, choiceCount = 4): QuestionDto =>
  ({ id, position, type: 'CHOICE', text: null, topic: null, maxPoints: 4, choiceCount, correctChoice: null });
const open = (id: number, position: number): QuestionDto =>
  ({ id, position, type: 'OPEN', text: null, topic: null, maxPoints: 10, choiceCount: null, correctChoice: null });

const qs = [choice(1, 1), choice(2, 2), open(3, 3)];

function sample(): Drafts {
  let s = emptySheet(3);
  s = updateCell(s, 0, { entered: true, answer: 'B' });
  s = updateCell(s, 2, { points: '7,5', text: 'answer' });
  return { '1001': makeDraft({ ...s, active: 1 }, '["","",[null,""]]', 'Ada Example') };
}

describe('exam entry drafts', () => {
  it('uses one storage key per exam', () => {
    expect(draftKey(42)).toBe('sis.examEntryDraft.42');
  });

  it('round-trips through storage text', () => {
    const d = sample();
    const back = parseDrafts(serializeDrafts(qs, d), qs);
    expect(back).toEqual(d);
    expect(draftSheet(back['1001']).cells[0]).toEqual({ entered: true, answer: 'B', points: '', text: '' });
    expect(draftSheet(back['1001']).active).toBe(1);
  });

  it('serializes an empty map as null (remove the entry)', () => {
    expect(serializeDrafts(qs, {})).toBeNull();
  });

  it('drops every draft when the questions changed', () => {
    const raw = serializeDrafts(qs, sample());
    expect(parseDrafts(raw, [choice(1, 1), choice(2, 2, 5), open(3, 3)])).toEqual({});
    expect(parseDrafts(raw, [choice(1, 1), choice(2, 2)])).toEqual({});
    expect(questionsKey(qs)).not.toBe(questionsKey([choice(1, 1), choice(2, 2), choice(3, 3)]));
  });

  it('ignores malformed storage content', () => {
    expect(parseDrafts(null, qs)).toEqual({});
    expect(parseDrafts('not json', qs)).toEqual({});
    expect(parseDrafts('[]', qs)).toEqual({});
    expect(parseDrafts(JSON.stringify({ v: 2, questions: questionsKey(qs), students: {} }), qs)).toEqual({});
    const bad = { v: 1, questions: questionsKey(qs), students: { a: { base: 1, cells: [] }, b: { base: '', cells: [{}, {}] } } };
    expect(parseDrafts(JSON.stringify(bad), qs)).toEqual({});
  });

  it('cleans field types and clamps the active index', () => {
    const raw = JSON.stringify({ v: 1, questions: questionsKey(qs), students: {
      '7': { base: 'x', cells: [{ entered: 'yes', answer: 3, points: 5 }, { entered: true, answer: 'C' }, { text: 'hi' }], active: 99 },
    } });
    expect(parseDrafts(raw, qs)).toEqual({ '7': {
      base: 'x', name: '',
      cells: [
        { entered: false, answer: null, points: '', text: '' },
        { entered: true, answer: 'C', points: '', text: '' },
        { entered: false, answer: null, points: '', text: 'hi' },
      ],
      active: 2,
    } });
  });

  it('adds, replaces and removes a student draft without touching the others', () => {
    const d = sample();
    const two = withDraft(d, '1002', d['1001']);
    expect(Object.keys(two).sort()).toEqual(['1001', '1002']);
    expect(withDraft(two, '1001', null)).toEqual({ '1002': d['1001'] });
    expect(withDraft(d, '9999', null)).toBe(d);
  });

  it('copies cells so later sheet edits do not leak into a stored draft', () => {
    const s = updateCell(emptySheet(3), 0, { entered: true, answer: 'A' });
    const d = makeDraft(s, '', null);
    s.cells[0].answer = 'D';
    expect(d.cells[0].answer).toBe('A');
    expect(d.name).toBe('');
  });
});
