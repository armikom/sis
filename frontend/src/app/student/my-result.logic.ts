import type { MyResultDetailDto, MyResultQuestionDto } from '../core/api.models';
import { apiTopics, fromApiTopic, Topic } from '../shared/topics/topic';

export interface ReviewTopicRow { topic: Topic; missed: number; total: number }
export interface ResultQuestionRow extends MyResultQuestionDto { topicList: Topic[]; missed: boolean; status: 'right' | 'wrong' | 'partial' | 'none' }
export interface TextSegment { code: boolean; text: string }

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

/** The page's view model: review topics as delivered (server order), questions by position. */
export function resultView(d: MyResultDetailDto): { reviewTopics: ReviewTopicRow[]; questions: ResultQuestionRow[]; hasTopics: boolean; missedCount: number } {
  const reviewTopics = (d.reviewTopics ?? [])
    .filter(r => r && r.topic)
    .map(r => ({ topic: fromApiTopic(r.topic), missed: r.missed ?? 0, total: r.total ?? 0 }));
  const questions = [...(d.questions ?? [])]
    .sort((a, b) => a.position - b.position)
    .map(q => ({ ...q, topicList: apiTopics(q.topics), missed: isMissed(q), status: questionStatus(q) }));
  const hasTopics = reviewTopics.length > 0 || questions.some(q => q.topicList.length > 0);
  return { reviewTopics, questions, hasTopics, missedCount: questions.filter(q => q.missed).length };
}

/**
 * Question text as plain text: fenced code blocks (```) become their own segments so they can be shown in a monospace
 * block; everything else stays as written (shown with white-space: pre-wrap, never as HTML).
 */
export function textSegments(text: string | null | undefined): TextSegment[] {
  if (!text) return [];
  const out: TextSegment[] = [];
  const re = /^```[^\n]*\n([\s\S]*?)^```[ \t]*$/gm;
  let last = 0;
  for (let m = re.exec(text); m; m = re.exec(text)) {
    const before = text.slice(last, m.index).replace(/\n$/, '');
    if (before.trim()) out.push({ code: false, text: before });
    out.push({ code: true, text: m[1].replace(/\n$/, '') });
    last = m.index + m[0].length;
  }
  const rest = text.slice(last).replace(/^\n/, '');
  if (rest.trim()) out.push({ code: false, text: rest });
  return out;
}
