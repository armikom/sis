import type { ExamOptionDto, ExamRequest, QuestionDto, QuestionRequest, QuestionType, TopicDto } from '../core/api.models';

export interface OptionDraft { letter: string; text: string; rationale: string }

/** Editable question row (docs/11-EXAMS.md §3.3). `key` is a stable client-side track id. */
export interface QuestionDraft {
  key: number;
  id: number | null;
  type: QuestionType;
  topic: string;
  text: string;
  maxPoints: number | null;
  choiceCount: number;
  correctChoice: string | null;
  /** Explicitly linked chapter topics (sent as `topicIds`). */
  topicIds: number[];
  /** Effective topics as last loaded (explicit + the linked pool question's); read-only. */
  effectiveTopics: TopicDto[];
  /** Option texts/rationales by letter (letters beyond choiceCount are kept while editing but not sent). */
  options: OptionDraft[];
  explanation: string;
  /**
   * Send `options` / `explanation`: the content editor was opened, or the question already had content. Otherwise
   * both are left out of the request and the server keeps what it has.
   */
  contentEdited: boolean;
}

export interface BulkOptions { count: number; type: QuestionType; choiceCount: number; points: number; topic: string; }

export const MAX_QUESTIONS = 200;
export const MAX_POINTS = 1000;
export const MAX_TEXT = 20000;
export const MAX_OPTION_TEXT = 2000;
export const MAX_RATIONALE = 4000;
export const MAX_EXPLANATION = 20000;
export const LETTERS = ['A', 'B', 'C', 'D', 'E', 'F'];
export const TYPE_LABELS: Record<QuestionType, string> = { CHOICE: 'Seçenekli', OPEN: 'Açık uçlu', FREE: 'Serbest' };

let nextKey = 1;
export function newKey(): number { return nextKey++; }

export function newRow(partial: Partial<QuestionDraft> = {}): QuestionDraft {
  return {
    key: newKey(), id: null, type: 'CHOICE', topic: '', text: '', maxPoints: 4, choiceCount: 4, correctChoice: null,
    topicIds: [], effectiveTopics: [], options: [], explanation: '', contentEdited: false, ...partial,
  };
}

export function fromDto(q: QuestionDto): QuestionDraft {
  const options: OptionDraft[] = (Array.isArray(q.options) ? q.options : [])
    .map(o => ({ letter: (o.letter ?? '').toUpperCase(), text: o.text ?? '', rationale: o.rationale ?? '' }))
    .filter(o => o.letter);
  const explanation = q.explanation ?? '';
  return {
    key: newKey(), id: q.id, type: q.type, topic: q.topic ?? '', text: q.text ?? '', maxPoints: q.maxPoints,
    choiceCount: q.choiceCount ?? 4, correctChoice: q.type === 'CHOICE' ? q.correctChoice : null,
    topicIds: Array.isArray(q.topicIds) ? [...q.topicIds] : [], effectiveTopics: Array.isArray(q.topics) ? q.topics : [],
    options, explanation, contentEdited: explanation.trim() !== '' || options.some(o => o.text.trim() || o.rationale.trim()),
  };
}

/** The option rows shown for a CHOICE question: A..choiceCount, empty where nothing is recorded. */
export function optionRows(r: Pick<QuestionDraft, 'choiceCount' | 'options'>): OptionDraft[] {
  return LETTERS.slice(0, validChoiceCount(r.choiceCount)).map(l => r.options.find(o => o.letter === l) ?? { letter: l, text: '', rationale: '' });
}

/** A copy of `options` with one letter's fields changed (added when missing), ordered by letter. */
export function setOption(options: OptionDraft[], letter: string, p: Partial<Omit<OptionDraft, 'letter'>>): OptionDraft[] {
  const cur = options.find(o => o.letter === letter) ?? { letter, text: '', rationale: '' };
  return [...options.filter(o => o.letter !== letter), { ...cur, ...p }].sort((a, b) => a.letter.localeCompare(b.letter));
}

/** Whether a question has recorded content beyond the one-line row (text, option texts/rationales, explanation). */
export function hasContent(r: Pick<QuestionDraft, 'text' | 'options' | 'explanation' | 'choiceCount' | 'type'>): boolean {
  if (r.text.trim() || r.explanation.trim()) return true;
  return r.type === 'CHOICE' && optionRows(r).some(o => o.text.trim() || o.rationale.trim());
}

/** The options sent for a row: CHOICE only, letters within choiceCount, rows with some content. */
export function requestOptions(r: QuestionDraft): ExamOptionDto[] {
  if (r.type !== 'CHOICE') return [];
  return optionRows(r)
    .filter(o => o.text.trim() || o.rationale.trim())
    .map(o => ({ letter: o.letter, text: o.text.trim() ? o.text : null, rationale: o.rationale.trim() ? o.rationale : null }));
}

export function validChoiceCount(n: number): number {
  return Number.isFinite(n) ? Math.min(6, Math.max(2, Math.trunc(n))) : 4;
}

/** Generate `count` rows (clamped to 0..MAX_QUESTIONS). */
export function bulkRows(o: BulkOptions): QuestionDraft[] {
  const n = Number.isFinite(o.count) ? Math.min(MAX_QUESTIONS, Math.max(0, Math.trunc(o.count))) : 0;
  return Array.from({ length: n }, () => newRow({
    type: o.type, topic: o.topic.trim(), maxPoints: o.points, choiceCount: validChoiceCount(o.choiceCount),
  }));
}

/** Keep only letters A-Z (upper-cased); whitespace, digits and punctuation are ignored. */
export function parseKey(input: string): string {
  return input.toUpperCase().replace(/[^A-Z]/g, '');
}

export interface KeyResult {
  rows: QuestionDraft[];
  /** Letters that were set on a question. */
  applied: number;
  /** Number of CHOICE questions. */
  choiceTotal: number;
  /** Letters parsed from the input. */
  letters: number;
  /** 1-based question numbers whose letter is outside A..choiceCount (their key is left empty). */
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
    if (LETTERS.indexOf(letter) >= 0 && LETTERS.indexOf(letter) < r.choiceCount) {
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

/** Validation errors (Turkish), mirroring spec §3.3. */
export function validate(title: string, rows: QuestionDraft[]): string[] {
  const errs: string[] = [];
  const t = title.trim();
  if (!t) errs.push('Başlık boş olamaz.');
  else if (t.length > 200) errs.push('Başlık en fazla 200 karakter olabilir.');
  if (rows.length > MAX_QUESTIONS) errs.push(`En fazla ${MAX_QUESTIONS} soru eklenebilir.`);
  rows.forEach((r, i) => {
    const n = i + 1;
    if (r.maxPoints == null || !Number.isFinite(r.maxPoints) || r.maxPoints <= 0 || r.maxPoints > MAX_POINTS) {
      errs.push(`Soru ${n}: puan 0'dan büyük ve en fazla ${MAX_POINTS} olmalı.`);
    }
    if (r.type === 'CHOICE') {
      if (!Number.isInteger(r.choiceCount) || r.choiceCount < 2 || r.choiceCount > 6) errs.push(`Soru ${n}: seçenek sayısı 2 ile 6 arasında olmalı.`);
      else if (r.correctChoice && LETTERS.indexOf(r.correctChoice) >= r.choiceCount) errs.push(`Soru ${n}: doğru cevap (${r.correctChoice}) seçenek aralığı dışında.`);
    }
    if (r.text.length > MAX_TEXT) errs.push(`Soru ${n}: metin en fazla ${MAX_TEXT} karakter olabilir.`);
    if (r.explanation.length > MAX_EXPLANATION) errs.push(`Soru ${n}: cevap açıklaması en fazla ${MAX_EXPLANATION} karakter olabilir.`);
    if (r.type === 'CHOICE') {
      for (const o of optionRows(r)) {
        if (o.text.length > MAX_OPTION_TEXT) errs.push(`Soru ${n}: ${o.letter} seçeneği en fazla ${MAX_OPTION_TEXT} karakter olabilir.`);
        if (o.rationale.length > MAX_RATIONALE) errs.push(`Soru ${n}: ${o.letter} açıklaması en fazla ${MAX_RATIONALE} karakter olabilir.`);
      }
    }
    if (r.topic.trim().length > 120) errs.push(`Soru ${n}: konu en fazla 120 karakter olabilir.`);
  });
  return errs;
}

export function buildRequest(title: string, examDate: string, rows: QuestionDraft[]): ExamRequest {
  const questions: QuestionRequest[] = rows.map(r => {
    const choice = r.type === 'CHOICE';
    return {
      ...(r.id != null ? { id: r.id } : {}),
      type: r.type,
      text: r.text.trim() ? r.text : null,
      topic: r.topic.trim() || null,
      maxPoints: r.maxPoints ?? 0,
      choiceCount: choice ? r.choiceCount : null,
      correctChoice: choice ? r.correctChoice : null,
      topicIds: r.topicIds,
      ...(r.contentEdited ? { options: requestOptions(r), explanation: r.explanation.trim() ? r.explanation : null } : {}),
    };
  });
  return { title: title.trim(), examDate: examDate || null, questions };
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
