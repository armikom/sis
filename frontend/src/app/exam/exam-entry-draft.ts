/**
 * Browser-side drafts of the answer-entry tool: unsaved sheets survive a reload, a closed tab or navigating away.
 * One localStorage entry per exam holds the unsaved sheets keyed by student number; a sheet is removed once it
 * is saved, discarded or no longer differs from the server. Pure functions; storage access is in the component.
 */
import type { QuestionDto } from '../core/api.models';
import { EXAM_DRAFT_PREFIX } from '../core/storage';
import { emptyCell, type Cell, type Sheet } from './exam-entry.logic';

export function draftKey(examId: string | number): string {
  return EXAM_DRAFT_PREFIX + examId;
}

export interface StudentDraft {
  /** sheetSignature of the server result the edits started from (detects a result changed elsewhere). */
  base: string;
  /** Name shown for students outside the roster, so they are listed again after a reload. */
  name: string;
  cells: Cell[];
  active: number;
}

export type Drafts = Record<string, StudentDraft>;

interface StoredDrafts {
  v: 1;
  /** Question layout the cells belong to; a changed exam invalidates every draft. */
  questions: string;
  students: Drafts;
}

/** Fingerprint of what the cells are indexed and interpreted by. */
export function questionsKey(questions: readonly QuestionDto[]): string {
  return JSON.stringify(questions.map(q => [q.id, q.type, q.choiceCount ?? 0]));
}

export function makeDraft(sheet: Sheet, base: string, name: string | null | undefined): StudentDraft {
  return { base, name: name ?? '', cells: sheet.cells.map(c => ({ ...c })), active: sheet.active };
}

export function draftSheet(d: StudentDraft): Sheet {
  return { cells: d.cells.map(c => ({ ...c })), active: d.active };
}

export function withDraft(drafts: Drafts, studentNo: string, d: StudentDraft | null): Drafts {
  if (d) return { ...drafts, [studentNo]: d };
  if (!(studentNo in drafts)) return drafts;
  const rest = { ...drafts };
  delete rest[studentNo];
  return rest;
}

/** null = nothing to keep (remove the storage entry). */
export function serializeDrafts(questions: readonly QuestionDto[], drafts: Drafts): string | null {
  if (Object.keys(drafts).length === 0) return null;
  const stored: StoredDrafts = { v: 1, questions: questionsKey(questions), students: drafts };
  return JSON.stringify(stored);
}

/** Drafts that still fit the exam's questions; anything malformed or from another question layout is dropped. */
export function parseDrafts(raw: string | null, questions: readonly QuestionDto[]): Drafts {
  if (!raw) return {};
  let data: unknown;
  try { data = JSON.parse(raw); } catch { return {}; }
  if (!isObject(data) || data['v'] !== 1 || data['questions'] !== questionsKey(questions) || !isObject(data['students'])) return {};
  const out: Drafts = {};
  for (const [no, d] of Object.entries(data['students'])) {
    const draft = cleanDraft(d, questions);
    if (no && draft) out[no] = draft;
  }
  return out;
}

function cleanDraft(d: unknown, questions: readonly QuestionDto[]): StudentDraft | null {
  if (!isObject(d) || typeof d['base'] !== 'string' || !Array.isArray(d['cells']) || d['cells'].length !== questions.length) return null;
  const cells: Cell[] = [];
  for (const c of d['cells'] as unknown[]) {
    if (!isObject(c)) return null;
    const answer = typeof c['answer'] === 'string' ? c['answer'] : null;
    cells.push({
      ...emptyCell(),
      entered: c['entered'] === true,
      answer,
      points: typeof c['points'] === 'string' ? c['points'] : '',
      text: typeof c['text'] === 'string' ? c['text'] : '',
    });
  }
  const a = typeof d['active'] === 'number' && Number.isInteger(d['active']) ? d['active'] : 0;
  return {
    base: d['base'],
    name: typeof d['name'] === 'string' ? d['name'] : '',
    cells,
    active: Math.max(0, Math.min(cells.length - 1, a)),
  };
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}
