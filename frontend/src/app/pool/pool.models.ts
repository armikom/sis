/*
 * Question-pool JSON shapes. Unlike the rest of SIS these are snake_case: the pool endpoints keep the field names of
 * the former question-pool backend (docs/API-POOL.md).
 */

export type QuestionStatus = 'PENDING' | 'NEEDS_REVISION' | 'APPROVED' | 'REJECTED';
export type AiStatus = 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED' | string | null;
export type Letter = 'A' | 'B' | 'C' | 'D';

/** GET /api/pool/courses/{courseId}/me */
export interface PoolMe {
  course: { id: number; code: string; name: string; term: string; language: string };
  staff: boolean;
  nickname: string;
  questions_per_chapter: number;
  ask_ai_per_day: number;
  ai_enabled: boolean;
}

/** GET /api/pool/courses/{courseId}/chapters — with the signed-in person's own progress. */
export interface ChapterSummary {
  id: number;
  number: number;
  title: string;
  open: boolean;
  has_doc_en: boolean;
  has_doc_tr: boolean;
  approved: number;
  my_questions: number;
  my_attempts: number;
  my_correct: number;
}

/* ---- topics: the numbered sections ("## N. Title") of a chapter text ---- */

/** One topic of GET /api/pool/chapters/{id} (`topics`) and of POST …/staff/chapters/{id}/topics/sync. */
export interface ChapterTopic {
  id: number;
  number: number;
  title_en: string;
  title_tr: string;
}

/** A topic linked to a pool question (`topics` of every single-question response and of the staff list). */
export interface PoolTopicRef extends ChapterTopic {
  chapter_id: number;
  chapter_number: number;
  chapter_title?: string;
}

/** GET /api/pool/courses/{courseId}/topics */
export interface CourseTopic extends PoolTopicRef {
  chapter_title: string;
  question_count: number;
}

/** GET /api/pool/topics/{id}: the Markdown of the section; either language may be empty. */
export interface TopicDetail extends PoolTopicRef {
  course_id: number;
  chapter_title: string;
  content_en: string | null;
  content_tr: string | null;
}

/** GET /api/pool/chapters/{id} */
export interface ChapterDetail {
  id: number;
  number: number;
  title: string;
  open: boolean;
  doc_en: string;
  doc_tr: string;
  course_id?: number;
  topics?: ChapterTopic[];
}

export interface ChapterRequest {
  number?: number;
  title?: string;
  doc_en?: string;
  doc_tr?: string;
  open?: boolean;
}

/** One row of GET /api/pool/chapters/{chapterId}/questions */
export interface QuestionListItem {
  id: number;
  stem: string;
  status: QuestionStatus;
  author: string;
  mine: boolean;
  attempted: boolean;
  my_correct: boolean | null;
  comments: number;
  topics?: PoolTopicRef[];
}

/** The editable fields of a question (POST /questions, PUT /questions/{id}). */
export interface QuestionInput {
  chapter_id: number | null;
  stem: string;
  option_a: string;
  option_b: string;
  option_c: string;
  option_d: string;
  correct: Letter | '';
  rationale_a: string;
  rationale_b: string;
  rationale_c: string;
  rationale_d: string;
  reference: string;
  /** Optional on create/update; omitted keeps the current links. */
  topic_ids?: number[];
}

/**
 * GET /api/pool/questions/{id}. Before the first attempt (and when not the author/staff) `correct`, the rationales,
 * `explanation` and `my_selected` are absent; `revealed` tells which state it is.
 */
export interface QuestionDetail extends Partial<Omit<QuestionInput, 'chapter_id' | 'topic_ids'>> {
  id: number;
  chapter_id: number;
  stem: string;
  option_a: string;
  option_b: string;
  option_c: string;
  option_d: string;
  status: QuestionStatus;
  author: string;
  mine: boolean;
  revealed: boolean;
  my_selected?: Letter | null;
  explanation?: string | null;
  instructor_note?: string | null;
  ai_status?: AiStatus;
  ai_review?: string | null;
  /** Visible before the first attempt too. */
  topics?: PoolTopicRef[];
}

/** GET /api/pool/courses/{courseId}/questions/mine */
export interface MyQuestion {
  id: number;
  chapter_id?: number;
  chapter_number?: number;
  chapter_title: string;
  stem: string;
  status: QuestionStatus;
  ai_status: AiStatus;
  attempts: number;
  comments: number;
  instructor_note: string | null;
}

export interface PoolComment {
  id: number;
  parent_id: number | null;
  author: string;
  real_name?: string | null;
  is_ai: boolean;
  wrote_question: boolean;
  mine: boolean;
  body: string;
  created_at: string;
}

/** The JSON that the automatic check stores in `ai_review`. */
export interface AiReviewResult {
  verdict?: 'OK' | 'IMPROVE' | string;
  key_correct?: 'yes' | 'no' | 'unsure' | string;
  issues?: string[];
  duplicate_of?: number[];
  english_suggestion?: string;
}

/* ---- staff ---- */

export interface ReviewQuestion extends QuestionInput {
  id: number;
  status: QuestionStatus;
  chapter_number: number;
  author: string;
  author_id: number;
  author_name: string;
  created_at: string;
  attempts: number;
  correct_attempts: number;
  open_flags: number;
  ai_status: AiStatus;
  ai_review: string | null;
  explanation: string | null;
  instructor_note: string | null;
  topics?: PoolTopicRef[];
}

export interface StatusCount { status: QuestionStatus; count: number }

export interface StudentRow {
  id: number;
  full_name: string;
  student_no: string | null;
  nickname: string;
  questions: number;
  approved: number;
  chapters: number;
  attempts: number;
  correct: number;
  comments: number;
  flags: number;
  last_login_at: string | null;
}

export interface StudentDetail {
  id: number;
  full_name: string;
  student_no: string | null;
  email: string | null;
  nickname: string;
  last_login_at: string | null;
  questions: { id: number; chapter_number: number; status: QuestionStatus; created_at: string; stem: string }[];
  attempts_by_chapter: { chapter_number: number; attempts: number; correct: number }[];
  comments: { id: number; question_id: number; stem: string; body: string; created_at: string; deleted: boolean }[];
  flags: { id: number; question_id: number; reason: string; created_at: string }[];
}

export interface FlagRow {
  id: number;
  question_id: number;
  stem: string;
  reason: string;
  resolved: boolean;
  student_name: string;
  nickname: string;
  created_at: string;
}

export interface ExamSummary { id: number; title: string; questions: number; forms: number; created_at: string }

export interface ExamRequest {
  title: string;
  forms: number;
  skip_flagged: boolean;
  /** chapter id → number of questions to draw */
  counts: Record<string, number>;
}

export interface ExamItem {
  item_id: number;
  question_id: number;
  chapter_number: number;
  author_name: string;
  attempts: number;
  correct_attempts: number;
  open_flags: number;
  status: QuestionStatus;
  stem: string;
  option_a: string;
  option_b: string;
  option_c: string;
  option_d: string;
  correct: Letter;
}

export interface ExamDetail { id: number; title: string; forms: number; created_at?: string; items: ExamItem[] }

export interface ExamForm {
  title: string;
  form: string;
  questions: { number: number; question_id: number; stem: string; options: string[]; correct: Letter }[];
}

/* ---- MCP token ---- */

export interface McpTokenInfo {
  endpoint?: string;
  has_token: boolean;
  hint?: string;
  created_at?: string;
  last_used_at?: string | null;
  /** Only in the POST response, shown once. */
  token?: string;
}
