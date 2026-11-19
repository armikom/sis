# SIS — Student Information System

One course-centred system that merges **yoklama** (rotating-QR lecture attendance, exam results and learning analysis) and **question-pool** (student-written question bank). See `docs/PROPOSAL.md` for the design and `docs/DECISIONS.md` for implementation defaults.

## Architecture

- **Backend:** Spring Boot 4.1 (Java 21), one modular monolith under `com.armikom.sis`:
  - `core` — people, courses (with term and per-course settings), enrollments (OWNER / INSTRUCTOR / ASSISTANT / STUDENT), chapters, sign-in (e-mail magic link + username/password, DB-backed sessions), mail (SMTP or EWS), per-course authorisation (`CourseAccess`)
  - attendance (`attendance`, `checkin`, `session`, `classroom`, `crypto`, `decision`, `frames`, `live`, `export`) — rotating-QR check-in, unchanged protocol (`docs/legacy-yoklama/02-PROTOCOL.md` is normative)
  - `exam` — exams, result entry, analysis
  - `pool` — student-written question pool per course (chapters, questions, attempts, discussion, flags, staff review, AI check via OpenRouter, exam builder on the unified exam tables); API in `docs/API-POOL.md`
  - `agent` — MCP server at `/mcp` (personal bearer tokens, course-aware tools for students and staff); see `docs/MCP.md`
- **Frontend:** Angular 22 (standalone, zoneless, signals), QR decoding via zxing-wasm
- **Database:** PostgreSQL only; schema in `src/main/resources/db/migration` (Flyway)
- **BLE beacon:** ESP32 firmware in `firmware/` (optional proximity check)

## Local development

Prerequisites: JDK 21+, Node.js 24.15+ (or 26+), Docker.

```bash
docker compose up -d db                 # PostgreSQL 17 on 127.0.0.1:5433 (db/user/password: sis)
./mvnw -Pno-frontend spring-boot:run    # dev profile; creates admin / admin123 on first start
cd frontend && npm start                # http://localhost:4200, /api proxied to :8080
```

Without SMTP/EWS settings, sign-in links are written to the application log.

### Tests

```bash
./mvnw -Pno-frontend test               # backend (integration tests start PostgreSQL via Testcontainers)
cd frontend && npm test -- --watch=false
./mvnw verify                           # everything, including the frontend build
```

### Whole stack in Docker

```bash
cp .env.example .env    # optional
docker compose up --build
```

App: http://localhost:8790 — `admin` / `admin123` unless changed in `.env`. Production setup: `deploy/`.

## Configuration

| Variable | Description |
|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | Database (dev default `jdbc:postgresql://localhost:5433/sis`, `sis`/`sis`) |
| `APP_MASTER_SECRET` | 32-byte base64 key; must stay the same across deployments (QR session keys, device hints) |
| `APP_PUBLIC_BASE_URL` | Fallback public URL for QR and login links (normally taken from the request / X-Forwarded-*) |
| `APP_LINK_HOSTS` | Optional comma-separated host allow-list for mailed login links |
| `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD` | System administrator created on start when no person has that username |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM` | SMTP for sign-in links |
| `EWS_URL` | Exchange Web Services endpoint; when set, mail goes through EWS with `SMTP_USER`/`SMTP_PASSWORD` |
| `APP_FRAMES_DIR` | Camera frame storage (default `./data/frames`, 7-day retention) |
| `OPENROUTER_API_KEY` | Question pool AI (question check, study explanations, ask-AI); empty = AI off |
| `OPENROUTER_MODEL` / `OPENROUTER_URL` | Optional; default `anthropic/claude-sonnet-5.5` and the OpenRouter chat API |
| `TZ` | Time zone for CSV exports (default `Europe/Istanbul`) |

## Usage

- **Sign in** at `/login`: everybody with an e-mail on a course roster (or staff list) gets a single-use link valid for 15 minutes; staff may also use username + password. Staff land on `/t`, students on `/courses`.
- **Staff** (`/t`): create a course (creator becomes owner), import the roster (`studentNo, fullName[, email]` or `email, fullName, studentNo`, pasted or as CSV; click a student for their attendance, exam history and question-pool activity on one page), add co-instructors/assistants, set course options, run attendance sessions (projector view, live dashboard, review, CSV) and exams.
- **Students**: check in at `/` or by scanning the projected QR (`/c/:code`, `/q/:token`, no sign-in needed; only student numbers on the course roster are accepted); signed in, `/courses` shows their own attendance and exam results.
- **Question pool** (course setting `poolEnabled`): staff create chapters and their texts, review student questions (approve / send back / reject), handle reports, see participation and draw exams with up to 4 shuffled forms; students write questions (`questionsPerChapter` per chapter), answer others' questions (answer hidden until the first attempt, only the first counts) and discuss them. Limits and the AI course description (`aiContext`) are course settings.
- **AI agents**: everyone can create a personal MCP token (`/api/mcp-token`) and connect Claude Code or another MCP client to `/mcp`; see `docs/MCP.md`.
- **System admin**: `/t/admin/people` — search, edit, deactivate or delete people.

## Security

- One session mechanism: opaque random token in the HttpOnly `sis_session` cookie, only its SHA-256 stored in `user_session`, 30-day expiry, survives restarts. CSRF protection via the `XSRF-TOKEN` cookie / `X-XSRF-TOKEN` header.
- QR tokens are HMAC-SHA256 signed; signature comparisons are constant-time.
- Device cookie (HttpOnly) and IP/device rate limiting on public endpoints and sign-in.
- Flags, latencies and thresholds never reach students; the decision engine only flags, the instructor decides.
- Camera frames are deleted automatically after 7 days.
