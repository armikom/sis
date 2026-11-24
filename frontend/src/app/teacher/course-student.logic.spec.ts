import { describe, it, expect } from 'vitest';
import type { SessionAttendanceDto, StudentAttendanceState, StudentExamDto } from '../core/api.models';
import type { StudentDetail } from '../pool/pool.models';
import { attendanceSummary, examOverall, poolSummary } from './course-student.logic';

const row = (state: StudentAttendanceState): SessionAttendanceDto => ({
  sessionId: 1, title: 'H', date: '2026-10-01T09:00:00Z', sessionStatus: 'CLOSED', state,
  source: null, flags: [], teacherNote: null, checkedInAt: null,
});

describe('attendanceSummary', () => {
  it('counts states; pending is not presence', () => {
    const s = attendanceSummary([row('PRESENT'), row('PRESENT'), row('PENDING'), row('REJECTED'), row('ABSENT')]);
    expect(s).toEqual({ total: 5, present: 2, pending: 1, rejected: 1, excused: 0, absent: 1, rate: 0.4 });
  });
  it('excused sessions are neither absent nor part of the rate base', () => {
    const s = attendanceSummary([row('PRESENT'), row('EXCUSED'), row('ABSENT')]);
    expect(s).toEqual({ total: 3, present: 1, pending: 0, rejected: 0, excused: 1, absent: 1, rate: 0.5 });
  });
  it('null rate when every session is excused', () => expect(attendanceSummary([row('EXCUSED')]).rate).toBeNull());
  it('null rate without sessions', () => expect(attendanceSummary([]).rate).toBeNull());
});

describe('examOverall', () => {
  const e = (totalPoints: number, maxPoints: number): StudentExamDto =>
    ({ examId: 1, title: 'V', examDate: null, totalPoints, maxPoints, percent: null, complete: true });
  it('weights by possible points', () => expect(examOverall([e(10, 20), e(30, 30)])).toBeCloseTo(0.8));
  it('null without exams', () => expect(examOverall([])).toBeNull());
});

describe('poolSummary', () => {
  it('sums attempts across chapters and skips hidden comments', () => {
    const d: StudentDetail = {
      id: 1, full_name: 'A', student_no: '1', email: null, nickname: 'n', last_login_at: null,
      questions: [
        { id: 1, chapter_number: 1, status: 'APPROVED', created_at: '', stem: '' },
        { id: 2, chapter_number: 2, status: 'NEEDS_REVISION', created_at: '', stem: '' },
        { id: 3, chapter_number: 2, status: 'REJECTED', created_at: '', stem: '' },
      ],
      attempts_by_chapter: [{ chapter_number: 1, attempts: 4, correct: 3 }, { chapter_number: 2, attempts: 6, correct: 2 }],
      comments: [
        { id: 1, question_id: 1, stem: '', body: '', created_at: '', deleted: false },
        { id: 2, question_id: 1, stem: '', body: '', created_at: '', deleted: true },
      ],
      flags: [],
    };
    expect(poolSummary(d)).toEqual({ written: 3, approved: 1, pending: 1, attempts: 10, correct: 5, rate: 0.5, comments: 1, flags: 0 });
  });
});
