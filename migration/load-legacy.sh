#!/usr/bin/env bash
# Load the two legacy pg_dump files into schemas legacy_yoklama and legacy_qp of the SIS database.
#
# Usage: load-legacy.sh <yoklama.sql> <questionpool.sql> <target database URL>
#   e.g. load-legacy.sh yoklama.sql questionpool.sql postgresql://sis:secret@localhost:5432/sis
#
# Method: the dumps are plain SQL with every object qualified as "public.xxx". We rewrite that
# qualifier to the legacy schema name, but ONLY outside COPY data blocks (so row data such as question
# text can never be touched), and load it with ON_ERROR_STOP in a single transaction.
# Needs only psql (any version >= the pg_dump major) and awk; no CREATEDB privilege.
set -euo pipefail

if [ $# -ne 3 ]; then
  echo "usage: $0 <yoklama.sql> <questionpool.sql> <database-url>" >&2
  exit 2
fi
YOKLAMA_DUMP=$1
QP_DUMP=$2
DB_URL=$3
# PSQL may be a multi-word command, e.g. PSQL="docker compose exec -T db psql" (then use a URL valid inside the container).
read -r -a PSQL <<< "${PSQL:-psql}"

for f in "$YOKLAMA_DUMP" "$QP_DUMP"; do
  [ -r "$f" ] || { echo "cannot read $f" >&2; exit 2; }
done

load() { # <dump> <schema>
  local dump=$1 schema=$2
  echo ">> loading $dump into schema $schema"
  if [ "$("${PSQL[@]}" "$DB_URL" -Atqc "select count(*) from pg_namespace where nspname = '$schema'")" != "0" ]; then
    echo "schema $schema already exists - drop it first: drop schema $schema cascade;" >&2
    exit 1
  fi
  {
    echo "begin;"
    echo "create schema $schema;"
    awk -v schema="$schema" '
      BEGIN { in_copy = 0 }
      in_copy { if ($0 == "\\.") in_copy = 0; print; next }
      /^COPY / { in_copy = 1; gsub(/public\./, schema "."); print; next }
      { gsub(/public\./, schema "."); print }
    ' "$dump"
    echo "commit;"
  } | "${PSQL[@]}" "$DB_URL" -v ON_ERROR_STOP=1 -q -f - >/dev/null
  # pg_dump sets search_path to empty for the session only; nothing leaks.
  "${PSQL[@]}" "$DB_URL" -Atqc "select '   tables: ' || count(*) from information_schema.tables where table_schema = '$schema'"
}

load "$YOKLAMA_DUMP" legacy_yoklama
load "$QP_DUMP" legacy_qp
echo ">> done"
