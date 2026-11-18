# Question pool and agent API

Port of question-pool's REST API into SIS, scoped per course. Packages: `com.armikom.sis.pool` (REST + AI) and
`com.armikom.sis.agent` (MCP endpoint and tokens).

## Conventions

- **Field names are snake_case** in requests and responses (as in question-pool). The rest of SIS uses
  camelCase; only the pool/agent endpoints below are snake_case.
- All endpoints need a signed-in person (cookie `sis_session`) and, for POST/PUT/DELETE, the CSRF header
  (`X-XSRF-TOKEN` from the `XSRF-TOKEN` cookie), like every other `/api/**` call. `/mcp` is the exception
  (bearer token, no cookie, no CSRF).
- Timestamps are ISO-8601 strings.
- **Access.** "member" = live enrollment in the course in any role, or system admin. "staff" = OWNER, INSTRUCTOR
  or ASSISTANT of the course, or system admin. The course of `/api/pool/chapters/{id}` and
  `/api/pool/questions/{id}` is derived from the chapter / question.
- **Pool switch.** Every endpoint needs `course.poolEnabled`. When it is off: 404 for non-staff, 409 for staff
  (message "The question pool is not enabled for this course.").
- **Errors** use the SIS shape `{ "code": "...", "message": "...", "details": {} }`. For pool/agent endpoints
  `message` is the human-readable English text question-pool sent (e.g. "You already have 2 questions in this
  chapter."), so the UI can show `message` as it is. Codes: `VALIDATION_ERROR` (400), `UNAUTHORIZED` (401),
  `FORBIDDEN` (403), `NOT_FOUND` (404), `CONFLICT` (409), `RATE_LIMITED` (429), `BAD_GATEWAY` (502).
- An entity addressed under `/api/pool/courses/{courseId}/staff/...` must belong to that course, else 404.
- `{courseId}` / `{id}` are numeric database ids. Student ids in the participation report are **person ids**.

## Rules (unchanged from question-pool)

- Attempt first: `correct`, `rationale_a..d`, `reference`, `explanation` are hidden from a student until their
  first answer; the discussion thread (comments, ask-ai, flag) is closed until then (403 "Answer the question
  first.").
- Only the first answer counts; a second `attempt` does not change it (the response is still 200 with the stored
  answer in `my_selected`).
- The author and staff never answer (400 "You cannot answer this question.").
- Lifecycle: create/edit → `PENDING` and the AI check runs (`ai_status` RUNNING → DONE/FAILED; SKIPPED without
  `OPENROUTER_API_KEY`) → staff decision `APPROVED` / `NEEDS_REVISION` / `REJECTED`. On approval the AI writes the
  study `explanation`. Authors may edit/delete only while PENDING or NEEDS_REVISION (delete: anything but APPROVED).
- Per-chapter limit: a student may have `course.questionsPerChapter` non-rejected questions per chapter; closed
  chapters take no new questions from students. Staff are exempt from both.
- Ask-AI: `course.askAiPerDay` AI answers per student per rolling 24 h (counted within the course); staff exempt.
- Students see nicknames only; staff comments show as "Instructor"; `real_name` is filled only for staff viewers.
- People who were removed (soft-deleted) keep the authorship of their questions, comments and answers.

## Student endpoints (member)

### `GET /api/pool/courses/{courseId}/me`
```
{ "course": {"id", "code", "name", "term", "language"}, "staff": bool, "nickname",
  "questions_per_chapter", "ask_ai_per_day", "ai_enabled": bool }
```
(Replaces what the Vue app read from `/api/me`: `role` → `staff`, `questions_per_chapter`.)

### `GET /api/pool/courses/{courseId}/chapters`
`[ { "id", "number", "title", "open", "has_doc_en", "has_doc_tr", "approved", "my_questions", "my_attempts",
"my_correct" } ]` ordered by number.

### `GET /api/pool/chapters/{id}`
`{ "id", "number", "title", "open", "doc_en", "doc_tr", "course_id", "topics" }` (`course_id` is new; `topics`: see
"Topics").

### `GET /api/pool/chapters/{chapterId}/questions`
`[ { "id", "stem", "status", "mine", "author", "attempted", "my_correct", "comments" } ]` — approved, pending and
needs-revision questions of the chapter plus all of my own (rejected included), ordered by id.

### `GET /api/pool/courses/{courseId}/questions/mine`
`[ { "id", "stem", "status", "ai_status", "instructor_note", "created_at", "chapter_id", "chapter_number",
"chapter_title", "attempts", "comments" } ]`

### `POST /api/pool/questions`
Body (all strings except `chapter_id`):
```
{ "chapter_id", "stem", "option_a", "option_b", "option_c", "option_d", "correct",
  "rationale_a", "rationale_b", "rationale_c", "rationale_d", "reference" }
```
Limits: stem 15–800, options 1–300 (all four different, case-insensitive), correct A–D, rationales 10–600,
reference 3–200 characters. → `{ "id" }`

### `GET /api/pool/questions/{id}`
```
{ "id", "chapter_id", "course_id", "stem", "option_a".."option_d", "correct", "rationale_a".."rationale_d",
  "reference", "status", "ai_status", "ai_review" (JSON text or null), "instructor_note", "explanation",
  "created_at", "author", "mine", "revealed", "my_selected" }
```
Non-privileged viewers (not author, not staff) only see APPROVED questions (else 404); for them `ai_status`,
`ai_review`, `instructor_note` are absent, and before their first answer `correct`, `rationale_*`, `reference`,
`explanation` are absent too. `course_id` is new.

### `PUT /api/pool/questions/{id}` — author only, same body as POST (`chapter_id` ignored) → `{ "id" }`
### `DELETE /api/pool/questions/{id}` — author only, not when APPROVED → `{ "ok": true }`
### `POST /api/pool/questions/{id}/attempt` — `{ "selected": "A" }` → same shape as `GET /questions/{id}`
### `GET /api/pool/questions/{id}/comments`
`[ { "id", "parent_id", "body", "is_ai", "created_at", "mine", "author", "wrote_question", "real_name" } ]`
### `POST /api/pool/questions/{id}/comments` — `{ "body", "parent_id"? }` (body 1–2000) → `{ "id" }`
### `POST /api/pool/questions/{id}/ask-ai` — `{ "body", "parent_id"? }` → `{ "id" }` (id of the student's comment;
the AI reply is stored as its child). 400 when AI is off, 429 over the daily limit, 502 when the AI fails.
### `POST /api/pool/questions/{id}/flag` — `{ "reason" }` (5–500) → `{ "ok": true }`

## Staff endpoints (`/api/pool/courses/{courseId}/staff/...`)

### `GET students` — participation report
Live STUDENT enrollments of the course (people not deleted), ordered by name:
`[ { "id", "full_name", "student_no", "email", "nickname", "active", "last_login_at", "questions", "approved",
"chapters", "attempts", "correct", "comments", "flags" } ]` — all counts within this course.

### `GET students/{id}` (person id)
```
{ "id", "full_name", "student_no", "email", "nickname", "last_login_at",
  "questions": [ {"id", "stem", "status", "created_at", "chapter_number", "ai_review"} ],
  "comments":  [ {"id", "question_id", "body", "created_at", "deleted", "stem"} ],
  "flags":     [ {"id", "question_id", "reason", "resolved", "created_at"} ],
  "attempts_by_chapter": [ {"chapter_number", "attempts", "correct"} ] }
```

### `GET questions?status=PENDING&chapter_id=&topic_id=`
`status` default PENDING; `chapter_id` optional (`chapterId` accepted too); `topic_id` optional. Rows also carry
`topics` (see "Topics").
`[ { "id", "chapter_id", "chapter_number", "stem", "option_a".."option_d", "correct", "rationale_a".."rationale_d",
"reference", "status", "ai_status", "ai_review", "instructor_note", "explanation", "created_at", "author",
"author_name", "author_id", "attempts", "correct_attempts", "open_flags" } ]`

### `GET questions/counts` → `[ { "status", "count" } ]`
### `POST questions/{id}/decision` — `{ "action": "APPROVE"|"REVISE"|"REJECT", "note" }` (note required for REVISE/REJECT) → `{ "ok": true }`
### `PUT questions/{id}` — question body as above; wording fix, state unchanged → `{ "ok": true }`
### `POST questions/{id}/ai-check` → `{ "ok": true }`
### `POST questions/{id}/explain` → `{ "ok": true }`
### `GET flags`
`[ { "id", "question_id", "reason", "resolved", "created_at", "stem", "status", "student_name", "nickname" } ]`
(open first, newest first)
### `POST flags/{id}/resolve` → `{ "ok": true }`
### `POST comments/{id}/hide` → `{ "ok": true }`

### Chapters
- `POST chapters` — `{ "number", "title", "doc_en"?, "doc_tr"?, "open"? }` → `{ "id" }` (409 when the number
  exists; number 1–999, title 1–300).
- `PUT chapters/{id}` — `{ "title"?, "doc_en"?, "doc_tr"?, "open"?, "number"? }`; null = unchanged → `{ "ok": true }`
  (`number` is new).
- `DELETE chapters/{id}` — only without questions (409 otherwise) → `{ "ok": true }`

### Exams (built on the unified `exam` / `exam_question` tables)
- `GET exams` → `[ { "id", "title", "forms", "created_at", "questions" } ]` — only exams with at least one
  pool-linked question, newest first.
- `POST exams` — `{ "title", "forms"? (1–4, default 2), "counts": { "<chapter id>": n, ... },
  "skip_flagged"? (default true) }` → `{ "id" }`. Draws approved questions at random; fails and saves nothing when a
  chapter has too few. Creates `exam` (forms, random seed) and one `exam_question` per question (type CHOICE,
  choice_count 4, correct_choice, text = stem, topic = chapter title, max_points 1, pool_question_id, explanation, and
  the four options with rationales in `exam_question_option`).
- `GET exams/{id}` → `{ "id", "title", "forms", "created_at", "items": [ { "item_id", "question_id",
  "chapter_number", "stem", "option_a".."option_d", "correct", "status", "author_name", "attempts",
  "correct_attempts", "open_flags" } ] }` (`item_id` = exam_question id; chapter order).
- `DELETE exams/{id}` → `{ "ok": true }` (409 when the exam already has results).
- `POST exams/{id}/items/{itemId}/swap` → `{ "ok": true }` — another random approved question of the same chapter.
- `POST exams/{id}/items/{itemId}/remove` → `{ "ok": true }`
  (swap/remove: 409 when the exam already has results).
- `GET exams/{id}/forms/{form}` (form = 1..forms) → `{ "title", "form": "A", "questions": [ { "number",
  "question_id", "chapter_number", "stem", "options": [4 strings], "correct": "A".."D" } ] }` — question order
  shuffled inside each chapter and options shuffled per form; deterministic from the exam seed.

## Topics

A **topic** is a numbered section of a chapter text: the `## N. Title` level-2 headings of `doc_en` / `doc_tr`
(table `topic`, migration `V2__topics.sql`). Pool questions (`question_topic`) and exam questions
(`exam_question_topic`) can be linked to one or more topics of their course (any chapter of it).

### How topics are derived (`TopicSync`)
- One topic per distinct number `N` found in a `## N. …` heading of either language (numbers may start at 0).
  `title_en` / `title_tr` = the heading text after `N.` (trimmed, rest kept as is, e.g. `The RIPR model (slides
  8–11)`); `""` when that language has no such heading. Unnumbered level-2 headings (`## Key terms`) are not
  topics; `### …` headings belong to their parent section; headings inside fenced code blocks are ignored; when a
  number appears twice in one document the first heading wins.
- Upsert by `(chapter_id, number)`: ids stay stable. A topic whose number disappeared from both documents is deleted
  only when no question / exam question links to it; otherwise it is kept with its old titles.
- Runs on application start for every chapter without topics, after every chapter `POST` / `PUT` (staff), and on
  demand (`…/topics/sync` below).
- **Section content** of a topic in a language = the Markdown from its heading line (inclusive) to the next
  level-2 heading (exclusive), trailing blank lines removed; `""` when that language has no such section.

### Shapes
- Chapter topic: `{ "id", "number", "title_en", "title_tr" }`
- Question topic: `{ "id", "chapter_id", "chapter_number", "number", "title_en", "title_tr" }`
- Ordering everywhere: chapter number, then topic number.

### `GET /api/pool/chapters/{id}` — now also `"topics": [chapter topic…]` ordered by number.

### `GET /api/pool/courses/{courseId}/topics` (member)
`[ { "id", "chapter_id", "chapter_number", "chapter_title", "number", "title_en", "title_tr", "question_count" } ]`
— `question_count` = APPROVED pool questions linked to the topic. Ordered by chapter number, number.
Like the topic preview, this does **not** need `poolEnabled` (exam editors and exam results use topics).

### `GET /api/pool/topics/{id}` (member of the topic's course, **also when the pool is disabled**)
`{ "id", "course_id", "chapter_id", "chapter_number", "chapter_title", "number", "title_en", "title_tr",
"content_en", "content_tr" }` — 404 "Topic not found." when it does not exist, 403 for non-members.

### `POST /api/pool/courses/{courseId}/staff/chapters/{id}/topics/sync` (staff)
Runs TopicSync for the chapter → `[chapter topic…]` (the chapter's topics after the sync).

### Question links
- `POST /api/pool/questions`, `PUT /api/pool/questions/{id}` (author) and `PUT …/staff/questions/{id}` accept an
  optional `"topic_ids": [1, 2]`. Absent / null = unchanged (none on create); present = replaces the set (`[]`
  clears). Every id must be a topic of the question's course (any chapter), else 400 "A topic does not belong to
  this course." Duplicates are ignored.
- `PUT /api/pool/courses/{courseId}/staff/questions/{id}/topics` — `{ "topic_ids": [..] }` (required, `[]` clears)
  → `{ "ok": true, "topics": [question topic…] }`. Works in every question state.
- `"topics": [question topic…]` is in every question response: `GET /questions/{id}` and the `attempt` answer
  (always visible, also before the first answer), each row of `GET /courses/{courseId}/questions/mine`, of
  `GET /chapters/{chapterId}/questions` and of the staff `GET questions`.
- Filters: `GET /api/pool/chapters/{chapterId}/questions?topic_id=` and staff `GET questions?topic_id=` (optional).

### Exams (core API, camelCase)
- Exam DTO (`GET/POST/PUT /api/exams…`) and the exam list rows gain `"published": bool` (default false).
- `POST /api/exams/{id}/publish` and `POST /api/exams/{id}/unpublish` (course staff) → exam DTO.
- Each exam question DTO gains `"topicIds": [..]` (explicit links, `exam_question_topic`) and `"topics"`: the
  EFFECTIVE topics = explicit ∪ topics of the linked pool question (`poolQuestionId`):
  `[ { "id", "chapterId", "chapterNumber", "chapterTitle", "number", "titleEn", "titleTr" } ]`.
- Exam create/update accept `"topicIds"` per question (null = keep current links on update / none on create, list =
  replace); every id must be a topic of the exam's course, else 400 `VALIDATION_ERROR` with
  `details["questions[i].topicIds"]`.
- Question text and explanation: each exam question DTO also has `"explanation": string|null` and
  `"options": [ { "letter", "text", "rationale" } ]` (ordered by letter, `[]` when none recorded; `rationale` may be
  null). `text` is no longer limited to 2000 characters (cap 20 000; explanation cap 20 000; option text / rationale
  cap 5 000). Create/update accept both per question: absent/null = unchanged on update (none on create); a blank
  `explanation` clears it; `options` present = replaces the set. CHOICE: letters A..(choiceCount), each once, else
  400 `details["questions[i].options[j].letter"]`; OPEN/FREE accept only `[]` (else
  `details["questions[i].options"]`). Shrinking `choiceCount` does not drop stored options beyond the new range.
- The pool exam builder fills `explanation` (the pool question's explanation at draw time) and the four options
  A–D with the pool question's option texts and rationales in the pool's own letter order (consistent with
  `correctChoice`); swap replaces them. The printable forms still read the pool question.

### Student results (core API, camelCase)
- `GET /api/courses/{id}/my-results` lists only **published** exams with a result for the caller (shape unchanged:
  `[ { "examId", "title", "examDate", "points", "maxPoints" } ]`).
- `GET /api/courses/{id}/my-results/{examId}` → 404 unless the exam belongs to the course, is published and has a
  result for the caller:
  ```
  { "examId", "title", "examDate", "points", "maxPoints",
    "stats": { "participants", "average", "median", "maxPoints" },
    "questions": [ { "position", "text", "type", "myAnswer", "correctChoice", "correct", "points", "maxPoints",
                     "topics": [exam topic…], "options": [ { "letter", "text", "rationale" } ], "explanation",
                     "stats": { "answered", "correctShare", "averagePoints", "choiceCounts" } } ],
    "reviewTopics": [ { "topic": {exam topic}, "missed", "total" } ] }
  ```
  Class aggregates only (never names, numbers or per-student rows): top-level `stats` over every result of the
  exam — `participants` = number of results, `average` / `median` of the totals, `maxPoints` = the **highest
  total** scored in the class (the possible maximum is the top-level `maxPoints`); 2 decimals, null without
  results. Per question `stats`: `answered` = results with a stored answer row for the question, `correctShare` =
  share (0..1, 4 decimals) of the scored answers with full points (null when none is scored), `averagePoints` =
  average points of the scored answers (2 decimals, null when none), `choiceCounts` = for CHOICE `{ "A": n, …
  up to choiceCount, "blank": n }` (letters outside the range are not counted), `{}` for OPEN/FREE.
  `questions` ordered by position, all questions of the exam. `myAnswer` = the stored answer text (null for FREE or
  no answer). `correct` = true/false for a CHOICE question with a key and a stored answer row, else null. A question
  is **missed** when it has points and points < maxPoints (ungraded questions are not missed). `reviewTopics`: per
  effective topic, `total` = the exam's questions with that topic, `missed` = how many of them were missed; only
  topics with missed > 0, sorted by missed desc, then chapterNumber, number. Never other students' data.

## Agent

### `GET /api/mcp-token`
`{ "endpoint": "<base>/mcp", "has_token": bool, "hint"?, "created_at"?, "last_used_at"? }`
### `POST /api/mcp-token` → same plus `"token"` (shown once; replaces the old token)
### `DELETE /api/mcp-token` → `{ "ok": true }`

### `POST /mcp`
MCP Streamable HTTP (stateless JSON-RPC, one message per POST), `Authorization: Bearer <token>`. See `docs/MCP.md`.
