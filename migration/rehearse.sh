#!/usr/bin/env bash
# Full local dress rehearsal of the migration in a throw-away postgres:17 container.
#
# Usage: rehearse.sh <yoklama.sql> <questionpool.sql>
# Env:   REHEARSE_PORT (default: a free port), KEEP=1 to leave the container running afterwards,
#        BOOTSTRAP_ADMIN=0 to skip simulating the admin person the app creates on first start.
# Needs: docker, psql (PSQL env var to override the binary).
set -euo pipefail

if [ $# -ne 2 ]; then echo "usage: $0 <yoklama.sql> <questionpool.sql>" >&2; exit 2; fi
HERE=$(cd "$(dirname "$0")" && pwd)
YOKLAMA_DUMP=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
QP_DUMP=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
V1="$HERE/../src/main/resources/db/migration/V1__init.sql"
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

echo ">> applying V1__init.sql (what Flyway does on first app start)"
"${PSQL_CMD[@]}" "$URL" -v ON_ERROR_STOP=1 -q -f "$V1"

if [ "${BOOTSTRAP_ADMIN:-1}" = "1" ]; then
  echo ">> simulating the bootstrap admin person created by the app on first start"
  "${PSQL_CMD[@]}" "$URL" -v ON_ERROR_STOP=1 -q -c "insert into person(full_name, username, password_hash, nickname, system_admin) values ('Administrator', 'admin', 'bootstrap-hash', 'Instructor', true)"
fi

echo ">> load-legacy"
PSQL="${PSQL_CMD[*]}" "$HERE/load-legacy.sh" "$YOKLAMA_DUMP" "$QP_DUMP" "$URL"

echo ">> migrate"
"${PSQL_CMD[@]}" "$URL" -v ON_ERROR_STOP=1 -f "$HERE/migrate.sql"

echo ">> verify"
"${PSQL_CMD[@]}" "$URL" -v ON_ERROR_STOP=1 -f "$HERE/verify.sql"

echo ">> link Midterm 1 to the question pool"
"${PSQL_CMD[@]}" "$URL" -v ON_ERROR_STOP=1 -q -f "$HERE/link-midterm1.sql"

echo ">> guard test: a second migrate run must fail loudly"
if "${PSQL_CMD[@]}" "$URL" -v ON_ERROR_STOP=1 -q -f "$HERE/migrate.sql" >/dev/null 2>&1; then
  echo "ERROR: second migrate run did not fail" >&2; exit 1
fi
echo "   ok (refused)"

echo ">> final report"
"${PSQL_CMD[@]}" "$URL" -c "select section, metric, value from legacy_report.migration_report order by case section when 'target' then 1 when 'people' then 2 when 'enrollment' then 3 else 4 end, metric"
echo "REHEARSAL OK"
