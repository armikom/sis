import { describe, it, expect } from 'vitest';
import { examTotal, nextTodo, parseAiReview, renderMarkdown, slug, threadTree } from './pool-util';
import type { PoolComment, QuestionListItem } from './pool.models';

const c = (id: number, parent_id: number | null): PoolComment =>
  ({ id, parent_id, author: 'a', is_ai: false, wrote_question: false, mine: false, body: '', created_at: '' });
const q = (id: number, extra: Partial<QuestionListItem> = {}): QuestionListItem =>
  ({ id, stem: '', status: 'APPROVED', author: 'x', mine: false, attempted: false, my_correct: null, comments: 0, ...extra });

describe('pool-util', () => {
  it('builds a two-level thread; replies to replies go under the first comment', () => {
    const t = threadTree([c(1, null), c(2, 1), c(3, 2), c(4, null), c(5, 99)]);
    expect(t.map(x => x.root.id)).toEqual([1, 4, 5]);
    expect(t[0].replies.map(x => x.id)).toEqual([2, 3]);
  });

  it('finds the next unanswered approved question after the current one, wrapping around', () => {
    const list = [q(1), q(2, { attempted: true }), q(3, { mine: true }), q(4, { status: 'PENDING' }), q(5)];
    expect(nextTodo(list, 2)).toBe(5);
    expect(nextTodo(list, 5)).toBe(1);
    expect(nextTodo([q(7)], 7)).toBeNull();
  });

  it('parses the AI review JSON and tolerates garbage', () => {
    expect(parseAiReview('{"verdict":"OK"}')?.verdict).toBe('OK');
    expect(parseAiReview('nope')).toBeNull();
    expect(parseAiReview(null)).toBeNull();
  });

  it('sums exam counts', () => {
    expect(examTotal({ 1: 2, 2: '3', 3: null })).toBe(5);
  });

  it('renders markdown with GitHub-style heading ids and strips scripts', () => {
    expect(slug('2.1 Static Testing!')).toBe('21-static-testing');
    const html = renderMarkdown('# Hello World\n\n<script>alert(1)</script><img src=x onerror=alert(1)>');
    expect(html).toContain('id="hello-world"');
    expect(html).not.toContain('<script');
    expect(html).not.toContain('onerror');
  });
});
