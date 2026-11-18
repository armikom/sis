import { marked } from 'marked';
import DOMPurify from 'dompurify';
import type { AiReviewResult, PoolComment, QuestionListItem } from './pool.models';

export const LETTERS = ['A', 'B', 'C', 'D'] as const;

/** A server timestamp for display ("-" when missing). */
export function when(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }) : '-';
}

/** Heading ids as on GitHub, so "Contents" links inside a chapter text work. */
export function slug(text: string): string {
  return text.trim().toLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu, '').replace(/\s/g, '-');
}

/** Markdown → sanitized HTML (DOMPurify) with ids on the headings. */
export function renderMarkdown(text: string | null | undefined): string {
  if (!text) return '';
  const box = document.createElement('div');
  box.innerHTML = DOMPurify.sanitize(marked.parse(text, { async: false }) as string);
  box.querySelectorAll('h1, h2, h3, h4').forEach(h => (h.id = slug(h.textContent ?? '')));
  return box.innerHTML;
}

/** Two levels on screen: a comment and its replies. A reply to a reply is shown under the same first comment. */
export function threadTree(comments: PoolComment[]): { root: PoolComment; replies: PoolComment[] }[] {
  const byId = new Map(comments.map(c => [c.id, c]));
  const rootOf = (c: PoolComment) => {
    const seen = new Set<number>();
    while (c.parent_id != null && byId.has(c.parent_id) && !seen.has(c.id)) {
      seen.add(c.id);
      c = byId.get(c.parent_id)!;
    }
    return c;
  };
  const roots = comments.filter(c => c.parent_id == null || !byId.has(c.parent_id));
  return roots.map(root => ({ root, replies: comments.filter(c => c !== root && rootOf(c) === root) }));
}

/** The next approved question the student has not answered: the first after `currentId`, else the first one. */
export function nextTodo(list: QuestionListItem[], currentId: number): number | null {
  const todo = list.filter(x => x.status === 'APPROVED' && !x.mine && !x.attempted && x.id !== currentId);
  return (todo.find(x => x.id > currentId) ?? todo[0])?.id ?? null;
}

/** The stored automatic-check JSON, or null when missing or broken. */
export function parseAiReview(review: string | null | undefined): AiReviewResult | null {
  if (!review) return null;
  try {
    const r = JSON.parse(review);
    return r && typeof r === 'object' ? r as AiReviewResult : null;
  } catch {
    return null;
  }
}

export function asList<T>(v: T[] | null | undefined): T[] {
  return Array.isArray(v) ? v : [];
}

/** Sum of the per-chapter draw counts of the exam builder. */
export function examTotal(counts: Record<string, number | string | null>): number {
  return Object.values(counts).reduce<number>((sum, n) => sum + (Number(n) || 0), 0);
}
