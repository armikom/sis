import type { ExamDto, ExamGroupRequest, ExamOptionDto, ExamRequest, QuestionDto, QuestionRequest, QuestionType, TopicDto } from '../core/api.models';
import { DEFAULT_LABELS, MAX_LABELS, MIN_LABELS, normalizeLabels, optionLabels, printedLabels } from '../shared/question-labels';

export interface OptionDraft { letter: string; text: string; rationale: string }

/** Editable question row (docs/11-EXAMS.md §3.3). `key` is a stable client-side track id. */
export interface QuestionDraft {
  key: number;
  id: number | null;
  type: QuestionType;
  topic: string;
  text: string;
  /** Rich content (sanitized HTML); '' = none. */
  html: string;
  maxPoints: number | null;
  /** Option labels, one character each ("ABCD", "DY", "10"); meaningful for CHOICE only. */
  labels: string;
  correctChoice: string | null;
  /** Explicitly linked chapter topics (sent as `topicIds`). */
  topicIds: number[];
  /** Effective topics as last loaded (explicit + the linked pool question's); read-only. */
  effectiveTopics: TopicDto[];
  /** Option texts/rationales by letter (letters beyond the labels are kept while editing but not sent). */
  options: OptionDraft[];
  explanation: string;
  /** Key of the group (GroupDraft.key) this row belongs to; null = ungrouped. */
  groupKey: string | null;
  /** Label inside the group ('a'…); '' = by order. */
  subLabel: string;
  /**
   * Send `options` / `explanation` / `html`: the content editor was opened, or the question already had content.
   * Otherwise they are left out of the request and the server keeps what it has.
   */
  contentEdited: boolean;
}

/** A group of sub-questions (shared stem). `key` is the client-side id and the request's `ref`. */
export interface GroupDraft {
  key: string;
  id: number | null;
  text: string;
  html: string;
}

export interface BulkOptions { count: number; type: QuestionType; labels: string; points: number; topic: string; }

export const MAX_QUESTIONS = 200;
export const MAX_POINTS = 1000;
export const MAX_TEXT = 20000;
export const MAX_HTML = 500000;
export const MAX_OPTION_TEXT = 2000;
export const MAX_RATIONALE = 4000;
export const MAX_EXPLANATION = 20000;
export const MAX_SUB_LABEL = 8;
export const DEFAULT_ROW_LABELS = 'ABCD';
export const TYPE_LABELS: Record<QuestionType, string> = { CHOICE: 'Seçenekli', OPEN: 'Açık uçlu', FREE: 'Serbest' };

let nextKey = 1;
export function newKey(): number { return nextKey++; }
let nextGroupKey = 1;
export function newGroupKey(): string { return `n${nextGroupKey++}`; }

export function newRow(partial: Partial<QuestionDraft> = {}): QuestionDraft {
  return {
    key: newKey(), id: null, type: 'CHOICE', topic: '', text: '', html: '', maxPoints: 4, labels: DEFAULT_ROW_LABELS, correctChoice: null,
    topicIds: [], effectiveTopics: [], options: [], explanation: '', groupKey: null, subLabel: '', contentEdited: false, ...partial,
  };
}

export function groupKeyOf(groupId: number | null | undefined): string | null {
  return groupId == null ? null : `g${groupId}`;
}

export function fromDto(q: QuestionDto): QuestionDraft {
  const options: OptionDraft[] = (Array.isArray(q.options) ? q.options : [])
    .map(o => ({ letter: (o.letter ?? '').toUpperCase(), text: o.text ?? '', rationale: o.rationale ?? '' }))
    .filter(o => o.letter);
  const explanation = q.explanation ?? '';
  const html = q.html ?? '';
  const labels = q.type === 'CHOICE' ? optionLabels(q).join('') || DEFAULT_ROW_LABELS : DEFAULT_ROW_LABELS;
  return {
    key: newKey(), id: q.id, type: q.type, topic: q.topic ?? '', text: q.text ?? '', html, maxPoints: q.maxPoints,
    labels, correctChoice: q.type === 'CHOICE' ? q.correctChoice : null,
    topicIds: Array.isArray(q.topicIds) ? [...q.topicIds] : [], effectiveTopics: Array.isArray(q.topics) ? q.topics : [],
    options, explanation, groupKey: groupKeyOf(q.groupId), subLabel: q.subLabel ?? '',
    contentEdited: explanation.trim() !== '' || html.trim() !== '' || options.some(o => o.text.trim() || o.rationale.trim()),
  };
}

export function groupsFromDto(exam: Pick<ExamDto, 'groups'>): GroupDraft[] {
  return (exam.groups ?? []).map(g => ({ key: `g${g.id}`, id: g.id, text: g.text ?? '', html: g.html ?? '' }));
}

/** The option letters of a row (one character each). */
export function lettersOf(r: Pick<QuestionDraft, 'labels'>): string[] {
  return validLabels(r.labels).split('');
}

/** The option rows shown for a CHOICE question: one per label, empty where nothing is recorded. */
export function optionRows(r: Pick<QuestionDraft, 'labels' | 'options'>): OptionDraft[] {
  return lettersOf(r).map(l => r.options.find(o => o.letter === l) ?? { letter: l, text: '', rationale: '' });
}

/** A copy of `options` with one letter's fields changed (added when missing), ordered by letter. */
export function setOption(options: OptionDraft[], letter: string, p: Partial<Omit<OptionDraft, 'letter'>>): OptionDraft[] {
  const cur = options.find(o => o.letter === letter) ?? { letter, text: '', rationale: '' };
  return [...options.filter(o => o.letter !== letter), { ...cur, ...p }].sort((a, b) => a.letter.localeCompare(b.letter));
}

/** Whether a question has recorded content beyond the one-line row (text, html, option texts/rationales, explanation). */
export function hasContent(r: Pick<QuestionDraft, 'text' | 'html' | 'options' | 'explanation' | 'labels' | 'type'>): boolean {
  if (r.text.trim() || r.html.trim() || r.explanation.trim()) return true;
  return r.type === 'CHOICE' && optionRows(r).some(o => o.text.trim() || o.rationale.trim());
}

/** The options sent for a row: CHOICE only, letters within the labels, rows with some content. */
export function requestOptions(r: QuestionDraft): ExamOptionDto[] {
  if (r.type !== 'CHOICE') return [];
  return optionRows(r)
    .filter(o => o.text.trim() || o.rationale.trim())
    .map(o => ({ letter: o.letter, text: o.text.trim() ? o.text : null, rationale: o.rationale.trim() ? o.rationale : null }));
}

/** A usable label set: the normalised input, else the default A–D. */
export function validLabels(raw: string): string {
  return normalizeLabels(raw ?? '') ?? DEFAULT_ROW_LABELS;
}

/** Whether the typed label set is acceptable as is. */
export function labelsOk(raw: string): boolean {
  return normalizeLabels(raw ?? '') !== null;
}

/** Generate `count` rows (clamped to 0..MAX_QUESTIONS). */
export function bulkRows(o: BulkOptions): QuestionDraft[] {
  const n = Number.isFinite(o.count) ? Math.min(MAX_QUESTIONS, Math.max(0, Math.trunc(o.count))) : 0;
  return Array.from({ length: n }, () => newRow({
    type: o.type, topic: o.topic.trim(), maxPoints: o.points, labels: validLabels(o.labels),
  }));
}

/** Keep only letters and digits (upper-cased); whitespace and punctuation are ignored. */
export function parseKey(input: string): string {
  return input.toUpperCase().replace(/[^A-Z0-9]/g, '');
}

export interface KeyResult {
  rows: QuestionDraft[];
  /** Letters that were set on a question. */
  applied: number;
  /** Number of CHOICE questions. */
  choiceTotal: number;
  /** Letters parsed from the input. */
  letters: number;
  /** 1-based question numbers whose letter is not one of the question's labels (their key is left empty). */
  outOfRange: number[];
  /** Letters left over after all CHOICE questions were used. */
  extra: number;
}

/** Apply a letter string in order to the CHOICE questions; questions beyond the string are left untouched. */
export function applyKey(rows: QuestionDraft[], input: string): KeyResult {
  const letters = parseKey(input);
  let li = 0;
  let applied = 0;
  let choiceTotal = 0;
  const outOfRange: number[] = [];
  const out = rows.map((r, i) => {
    if (r.type !== 'CHOICE') return r;
    choiceTotal++;
    if (li >= letters.length) return r;
    const letter = letters[li++];
    if (lettersOf(r).includes(letter)) {
      applied++;
      return { ...r, correctChoice: letter };
    }
    outOfRange.push(i + 1);
    return { ...r, correctChoice: null };
  });
  return { rows: out, applied, choiceTotal, letters: letters.length, outOfRange, extra: Math.max(0, letters.length - choiceTotal) };
}

/** Current key as a string for review: one char per CHOICE question, "·" where unset. */
export function keyString(rows: QuestionDraft[]): string {
  return rows.filter(r => r.type === 'CHOICE').map(r => r.correctChoice ?? '·').join('');
}

export function totalPoints(rows: QuestionDraft[]): number {
  return Math.round(rows.reduce((s, r) => s + (r.maxPoints != null && r.maxPoints > 0 ? r.maxPoints : 0), 0) * 100) / 100;
}

/** Printed label of each row ("1", "2", "3a", …) in the current order. */
export function rowLabels(rows: readonly QuestionDraft[]): string[] {
  return printedLabels(rows.map(r => ({ groupKey: r.groupKey, subLabel: r.subLabel }))).labels;
}

/** Group keys in the order of their first row. */
export function groupOrder(rows: readonly QuestionDraft[]): string[] {
  const seen: string[] = [];
  for (const r of rows) if (r.groupKey && !seen.includes(r.groupKey)) seen.push(r.groupKey);
  return seen;
}

/** Index of the first row of each group (where its header is shown). */
export function groupStarts(rows: readonly QuestionDraft[]): Map<string, number> {
  const m = new Map<string, number>();
  rows.forEach((r, i) => { if (r.groupKey && !m.has(r.groupKey)) m.set(r.groupKey, i); });
  return m;
}

/** Group keys whose rows are not consecutive (the server rejects these). */
export function splitGroups(rows: readonly QuestionDraft[]): string[] {
  const closed = new Set<string>();
  const bad = new Set<string>();
  let open: string | null = null;
  for (const r of rows) {
    if (r.groupKey !== open) {
      if (open) closed.add(open);
      open = r.groupKey;
      if (open && closed.has(open)) bad.add(open);
    }
  }
  return [...bad];
}

/** Groups still referenced by a row; others are dropped (the server removes them anyway). */
export function pruneGroups(rows: readonly QuestionDraft[], groups: readonly GroupDraft[]): GroupDraft[] {
  const used = new Set(rows.map(r => r.groupKey));
  return groups.filter(g => used.has(g.key));
}

/** Moves the group's rows together so they are consecutive, in their current relative order, at the first row's place. */
export function gatherGroup(rows: QuestionDraft[], groupKey: string): QuestionDraft[] {
  const first = rows.findIndex(r => r.groupKey === groupKey);
  if (first < 0) return rows;
  const members = rows.filter(r => r.groupKey === groupKey);
  const rest = rows.filter(r => r.groupKey !== groupKey);
  const before = rest.slice(0, first);
  return [...before, ...members, ...rest.slice(first)];
}

/** Validation errors (Turkish), mirroring spec §3.3. */
export function validate(title: string, rows: QuestionDraft[], groups: readonly GroupDraft[] = []): string[] {
  const errs: string[] = [];
  const t = title.trim();
  if (!t) errs.push('Başlık boş olamaz.');
  else if (t.length > 200) errs.push('Başlık en fazla 200 karakter olabilir.');
  if (rows.length > MAX_QUESTIONS) errs.push(`En fazla ${MAX_QUESTIONS} soru eklenebilir.`);
  const labels = rowLabels(rows);
  const groupKeys = new Set(groups.map(g => g.key));
  const subLabels = new Map<string, Set<string>>();
  rows.forEach((r, i) => {
    const n = labels[i] ?? String(i + 1);
    if (r.maxPoints == null || !Number.isFinite(r.maxPoints) || r.maxPoints <= 0 || r.maxPoints > MAX_POINTS) {
      errs.push(`Soru ${n}: puan 0'dan büyük ve en fazla ${MAX_POINTS} olmalı.`);
    }
    if (r.type === 'CHOICE') {
      if (!labelsOk(r.labels)) errs.push(`Soru ${n}: seçenek etiketleri ${MIN_LABELS} ile ${MAX_LABELS} arasında, birbirinden farklı harf ya da rakam olmalı.`);
      else if (r.correctChoice && !lettersOf(r).includes(r.correctChoice)) errs.push(`Soru ${n}: doğru cevap (${r.correctChoice}) seçenekler arasında değil.`);
    }
    if (r.text.length > MAX_TEXT) errs.push(`Soru ${n}: metin en fazla ${MAX_TEXT} karakter olabilir.`);
    if (r.html.length > MAX_HTML) errs.push(`Soru ${n}: HTML içerik çok büyük (en fazla ${MAX_HTML} karakter).`);
    if (r.explanation.length > MAX_EXPLANATION) errs.push(`Soru ${n}: cevap açıklaması en fazla ${MAX_EXPLANATION} karakter olabilir.`);
    if (r.type === 'CHOICE') {
      for (const o of optionRows(r)) {
        if (o.text.length > MAX_OPTION_TEXT) errs.push(`Soru ${n}: ${o.letter} seçeneği en fazla ${MAX_OPTION_TEXT} karakter olabilir.`);
        if (o.rationale.length > MAX_RATIONALE) errs.push(`Soru ${n}: ${o.letter} açıklaması en fazla ${MAX_RATIONALE} karakter olabilir.`);
      }
    }
    if (r.topic.trim().length > 120) errs.push(`Soru ${n}: konu en fazla 120 karakter olabilir.`);
    if (r.groupKey) {
      if (!groupKeys.has(r.groupKey)) errs.push(`Soru ${n}: bağlı olduğu grup bulunamadı.`);
      const sub = r.subLabel.trim();
      if (sub.length > MAX_SUB_LABEL) errs.push(`Soru ${n}: alt soru etiketi en fazla ${MAX_SUB_LABEL} karakter olabilir.`);
      if (sub) {
        const set = subLabels.get(r.groupKey) ?? new Set<string>();
        if (set.has(sub)) errs.push(`Soru ${n}: alt soru etiketi (${sub}) grupta tekrar ediyor.`);
        set.add(sub);
        subLabels.set(r.groupKey, set);
      }
    }
  });
  for (const g of groups) {
    if (g.text.length > MAX_TEXT) errs.push(`Grup metni en fazla ${MAX_TEXT} karakter olabilir.`);
    if (g.html.length > MAX_HTML) errs.push(`Grup HTML içeriği çok büyük (en fazla ${MAX_HTML} karakter).`);
  }
  const groupNumbers = printedLabels(rows.map(r => ({ groupKey: r.groupKey, subLabel: r.subLabel }))).groupNumbers;
  for (const k of splitGroups(rows)) {
    errs.push(`Grup ${groupNumbers.get(k) ?? ''}: bir grubun soruları ardışık olmalı (araya başka soru girmemeli).`);
  }
  return errs;
}

export function buildRequest(title: string, examDate: string, rows: QuestionDraft[], groups: readonly GroupDraft[] = []): ExamRequest {
  const questions: QuestionRequest[] = rows.map(r => {
    const choice = r.type === 'CHOICE';
    const labels = choice ? validLabels(r.labels) : null;
    return {
      ...(r.id != null ? { id: r.id } : {}),
      type: r.type,
      text: r.text.trim() ? r.text : null,
      topic: r.topic.trim() || null,
      maxPoints: r.maxPoints ?? 0,
      choiceCount: labels ? labels.length : null,
      choiceLabels: labels,
      correctChoice: choice ? r.correctChoice : null,
      topicIds: r.topicIds,
      ...(r.groupKey ? { groupRef: r.groupKey, subLabel: r.subLabel.trim() || null } : {}),
      ...(r.contentEdited ? {
        options: requestOptions(r),
        explanation: r.explanation.trim() ? r.explanation : null,
        html: r.html.trim() ? r.html : '',
      } : {}),
    };
  });
  const used = pruneGroups(rows, groups);
  const groupReqs: ExamGroupRequest[] = used.map(g => ({
    ...(g.id != null ? { id: g.id } : {}), ref: g.key, text: g.text.trim() ? g.text : null, html: g.html.trim() ? g.html : null,
  }));
  return { title: title.trim(), examDate: examDate || null, questions, ...(groupReqs.length ? { groups: groupReqs } : {}) };
}

/** Existing questions that were removed or changed type (their answers are deleted on save). */
export function destructiveChanges(original: QuestionDto[], rows: QuestionDraft[]): { removed: number; typeChanged: number } {
  const byId = new Map<number, QuestionDraft>();
  for (const r of rows) if (r.id != null) byId.set(r.id, r);
  let removed = 0;
  let typeChanged = 0;
  for (const q of original) {
    const r = byId.get(q.id);
    if (!r) removed++;
    else if (r.type !== q.type) typeChanged++;
  }
  return { removed, typeChanged };
}

/** Effective topics of a row that come from its pool question, not from its explicit `topicIds`. */
export function inheritedTopics(r: QuestionDraft): TopicDto[] {
  return r.effectiveTopics.filter(t => !r.topicIds.includes(t.id));
}

/** Distinct non-empty trimmed topics in first-use order. */
export function topicsOf(rows: QuestionDraft[]): string[] {
  const seen = new Set<string>();
  for (const r of rows) { const t = r.topic.trim(); if (t) seen.add(t); }
  return [...seen];
}

export function moveRow<T>(rows: T[], index: number, delta: -1 | 1): T[] {
  const j = index + delta;
  if (j < 0 || j >= rows.length) return rows;
  const copy = rows.slice();
  [copy[index], copy[j]] = [copy[j], copy[index]];
  return copy;
}

export { DEFAULT_LABELS };
