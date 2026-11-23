/**
 * Pure logic of the per-student exam printout ("sınav dökümü"): for every student with a result, each question (and
 * its group's stem) with the student's answer, the key, right/wrong and the points. Built from the exam definition and
 * the results the staff screens already load; nothing is fetched twice.
 */
import type { ExamDto, ExamGroupDto, ExamResultDto, QuestionDto, RosterEntryDto } from '../core/api.models';
import { optionLabels, questionLabel } from '../shared/question-labels';

export interface SheetOption { letter: string; text: string | null; correct: boolean; mine: boolean }

export interface SheetItem {
  question: QuestionDto;
  /** Printed label ("4", "3a"). */
  label: string;
  /** Group header to print before this item (first question of the group), if any. */
  groupHead: ExamGroupDto | null;
  answer: string | null;
  points: number | null;
  status: 'right' | 'wrong' | 'partial' | 'blank' | 'ungraded';
  options: SheetOption[];
}

export interface StudentSheet {
  studentNo: string;
  fullName: string;
  form: string | null;
  total: number;
  max: number;
  percent: number | null;
  correct: number;
  wrong: number;
  blank: number;
  items: SheetItem[];
}

export function sortedQuestions(exam: ExamDto): QuestionDto[] {
  return [...exam.questions].sort((a, b) => a.position - b.position);
}

export function itemStatus(q: QuestionDto, answer: string | null, points: number | null): SheetItem['status'] {
  if (q.type === 'CHOICE') {
    if (!answer) return 'blank';
    if (!q.correctChoice) return 'ungraded';
    return answer.toUpperCase() === q.correctChoice.toUpperCase() ? 'right' : 'wrong';
  }
  if (points == null) return 'ungraded';
  if (points >= q.maxPoints) return 'right';
  return points > 0 ? 'partial' : 'wrong';
}

/** One student's sheet; roster name preferred over the result's snapshot. */
export function studentSheet(exam: ExamDto, result: ExamResultDto, rosterName?: string | null): StudentSheet {
  const groups = new Map((exam.groups ?? []).map(g => [g.id, g]));
  const byQ = new Map(result.answers.map(a => [a.questionId, a]));
  let prev: number | null = null;
  let correct = 0, wrong = 0, blank = 0;
  const items: SheetItem[] = sortedQuestions(exam).map(q => {
    const a = byQ.get(q.id);
    const answer = q.type === 'FREE' ? null : (a?.answer?.trim() || null);
    const points = a?.points ?? null;
    const status = itemStatus(q, answer, points);
    if (q.type === 'CHOICE') {
      if (status === 'right') correct++; else if (status === 'wrong') wrong++; else if (status === 'blank') blank++;
    }
    const gid = q.groupId ?? null;
    const groupHead = gid != null && gid !== prev ? groups.get(gid) ?? null : null;
    prev = gid;
    const mine = answer?.toUpperCase() ?? null;
    const key = q.correctChoice?.toUpperCase() ?? null;
    const texts = new Map((q.options ?? []).map(o => [o.letter.toUpperCase(), o.text]));
    const options: SheetOption[] = optionLabels(q).map(l => ({ letter: l, text: texts.get(l)?.trim() ? texts.get(l)! : null, correct: l === key, mine: l === mine }));
    return { question: q, label: questionLabel(q), groupHead, answer, points, status, options };
  });
  const max = exam.totalPoints;
  return {
    studentNo: result.studentNo, fullName: rosterName || result.fullName, form: result.form ?? null,
    total: result.totalPoints, max, percent: max > 0 ? result.totalPoints / max : null, correct, wrong, blank, items,
  };
}

/** Sheets of every student with a result, in student-number order (or only the one asked for). */
export function buildSheets(exam: ExamDto, results: ExamResultDto[], roster: RosterEntryDto[], onlyStudent: string | null = null): StudentSheet[] {
  const names = new Map(roster.map(r => [r.studentNo, r.fullName]));
  return results
    .filter(r => !onlyStudent || r.studentNo === onlyStudent)
    .sort((a, b) => a.studentNo.localeCompare(b.studentNo, 'tr', { numeric: true }))
    .map(r => studentSheet(exam, r, names.get(r.studentNo)));
}

export const STATUS_TEXT: Record<SheetItem['status'], string> = {
  right: 'Doğru', wrong: 'Yanlış', partial: 'Kısmi', blank: 'Boş', ungraded: 'Puanlanmadı',
};
