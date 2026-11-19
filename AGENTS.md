# Coding agent instructions

Read `README.md`, `docs/PROPOSAL.md` (sections 2–3) and `docs/DECISIONS.md` before changing code. Legacy yoklama specs are in `docs/legacy-yoklama/`.

## How to work
- Do not ask questions; pick a sensible default and append it as one line to `docs/DECISIONS.md` (`- [Area] decision — reason`).
- The schema is `src/main/resources/db/migration`; change it only through Flyway migrations (PostgreSQL only, no H2).
- Run the tests before committing: `./mvnw -Pno-frontend test` (Testcontainers needs Docker) and `cd frontend && npm test -- --watch=false`; `cd frontend && npx ng build` must pass.
- If a library API differs from the docs (Spring Boot 4.1, Angular 22, zxing-wasm 3.x, NimBLE 2.x), adapt to the installed version without changing behaviour.

## Public repository
This repository is public (github.com/armikom/sis). Never commit exam questions, answer keys, the question bank, real
student or staff names/e-mails/numbers, database dumps or secrets. Course-private migration data goes to the
git-ignored `migration/private/`; question banks and exam documents never go into the repository. Test data uses
`example.edu` addresses and made-up names. Before committing, check `git diff --cached` for such content.

## Production (https://sis.armikom.com)
Runs on eas.armikom.com, `/srv/apps/sis` (details: `deploy/eas/README.md`; server rules: `/srv/apps/AGENTS.md`).
The production database holds real students' attendance, answers and exam results.
- Deploy only with `deploy/eas/deploy.sh` (ships the committed `HEAD` of `main`). Never edit code or files on the server:
  `src/` is replaced on every deploy and the containers are read-only.
- Never change production data or schema by hand (`psql`, ad-hoc `UPDATE`/`DELETE`). Schema changes go through Flyway
  migrations; one-off data fixes go through a reviewed migration or an app feature, with the user's approval.
- Migrations are forward-only. `deploy.sh` takes a database backup before switching (`/srv/backups/sis`); do not skip
  or bypass it. A migration that rewrites or drops data needs the user's go-ahead before deploying.
- Rollback of the code: `ssh armikom@51.178.103.153 'cd /srv/apps/sis && docker tag sis-app:previous sis-app:current && docker compose up -d app'`.
  If a migration already ran, the old code may not start: restore the pre-deploy backup instead (`deploy/eas/README.md`).
- After a deploy check `curl -s https://sis.armikom.com/actuator/health` → `"status":"UP"`.

## Production settings that must not change
- `SERVER_FORWARD_HEADERS_STRATEGY=native` (`deploy/eas/compose.yml`): behind Cloudflare the visitor IP is appended to a
  client-sent `X-Forwarded-For`; the default `framework` strategy takes the leftmost (forgeable) value and breaks the
  per-IP rate limits. Keep `native` (rightmost non-internal address) whatever the proxy setup.
- `APP_MASTER_SECRET` (server `.env`): signs QR/check-in tokens and encrypts classroom BLE keys. Never change it once
  classrooms exist; before migrating old yoklama data it must equal the old yoklama value.
- `APP_LINK_HOSTS` / `APP_PUBLIC_BASE_URL`: only these hosts appear in mailed sign-in links (stops Host-header
  poisoning). A new public hostname must be added here, never removed from the check.
- Ports: the app is published only on `127.0.0.1:8101`; the database has no published port and sits on an
  `internal: true` network. Do not publish either to `0.0.0.0`.

## Rules that must not change
- **Check-in protocol** (`docs/legacy-yoklama/02-PROTOCOL.md`) is normative: token format, HMAC inputs, encodings and test vectors are implemented exactly.
- No client wall clock (`Date.now()`) in measurements and decisions — only `performance.now()` + measured offset. Server: the `Clock` bean.
- Students never receive flags, latency values or thresholds; students see nicknames of other students, never names.
- The session key only goes to course staff via `display-config`; raw tokens, session keys and BLE keys are never logged.
- Check-in, manual attendance and exam results accept only students on the course roster (live STUDENT enrollment).

## Auth contract for modules
- Current person: inject `com.armikom.sis.core.auth.CourseAccess` and call `currentPerson()` → `SisPrincipal(personId, fullName, email, studentNo, nickname, systemAdmin)`.
- Per-course checks: `requireMember(courseId)`, `requireStaff(courseId)`, `requireOwnerOrAdmin(courseId)` (return the `Course`; 401/403/404 as `ApiException`), `isStaff(courseId)`, `roleIn(courseId)`, `isStaffAnywhere()`, `requireSystemAdmin()`. System admins pass every check.
- `/api/admin/**` requires a system admin; `/api/public/**` is open and CSRF-exempt; all other `/api/**` need a signed-in person.

## Pool and agent modules
- `com.armikom.sis.pool` / `com.armikom.sis.agent` keep question-pool's JSON field names: snake_case request records (`@JsonNaming(SnakeCaseStrategy)`) and map responses built from SQL column names. Do not change the global Jackson naming. The endpoint contract is `docs/API-POOL.md`; keep it in sync.
- Pool errors: throw `PoolException` (English message shown by the UI); access through `PoolAccess` (course membership/staff + `poolEnabled`).
- MCP tools call the pool controllers so the rules stay in one place; `/mcp` runs with the token's person as the security principal (`docs/MCP.md`).

## Code style
- Java 21 (records, switch expressions), constructor injection, `final` fields, DTOs as records. Spring Boot 4 / Jackson 3: `tools.jackson.*`.
- Angular: standalone + signals + `inject()`, zoneless, `@if/@for/@switch`. New staff routes under `t` (staffGuard), student routes under `courses` (authGuard).
- User-facing text Turkish (`frontend/src/app/shared/messages.ts`; pool screens may be bilingual); identifiers, comments and commit messages English.
- No new dependencies without need; no UI library.

## Commands
```bash
docker compose up -d db                  # PostgreSQL on 127.0.0.1:5433
./mvnw -Pno-frontend spring-boot:run     # dev profile
cd frontend && npm start                 # 4200, /api → 8080 proxy
./mvnw verify                            # backend + frontend build + tests
deploy/eas/deploy.sh                     # production: eas.armikom.com (deploy/eas/README.md), ships committed HEAD
```
