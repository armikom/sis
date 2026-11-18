import type { TopicDto } from '../../core/api.models';
import type { ChapterTopic, PoolTopicRef } from '../../pool/pool.models';
import { slug } from '../../pool/pool-util';
import { getItem } from '../../core/storage';

export type DocLang = 'en' | 'tr';

/**
 * A chapter topic (a numbered "## N. Title" section of a chapter text) in one shape for every screen. The pool
 * endpoints send it snake_case, the exam/result endpoints camelCase: `fromPoolTopic` / `fromApiTopic` are the only
 * mapping points.
 */
export interface Topic {
  id: number;
  chapterId: number | null;
  chapterNumber: number | null;
  chapterTitle: string;
  number: number;
  titleEn: string;
  titleTr: string;
}

/** A pool topic; `chapter` fills the chapter fields for the chapter-level shape (`GET /chapters/{id}` → topics). */
export function fromPoolTopic(t: ChapterTopic | PoolTopicRef, chapter?: { id: number; number: number; title?: string }): Topic {
  const ref = t as Partial<PoolTopicRef>;
  return {
    id: t.id,
    chapterId: ref.chapter_id ?? chapter?.id ?? null,
    chapterNumber: ref.chapter_number ?? chapter?.number ?? null,
    chapterTitle: ref.chapter_title ?? chapter?.title ?? '',
    number: t.number,
    titleEn: t.title_en ?? '',
    titleTr: t.title_tr ?? '',
  };
}

export function fromApiTopic(t: TopicDto): Topic {
  return {
    id: t.id, chapterId: t.chapterId ?? null, chapterNumber: t.chapterNumber ?? null, chapterTitle: t.chapterTitle ?? '',
    number: t.number, titleEn: t.titleEn ?? '', titleTr: t.titleTr ?? '',
  };
}

export function poolTopics(list: (ChapterTopic | PoolTopicRef)[] | null | undefined, chapter?: { id: number; number: number; title?: string }): Topic[] {
  return Array.isArray(list) ? list.map(t => fromPoolTopic(t, chapter)) : [];
}

export function apiTopics(list: TopicDto[] | null | undefined): Topic[] {
  return Array.isArray(list) ? list.map(fromApiTopic) : [];
}

/** The remembered reading language of the chapter screen (localStorage `docLang`), English by default. */
export function preferredLang(): DocLang {
  return getItem('docLang') === 'tr' ? 'tr' : 'en';
}

/** `want` when that text has content, else the other language when it has, else `want`. */
export function pickLang(want: DocLang, en: string | null | undefined, tr: string | null | undefined): DocLang {
  const has = (l: DocLang) => !!(l === 'en' ? en : tr)?.trim();
  if (has(want)) return want;
  const other: DocLang = want === 'en' ? 'tr' : 'en';
  return has(other) ? other : want;
}

/** The topic title in a language, falling back to the other one. */
export function topicTitle(t: Pick<Topic, 'titleEn' | 'titleTr'>, lang: DocLang): string {
  return (lang === 'tr' ? t.titleTr || t.titleEn : t.titleEn || t.titleTr) || '';
}

/** The section heading as it appears in the chapter text: "4. Infeasible test requirements". */
export function topicHeading(t: Pick<Topic, 'number' | 'titleEn' | 'titleTr'>, lang: DocLang): string {
  return `${t.number}. ${topicTitle(t, lang)}`;
}

/** Chip text: "W5 · 4. Infeasible test requirements" (W = week / chapter number). */
export function topicLabel(t: Topic, lang: DocLang): string {
  const heading = topicHeading(t, lang);
  return t.chapterNumber != null ? `W${t.chapterNumber} · ${heading}` : heading;
}

/** Heading id of the section in the rendered chapter text (same `slug()` as renderMarkdown). */
export function topicFragment(t: Pick<Topic, 'number' | 'titleEn' | 'titleTr'>, lang: DocLang): string {
  return slug(topicHeading(t, lang));
}

/**
 * The element of a heading id inside `root`. When the exact id is missing (the title changed slightly), the first
 * h1–h4 whose id starts with the same section number ("4-…") is used.
 */
export function findSection(root: ParentNode, fragment: string, exactOnly = false): HTMLElement | null {
  if (!fragment) return null;
  const headings = Array.from(root.querySelectorAll<HTMLElement>('h1[id], h2[id], h3[id], h4[id]'));
  const exact = headings.find(h => h.id === fragment);
  if (exact || exactOnly) return exact ?? null;
  const num = /^(\d+)-/.exec(fragment)?.[1];
  return num ? headings.find(h => h.id.startsWith(`${num}-`)) ?? null : null;
}

/** Topics grouped by chapter for pickers: the chapter `firstChapterId` first, then by chapter number. */
export function groupByChapter(topics: Topic[], firstChapterId?: number | null): { chapterId: number | null; label: string; topics: Topic[] }[] {
  const groups = new Map<number | null, { chapterId: number | null; number: number; label: string; topics: Topic[] }>();
  for (const t of topics) {
    let g = groups.get(t.chapterId);
    if (!g) {
      const label = t.chapterTitle || (t.chapterNumber != null ? `W${t.chapterNumber}` : '');
      g = { chapterId: t.chapterId, number: t.chapterNumber ?? Number.MAX_SAFE_INTEGER, label, topics: [] };
      groups.set(t.chapterId, g);
    }
    g.topics.push(t);
  }
  const list = [...groups.values()];
  for (const g of list) g.topics.sort((a, b) => a.number - b.number);
  list.sort((a, b) => {
    if (firstChapterId != null && (a.chapterId === firstChapterId) !== (b.chapterId === firstChapterId)) return a.chapterId === firstChapterId ? -1 : 1;
    return a.number - b.number;
  });
  return list.map(({ chapterId, label, topics: ts }) => ({ chapterId, label, topics: ts }));
}

/**
 * Section Markdown for the preview. The section usually starts with its own heading, which the dialog already shows
 * as its title: a first heading line with the same number is dropped.
 */
export function stripLeadingHeading(markdown: string | null | undefined, number: number): string {
  const text = markdown ?? '';
  const m = /^\s*#{1,6}\s+(\d+)[.)]?\s[^\n]*\n?/.exec(text);
  return m && Number(m[1]) === number ? text.slice(m[0].length) : text;
}
