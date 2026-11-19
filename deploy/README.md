# SIS production deployment (host `ops`)

> **SIS now runs on eas.armikom.com: see [`eas/README.md`](eas/README.md).** This file describes the alternative
> setup behind the Cloudflare tunnel on `ops`; its backup, migration and cutover notes still apply.

Stack: `db` (postgres:17, volume `pg_data`, not published) and `app` (built from `../Dockerfile`, volume
`frame_data` at `/app/data/frames`). The app listens only on `127.0.0.1:${APP_PORT:-8791}`; the host-level Cloudflare
tunnel (cloudflared) is the only way in. No Caddy, no certificates here. Compose project name is `sis`
(volumes `sis_pg_data`, `sis_frame_data`).

## First deployment

```bash
git clone <repo> /opt/sis && cd /opt/sis/deploy
cp .env.example .env && chmod 600 .env && $EDITOR .env
```

Fill in at least `DB_PASSWORD`, `APP_ADMIN_PASSWORD` and `APP_MASTER_SECRET`. `APP_MASTER_SECRET` must be the
same value as in the old yoklama deployment (copy it from `yoklama/deploy/.env` on `ops`).

```bash
docker compose up -d --build
docker compose ps                         # app becomes "healthy" after ~1 minute
curl -s http://127.0.0.1:8791/actuator/health
```

Then follow `../migration/README.md` (dumps, load, migrate, verify, frames) before opening it to users.

## Update

```bash
cd /opt/sis && git pull
cd deploy && docker compose up -d --build        # Flyway applies new migrations on start
docker image prune -f
```

Roll back an update: `git checkout <previous tag/commit>` and `docker compose up -d --build`
(database migrations are forward-only: take a backup first, see below).

## Backups

```bash
BACKUP_DIR=/srv/backups/sis ./backup.sh                 # pg_dump (gz) + frames tar, keeps 14 days
# cron, nightly 02:15:
15 2 * * *  cd /opt/sis/deploy && BACKUP_DIR=/srv/backups/sis ./backup.sh >> /var/log/sis-backup.log 2>&1
```

Restore: `gunzip -c sis-db-<stamp>.sql.gz | docker compose exec -T db psql -U sis -d sis` into an empty database;
frames: `docker compose exec -T app tar -C /app/data/frames -xzf - < sis-frames-<stamp>.tar.gz`.
Copy the backup directory off the host as well.

## Cloudflare tunnel hostnames

Zero Trust -> Networks -> Tunnels -> (the tunnel of host `ops`) -> Public hostnames:

| Hostname | Service |
|---|---|
| `sis.armikom.com` | `http://localhost:8791` |
| `yoklama.armikom.com` (change from the old yoklama service at cutover) | `http://localhost:8791` |
| `seng403-questions.armikom.com` (change from the old question-pool service at cutover) | `http://localhost:8791` |

(If you manage the tunnel with a local `config.yml`, add three `ingress` rules with the same service and reload
cloudflared.) Question-pool runs on a different host: the hostname currently points to a tunnel there. Move the
hostname to the tunnel on `ops` (or give the `ops` tunnel the route) at cutover. Device cookies are per hostname:
students sign in again once, and the "same phone" hint restarts on a new hostname.

## Cutover checklist

1. Rehearsal (`migration/rehearse.sh`) was green on fresh dumps; staff know the date; pick an evening without lecture.
2. SIS stack is up, not used; `.env` has the same `APP_MASTER_SECRET` as old yoklama.
3. Stop the old stacks (containers and volumes stay): `docker compose stop` on yoklama (`ops`) and question-pool
   (`armikom-vm-external`).
4. Final dumps -> `docker compose stop app` -> load-legacy -> migrate -> verify (all ok) -> copy frames -> `docker compose up -d app`.
5. Point `sis.armikom.com` (and `yoklama.armikom.com`, `seng403-questions.armikom.com`) at `http://localhost:8791`.
6. Smoke test: staff login (yoklama staff with their old password, question-pool staff via e-mail link), open a test
   session and check in from a phone, answer a question in the pool, open the Midterm 1 exam analysis, look at a
   stored frame in an attendance record.
7. Run `./backup.sh` once and install the cron entry.
8. After a month: drop the legacy schemas (see migration README), then retire the old stacks and volumes.

## Rollback

The old stacks and volumes are untouched. Start them again (`docker compose start` in the old projects), point
`yoklama.armikom.com` and `seng403-questions.armikom.com` back to their old services in the tunnel, and stop the
SIS app (`docker compose stop app`). Anything recorded in SIS since the cutover stays in the SIS database.
