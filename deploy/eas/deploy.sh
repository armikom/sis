#!/usr/bin/env bash
# Deploy the committed HEAD of this repository to eas.armikom.com (/srv/apps/sis).
#
#   deploy/eas/deploy.sh            # build HEAD on the server, back up the database, restart the app
#   EAS_HOST=user@host deploy/eas/deploy.sh
#
# Only committed code is shipped (git archive HEAD); uncommitted changes are ignored with a warning.
# First-time setup (.env, cron, DNS) is described in deploy/eas/README.md.
set -euo pipefail

EAS_HOST=${EAS_HOST:-armikom@51.178.103.153}
APP_DIR=/srv/apps/sis
cd "$(git rev-parse --show-toplevel)"

SHA=$(git rev-parse --short=12 HEAD)
if ! git diff --quiet HEAD -- src frontend pom.xml Dockerfile .mvn mvnw; then
  echo "warning: uncommitted changes in src/frontend/pom are NOT deployed (shipping $SHA)" >&2
fi

echo "==> upload source $SHA"
git archive --format=tar HEAD | ssh "$EAS_HOST" "set -e; mkdir -p $APP_DIR; cd $APP_DIR; rm -rf src.new; mkdir src.new; tar -x -C src.new; rm -rf src; mv src.new src; echo $SHA > src/REVISION"
scp -q deploy/eas/compose.yml deploy/eas/sis.caddy deploy/backup.sh "$EAS_HOST:$APP_DIR/"

ssh "$EAS_HOST" SHA="$SHA" APP_DIR="$APP_DIR" bash -s <<'REMOTE'
set -euo pipefail
cd "$APP_DIR"
chmod 700 "$APP_DIR"; chmod +x backup.sh
[ -f .env ] || { echo ".env missing in $APP_DIR (see README)"; exit 1; }
chmod 600 .env

echo "==> caddy site"
if ! sudo cmp -s sis.caddy /etc/caddy/sites/sis.caddy; then
  sudo install -m 644 sis.caddy /etc/caddy/sites/sis.caddy
  sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null
  sudo systemctl reload caddy
fi

echo "==> build sis-app:$SHA"
docker build --pull -q -t "sis-app:$SHA" src

if docker compose ps --status running --services 2>/dev/null | grep -qx db; then
  echo "==> backup before update"
  BACKUP_DIR=/srv/backups/sis ./backup.sh
fi

PREV=$(docker image inspect -f '{{.Id}}' sis-app:current 2>/dev/null || true)
[ -n "$PREV" ] && docker tag sis-app:current sis-app:previous
docker tag "sis-app:$SHA" sis-app:current

echo "==> up"
docker compose up -d --remove-orphans
for i in $(seq 1 60); do
  state=$(docker inspect -f '{{.State.Health.Status}}' sis-app-1 2>/dev/null || echo starting)
  [ "$state" = healthy ] && break
  [ "$state" = unhealthy ] && break
  sleep 5
done
echo "app: $state ($SHA)"
# keep the three newest commit tags (plus current/previous)
docker images sis-app --format '{{.Tag}}' | grep -vE '^(current|previous)$' | tail -n +4 | xargs -r -I{} docker rmi -f sis-app:{} >/dev/null || true
docker image prune -f >/dev/null
[ "$state" = healthy ] || { docker compose logs --tail 80 app; echo "rollback: docker tag sis-app:previous sis-app:current && docker compose up -d app"; exit 1; }
REMOTE
