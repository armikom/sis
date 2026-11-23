# MCP server — technical reference

SIS has a built-in [MCP](https://modelcontextprotocol.io) server, so students can study the question pool from their
own AI agent and staff can run the course — roster, chapters and topics, question review, exams and results,
attendance — from theirs. Ported from question-pool (`docs/mcp.md` there), made course-aware and extended to the
other modules. This page is for people who run or change the site.

## Overview

| Item | Value |
|---|---|
| Endpoint | `<public base URL>/mcp` (`GET /api/mcp-token` returns it as `endpoint`) |
| Transport | Streamable HTTP, stateless: one JSON-RPC message per `POST`, plain `application/json` answer. No SSE stream, no `Mcp-Session-Id`. `GET` and `DELETE` answer `405`. Notifications and client responses answer `202`. |
| Protocol versions | `2025-11-25`, `2025-06-18`, `2025-03-26`, `2024-11-05` (the client's version is echoed if known, else the newest) |
| Auth | `Authorization: Bearer sis_…`, a personal token per person. Missing or wrong token → `401` with `WWW-Authenticate: Bearer realm="sis"`. `/mcp` is outside cookie auth and CSRF (`SecurityConfig`). |
| Capabilities | `tools`, `resources`, `prompts`, `logging` (no-op) |
| Dependencies | None. The protocol is written by hand in `McpController`. |
| AI cost | None. MCP tools never call OpenRouter; the user's own agent does the talking. |

There is no OAuth, so the claude.ai and ChatGPT web connectors cannot connect. Clients that let you set a header
work: Claude Code, Claude Desktop (through `mcp-remote`), VS Code, Cursor, Codex CLI, Gemini CLI.

## Code (`com.armikom.sis.agent`)

| File | Role |
|---|---|
| `McpController` | `/mcp`: token auth, JSON-RPC dispatch, `initialize` (role-specific `instructions`), tools, resources. While a message runs, the token's person is the Spring Security principal, so `CourseAccess` and the pool controllers work unchanged. |
| `McpTool` | Tool record (name, schema, audience, read-only flag, handler), schema helpers (every schema gets the optional `course` property), `Args` reader |
| `McpCall` | One tool call: person, arguments, course resolution + audience check |
| `McpCourses` | The person's pool courses and the `course` argument resolution |
| `McpToolProvider` | A bean that contributes tools; the controller collects every provider in `@Order` |
| `McpStudentTools` | Tools for everyone and for students; progress queries (also used by teacher tools) |
| `McpTeacherTools` | Staff tools of the pool: participation, review, flags, roster, chapters and topics, pool exams |
| `McpExamTools` | Staff tools for exams and results (`ExamService`, `ExamAnalysisService`) |
| `McpAttendanceTools` | Staff tools for attendance sessions and records (`SessionService`, `AttendanceService`) |
| `McpPrompts` | Ready-made prompts |
| `McpTokenService` / `McpTokenController` | Tokens; `/api/mcp-token` (GET info, POST create/replace, DELETE) |

Tables (in `V1__init.sql`): `mcp_token`, `study_state`, `practice_answer`, `study_log`. Topic tools use `topic`,
`question_topic`, `exam_question_topic` (`V2__topics.sql`) through `PoolTopicController` / `Topics`; exam result
tools call `ExamService`.

Tools reuse the pool REST controllers (`PoolChapterController`, `PoolQuestionController`, `PoolStaffController`,
`PoolExamController`) and core's `RosterService`, so every rule applies in the same way: attempt first, first
answer counts, question limit per chapter, closed chapters, note required for REVISE/REJECT, pool switched on, and
so on.

## Courses

- A person's **MCP courses** are the not archived courses with a live enrollment: every course where they are staff
  (exams, attendance and the roster do not need the pool) and the `pool_enabled` ones where they are a student.
  `list_courses` shows them with the role and the `pool` flag; pool tools in a course without the pool fail with
  "The question pool is not enabled for this course.".
- Every tool takes an optional `course` argument: the course code (spaces and case ignored, e.g. `SENG403`) or the
  course id. Without it, the person's only pool course is used; with two or more the tool returns an error that
  lists the courses and asks for `course`. A code that exists in several terms needs the id.
- Tools addressed by a question, flag, comment or exam id (`get_question`, `answer_question`, `decide_question`,
  `resolve_flag`, `get_exam`, …) take the course from that row; `course` is not needed.
- **Audience per course:** student tools (`S`) need a STUDENT enrollment in that course; teacher tools (`I`) need
  OWNER/INSTRUCTOR/ASSISTANT there (or system admin). `tools/list` shows `S` tools to people with any student pool
  course and `I` tools to people with any staff course (or admins); `ALL` tools to everyone.
- System admins may address any course by id or code even without an enrollment.

## Tokens

- One token per person (`mcp_token.person_id` is unique). Creating a new one replaces the old one.
- Format `sis_` + 43 random URL-safe characters. Only the SHA-256 hash and a short hint (`sis_abc…wxyz`) are stored.
- `last_used_at` is updated at most once a minute.
- A token only works while the person is active and not deleted.
- Staff see in `class_overview` who has a token and when it was last used.

## Tools

`ALL` = everyone, `S` = students of the course, `I` = staff of the course. Read-only tools carry
`readOnlyHint: true`. All take `course?` (not repeated below).

### Shared (ALL)

| Tool | Arguments | Notes |
|---|---|---|
| `list_courses` | — | My pool courses: `id`, `code`, `name`, `term`, `language`, `role`, `staff` |
| `whoami` | — | Nickname, courses; for the course (if it can be decided) role and question limit. The real name only for staff. |
| `list_chapters` | — | Chapters with approved count and own numbers |
| `read_chapter` | `chapter`, `language` (`en`/`tr`) | Markdown text |
| `list_questions` | `chapter` | Stems of approved and waiting questions (for duplicate checks) |
| `get_question` | `question_id` | Key, reasons and explanation hidden until answered (except author and staff) |
| `get_comments` | `question_id` | Only after the question is answered |
| `post_comment` | `question_id`, `body`, `parent_id?` | |
| `list_topics` | `chapter?` | Topics of the course (numbered sections of the chapter texts) with `question_count` (approved linked) |
| `read_topic` | `topic` (id) or `chapter` + `number`, `lang?` (`en`/`tr`, default course language) | Section text in Markdown; falls back to the other language with a `note` |

`chapter` is always the chapter **number** within the course.

### Students (S)

| Tool | Arguments | Notes |
|---|---|---|
| `get_progress` | — | `weak_topics` (top 5 of `review_topics`); per chapter: `can_answer`, `answered`, `first_try_correct`, `not_answered`, `to_review`, `practice_answers`, `my_questions`, `my_approved`, `questions_to_write`, `study_status`, `confidence`, `notes`; totals; last 5 diary entries; `suggestion` |
| `next_question` | `chapter?`, `topic?` (id) | Random approved, unanswered, not own; lowest chapter first; `topic` = only questions linked to it. No key. |
| `answer_question` | `question_id`, `selected` | Writes `question_attempt` (first answer). Refuses if already answered. |
| `next_review_question` | `chapter?`, `topic?` (id) | Answered questions, last answer wrong first, then the oldest seen. No key. |
| `practice_answer` | `question_id`, `selected` | Writes `practice_answer`; never changes the first answer |
| `update_study_state` | `chapter`, `status?`, `confidence?` (1–5), `notes?` (≤4000) | Upsert; missing fields keep their value; notes replace |
| `my_exam_results` | `exam_id?` | Without: published exams with totals (`GET /api/courses/{id}/my-results`). With: the result detail of `GET …/my-results/{examId}` (questions with answer, key, options + rationales, explanation, topics, class stats; `stats`; `reviewTopics`) |
| `review_topics` | — | Weak topics, weakest first: `exam_missed`/`exam_total` (published exams, same rules as the result page), `pool_wrong`/`pool_answered` (first answers to approved linked questions), `unanswered_questions` |
| `log_study_session` | `summary` (≤1500), `chapter?` | Appends to `study_log` |
| `flag_question` | `question_id`, `reason` | |
| `my_questions` | — | |
| `submit_question` | `chapter`, `stem`, `option_a..d`, `correct`, `rationale_a..d`, `reference`, `topic_ids?` | Same checks as the web form; starts the AI check |
| `revise_question` | `question_id` + changed fields (incl. `topic_ids`) | Missing fields are taken from the current question |

"To review" means: the latest answer of the student to that question (practice answer if any, else the first
answer) was wrong. Diary entries without a chapter belong to no course: the student sees them in `get_progress` of
every course, staff do not see them in `student_detail`.

### Staff (I)

| Tool | Arguments | Notes |
|---|---|---|
| `class_overview` | — | Participation report of the course + `has_mcp_token`, `mcp_last_used_at` |
| `student_detail` | `student` (id, e-mail, number, nickname or part of the name) | Detail + `progress`, `study_log` (20), `mcp`; several matches → list |
| `study_overview` | — | Per chapter counts of study states, average confidence, practice numbers; up to 200 student notes |
| `import_students` | `csv` | Core roster import, mode MERGE (never removes). Lines `number, name[, e-mail]` or `e-mail, name, number`; a student number is required. |
| `question_counts` | — | Per chapter by state |
| `list_questions_for_review` | `status?` (default `PENDING`), `chapter?` | All fields, parsed AI review, real author name |
| `decide_question` | `question_id`, `action` (`APPROVE`/`REVISE`/`REJECT`), `note?` | Note required except APPROVE; APPROVE starts the AI explanation |
| `edit_question` | `question_id` + changed fields | State does not change |
| `question_stats` | `chapter?`, `min_answers?` (default 1) | Approved questions, `correct_pct`, `chose_a..d`, open flags; hardest first |
| `list_flags` | `include_resolved?` | |
| `resolve_flag` | `flag_id` | |
| `hide_comment` | `comment_id` | `destructiveHint: true` |
| `course_roster` | — | Roster of the course: `personId`, `studentNo`, `fullName`, `email` |
| `add_student` | `student_no`, `full_name`, `email?` | One roster entry (`RosterService.add`); many → `import_students` |
| `set_chapter_open` | `chapter`, `open` | |
| `create_chapter` | `number`, `title`, `doc_en?`, `doc_tr?`, `open?` | Topics derived at once; returns the chapter with its topics |
| `update_chapter` | `chapter` + any of `number`, `title`, `doc_en`, `doc_tr`, `open` | A text replaces the whole text of that language (read it first); topics re-derived, ids stay |
| `list_exams` | — | Exams with pool questions |
| `get_exam` | `exam_id` | Items with key and numbers (printing stays on the web site) |
| `set_question_topics` | `question_id`, `topic_ids` | Replaces the topics (`[]` clears), any state |
| `sync_topics` | `chapter?` | Re-derives topics from the chapter text(s); ids stay, linked topics are never deleted |
| `topic_stats` | `chapter?` | Per topic: `linked_questions`, `approved_questions`, `first_answers`, `first_answer_correct_pct`, `exam_questions`, `exam_answers`, `exam_correct_pct` (full points, effective exam topics, all exams) |
| `publish_exam` | `exam_id`, `published?` (default true) | Publishes / hides exam results for students |
| `create_exam` | `title`, `questions_per_chapter` (`{"6": 3}`, chapter numbers), `forms?`, `skip_flagged?` | Fails and saves nothing if a chapter is short |

Not available by MCP on purpose: deleting people, roster entries, exams or sessions, exam result deletion, frame
images, the session key / display config, the AI helper of the discussion thread (it would spend the site's
OpenRouter money).

## Prompts and resources

- Student prompts: `study_chapter(week, course?)`, `quiz_me(week?, count?, course?)`, `review_mistakes(week?,
  course?)`, `improve_my_question(week, course?)` (`week` = chapter number), `review_exam(exam?, course?)` (missed
  topics of a published exam: `my_exam_results` → `read_topic` → explain with options/rationales → practise with
  `next_question(topic)`).
- Staff prompts: `review_pending(week?, course?)`, `weekly_report(course?)`, `tag_exam_topics(exam?, course?)`
  (propose topics for every question of an exam, link them with `set_exam_question_topics` after approval).
- Resources: `sis://courses/<courseId>/chapters/<number>/<en|tr>` (Markdown) for every chapter of the person's pool
  courses, also as the template `sis://courses/{course_id}/chapters/{number}/{language}`.

## Behaviour rules for agents

`initialize` returns `instructions` that the client gives to the model (staff text, student text, or both for a
person who is staff in one course and student in another):

- **Students:** never reveal or hint the key before the student chooses; never choose for the student; start with
  `get_progress`, save weak points, end with `log_study_session`; give feedback on questions but do not write them;
  ask before posting, flagging or submitting; no lab work; simple English, Turkish when the student writes Turkish;
  staff can read the study state.
- **Staff:** ask before every change; notes to students in simple English; never show real names to students.

The server enforces the hard rules (hidden key, first answer, limits). The soft rules depend on the agent.

## Errors

- JSON-RPC errors: `-32700` bad JSON, `-32600` not an object (batches are not supported), `-32601` unknown method,
  `-32602` unknown tool/prompt or missing prompt argument, `-32002` unknown resource, `-32001` (with HTTP 401) bad
  token.
- Tool errors come back as a normal result with `isError: true` and a sentence the agent can act on (for example
  "Answer the question first." or the course-selection message). Unexpected exceptions are logged with the person
  id and return a general message.
- Every successful tool call is logged at INFO: `MCP tool <name> by person <id>`.

## Adding a tool

1. Add an `McpTool.read(...)` or `McpTool.write(...)` entry to `tools()` in `McpStudentTools` or `McpTeacherTools`.
   Choose the audience; describe it in simple English, because the model reads the description.
2. Build the schema with `schema("name!", str(...), "other", integer(...))` (`!` = required; `course` is added).
3. Get the course with `call.courseId()` (from `course` / default) or `call.courseOfQuestion(id)` /
   `call.courseOf(sql, id, msg)`; these also check the audience. Read arguments with `call.args()`.
4. Throw `IllegalArgumentException` or `PoolException` with a clear message; it reaches the agent as a tool error.
   Prefer calling an existing controller method, so the web rules stay in one place.
5. Add it to the tool tables in this file.

## Testing by hand

```bash
TOKEN=sis_...   # POST /api/mcp-token (or the AI agent page)
URL=http://localhost:8080/mcp
call() { curl -s $URL -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -d "$1"; echo; }

call '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
call '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
call '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_courses","arguments":{}}}'
call '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_progress","arguments":{"course":"SENG403"}}}'
```

Or with Claude Code: `claude mcp add --transport http sis $URL --header "Authorization: Bearer $TOKEN"`.
