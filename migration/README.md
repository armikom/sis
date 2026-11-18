# SIS data migration (yoklama + question-pool -> SIS)

One-off, rehearsed, single-transaction migration. Primary keys are preserved everywhere except `person`
(frame paths, question links, exam references stay valid).

| File | Purpose |
|---|---|
| `load-legacy.sh` | loads the two pg_dump files into schemas `legacy_yoklama` and `legacy_qp` of the SIS database |
| `migrate.sql` | `INSERT ... SELECT` into the SIS tables, one transaction, fails loudly; leaves a report in schema `legacy_report` |
| `verify.sql` | ~90 independent checks (counts, hashes, orphans, enrollments, duplicates, sequences); raises on any failure |
| `rehearse.sh` | the whole chain against a throw-away `postgres:17` container |

Editable defaults are at the top of `migrate.sql`: the course code that owns the question pool (`SENG 403`),
the course skip list (`TEST101`), the test-student rule (e-mail domain other than `stu.thk.edu.tr`, unless the
account authored questions), the term (`2026-FALL`), the pool settings and the yoklama-username -> question-pool-e-mail
mapping list (`gokcer` -> `owner@example.edu`).

## 0. Rehearse first (any laptop with docker and psql >= 17.6)

```bash
migration/rehearse.sh /path/yoklama.sql /path/questionpool.sql      # ends with "REHEARSAL OK"
```

The dumps hold real personal data: keep them outside the repository, `chmod 600`, delete them after the cutover.

## 1. Prerequisites

- SIS stack deployed (see `deploy/README.md`) but **not yet used** by anyone.
- `psql` 17.6 or newer (older clients choke on the `\restrict` lines of recent pg_dump files), or use the
  psql inside the SIS db container (variant B below).
- `APP_MASTER_SECRET` of SIS equals the old yoklama one.

## 2. Take the dumps (final ones: after both old stacks are stopped)

```bash
# host "ops"
ssh ops 'docker exec yoklama-db-1 pg_dump -U yoklama --no-owner --no-privileges yoklama' > yoklama.sql
# host "armikom-vm-external"
ssh armikom-vm-external 'docker exec question-pool-db-1 pg_dump -U questionpool --no-owner --no-privileges questionpool' > questionpool.sql
chmod 600 yoklama.sql questionpool.sql
```

## 3. Let Flyway create the schema, then stop the app

```bash
cd deploy
docker compose up -d --build          # first start applies V1__init.sql, creates the bootstrap admin person
docker compose logs -f app            # wait for "Started ...Application", Ctrl-C
docker compose stop app               # nobody may write while migrating
```

(The bootstrap admin created from `APP_ADMIN_USERNAME` is tolerated: when its username equals a yoklama teacher
account it is taken over and receives the old password hash. Any other pre-existing person aborts the run.)

## 4. Load, migrate, verify

Variant A, psql on the host (db port must be reachable, e.g. run on `ops` with a temporary `-p 127.0.0.1:5433:5432`
override, or use variant B):

```bash
DB=postgresql://sis:$DB_PASSWORD@127.0.0.1:5433/sis
migration/load-legacy.sh yoklama.sql questionpool.sql "$DB"
psql "$DB" -v ON_ERROR_STOP=1 -f migration/migrate.sql
psql "$DB" -v ON_ERROR_STOP=1 -f migration/verify.sql
```

Variant B, psql inside the db container (nothing published):

```bash
cd deploy
DB=postgresql://sis:$DB_PASSWORD@localhost:5432/sis
PSQL="docker compose exec -T db psql" ../migration/load-legacy.sh ../yoklama.sql ../questionpool.sql "$DB"
docker compose exec -T db psql -U sis -d sis -v ON_ERROR_STOP=1 < ../migration/migrate.sql
docker compose exec -T db psql -U sis -d sis -v ON_ERROR_STOP=1 < ../migration/verify.sql
```

`verify.sql` must end with `checks_failed = 0` and `all checks passed`. `migrate.sql` is atomic: if it errors,
nothing was written; fix the cause and run it again. If it already committed and you must redo it:
`docker compose down && docker volume rm sis_pg_data && docker compose up -d db app`, then repeat from step 3
(or, on the same database, truncate all SIS tables - do not do that by hand on production data).

Read the report (`select * from legacy_report.migration_report;`): counts per table, name mismatches,
auto-enrolled students (attendance/exam rows of students not on the roster), skipped rows.

## 5. Frames volume

Old volume `yoklama_frame_data` (mounted at `/app/data/frames` in `yoklama-app-1`) -> SIS volume `sis_frame_data`,
same relative paths (the `frame_path` columns were copied unchanged). Both on host `ops`:

```bash
docker run --rm -v yoklama_frame_data:/from:ro -v sis_frame_data:/to alpine sh -c 'cp -a /from/. /to/'
cd deploy
docker compose run --rm --no-deps -u root app chown -R yoklama:yoklama /app/data/frames   # user name from the Dockerfile
docker compose up -d app
```

Check: `docker compose exec app sh -c 'find /app/data/frames -type f | wc -l'` equals the old count.

## 6. After the cutover

Keep the legacy schemas read-only for a month (nobody connects to them), then:

```sql
drop schema legacy_yoklama cascade;
drop schema legacy_qp cascade;
drop schema legacy_report cascade;   -- contains student numbers and names
```

Delete the dump files, and retire the old stacks and volumes only after that month.

## Rollback

- Before committing: `migrate.sql` rolls back on any error.
- After cutover: the old stacks and volumes were only stopped, never changed. Point the hostnames
  `yoklama.armikom.com` and `seng403-questions.armikom.com` back to the old services and start the old containers
  (`docker compose start` in the old projects). Attendance and answers recorded in SIS after the cutover are not
  copied back - decide quickly, or export them from SIS first.
- Data decisions that cannot be undone by the script: name differences keep question-pool's name, students
  seen only in attendance/exam data were auto-enrolled (both listed in `legacy_report`).

## What is migrated, what is not

Not migrated on purpose: question-pool `login_token`, `user_session` (everyone signs in again once), question-pool
`exam`/`exam_item` (asserted empty), yoklama `require_roster` (check-in is roster-only now), skipped courses and test
accounts. Passwords: yoklama BCrypt hashes are copied unchanged; question-pool students have no password (e-mail links).
