import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type * as P from './pool.models';

type Id = number | string;

/** Question-pool endpoints (/api/pool/**, /api/mcp-token). Errors come through apiErrorInterceptor as ApiError. */
@Injectable({ providedIn: 'root' })
export class PoolApiService {
  private readonly http = inject(HttpClient);

  private get<T>(url: string, params?: Record<string, string>) { return firstValueFrom(this.http.get<T>(url, { params })); }
  private post<T>(url: string, body: unknown = {}) { return firstValueFrom(this.http.post<T>(url, body)); }
  private put<T>(url: string, body: unknown) { return firstValueFrom(this.http.put<T>(url, body)); }
  private del<T>(url: string) { return firstValueFrom(this.http.delete<T>(url)); }
  private staff(courseId: Id, rest: string) { return `/api/pool/courses/${courseId}/staff/${rest}`; }

  /* Course context */
  me(courseId: Id) { return this.get<P.PoolMe>(`/api/pool/courses/${courseId}/me`); }

  /* Chapters */
  chapters(courseId: Id) { return this.get<P.ChapterSummary[]>(`/api/pool/courses/${courseId}/chapters`); }
  chapter(id: Id) { return this.get<P.ChapterDetail>(`/api/pool/chapters/${id}`); }
  chapterQuestions(chapterId: Id, topicId?: Id | null) {
    return this.get<P.QuestionListItem[]>(`/api/pool/chapters/${chapterId}/questions`, topicId ? { topic_id: String(topicId) } : undefined);
  }

  /* Topics */
  courseTopics(courseId: Id) { return this.get<P.CourseTopic[]>(`/api/pool/courses/${courseId}/topics`); }
  topic(id: Id) { return this.get<P.TopicDetail>(`/api/pool/topics/${id}`); }
  syncTopics(courseId: Id, chapterId: Id) { return this.post<P.ChapterTopic[]>(this.staff(courseId, `chapters/${chapterId}/topics/sync`)); }
  /** The response shape is not fixed by the contract: the topics, or the question with `topics`. */
  setQuestionTopics(courseId: Id, questionId: Id, topicIds: number[]) {
    return this.put<P.PoolTopicRef[] | { topics?: P.PoolTopicRef[] } | null>(this.staff(courseId, `questions/${questionId}/topics`), { topic_ids: topicIds });
  }

  /* Questions */
  myQuestions(courseId: Id) { return this.get<P.MyQuestion[]>(`/api/pool/courses/${courseId}/questions/mine`); }
  question(id: Id) { return this.get<P.QuestionDetail>(`/api/pool/questions/${id}`); }
  createQuestion(q: P.QuestionInput) { return this.post<{ id: number }>('/api/pool/questions', q); }
  updateQuestion(id: Id, q: P.QuestionInput) { return this.put<unknown>(`/api/pool/questions/${id}`, q); }
  deleteQuestion(id: Id) { return this.del<unknown>(`/api/pool/questions/${id}`); }
  attempt(id: Id, selected: string) { return this.post<P.QuestionDetail>(`/api/pool/questions/${id}/attempt`, { selected }); }
  flag(id: Id, reason: string) { return this.post<unknown>(`/api/pool/questions/${id}/flag`, { reason }); }
  comments(id: Id) { return this.get<P.PoolComment[]>(`/api/pool/questions/${id}/comments`); }
  comment(id: Id, body: string, parentId: number | null, askAi: boolean) {
    return this.post<unknown>(`/api/pool/questions/${id}/${askAi ? 'ask-ai' : 'comments'}`, { body, parent_id: parentId });
  }

  /* Staff */
  staffQuestions(courseId: Id, status: string, chapterId?: Id | null, topicId?: Id | null) {
    const params: Record<string, string> = { status };
    if (chapterId) params['chapter_id'] = String(chapterId);
    if (topicId) params['topic_id'] = String(topicId);
    return this.get<P.ReviewQuestion[]>(this.staff(courseId, 'questions'), params);
  }
  questionCounts(courseId: Id) { return this.get<P.StatusCount[]>(this.staff(courseId, 'questions/counts')); }
  decide(courseId: Id, questionId: Id, action: 'APPROVE' | 'REVISE' | 'REJECT', note: string) {
    return this.post<unknown>(this.staff(courseId, `questions/${questionId}/decision`), { action, note });
  }
  staffUpdateQuestion(courseId: Id, questionId: Id, q: P.QuestionInput) { return this.put<unknown>(this.staff(courseId, `questions/${questionId}`), q); }
  rerun(courseId: Id, questionId: Id, what: 'ai-check' | 'explain') { return this.post<unknown>(this.staff(courseId, `questions/${questionId}/${what}`)); }
  students(courseId: Id) { return this.get<P.StudentRow[]>(this.staff(courseId, 'students')); }
  student(courseId: Id, personId: Id) { return this.get<P.StudentDetail>(this.staff(courseId, `students/${personId}`)); }
  flags(courseId: Id) { return this.get<P.FlagRow[]>(this.staff(courseId, 'flags')); }
  resolveFlag(courseId: Id, flagId: Id) { return this.post<unknown>(this.staff(courseId, `flags/${flagId}/resolve`)); }
  hideComment(courseId: Id, commentId: Id) { return this.post<unknown>(this.staff(courseId, `comments/${commentId}/hide`)); }
  createChapter(courseId: Id, c: P.ChapterRequest) { return this.post<{ id: number }>(this.staff(courseId, 'chapters'), c); }
  updateChapter(courseId: Id, chapterId: Id, c: P.ChapterRequest) { return this.put<unknown>(this.staff(courseId, `chapters/${chapterId}`), c); }
  deleteChapter(courseId: Id, chapterId: Id) { return this.del<unknown>(this.staff(courseId, `chapters/${chapterId}`)); }

  /* Exams */
  exams(courseId: Id) { return this.get<P.ExamSummary[]>(this.staff(courseId, 'exams')); }
  createExam(courseId: Id, e: P.ExamRequest) { return this.post<{ id: number }>(this.staff(courseId, 'exams'), e); }
  exam(courseId: Id, examId: Id) { return this.get<P.ExamDetail>(this.staff(courseId, `exams/${examId}`)); }
  deleteExam(courseId: Id, examId: Id) { return this.del<unknown>(this.staff(courseId, `exams/${examId}`)); }
  examItem(courseId: Id, examId: Id, itemId: Id, what: 'swap' | 'remove') {
    return this.post<unknown>(this.staff(courseId, `exams/${examId}/items/${itemId}/${what}`));
  }
  examForm(courseId: Id, examId: Id, form: Id) { return this.get<P.ExamForm>(this.staff(courseId, `exams/${examId}/forms/${form}`)); }

  /* MCP token (not course-scoped) */
  mcpToken() { return this.get<P.McpTokenInfo>('/api/mcp-token'); }
  createMcpToken() { return this.post<P.McpTokenInfo>('/api/mcp-token'); }
  deleteMcpToken() { return this.del<unknown>('/api/mcp-token'); }
}
