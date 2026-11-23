import { describe, it, expect } from 'vitest';
import { autoSubLabel, describeLabels, normalizeLabels, optionLabels, printedLabels, questionLabel, reviewLabels } from './question-labels';

describe('question labels', () => {
  it('derives option labels from stored labels or the count', () => {
    expect(optionLabels({ type: 'CHOICE', choiceCount: 4 })).toEqual(['A', 'B', 'C', 'D']);
    expect(optionLabels({ type: 'CHOICE', choiceCount: 4, choiceLabels: 'DY' })).toEqual(['D', 'Y']);
    expect(optionLabels({ type: 'OPEN', choiceCount: 4 })).toEqual([]);
    expect(optionLabels({ type: 'CHOICE', choiceCount: null })).toEqual([]);
  });
  it('normalises and describes label sets', () => {
    expect(normalizeLabels(' d / y ')).toBe('DY');
    expect(normalizeLabels('1,0')).toBe('10');
    expect(normalizeLabels('abcdefghij')).toBe('ABCDEFGHIJ');
    expect(normalizeLabels('abcdefghijk')).toBeNull();
    expect(normalizeLabels('A')).toBeNull();
    expect(normalizeLabels('AA')).toBeNull();
    expect(normalizeLabels('A-B')).toBeNull();
    expect(describeLabels('ABCD')).toBe('A–D');
    expect(describeLabels('DY')).toBe('D/Y');
    expect(describeLabels('10')).toBe('1/0');
  });
  it('numbers groups once: 1, 2, 3a, 3b, 4', () => {
    const { labels, groupNumbers } = printedLabels([
      { groupKey: null }, { groupKey: null }, { groupKey: 'g' }, { groupKey: 'g', subLabel: 'z' }, { groupKey: null }, { groupKey: 'h' },
    ]);
    expect(labels).toEqual(['1', '2', '3a', '3z', '4', '5a']);
    expect(groupNumbers.get('g')).toBe(3);
    expect(groupNumbers.get('h')).toBe(5);
  });
  it('auto sub-labels run a..z, aa, ab', () => {
    expect([0, 1, 25, 26, 27].map(autoSubLabel)).toEqual(['a', 'b', 'z', 'aa', 'ab']);
  });
  it('falls back to the position without a server label', () => {
    expect(questionLabel({ position: 7 })).toBe('7');
    expect(questionLabel({ position: 7, label: '3b' })).toBe('3b');
    expect(questionLabel({ position: 7, label: ' ' })).toBe('7');
  });
  it('review labels fall back to the letters seen', () => {
    expect(reviewLabels({ type: 'CHOICE', choiceLabels: '10' })).toEqual(['1', '0']);
    expect(reviewLabels({ type: 'CHOICE', options: [{ letter: 'b', text: null, rationale: null }], stats: { answered: 1, correctShare: null, averagePoints: null, choiceCounts: { A: 1, blank: 0 } } })).toEqual(['A', 'B']);
  });
});
