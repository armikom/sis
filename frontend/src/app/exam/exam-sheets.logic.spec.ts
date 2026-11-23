import { describe, it, expect } from 'vitest';
import { buildSheets, itemStatus, studentSheet } from './exam-sheets.logic';
import type { ExamDto, ExamResultDto } from '../core/api.models';

const exam: ExamDto = {
  id: 1, courseId: 1, courseCode: 'C', courseName: 'Course', title: 'Vize', examDate: null, totalPoints: 12, resultCount: 2, createdAt: '',
  groups: [{ id: 9, position: 2, label: '2', text: 'Common stem', html: null, questionIds: [2, 3] }],
  questions: [
    { id: 1, position: 1, label: '1', type: 'CHOICE', text: null, topic: null, maxPoints: 4, choiceCount: 4, choiceLabels: 'ABCD', correctChoice: 'B',
      options: [{ letter: 'A', text: 'one', rationale: null }, { letter: 'B', text: 'two', rationale: null }] },
    { id: 2, position: 2, label: '2a', groupId: 9, type: 'CHOICE', text: 'True?', topic: null, maxPoints: 2, choiceCount: 2, choiceLabels: 'DY', correctChoice: 'D' },
    { id: 3, position: 3, label: '2b', groupId: 9, type: 'CHOICE', text: null, topic: null, maxPoints: 2, choiceCount: 2, choiceLabels: 'DY', correctChoice: null },
    { id: 4, position: 4, label: '3', type: 'OPEN', text: null, topic: null, maxPoints: 4, choiceCount: null, correctChoice: null },
  ],
};
const result = (no: string, answers: ExamResultDto['answers'], total: number): ExamResultDto =>
  ({ id: 1, studentNo: no, fullName: 'Snapshot', note: null, totalPoints: total, gradedCount: 3, complete: false, updatedAt: '', answers });

describe('exam sheets', () => {
  it('marks each item against the key and counts choice outcomes', () => {
    const s = studentSheet(exam, result('2', [
      { questionId: 1, answer: 'A', points: 0 }, { questionId: 2, answer: 'D', points: 2 }, { questionId: 3, answer: 'Y', points: null },
      { questionId: 4, answer: 'free text', points: 3 },
    ], 5), 'Roster Name');
    expect(s.fullName).toBe('Roster Name');
    expect(s.items.map(i => i.label)).toEqual(['1', '2a', '2b', '3']);
    expect(s.items.map(i => i.status)).toEqual(['wrong', 'right', 'ungraded', 'partial']);
    expect(s.items[1].groupHead?.id).toBe(9);
    expect(s.items[2].groupHead).toBeNull();
    expect(s.items[0].options).toEqual([
      { letter: 'A', text: 'one', correct: false, mine: true }, { letter: 'B', text: 'two', correct: true, mine: false },
      { letter: 'C', text: null, correct: false, mine: false }, { letter: 'D', text: null, correct: false, mine: false },
    ]);
    expect(s.items[1].options.map(o => o.letter)).toEqual(['D', 'Y']);
    expect([s.correct, s.wrong, s.blank]).toEqual([1, 1, 0]);
    expect(s.percent).toBeCloseTo(5 / 12);
  });
  it('treats missing choice answers as blank and orders students by number', () => {
    const sheets = buildSheets(exam, [result('10', [], 0), result('9', [{ questionId: 1, answer: null, points: 0 }], 0)], []);
    expect(sheets.map(s => s.studentNo)).toEqual(['9', '10']);
    expect(sheets[0].items[0].status).toBe('blank');
    expect(sheets[0].blank).toBe(3);
    expect(buildSheets(exam, [result('10', [], 0), result('9', [], 0)], [], '10')).toHaveLength(1);
  });
  it('status of non-choice items follows the points', () => {
    const q = exam.questions[3];
    expect(itemStatus(q, 'x', null)).toBe('ungraded');
    expect(itemStatus(q, 'x', 4)).toBe('right');
    expect(itemStatus(q, 'x', 0)).toBe('wrong');
  });
});
