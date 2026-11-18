# SIS — Student Information System: analysis and implementation proposal

Date: 2026-11-18. Status: proposal, nothing implemented yet.

SIS merges two running apps into one course-centred system:

- **yoklama** — rotating-QR lecture attendance (plus exam result entry and learning analysis).
- **question-pool** ("question bank") — student-written multiple-choice questions, peer answering, discussion, AI review, exam builder.

---

## 1. What exists today

### 1.1 Side by side

| | yoklama | question-pool |
|---|---|---|
| Source of truth | `github.com/gokcer/yoklama`, `main` = `5db232a` | `github.com/gokcer/question-pool`, `main` = `7d62d97` |
| Runs on | `ops` (`~/wt/yoklama-deploy`, branch `deploy-merge-1118`), host port 8790, host-level Cloudflare tunnel → `yoklama.armikom.com` | `armikom-vm-external` (`~/dockers/question-pool`), own cloudflared container → `seng403-questions.armikom.com` |
| Backend | Spring Boot 4.1, Java 21, **JPA + Spring Security**, Flyway (V1–V2) | Spring Boot 4.1.2, Java 21, **plain `JdbcClient` + hand-written cookie interceptor**, Flyway (V1–V3 deployed) |
| Frontend | **Angular 22** (zoneless, signals), 19 components, ~6 700 lines + ~1 150 lines of specs | **Vue 3 + Vite**, 21 views, ~2 100 lines, no tests |
| Java size | ~6 750 lines main + ~2 500 lines tests, normative spec in `docs/` | ~3 100 lines, no tests |
| Database | PostgreSQL 17, 9.5 MB | PostgreSQL 17, 9.6 MB |
| Staff login | username + password (`teacher_user`), roles ADMIN / TEACHER | e-mail magic link, role INSTRUCTOR, list comes from `INSTRUCTOR_EMAIL` env |
| Student identity | **no account** — session code + student number + device cookie | **account** — school e-mail magic link, nickname shown to peers |
| Multi-course | Yes. A course belongs to one owner teacher | **No.** Chapters are global; course name is hard-coded in `AiService`, the views, page title, MCP names; limits come from env |
| Other | SSE live dashboard, camera frames on disk (7-day retention), BLE beacon firmware, CSV export | OpenRouter AI (check / explain / thread answers), EWS mail through the university Exchange, printable exam forms |

### 1.2 Live data (read-only snapshot, 2026-11-18)

**yoklama**

| Table | Rows | Notes |
|---|---|---|
| `teacher_user` | 4 | `admin` (ADMIN), `gokcer`, `instructor.a@example.edu`, `instructor.b@example.edu` |
| `course` | 3 | SENG 403, CENG 101, TEST101 — all owned by `gokcer` |
| `roster_entry` | 141 | SENG 403: 64, CENG 101: 76, TEST101: 1 — 139 distinct students, **2 students are in both real courses** |
| `class_session` | 7 | SENG 403: 4, CENG 101: 2, TEST101: 1 — all CLOSED |
| `attendance` | 111 | 93 by QR check-in, 18 manual; 1 row is not on any roster |
| `checkin_attempt` / `scan_record` | 174 / 1 060 | forensic data, 30-day retention |
| `exam` / `exam_question` / `exam_result` / `exam_answer` | 1 / 25 / 64 / 1 600 | "Midterm 1" of SENG 403, 25 CHOICE questions |
| `classroom`, `calibration_run` | 0 | unused so far |
| frames volume | 67 files, 4.6 MB | 7-day retention |

**question-pool**

| Table | Rows | Notes |
|---|---|---|
| `app_user` | 69 | 65 students (63 have signed in), 4 instructors (1 soft-deleted) |
| `chapter` | 7 | Week 1–7, each with English and Turkish text (6–60 KB) |
| `question` | 97 | 82 approved, 10 needs-revision, 4 pending, 1 rejected; 30 authors |
| `attempt` | 3 623 | 51 students answered, 5–12 November |
| `comment` | 11 | 4 written by the AI |
| `exam`, `exam_item`, `flag` | 0 | the exam builder has no saved exam in production |
| `login_token`, `user_session` | 172 / 143 | disposable |

### 1.3 Findings that shape the design

1. **Student number is a clean join key.** All 64 students on the yoklama SENG 403 roster exist in question-pool with the same number. The one extra question-pool student is a test account (`@armikom.com`, 10-digit number).
2. **E-mail cannot be derived from the student number** (0 of 65 addresses contain it). The 76 CENG 101 students have no e-mail anywhere, so they cannot sign in until a class list with e-mails is imported. QR check-in must therefore stay login-free.
3. **Ownership models differ.** yoklama: one owner per course (the two assistants see nothing today). question-pool: every instructor sees everything. SIS needs course membership with roles.
4. **Two different things are called "exam".** yoklama's exam = questions + per-student answers + analysis. question-pool's exam = a paper drawn from the pool with shuffled forms A/B. They are two halves of one workflow and should become one feature.
5. **Your local yoklama folder is 13 commits behind `origin/main`.** SIS must be based on `origin/main`, not on `/Users/devops/Documents/source/akademik/yoklama` as it is now.
6. **question-pool has an uncommitted MCP feature** (V4 migration, 5 new Java files, 2 views) that is not deployed. It should be committed before the port so it is not lost; production has no MCP data to migrate.
7. **The yoklama deployment is the dev quick-start compose**: port 8790 is published on all interfaces and `APP_PUBLIC_BASE_URL` is `http://localhost:8080` (QR links only work because the base URL is taken from `X-Forwarded-*`). SIS should deploy with a proper production compose.
8. **`APP_MASTER_SECRET` must move to SIS unchanged.** Device hints (the "same phone" fingerprint teachers see) are derived from it.
9. Disk is tight on both hosts: `ops` 87 % used (16 GB free), `armikom-vm` 94 % used (114 GB free).

---

## 2. Proposed architecture

### 2.1 Shape: one modular monolith

One Spring Boot application, one PostgreSQL database, one SPA, one Docker image, one hostname.

Rejected alternative — keep both apps and add single sign-on plus a shared "course service": less code to move, but two databases, two deployments, two UIs, duplicated rosters, and no way to join attendance, pool activity and exam results per student, which is the whole point of an SIS.

### 2.2 Base: yoklama's codebase, question-pool ported in

- yoklama is twice the size, has the test suite, a normative protocol spec with test vectors, Spring Security, rate limiting, and is already multi-course. Its check-in path (clock sync, HMAC tokens, decision engine, scanner worker) is the riskiest code to rewrite, so it should move as-is.
- question-pool's backend is 3 100 lines of SQL-first controllers. It can keep `JdbcClient` inside the new app (JPA and `JdbcClient` share one `DataSource`); each query gains a course filter.

### 2.3 Frontend: Angular only

Port question-pool's 21 Vue views (~2 100 lines, ~700 of them the MCP help page) to Angular standalone components. Keeping Vue mounted under `/pool` would be faster for a week and then cost two toolchains, two style systems and two auth clients forever.

UI language: attendance screens stay Turkish, pool screens stay bilingual as they are today. A per-course language setting can come later.

### 2.4 Modules (Java packages under `com.armikom.sis`)

| Module | Comes from | Contents |
|---|---|---|
| `core` | new | person, auth (magic link + password), session, course, term, enrollment, chapter, mail (SMTP/EWS), audit |
| `attendance` | yoklama | classroom, class session, check-in, crypto, decision engine, frames, SSE, CSV export |
| `pool` | question-pool | question, attempt, comment, flag, AI service |
| `exam` | both | exam, questions, results, analysis (yoklama) + draw-from-pool, forms, print (question-pool) |
| `agent` | question-pool (uncommitted) | MCP endpoint, tokens, study state, practice answers, study log |

### 2.5 Data model

**Core (new)**

```
person        id, full_name, student_no UNIQUE NULL, email UNIQUE NULL,
              username UNIQUE NULL, password_hash NULL, nickname UNIQUE,
              system_admin bool, active, deleted_at, created_at, last_login_at
course        id, code, name, term, language,
              pool_enabled, attendance_enabled,
              questions_per_chapter, ask_ai_per_day, ai_context,
              archived_at, created_at            UNIQUE (code, term)
enrollment    course_id, person_id, role (OWNER|INSTRUCTOR|ASSISTANT|STUDENT),
              active, deleted_at                 PK (course_id, person_id)
chapter       id, course_id, number, title, doc_en, doc_tr, open
                                                 UNIQUE (course_id, number)
login_token, user_session, mcp_token             → person_id
```

- One `person` row per human. A student in two courses has two `enrollment` rows — today that student is two unrelated roster lines.
- A person without e-mail simply cannot sign in yet; they can still be on a roster and check in.
- `nickname` stays on the person (unique system-wide), so nothing about pseudonymity changes for the current SENG 403 students.
- `course.term` (for example `2026-FALL`) lets SENG 403 be offered again next year without mixing rosters and attendance. A new offering can copy chapters and approved questions from an earlier one.
- `ai_context` replaces the hard-coded "SENG 403 … (4th year)…" constant; `questions_per_chapter` and `ask_ai_per_day` replace the env values.

**Attendance** — `classroom`, `class_session`, `calibration_run`, `checkin_attempt`, `scan_record`, `attendance` keep their current columns. `roster_entry` is replaced by `enrollment`. `attendance` and `exam_result` gain a nullable `person_id`; `student_no` stays, because check-in can accept numbers that are not on the roster.

**Pool** — `question`, `comment`, `flag` unchanged (course comes through `chapter`). `attempt` is renamed `question_attempt` so it is not confused with `checkin_attempt`.

**Exam (unified)** — yoklama's four tables are the base. Added: `exam.forms`, `exam.seed`, `exam_question.pool_question_id` (nullable), `exam_result.form`. question-pool's `exam` / `exam_item` tables are dropped (0 rows in production).

### 2.6 Authentication and authorisation

- **One session mechanism**: Spring Security with a filter backed by the `user_session` table (opaque token, hash stored). Sessions survive restarts — today every yoklama redeploy logs teachers out. CSRF protection stays on for cookie sessions.
- **Sign-in**: e-mail magic link for everyone with an e-mail. Staff may also use username + password (existing hashes are migrated). MCP keeps its bearer token.
- **Check-in stays public**: `/c/:code` and `/q/:token` need no login. If a signed-in student opens them, the student number is pre-filled.
- **Authorisation is per course**: `CourseAccess.requireStaff(courseId)` replaces yoklama's 7 `requireOwner` calls and question-pool's global `/api/instructor/**` check. `system_admin` replaces the ADMIN role. Assistants become real course members.
- Rules that must not regress: flags, latencies and thresholds never reach students; the answer stays hidden until the first attempt; only the first attempt counts; students see nicknames only.

### 2.7 URLs

| Path | Who | Purpose |
|---|---|---|
| `/` | anyone | session code box (check-in) + sign in |
| `/c/:code`, `/q/:token` | anyone | check-in — **unchanged**, so projected QR codes and habits keep working |
| `/login` | anyone | magic link / staff password |
| `/courses` | student | my courses |
| `/courses/:id/chapters…`, `/questions/:id`, `/mine` | student | question pool |
| `/courses/:id/attendance`, `/courses/:id/results` | student | *new*: my attendance, my exam results |
| `/t/courses/:id/{roster,sessions,review,flags,chapters,exams,learning}` | staff | everything for one course |
| `/t/sessions/:id/display` | staff | projector view |
| `/t/admin/…` | admin | people, courses, terms |
| `/mcp` | token | AI agents; tools take a course argument |

### 2.8 Deployment

- Host: **`ops`** — yoklama already runs there behind the host tunnel, and `armikom-vm` is at 94 % disk. Compose project `sis`: `app` + `db` (PostgreSQL 17), app bound to `127.0.0.1` only.
- Hostname: `sis.armikom.com`. The two old hostnames point to the same service and redirect, so old links and bookmarks keep working (`seng403-questions…/questions/42` → `sis…/questions/42`).
- Nightly `pg_dump` plus the frames volume to a second location.

---

## 3. Data migration

### 3.1 Method

A repeatable, one-transaction SQL migration, rehearsed before the real run:

1. `pg_dump` both production databases.
2. Restore them into the SIS database as schemas `legacy_yoklama` and `legacy_qp`.
3. Run `migrate.sql`: `INSERT … SELECT` from the legacy schemas into the SIS tables, in one transaction.
4. Run `verify.sql`: counts and checksums per table, old against new. Any mismatch aborts.
5. Copy the frames volume.
6. Keep the legacy schemas read-only for a month, then drop them.

Primary keys are preserved for every table except people, so frame paths, question links and exam references stay valid. Sequences are reset afterwards.

### 3.2 People

| Source | Rule |
|---|---|
| question-pool students (65) | create `person` with e-mail, name, number, nickname, `last_login_at`, `deleted_at` |
| yoklama roster (141 rows, 139 students) | match on `student_no`; create the person if missing (74 CENG 101-only students, no e-mail) |
| name differs between the two systems | keep question-pool's, list the differences in the migration report |
| yoklama `gokcer` ↔ question-pool `owner@example.edu` | one person — explicit mapping row (username and e-mail differ) |
| `instructor.a@…`, `instructor.b@…` | match on username = e-mail |
| yoklama `admin` | person with `system_admin`, password login only |
| question-pool soft-deleted instructor | migrated with `deleted_at` so authorship stays intact |
| password hashes | copied as they are |

### 3.3 Table mapping

| Source | Rows | Target |
|---|---|---|
| y `course` | 3 | `course` (term `2026-FALL`); owner → `enrollment` OWNER |
| y `roster_entry` | 141 | `enrollment` STUDENT |
| qp `app_user.role` + `INSTRUCTOR_EMAIL` | 69 | `enrollment` in SENG 403 (65 students + 4 instructors, one soft-deleted) |
| y `class_session`, `calibration_run`, `checkin_attempt`, `scan_record`, `attendance` | 7 / 0 / 174 / 1 060 / 111 | same tables; `attendance.person_id` filled by student number |
| y `exam`, `exam_question`, `exam_result`, `exam_answer` | 1 / 25 / 64 / 1 600 | same tables; `exam_result.person_id` filled |
| y `classroom` | 0 | — |
| y frames volume | 67 files | SIS frames volume, same relative paths |
| qp `chapter` | 7 | `chapter` with `course_id` = SENG 403 |
| qp `question` | 97 | `question`, author remapped |
| qp `attempt` | 3 623 | `question_attempt`, user remapped |
| qp `comment`, `flag` | 11 / 0 | same, author remapped |
| qp `exam`, `exam_item` | 0 | not migrated |
| qp `login_token`, `user_session` | 172 / 143 | not migrated — everyone signs in once on the new hostname |
| qp env `QUESTIONS_PER_CHAPTER=2`, `ASK_AI_PER_DAY=50`, AI course text | — | `course` settings of SENG 403 |

Expected result, test accounts aside: 143 people (138 students + 5 staff accounts, one of them soft-deleted), 2 real courses, 140 student enrollments (64 + 76).

### 3.4 Cutover

1. Rehearse the full migration on a copy; compare screens old against new for both courses.
2. Pick an evening with no lecture. Stop both old stacks (containers and volumes stay).
3. Final dumps → migrate → verify.
4. Point `sis.armikom.com`, `yoklama.armikom.com` and `seng403-questions.armikom.com` at SIS.
5. Smoke test: staff login, open a test session and check in from a phone, answer a question, open Midterm 1 analysis.
6. Rollback at any point: repoint the two old hostnames to the old stacks, which are untouched.

Side effect to announce: students sign in again once, and device hints for "same phone across sessions" restart if they use the new hostname (the device cookie is per hostname).

---

## 4. Implementation phases

| # | Phase | Result | Size |
|---|---|---|---|
| 0 | Scaffold | `sis` repo from yoklama `origin/main`, package rename, CI build, docs carried over | S |
| 1 | Core | person, course + term, enrollment, unified auth and sessions, mail (SMTP/EWS), per-course access checks | M |
| 2 | Attendance on core | roster → enrollment, owner → course staff; existing test suite green; check-in protocol untouched | M |
| 3 | Pool module | question-pool backend ported and course-scoped, AI context per course, Angular views, new integration tests for the attempt-first rules | L |
| 4 | Migration | `migrate.sql`, `verify.sql`, rehearsal report | M |
| 5 | Cutover | production deploy on `ops`, hostname switch, backups | S |
| 6 | Unified exams | draw a paper from the pool → print forms → enter results per form → item analysis back on the pool question | M |
| 7 | Student self-service + agents | my attendance, my results; MCP tools made course-aware | M |

Phases 0–5 give a like-for-like replacement with all data. Phases 6–7 are where the merge starts to pay off and can follow after cutover.

---

## 5. Decisions needed

| # | Question | Proposed default |
|---|---|---|
| 1 | Frontend | Angular only; port the Vue views |
| 2 | Host and hostname | `ops`, `sis.armikom.com`, old hostnames redirect |
| 3 | Terms | add `term` to courses now; current data becomes `2026-FALL` |
| 4 | CENG 101 student logins | import a class list with e-mails when available; until then attendance only |
| 5 | TEST101 course and the two test accounts | do not migrate |
| 6 | Forensic check-in data (`checkin_attempt`, `scan_record`, frames) | migrate everything; existing retention jobs keep trimming it |
| 7 | Cutover timing | after the rehearsal, on an evening without a lecture |
