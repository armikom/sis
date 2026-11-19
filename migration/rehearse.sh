#!/usr/bin/env bash
# Full local dress rehearsal of the migration in a throw-away postgres:17 container.
#
# Usage: rehearse.sh <yoklama.sql> <questionpool.sql>
# Env:   OWNER_QP_EMAIL (required): question-pool e-mail of yoklama user gokcer (see migrate.sql, staff_map),
#        REHEARSE_PORT (default: a free port), KEEP=1 to leave the container running afterwards,
#        BOOTSTRAP_ADMIN=0 to skip simulating the admin person the app creates on first start.
#        migration/private/link-midterm1.sql (holds the Midterm 1 answer key, not in git) runs when present.
# Needs: docker, psql (PSQL env var to override the binary).
set -euo pipefail

if [ $# -ne 2 ]; then echo "usage: $0 <yoklama.sql> <questionpool.sql>" >&2; exit 2; fi
: "${OWNER_QP_EMAIL:?set OWNER_QP_EMAIL to the question-pool e-mail of yoklama user gokcer}"
HERE=$(cd "$(dirname "$0")" && pwd)
YOKLAMA_DUMP=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
QP_DUMP=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
MIGRATIONS="$HERE/../src/main/resources/db/migration"
read -r -a PSQL_CMD <<< "${PSQL:-psql}"
NAME=sis-rehearsal-$$
PORT=${REHEARSE_PORT:-$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')}
URL="postgresql://postgres:rehearsal@127.0.0.1:$PORT/sis"

cleanup() {
  if [ "${KEEP:-0}" = "1" ]; then echo "container $NAME left running: $URL"; else docker rm -f "$NAME" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

echo ">> starting postgres:17 on 127.0.0.1:$PORT"
docker run -d --name "$NAME" -e POSTGRES_PASSWORD=rehearsal -e POSTGRES_DB=sis -p "127.0.0.1:$PORT:5432" postgres:17 >/dev/null
for _ in $(seq 1 60); do
  if "${PSQL_CMD[@]}" "$URL" -Atqc 'select 1' >/dev/null 2>&1; then break; fi
  sleep 1
done
"${PSQL_CMD[@]}" "$URL" -Atqc 'select 1' >/dev/null

echo ">> applying the Flyway migrations V*.sql in order (what the app does on first start)"
for f in $(ls "$MIGRATIONS"/V*.sql | sort -V); do
  "${PSQL_CMD[@]}" "$URL" -v ON_ERROR_STOP=1 -q -f "$f"
done

if [ "${BOOTSTRAP_ADMIN:-1}" = "1" ]; then
  echo ">> simulating the bootstrap admin person created by the app on first start"
  "${PSQL_CMD[@]}" "$URL" -v ON_ERROR_STOP=1 -q -c "insert into person(full_name, username, password_hash, nickname, system_admin) values ('Administrator', 'admin', 'bootstrap-hash', 'Instructor', true)"
fi

echo ">> load-legacy"
PSQL="${PSQL_CMD[*]}" "$HERE/load-legacy.sh" "$YOKLAMA_DUMP" "$QP_DUMP" "$URL"

echo ">> migrate"
"${PSQL_CMD[@]}" "$URL" -v ON_ERROR_STOP=1 -v owner_qp_email="$OWNER_QP_EMAIL" -f "$HERE/migrate.sql"

echo ">> verify"
"${PSQL_CMD[@]}" "$URL" -v ON_ERROR_STOP=1 -f "$HERE/verify.sql"

if [ -f "$HERE/private/link-midterm1.sql" ]; then
  echo ">> link Midterm 1 to the question pool"
  "${PSQL_CMD[@]}" "$URL" -v ON_ERROR_STOP=1 -q -f "$HERE/private/link-midterm1.sql"
else
  echo ">> skip Midterm 1 link (migration/private/link-midterm1.sql not present)"
fi

echo ">> guard test: a second migrate run must fail loudly"
if "${PSQL_CMD[@]}" "$URL" -v ON_ERROR_STOP=1 -v owner_qp_email="$OWNER_QP_EMAIL" -q -f "$HERE/migrate.sql" >/dev/null 2>&1; then
  echo "ERROR: second migrate run did not fail" >&2; exit 1
fi
echo "   ok (refused)"

echo ">> final report"
"${PSQL_CMD[@]}" "$URL" -c "select section, metric, value from legacy_report.migration_report order by case section when 'target' then 1 when 'people' then 2 when 'enrollment' then 3 else 4 end, metric"
echo "REHEARSAL OK"
