import type { ExamGroupDto, MyResultDetailDto, MyResultQuestionDto } from '../core/api.models';
import { apiTopics, fromApiTopic, Topic } from '../shared/topics/topic';
import { isLabel, questionLabel, reviewLabels } from '../shared/question-labels';
export { textSegments, type TextSegment } from '../shared/text-segments';

export interface ReviewTopicRow { topic: Topic; missed: number; total: number }

/** One answer option as the review page shows it: letter, text, class bar, markers, rationale. */
export interface OptionRow {
  letter: string;
  text: string | null;
  rationale: string | null;
  correct: boolean;
  mine: boolean;
  /** Class answers with this letter; null without statistics. */
  count: number | null;
  /** 0..1 of all class answers (blank included); null without statistics. */
  share: number | null;
}

export interface QuestionStatsView {
  answered: number;
  correctShare: number | null;
  averagePoints: number | null;
  /** Blank answers of a CHOICE question (null when not reported). */
  blank: number | null;
  blankShare: number | null;
}

export interface ResultQuestionRow extends MyResultQuestionDto {
  /** Number printed on the paper ("4", "3a"). */
  printed: string;
  /** The group whose header is shown right before this question (its first question), if any. */
  groupHead: ExamGroupDto | null;
  topicList: Topic[];
  missed: boolean;
  status: 'right' | 'wrong' | 'partial' | 'none';
  /** Empty: show the letter-only answer line (no option texts and no class bars). */
  optionRows: OptionRow[];
  /** Option texts or rationales were recorded for this question. */
  hasOptionTexts: boolean;
  statsView: QuestionStatsView | null;
}

export interface ResultView {
  reviewTopics: ReviewTopicRow[];
  questions: ResultQuestionRow[];
  hasTopics: boolean;
  missedCount: number;
  /** At least one question has a class correct share (the "most missed" order makes sense). */
  hasQuestionStats: boolean;
}

/** Missed (as the server counts it for `reviewTopics`): graded and below full points. Ungraded questions are not missed. */
export function isMissed(q: Pick<MyResultQuestionDto, 'points' | 'maxPoints'>): boolean {
  return q.points != null && q.points < q.maxPoints;
}

export function questionStatus(q: Pick<MyResultQuestionDto, 'correct' | 'points' | 'maxPoints'>): ResultQuestionRow['status'] {
  if (q.correct === true) return 'right';
  if (q.correct === false) return 'wrong';
  if (q.points == null) return 'none';
  if (q.points >= q.maxPoints) return 'right';
  return q.points > 0 ? 'partial' : 'wrong';
}

const BLANK = 'blank';
const isLetter = isLabel;
const sum = (counts: Record<string, number>) => Object.values(counts).reduce((a, n) => a + (Number(n) || 0), 0);

/** Class statistics of a question, or null when the server sent none. */
export function questionStats(q: MyResultQuestionDto): QuestionStatsView | null {
  const s = q.stats;
  if (!s) return null;
  const counts = s.choiceCounts ?? {};
  const total = sum(counts);
  const blank = BLANK in counts ? Number(counts[BLANK]) || 0 : null;
  return {
    answered: s.answered ?? 0,
    correctShare: typeof s.correctShare === 'number' ? Math.min(1, Math.max(0, s.correctShare)) : null,
    averagePoints: s.averagePoints ?? null,
    blank,
    blankShare: blank != null && total > 0 ? blank / total : null,
  };
}

/**
 * Option rows: every recorded option, plus letters that only appear in the class counts, the key or my answer, so each
 * letter is shown once, in the question's label order. Empty for questions with neither option texts nor class counts
 * (they keep the letter-only line).
 */
export function optionRows(q: MyResultQuestionDto): OptionRow[] {
  const options = (q.options ?? []).filter(o => isLetter(o.letter?.toUpperCase()));
  const counts = q.stats?.choiceCounts ?? {};
  const countLetters = Object.keys(counts).map(k => k.toUpperCase()).filter(isLetter);
  if (!options.length && !countLetters.length) return [];
  const total = sum(counts);
  const hasCounts = countLetters.length > 0;
  const mine = q.myAnswer?.trim().toUpperCase() ?? null;
  const key = q.correctChoice?.toUpperCase() ?? null;
  const letters = new Set<string>([...options.map(o => o.letter.toUpperCase()), ...countLetters]);
  if (isLetter(key)) letters.add(key);
  if (isLetter(mine)) letters.add(mine);
  const countOf = (l: string) => Number(counts[l] ?? counts[l.toLowerCase()] ?? 0) || 0;
  const order = reviewLabels(q);
  const rank = (l: string) => { const i = order.indexOf(l); return i < 0 ? order.length : i; };
  return [...letters].sort((a, b) => (rank(a) - rank(b)) || a.localeCompare(b)).map(l => {
    const o = options.find(x => x.letter.toUpperCase() === l);
    const count = hasCounts ? countOf(l) : null;
    return {
      letter: l, text: o?.text?.trim() ? o.text : null, rationale: o?.rationale?.trim() ? o.rationale : null,
      correct: l === key, mine: l === mine, count, share: count != null && total > 0 ? count / total : null,
    };
  });
}

/** The page's view model: review topics as delivered (server order), questions by position. */
export function resultView(d: MyResultDetailDto): ResultView {
  const reviewTopics = (d.reviewTopics ?? [])
    .filter(r => r && r.topic)
    .map(r => ({ topic: fromApiTopic(r.topic), missed: r.missed ?? 0, total: r.total ?? 0 }));
  const groups = new Map((d.groups ?? []).map(g => [g.id, g]));
  let prevGroup: number | null = null;
  const questions = [...(d.questions ?? [])]
    .sort((a, b) => a.position - b.position)
    .map(q => {
      const rows = optionRows(q);
      const gid = q.groupId ?? null;
      const groupHead = gid != null && gid !== prevGroup ? groups.get(gid) ?? null : null;
      prevGroup = gid;
      return {
        ...q, printed: questionLabel(q), groupHead, topicList: apiTopics(q.topics), missed: isMissed(q), status: questionStatus(q),
        optionRows: rows, hasOptionTexts: rows.some(r => r.text || r.rationale), statsView: questionStats(q),
      };
    });
  const hasTopics = reviewTopics.length > 0 || questions.some(q => q.topicList.length > 0);
  return {
    reviewTopics, questions, hasTopics,
    missedCount: questions.filter(q => q.missed).length,
    hasQuestionStats: questions.some(q => q.statsView?.correctShare != null),
  };
}

/** "En çok yanlış yapılanlar": lowest class correct share first; questions without a share last; then by position. */
export function sortByMostMissed<T extends { position: number; statsView: QuestionStatsView | null }>(list: T[]): T[] {
  const share = (q: T) => q.statsView?.correctShare ?? Number.POSITIVE_INFINITY;
  return [...list].sort((a, b) => (share(a) === share(b) ? 0 : share(a) < share(b) ? -1 : 1) || a.position - b.position);
}

/** 0..1 → whole percent (null stays null). */
export function percent(share: number | null | undefined): number | null {
  return share == null || !Number.isFinite(share) ? null : Math.round(share * 100);
}

/**
 * Turkish third-person possessive after a number written with digits: "%72'si", "%60'ı", "%100'ü" (vowel harmony of
 * the number as read aloud).
 */
export function possessiveSuffix(n: number): string {
  const v = Math.abs(Math.trunc(n));
  const units = ['', 'i', 'si', 'ü', 'ü', 'i', 'sı', 'si', 'i', 'u'];
  const tens = ['', 'u', 'si', 'u', 'ı', 'si', 'ı', 'i', 'i', 'ı'];
  if (v === 0) return 'ı';
  if (v % 10) return units[v % 10];
  if (v % 100) return tens[(v % 100) / 10];
  return v % 1000 ? 'ü' : 'i';
}

/** "Sınıfın %72'si doğru yanıtladı" (null without a share). */
export function correctShareText(share: number | null): string | null {
  const p = percent(share);
  return p == null ? null : `Sınıfın %${p}'${possessiveSuffix(p)} doğru yanıtladı`;
}
