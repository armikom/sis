#!/usr/bin/env bash
# Nightly SIS backup: gzip'd pg_dump + tar of the frames volume, 14 days retention.
#
#   BACKUP_DIR=/srv/backups/sis ./backup.sh
#   cron:  15 2 * * *  cd /opt/sis/deploy && BACKUP_DIR=/srv/backups/sis ./backup.sh >> /var/log/sis-backup.log 2>&1
#
# Env: BACKUP_DIR (required), KEEP_DAYS (default 14), COMPOSE_PROJECT (default: compose project of this directory),
#      FRAMES (default 1; set 0 to skip the frames archive).
set -euo pipefail

: "${BACKUP_DIR:?BACKUP_DIR must be set, e.g. BACKUP_DIR=/srv/backups/sis}"
KEEP_DAYS=${KEEP_DAYS:-14}
cd "$(dirname "$0")"
DC=(docker compose)
[ -n "${COMPOSE_PROJECT:-}" ] && DC+=(-p "$COMPOSE_PROJECT")

STAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP_DIR"
umask 077

echo "[$(date -Is)] pg_dump -> $BACKUP_DIR/sis-db-$STAMP.sql.gz"
"${DC[@]}" exec -T db pg_dump -U sis --no-owner --no-privileges sis | gzip -9 > "$BACKUP_DIR/sis-db-$STAMP.sql.gz.part"
mv "$BACKUP_DIR/sis-db-$STAMP.sql.gz.part" "$BACKUP_DIR/sis-db-$STAMP.sql.gz"

if [ "${FRAMES:-1}" = "1" ]; then
  echo "[$(date -Is)] frames -> $BACKUP_DIR/sis-frames-$STAMP.tar.gz"
  "${DC[@]}" exec -T app tar -C /app/data/frames -czf - . > "$BACKUP_DIR/sis-frames-$STAMP.tar.gz.part"
  mv "$BACKUP_DIR/sis-frames-$STAMP.tar.gz.part" "$BACKUP_DIR/sis-frames-$STAMP.tar.gz"
fi

echo "[$(date -Is)] pruning backups older than $KEEP_DAYS days"
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'sis-db-*.sql.gz' -o -name 'sis-frames-*.tar.gz' -o -name '*.part' \) -mtime "+$KEEP_DAYS" -delete
echo "[$(date -Is)] done"
