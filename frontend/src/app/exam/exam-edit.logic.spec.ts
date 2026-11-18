import { describe, it, expect } from 'vitest';
import {
  applyKey, bulkRows, buildRequest, destructiveChanges, fromDto, hasContent, optionRows, setOption, keyString, moveRow, newRow, parseKey, totalPoints, validate,
} from './exam-edit.logic';
import type { QuestionDto } from '../core/api.models';

describe('bulkRows', () => {
  it('generates N rows with the given settings', () => {
    const rows = bulkRows({ count: 25, type: 'CHOICE', choiceCount: 4, points: 4, topic: ' Türev ' });
    expect(rows).toHaveLength(25);
    expect(rows[0]).toMatchObject({ type: 'CHOICE', choiceCount: 4, maxPoints: 4, topic: 'Türev', id: null });
    expect(new Set(rows.map(r => r.key)).size).toBe(25);
    expect(totalPoints(rows)).toBe(100);
  });
  it('clamps count and choice count', () => {
    expect(bulkRows({ count: 999, type: 'OPEN', choiceCount: 9, points: 1, topic: '' })).toHaveLength(200);
    expect(bulkRows({ count: -3, type: 'OPEN', choiceCount: 4, points: 1, topic: '' })).toHaveLength(0);
    expect(bulkRows({ count: 1, type: 'CHOICE', choiceCount: 9, points: 1, topic: '' })[0].choiceCount).toBe(6);
  });
});

describe('answer key', () => {
  it('parses letters only', () => {
    expect(parseKey('a b,c-d 1 2\nE.')).toBe('ABCDE');
  });
  it('applies in order to CHOICE questions only', () => {
    const rows = [newRow(), newRow({ type: 'OPEN' }), newRow(), newRow()];
    const r = applyKey(rows, 'A C D');
    expect(r.applied).toBe(3);
    expect(r.choiceTotal).toBe(3);
    expect(r.extra).toBe(0);
    expect(r.rows.map(x => x.correctChoice)).toEqual(['A', null, 'C', 'D']);
    expect(keyString(r.rows)).toBe('ACD');
  });
  it('flags out-of-range letters and extras, keeps unreached questions', () => {
    const rows = [newRow({ choiceCount: 3, correctChoice: 'A' }), newRow({ correctChoice: 'B' }), newRow({ correctChoice: 'C' })];
    const r = applyKey(rows, 'DB');
    expect(r.outOfRange).toEqual([1]);
    expect(r.applied).toBe(1);
    expect(r.rows.map(x => x.correctChoice)).toEqual([null, 'B', 'C']);
    expect(applyKey(rows, 'ABCDEF').extra).toBe(3);
  });
  it('shows unset answers with a placeholder', () => {
    expect(keyString([newRow({ correctChoice: 'A' }), newRow()])).toBe('A·');
  });
});

describe('validate', () => {
  it('accepts a valid exam', () => {
    expect(validate('Vize', [newRow({ correctChoice: 'D' })])).toEqual([]);
    expect(validate('Vize', [])).toEqual([]);
  });
  it('reports each problem', () => {
    expect(validate('  ', [])).toHaveLength(1);
    expect(validate('x'.repeat(201), [])).toHaveLength(1);
    expect(validate('a', [newRow({ maxPoints: 0 })])).toHaveLength(1);
    expect(validate('a', [newRow({ maxPoints: null })])).toHaveLength(1);
    expect(validate('a', [newRow({ maxPoints: 1001 })])).toHaveLength(1);
    expect(validate('a', [newRow({ choiceCount: 7 })])).toHaveLength(1);
    expect(validate('a', [newRow({ choiceCount: 3, correctChoice: 'D' })])).toHaveLength(1);
    expect(validate('a', [newRow({ text: 'x'.repeat(20001) })])).toHaveLength(1);
    expect(validate('a', Array.from({ length: 201 }, () => newRow()))).toHaveLength(1);
  });
  it('ignores choice fields on non-CHOICE rows', () => {
    expect(validate('a', [newRow({ type: 'OPEN', choiceCount: 99, correctChoice: 'Z' })])).toEqual([]);
  });
});

describe('buildRequest', () => {
  it('keeps ids, normalises blanks and nulls choice fields for other types', () => {
    const req = buildRequest(' Vize ', '', [
      newRow({ id: 12, topic: ' Türev ', correctChoice: 'B' }),
      newRow({ type: 'OPEN', text: '  ', choiceCount: 5, correctChoice: 'A', maxPoints: 10 }),
    ]);
    expect(req.title).toBe('Vize');
    expect(req.examDate).toBeNull();
    expect(req.questions[0]).toEqual({ id: 12, type: 'CHOICE', text: null, topic: 'Türev', maxPoints: 4, choiceCount: 4, correctChoice: 'B', topicIds: [] });
    expect(req.questions[1]).toEqual({ type: 'OPEN', text: null, topic: null, maxPoints: 10, choiceCount: null, correctChoice: null, topicIds: [] });
  });

  it('leaves options and explanation out unless the content was opened or already recorded', () => {
    const untouched = buildRequest('V', '', [newRow({ options: [{ letter: 'A', text: 'x', rationale: '' }] })]).questions[0];
    expect('options' in untouched).toBe(false);
    expect('explanation' in untouched).toBe(false);

    const opened = buildRequest('V', '', [newRow({ contentEdited: true, choiceCount: 2, explanation: ' ', options: [
      { letter: 'A', text: 'yes', rationale: 'because' }, { letter: 'B', text: '', rationale: '' }, { letter: 'C', text: 'hidden', rationale: '' },
    ] })]).questions[0];
    expect(opened.options).toEqual([{ letter: 'A', text: 'yes', rationale: 'because' }]);
    expect(opened.explanation).toBeNull();

    const open = buildRequest('V', '', [newRow({ type: 'OPEN', contentEdited: true, explanation: 'Why', options: [{ letter: 'A', text: 'x', rationale: '' }] })]).questions[0];
    expect(open.options).toEqual([]);
    expect(open.explanation).toBe('Why');
  });

  it('marks loaded questions with content as edited, so a save keeps sending it', () => {
    const base = { id: 1, position: 1, type: 'CHOICE' as const, text: null, topic: null, maxPoints: 4, choiceCount: 4, correctChoice: 'A' };
    expect(fromDto({ ...base, options: [{ letter: 'b', text: 'two', rationale: null }] })).toMatchObject({ contentEdited: true, options: [{ letter: 'B', text: 'two', rationale: '' }] });
    expect(fromDto({ ...base, options: [], explanation: null }).contentEdited).toBe(false);
  });
});

describe('content helpers', () => {
  it('shows one option row per letter up to choiceCount and edits them by letter', () => {
    const r = newRow({ choiceCount: 3, options: [{ letter: 'B', text: 'b', rationale: '' }, { letter: 'E', text: 'e', rationale: '' }] });
    expect(optionRows(r).map(o => `${o.letter}:${o.text}`)).toEqual(['A:', 'B:b', 'C:']);
    expect(setOption(r.options, 'A', { rationale: 'r' }).map(o => o.letter)).toEqual(['A', 'B', 'E']);
    expect(hasContent(r)).toBe(true);
    expect(hasContent(newRow({ choiceCount: 2, options: [{ letter: 'C', text: 'x', rationale: '' }] }))).toBe(false);
  });
});

describe('destructiveChanges', () => {
  const q = (id: number, type: 'CHOICE' | 'OPEN'): QuestionDto => ({ id, position: id, type, text: null, topic: null, maxPoints: 1, choiceCount: null, correctChoice: null });
  it('counts removed and type-changed questions', () => {
    const orig = [q(1, 'CHOICE'), q(2, 'CHOICE'), q(3, 'OPEN')];
    const rows = [newRow({ id: 1 }), newRow({ id: 3, type: 'CHOICE' }), newRow()];
    expect(destructiveChanges(orig, rows)).toEqual({ removed: 1, typeChanged: 1 });
  });
});

describe('moveRow', () => {
  it('swaps neighbours and ignores out-of-bounds moves', () => {
    expect(moveRow([1, 2, 3], 1, -1)).toEqual([2, 1, 3]);
    expect(moveRow([1, 2, 3], 2, 1)).toEqual([1, 2, 3]);
  });
});
