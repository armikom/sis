import type { SessionAttendanceDto, StudentAttendanceState, StudentExamDto } from '../core/api.models';
import type { QuestionStatus, StudentDetail } from '../pool/pool.models';

export interface AttendanceSummary {
  total: number; present: number; pending: number; rejected: number; excused: number; absent: number;
  /**
   * present / (total - excused), or null without such sessions. Pending records do not count until a decision is
   * made; excused (izinli) sessions are left out of the base.
   */
  rate: number | null;
}

export function attendanceSummary(rows: SessionAttendanceDto[]): AttendanceSummary {
  const count = (s: StudentAttendanceState) => rows.filter(r => r.state === s).length;
  const present = count('PRESENT');
  const excused = count('EXCUSED');
  const base = rows.length - excused;
  return {
    total: rows.length, present, pending: count('PENDING'), rejected: count('REJECTED'), excused,
    absent: count('ABSENT'), rate: base > 0 ? present / base : null,
  };
}

/** Overall exam success = earned / possible over all exams with a result, or null without any. */
export function examOverall(exams: StudentExamDto[]): number | null {
  const max = exams.reduce((s, e) => s + (e.maxPoints || 0), 0);
  if (!max) return null;
  return exams.reduce((s, e) => s + (e.totalPoints || 0), 0) / max;
}

export interface PoolSummary {
  written: number; approved: number; pending: number; attempts: number; correct: number;
  /** correct first answers / answered, or null when nothing was answered. */
  rate: number | null; comments: number; flags: number;
}

export function poolSummary(d: StudentDetail): PoolSummary {
  const byStatus = (s: QuestionStatus) => d.questions.filter(q => q.status === s).length;
  const attempts = d.attempts_by_chapter.reduce((s, a) => s + Number(a.attempts), 0);
  const correct = d.attempts_by_chapter.reduce((s, a) => s + Number(a.correct), 0);
  return {
    written: d.questions.length, approved: byStatus('APPROVED'), pending: byStatus('PENDING') + byStatus('NEEDS_REVISION'),
    attempts, correct, rate: attempts ? correct / attempts : null,
    comments: d.comments.filter(c => !c.deleted).length, flags: d.flags.length,
  };
}

export const ATTENDANCE_STATE: Record<StudentAttendanceState, { label: string; badge: string }> = {
  PRESENT: { label: 'Katıldı', badge: 'badge-success' },
  PENDING: { label: 'İnceleme bekliyor', badge: 'badge-warning' },
  REJECTED: { label: 'Reddedildi', badge: 'badge-danger' },
  EXCUSED: { label: 'İzinli', badge: 'badge-info' },
  ABSENT: { label: 'Katılmadı', badge: 'badge-danger' },
};

export const POOL_STATUS_TR: Record<QuestionStatus, { label: string; badge: string }> = {
  PENDING: { label: 'Onay bekliyor', badge: 'badge-warning' },
  NEEDS_REVISION: { label: 'Düzeltme istendi', badge: 'badge-warning' },
  APPROVED: { label: 'Onaylandı', badge: 'badge-success' },
  REJECTED: { label: 'Reddedildi', badge: 'badge-danger' },
};

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('tr-TR', { dateStyle: 'medium', timeStyle: 'short' });
}
