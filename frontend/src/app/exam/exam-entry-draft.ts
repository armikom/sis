/**
 * Drafts of the answer-entry tool: unsaved sheets survive a reload, a closed tab, navigating away and (through the
 * server copy) a change of browser or computer. One localStorage entry per exam holds the unsaved sheets keyed by
 * student number and is mirrored to `/api/exams/{id}/drafts`; a sheet is removed once it is saved, discarded or no
 * longer differs from the saved result. Pure functions; storage and HTTP access are in the component.
 */
import type { ExamDraftDto, QuestionDto } from '../core/api.models';
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
  /** Server `updatedAt` while this draft equals the server copy; absent = local edits not yet on the server. */
  synced?: string;
  /** Staff member who wrote the server copy, kept while the draft is unchanged since it came from the server. */
  by?: string;
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
  const out: StudentDraft = {
    base: d['base'],
    name: typeof d['name'] === 'string' ? d['name'] : '',
    cells,
    active: Math.max(0, Math.min(cells.length - 1, a)),
  };
  if (typeof d['synced'] === 'string') out.synced = d['synced'];
  if (typeof d['by'] === 'string') out.by = d['by'];
  return out;
}

/** Same entries and starting point (the active question alone does not count as a change). */
export function sameContent(a: StudentDraft | undefined, b: StudentDraft | null): boolean {
  if (!a || !b) return !a && !b;
  return a.base === b.base && a.name === b.name && JSON.stringify(a.cells) === JSON.stringify(b.cells);
}

/* ---------- server copy ---------- */

/** Body of `PUT /api/exams/{id}/drafts/{studentNo}`: the draft plus the question layout it belongs to. */
export function toServerSheet(questions: readonly QuestionDto[], d: StudentDraft): unknown {
  return { v: 1, questions: questionsKey(questions), base: d.base, name: d.name, cells: d.cells, active: d.active };
}

/** A server draft that fits the questions (marked as synced), or null. */
export function fromServer(dto: ExamDraftDto, questions: readonly QuestionDto[]): StudentDraft | null {
  const s = dto.sheet;
  if (!isObject(s) || s['v'] !== 1 || s['questions'] !== questionsKey(questions)) return null;
  const d = cleanDraft({ base: s['base'], name: s['name'], cells: s['cells'], active: s['active'] }, questions);
  return d ? { ...d, synced: dto.updatedAt, by: dto.updatedBy } : null;
}

export interface MergeResult {
  drafts: Drafts;
  /** Student numbers to send: PUT when a draft is kept, DELETE when not. */
  push: string[];
}

/**
 * Local (this browser) and server drafts → what the screen starts with. Without client clocks:
 * - unsynced local edits win (they are this browser's latest work) and are pushed;
 * - a synced local draft takes the server copy when that changed elsewhere, and is dropped when the server copy is
 *   gone (saved or discarded elsewhere);
 * - server drafts this browser does not know are taken over; ones from another question layout are deleted.
 */
export function mergeDrafts(local: Drafts, server: readonly ExamDraftDto[], questions: readonly QuestionDto[]): MergeResult {
  const drafts: Drafts = {};
  const push = new Set<string>();
  const remote = new Map<string, StudentDraft>();
  for (const dto of server) {
    const d = fromServer(dto, questions);
    if (d) remote.set(dto.studentNo, d); else push.add(dto.studentNo);
  }
  for (const [no, l] of Object.entries(local)) {
    const r = remote.get(no);
    if (l.synced === undefined) { drafts[no] = l; push.add(no); }
    else if (r) drafts[no] = r.synced === l.synced ? l : r;
    // synced locally, gone on the server: done elsewhere
  }
  for (const [no, r] of remote) if (!(no in local)) drafts[no] = r;
  for (const no of push) if (no in drafts && drafts[no].synced !== undefined) push.delete(no);
  return { drafts, push: [...push].sort() };
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}
