/**
 * Numbering and option labels of exam questions, shared by the staff screens and the student review.
 * Mirrors the server (ExamLabels): questions are stored in entry order; a group of sub-questions takes one printed
 * number, so Q1, Q2, group(Q3, Q4), Q5 print as 1, 2, 3a, 3b, 4.
 */
import type { MyResultQuestionDto, QuestionDto } from '../core/api.models';

export const DEFAULT_LABELS = 'ABCDEFGHIJ';
export const MIN_LABELS = 2;
export const MAX_LABELS = 10;

/** Label sets offered in the editor; "custom" lets staff type their own. */
export const LABEL_PRESETS: { labels: string; name: string }[] = [
  { labels: 'ABCD', name: 'A–D' },
  { labels: 'ABCDE', name: 'A–E' },
  { labels: 'ABC', name: 'A–C' },
  { labels: 'AB', name: 'A–B' },
  { labels: 'ABCDEF', name: 'A–F' },
  { labels: 'DY', name: 'D / Y' },
  { labels: 'TF', name: 'T / F' },
  { labels: '10', name: '1 / 0' },
  { labels: 'EH', name: 'E / H' },
];

/** Option labels of a question as single characters: stored labels, else A.. of choiceCount, else none. */
export function optionLabels(q: Pick<QuestionDto, 'type' | 'choiceCount'> & { choiceLabels?: string | null }): string[] {
  if (q.type !== 'CHOICE') return [];
  const stored = (q.choiceLabels ?? '').trim();
  if (stored) return stored.split('');
  const n = Math.max(0, Math.min(MAX_LABELS, q.choiceCount ?? 0));
  return DEFAULT_LABELS.slice(0, n).split('');
}

/** Labels of a review question (same rule; falls back to the letters seen in options or counts). */
export function reviewLabels(q: Pick<MyResultQuestionDto, 'type' | 'choiceLabels' | 'options' | 'stats'>): string[] {
  if (q.type !== 'CHOICE') return [];
  const stored = (q.choiceLabels ?? '').trim();
  if (stored) return stored.split('');
  const seen = new Set<string>();
  for (const o of q.options ?? []) if (o.letter) seen.add(o.letter.toUpperCase());
  for (const k of Object.keys(q.stats?.choiceCounts ?? {})) if (isLabel(k)) seen.add(k.toUpperCase());
  return [...seen].sort();
}

/** One upper-case letter or digit. */
export function isLabel(s: string | null | undefined): s is string {
  return !!s && /^[A-Z0-9]$/.test(s);
}

/**
 * Normalises a label set as typed: separators (whitespace , / |) dropped, letters upper-cased. Null unless
 * MIN_LABELS..MAX_LABELS distinct characters from A-Z / 0-9 remain (same rule as the server).
 */
export function normalizeLabels(raw: string): string | null {
  const s = raw.replace(/[\s,/|]+/g, '').toUpperCase();
  if (s.length < MIN_LABELS || s.length > MAX_LABELS) return null;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (!/[A-Z0-9]/.test(c) || s.indexOf(c) !== i) return null;
  }
  return s;
}

/** "A–D" for ABCD, "D/Y" for DY. */
export function describeLabels(labels: string): string {
  if (!labels) return '';
  if (labels.length >= 3 && DEFAULT_LABELS.startsWith(labels)) return `${labels[0]}–${labels[labels.length - 1]}`;
  return labels.split('').join('/');
}

/** a, b, …, z, aa, ab, … */
export function autoSubLabel(index: number): string {
  let s = '';
  let i = index;
  do { s = String.fromCharCode(97 + (i % 26)) + s; i = Math.floor(i / 26) - 1; } while (i >= 0);
  return s;
}

export interface Numbered { groupKey: string | number | null; subLabel?: string | null }

/** Printed labels of items in entry order ("1", "2", "3a", "3b", "4"); group numbers by group key. */
export function printedLabels(items: readonly Numbered[]): { labels: string[]; groupNumbers: Map<string | number, number> } {
  const groupNumbers = new Map<string | number, number>();
  const indexInGroup = new Map<string | number, number>();
  const labels: string[] = [];
  let n = 0;
  for (const it of items) {
    if (it.groupKey == null) { labels.push(String(++n)); continue; }
    if (!groupNumbers.has(it.groupKey)) groupNumbers.set(it.groupKey, ++n);
    const i = indexInGroup.get(it.groupKey) ?? 0;
    indexInGroup.set(it.groupKey, i + 1);
    const sub = it.subLabel?.trim() || autoSubLabel(i);
    labels.push(groupNumbers.get(it.groupKey)! + sub);
  }
  return { labels, groupNumbers };
}

/** The label the server sent, or one computed from position/group for older servers. */
export function questionLabel(q: { label?: string | null; position: number }): string {
  return q.label?.trim() || String(q.position);
}
