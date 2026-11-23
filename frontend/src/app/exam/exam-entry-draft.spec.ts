import { describe, it, expect } from 'vitest';
import type { ExamDraftDto, QuestionDto } from '../core/api.models';
import {
  draftKey, draftSheet, fromServer, makeDraft, mergeDrafts, parseDrafts, questionsKey, sameContent, serializeDrafts, toServerSheet, withDraft,
  type Drafts, type StudentDraft,
} from './exam-entry-draft';
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

describe('server copy of drafts', () => {
  const base = sample()['1001'];
  const dto = (studentNo: string, d: StudentDraft, updatedAt: string, layout = qs): ExamDraftDto =>
    ({ studentNo, sheet: toServerSheet(layout, d), updatedAt, updatedBy: 'Staff Example' });
  const edited: StudentDraft = { ...base, cells: base.cells.map((c, i) => (i === 1 ? { ...c, entered: true, answer: 'A' } : c)) };

  it('round-trips a draft through the server body, marked as synced', () => {
    expect(fromServer(dto('1001', base, 't1'), qs)).toEqual({ ...base, synced: 't1', by: 'Staff Example' });
  });

  it('rejects server drafts of another question layout or shape', () => {
    expect(fromServer(dto('1001', base, 't1', [choice(1, 1), choice(2, 2), choice(3, 3)]), qs)).toBeNull();
    expect(fromServer({ studentNo: '1', sheet: 'x', updatedAt: 't', updatedBy: '' }, qs)).toBeNull();
  });

  it('keeps sync markers through local storage', () => {
    const d = { '1001': { ...base, synced: 't1', by: 'Staff Example' } };
    expect(parseDrafts(serializeDrafts(qs, d), qs)).toEqual(d);
  });

  it('compares content without the active question or sync markers', () => {
    expect(sameContent(base, { ...base, active: 0, synced: 'x' })).toBe(true);
    expect(sameContent(base, edited)).toBe(false);
    expect(sameContent(undefined, null)).toBe(true);
    expect(sameContent(base, null)).toBe(false);
  });

  it('unsynced local edits win and are pushed', () => {
    const m = mergeDrafts({ '1001': edited }, [dto('1001', base, 't2')], qs);
    expect(m.drafts['1001']).toBe(edited);
    expect(m.push).toEqual(['1001']);
  });

  it('a synced local draft follows the server', () => {
    const local = { ...base, synced: 't1' };
    // unchanged on the server
    expect(mergeDrafts({ '1001': local }, [dto('1001', base, 't1')], qs)).toEqual({ drafts: { '1001': local }, push: [] });
    // changed elsewhere
    const m = mergeDrafts({ '1001': local }, [dto('1001', edited, 't2')], qs);
    expect(m.drafts['1001']).toEqual({ ...edited, synced: 't2', by: 'Staff Example' });
    expect(m.push).toEqual([]);
    // saved or discarded elsewhere
    expect(mergeDrafts({ '1001': local }, [], qs)).toEqual({ drafts: {}, push: [] });
  });

  it('takes over unknown server drafts and deletes ones of another layout', () => {
    const m = mergeDrafts({}, [dto('1002', base, 't3'), dto('1003', base, 't3', [choice(9, 1)])], qs);
    expect(Object.keys(m.drafts)).toEqual(['1002']);
    expect(m.push).toEqual(['1003']);
  });
});
