# MCP server — technical reference

SIS has a built-in [MCP](https://modelcontextprotocol.io) server for the question pool, so students and staff can
use the pool from their own AI agent. Ported from question-pool (`docs/mcp.md` there) and made course-aware. This
page is for people who run or change the site.

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
| `McpStudentTools` | Tools for everyone and for students; progress queries (also used by teacher tools) |
| `McpTeacherTools` | Tools for course staff |
| `McpPrompts` | Ready-made prompts |
| `McpTokenService` / `McpTokenController` | Tokens; `/api/mcp-token` (GET info, POST create/replace, DELETE) |

Tables (in `V1__init.sql`): `mcp_token`, `study_state`, `practice_answer`, `study_log`.

Tools reuse the pool REST controllers (`PoolChapterController`, `PoolQuestionController`, `PoolStaffController`,
`PoolExamController`) and core's `RosterService`, so every rule applies in the same way: attempt first, first
answer counts, question limit per chapter, closed chapters, note required for REVISE/REJECT, pool switched on, and
so on.

## Courses

- A person's **pool courses** are the courses with `pool_enabled`, not archived, where they have a live enrollment
  (`list_courses` shows them with the role).
- Every tool takes an optional `course` argument: the course code (spaces and case ignored, e.g. `SENG403`) or the
  course id. Without it, the person's only pool course is used; with two or more the tool returns an error that
  lists the courses and asks for `course`. A code that exists in several terms needs the id.
- Tools addressed by a question, flag, comment or exam id (`get_question`, `answer_question`, `decide_question`,
  `resolve_flag`, `get_exam`, …) take the course from that row; `course` is not needed.
- **Audience per course:** student tools (`S`) need a STUDENT enrollment in that course; teacher tools (`I`) need
  OWNER/INSTRUCTOR/ASSISTANT there (or system admin). `tools/list` shows `S` tools to people with any student pool
  course and `I` tools to people with any staff pool course (or admins); `ALL` tools to everyone.
- System admins may address any pool-enabled course by id or code even without an enrollment.

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

`chapter` is always the chapter **number** within the course.

### Students (S)

| Tool | Arguments | Notes |
|---|---|---|
| `get_progress` | — | Per chapter: `can_answer`, `answered`, `first_try_correct`, `not_answered`, `to_review`, `practice_answers`, `my_questions`, `my_approved`, `questions_to_write`, `study_status`, `confidence`, `notes`; totals; last 5 diary entries; `suggestion` |
| `next_question` | `chapter?` | Random approved, unanswered, not own; lowest chapter first. No key. |
| `answer_question` | `question_id`, `selected` | Writes `question_attempt` (first answer). Refuses if already answered. |
| `next_review_question` | `chapter?` | Answered questions, last answer wrong first, then the oldest seen. No key. |
| `practice_answer` | `question_id`, `selected` | Writes `practice_answer`; never changes the first answer |
| `update_study_state` | `chapter`, `status?`, `confidence?` (1–5), `notes?` (≤4000) | Upsert; missing fields keep their value; notes replace |
| `log_study_session` | `summary` (≤1500), `chapter?` | Appends to `study_log` |
| `flag_question` | `question_id`, `reason` | |
| `my_questions` | — | |
| `submit_question` | `chapter`, `stem`, `option_a..d`, `correct`, `rationale_a..d`, `reference` | Same checks as the web form; starts the AI check |
| `revise_question` | `question_id` + changed fields | Missing fields are taken from the current question |

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
| `set_chapter_open` | `chapter`, `open` | |
| `list_exams` | — | Exams with pool questions |
| `get_exam` | `exam_id` | Items with key and numbers (printing stays on the web site) |
| `create_exam` | `title`, `questions_per_chapter` (`{"6": 3}`, chapter numbers), `forms?`, `skip_flagged?` | Fails and saves nothing if a chapter is short |

Not available by MCP on purpose: deleting people or roster entries, deleting exams, editing chapter texts, the AI
helper of the discussion thread (it would spend the site's OpenRouter money).

## Prompts and resources

- Student prompts: `study_chapter(week, course?)`, `quiz_me(week?, count?, course?)`, `review_mistakes(week?,
  course?)`, `improve_my_question(week, course?)` (`week` = chapter number).
- Staff prompts: `review_pending(week?, course?)`, `weekly_report(course?)`.
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
