import { describe, it, expect } from 'vitest';
import {
  applyKey, bulkRows, buildRequest, destructiveChanges, fromDto, gatherGroup, groupsFromDto, hasContent, optionRows, pruneGroups,
  rowLabels, setOption, splitGroups, keyString, moveRow, newRow, parseKey, totalPoints, validate, validLabels,
} from './exam-edit.logic';
import type { QuestionDto } from '../core/api.models';

describe('bulkRows', () => {
  it('generates N rows with the given settings', () => {
    const rows = bulkRows({ count: 25, type: 'CHOICE', labels: 'ABCD', points: 4, topic: ' Türev ' });
    expect(rows).toHaveLength(25);
    expect(rows[0]).toMatchObject({ type: 'CHOICE', labels: 'ABCD', maxPoints: 4, topic: 'Türev', id: null });
    expect(new Set(rows.map(r => r.key)).size).toBe(25);
    expect(totalPoints(rows)).toBe(100);
  });
  it('clamps count and falls back to A–D for unusable labels', () => {
    expect(bulkRows({ count: 999, type: 'OPEN', labels: 'ABCD', points: 1, topic: '' })).toHaveLength(200);
    expect(bulkRows({ count: -3, type: 'OPEN', labels: 'ABCD', points: 1, topic: '' })).toHaveLength(0);
    expect(bulkRows({ count: 1, type: 'CHOICE', labels: 'A', points: 1, topic: '' })[0].labels).toBe('ABCD');
    expect(bulkRows({ count: 1, type: 'CHOICE', labels: 'd/y', points: 1, topic: '' })[0].labels).toBe('DY');
  });
});

describe('labels', () => {
  it('normalises typed label sets', () => {
    expect(validLabels('a b c')).toBe('ABC');
    expect(validLabels('T, F')).toBe('TF');
    expect(validLabels('1/0')).toBe('10');
    expect(validLabels('AAB')).toBe('ABCD');
    expect(validLabels('ABCDEFGHIJK')).toBe('ABCD');
  });
  it('reads labels from a loaded question, old servers included', () => {
    const base = { id: 1, position: 1, type: 'CHOICE' as const, text: null, topic: null, maxPoints: 4, choiceCount: 5, correctChoice: 'A' };
    expect(fromDto(base).labels).toBe('ABCDE');
    expect(fromDto({ ...base, choiceLabels: 'DY', choiceCount: 2 }).labels).toBe('DY');
    expect(fromDto({ ...base, type: 'OPEN' }).labels).toBe('ABCD');
  });
});

describe('answer key', () => {
  it('parses letters and digits only', () => {
    expect(parseKey('a b,c-d 1 0\nE.')).toBe('ABCD10E');
  });
  it('applies in order to CHOICE questions only', () => {
    const rows = [newRow(), newRow({ type: 'OPEN' }), newRow(), newRow({ labels: 'DY' })];
    const r = applyKey(rows, 'A C Y');
    expect(r.applied).toBe(3);
    expect(r.choiceTotal).toBe(3);
    expect(r.extra).toBe(0);
    expect(r.rows.map(x => x.correctChoice)).toEqual(['A', null, 'C', 'Y']);
    expect(keyString(r.rows)).toBe('ACY');
  });
  it('flags out-of-range letters and extras, keeps unreached questions', () => {
    const rows = [newRow({ labels: 'ABC', correctChoice: 'A' }), newRow({ correctChoice: 'B' }), newRow({ correctChoice: 'C' })];
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

describe('groups', () => {
  const g1 = { key: 'g1', id: 1, text: 'stem', html: '' };
  it('numbers rows with a group as one printed number', () => {
    const rows = [newRow(), newRow({ groupKey: 'g1' }), newRow({ groupKey: 'g1', subLabel: 'x' }), newRow()];
    expect(rowLabels(rows)).toEqual(['1', '2a', '2x', '3']);
  });
  it('detects split groups and gathers them', () => {
    const rows = [newRow({ groupKey: 'g1' }), newRow(), newRow({ groupKey: 'g1' })];
    expect(splitGroups(rows)).toEqual(['g1']);
    const fixed = gatherGroup(rows, 'g1');
    expect(fixed.map(r => r.groupKey)).toEqual(['g1', 'g1', null]);
    expect(splitGroups(fixed)).toEqual([]);
    expect(validate('a', rows, [g1]).some(e => e.includes('ardışık'))).toBe(true);
  });
  it('validates group references and sub-labels', () => {
    expect(validate('a', [newRow({ groupKey: 'nope' })], [g1])).toHaveLength(1);
    expect(validate('a', [newRow({ groupKey: 'g1', subLabel: 'a' }), newRow({ groupKey: 'g1', subLabel: 'a' })], [g1])).toHaveLength(1);
    expect(validate('a', [newRow({ groupKey: 'g1', subLabel: 'x'.repeat(9) })], [g1])).toHaveLength(1);
    expect(validate('a', [newRow({ groupKey: 'g1' }), newRow({ groupKey: 'g1' })], [g1])).toEqual([]);
  });
  it('drops groups nobody refers to and sends the rest with refs', () => {
    const rows = [newRow({ groupKey: 'g1', subLabel: ' b ' }), newRow({ groupKey: 'g1' })];
    const groups = [g1, { key: 'n2', id: null, text: '', html: '<p>x</p>' }];
    expect(pruneGroups(rows, groups).map(g => g.key)).toEqual(['g1']);
    const req = buildRequest('V', '', rows, groups);
    expect(req.groups).toEqual([{ id: 1, ref: 'g1', text: 'stem', html: null }]);
    expect(req.questions[0]).toMatchObject({ groupRef: 'g1', subLabel: 'b' });
    expect(req.questions[1]).toMatchObject({ groupRef: 'g1', subLabel: null });
    expect('groups' in buildRequest('V', '', [newRow()], groups)).toBe(false);
  });
  it('loads groups from the exam', () => {
    expect(groupsFromDto({ groups: [{ id: 7, position: 2, label: '2', text: null, html: '<b>x</b>', questionIds: [] }] }))
      .toEqual([{ key: 'g7', id: 7, text: '', html: '<b>x</b>' }]);
    expect(groupsFromDto({})).toEqual([]);
  });
});

describe('validate', () => {
  it('accepts a valid exam', () => {
    expect(validate('Vize', [newRow({ correctChoice: 'D' })])).toEqual([]);
    expect(validate('Vize', [newRow({ labels: 'DY', correctChoice: 'Y' })])).toEqual([]);
    expect(validate('Vize', [])).toEqual([]);
  });
  it('reports each problem', () => {
    expect(validate('  ', [])).toHaveLength(1);
    expect(validate('x'.repeat(201), [])).toHaveLength(1);
    expect(validate('a', [newRow({ maxPoints: 0 })])).toHaveLength(1);
    expect(validate('a', [newRow({ maxPoints: null })])).toHaveLength(1);
    expect(validate('a', [newRow({ maxPoints: 1001 })])).toHaveLength(1);
    expect(validate('a', [newRow({ labels: 'AAB' })])).toHaveLength(1);
    expect(validate('a', [newRow({ labels: 'ABC', correctChoice: 'D' })])).toHaveLength(1);
    expect(validate('a', [newRow({ text: 'x'.repeat(20001) })])).toHaveLength(1);
    expect(validate('a', Array.from({ length: 201 }, () => newRow()))).toHaveLength(1);
  });
  it('ignores choice fields on non-CHOICE rows', () => {
    expect(validate('a', [newRow({ type: 'OPEN', labels: 'Z', correctChoice: 'Z' })])).toEqual([]);
  });
});

describe('buildRequest', () => {
  it('keeps ids, normalises blanks and nulls choice fields for other types', () => {
    const req = buildRequest(' Vize ', '', [
      newRow({ id: 12, topic: ' Türev ', correctChoice: 'B' }),
      newRow({ type: 'OPEN', text: '  ', labels: 'ABCDE', correctChoice: 'A', maxPoints: 10 }),
      newRow({ labels: 'd y', correctChoice: 'Y', maxPoints: 2 }),
    ]);
    expect(req.title).toBe('Vize');
    expect(req.examDate).toBeNull();
    expect(req.questions[0]).toEqual({ id: 12, type: 'CHOICE', text: null, topic: 'Türev', maxPoints: 4, choiceCount: 4, choiceLabels: 'ABCD', correctChoice: 'B', topicIds: [] });
    expect(req.questions[1]).toEqual({ type: 'OPEN', text: null, topic: null, maxPoints: 10, choiceCount: null, choiceLabels: null, correctChoice: null, topicIds: [] });
    expect(req.questions[2]).toMatchObject({ choiceCount: 2, choiceLabels: 'DY', correctChoice: 'Y' });
  });

  it('leaves options, explanation and html out unless the content was opened or already recorded', () => {
    const untouched = buildRequest('V', '', [newRow({ options: [{ letter: 'A', text: 'x', rationale: '' }] })]).questions[0];
    expect('options' in untouched).toBe(false);
    expect('explanation' in untouched).toBe(false);
    expect('html' in untouched).toBe(false);

    const opened = buildRequest('V', '', [newRow({ contentEdited: true, labels: 'AB', explanation: ' ', options: [
      { letter: 'A', text: 'yes', rationale: 'because' }, { letter: 'B', text: '', rationale: '' }, { letter: 'C', text: 'hidden', rationale: '' },
    ] })]).questions[0];
    expect(opened.options).toEqual([{ letter: 'A', text: 'yes', rationale: 'because' }]);
    expect(opened.explanation).toBeNull();
    expect(opened.html).toBe('');

    const open = buildRequest('V', '', [newRow({ type: 'OPEN', contentEdited: true, explanation: 'Why', html: '<p>h</p>', options: [{ letter: 'A', text: 'x', rationale: '' }] })]).questions[0];
    expect(open.options).toEqual([]);
    expect(open.explanation).toBe('Why');
    expect(open.html).toBe('<p>h</p>');
  });

  it('marks loaded questions with content as edited, so a save keeps sending it', () => {
    const base = { id: 1, position: 1, type: 'CHOICE' as const, text: null, topic: null, maxPoints: 4, choiceCount: 4, correctChoice: 'A' };
    expect(fromDto({ ...base, options: [{ letter: 'b', text: 'two', rationale: null }] })).toMatchObject({ contentEdited: true, options: [{ letter: 'B', text: 'two', rationale: '' }] });
    expect(fromDto({ ...base, options: [], explanation: null }).contentEdited).toBe(false);
    expect(fromDto({ ...base, html: '<p>x</p>' })).toMatchObject({ contentEdited: true, html: '<p>x</p>' });
    expect(fromDto({ ...base, groupId: 3, subLabel: 'b' })).toMatchObject({ groupKey: 'g3', subLabel: 'b' });
  });
});

describe('content helpers', () => {
  it('shows one option row per label and edits them by letter', () => {
    const r = newRow({ labels: 'ABC', options: [{ letter: 'B', text: 'b', rationale: '' }, { letter: 'E', text: 'e', rationale: '' }] });
    expect(optionRows(r).map(o => `${o.letter}:${o.text}`)).toEqual(['A:', 'B:b', 'C:']);
    expect(optionRows(newRow({ labels: 'DY' })).map(o => o.letter)).toEqual(['D', 'Y']);
    expect(setOption(r.options, 'A', { rationale: 'r' }).map(o => o.letter)).toEqual(['A', 'B', 'E']);
    expect(hasContent(r)).toBe(true);
    expect(hasContent(newRow({ labels: 'AB', options: [{ letter: 'C', text: 'x', rationale: '' }] }))).toBe(false);
    expect(hasContent(newRow({ html: '<p>x</p>' }))).toBe(true);
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
