# SIS on eas.armikom.com

Production home of SIS: the Armikom application server `eas.armikom.com` (51.178.103.153). Server-wide rules
for agents are in `/srv/apps/AGENTS.md` on the server; read them before changing anything there.

| | |
|---|---|
| URL | https://sis.eas.armikom.com (host Caddy, automatic TLS) |
| Server dir | `/srv/apps/sis` (`compose.yml`, `.env`, `src/` = last deployed `git archive`, `backup.sh`, `NOTES.md`) |
| Compose project | `sis` → containers `sis-db-1`, `sis-app-1`; volumes `sis_pg_data`, `sis_frame_data` |
| App port | `127.0.0.1:8101` (only Caddy connects) |
| Image | `sis-app:<commit>`, `sis-app:current`, `sis-app:previous` — built on the server |
| Backups | `/srv/backups/sis`, nightly 02:15 (armikom crontab) and before every deploy, 14 days |

Hardening: both containers `cap_drop: ALL`, `no-new-privileges`, read-only root file system, memory/CPU/pid
limits; the database sits on an `internal: true` network (no internet, no published port); the app also joins an
egress network for SMTP/EWS and OpenRouter. Docker runs with `userns-remap`, so container root is unprivileged on
the host.

## Deploy / update

From this repository on the Mac (ships the committed `HEAD` only):

```bash
deploy/eas/deploy.sh
```

It uploads the source, installs `sis.caddy` if it changed, builds `sis-app:<commit>`, backs up the database,
switches `sis-app:current` and waits for the health check. Flyway applies new migrations on start.

Rollback (database migrations are forward-only, restore the pre-deploy backup if a migration ran):

```bash
ssh armikom@51.178.103.153 'cd /srv/apps/sis && docker tag sis-app:previous sis-app:current && docker compose up -d app'
```

## Secrets (`/srv/apps/sis/.env`, chmod 600)

Generated on the server at first setup: `DB_PASSWORD`, `APP_MASTER_SECRET`, `APP_ADMIN_PASSWORD` (user `admin`).
Read the admin password there (`grep ADMIN_PASSWORD /srv/apps/sis/.env`); do not copy secrets elsewhere.
Fill in `SMTP_*`/`MAIL_FROM` or `EWS_URL` for mailed sign-in links (empty: links only in `docker compose logs app`)
and `OPENROUTER_API_KEY` for the pool AI, then `docker compose up -d app`.

## Migrating the old yoklama / question-pool data

`../../migration/README.md` applies, using variant B (psql inside the db container) in `/srv/apps/sis`
instead of `deploy/`. Two differences on this host:

1. Before the first migration, set `APP_MASTER_SECRET` in `.env` to the old yoklama value (`yoklama/deploy/.env`
   on `ops`). Only safe while SIS has no classrooms of its own.
2. Frames: copy the old `yoklama_frame_data` volume from `ops` to the `sis_frame_data` volume here (e.g.
   `tar` over ssh into `docker compose exec -T app tar -C /app/data/frames -xzf -`); paths stay the same.

Hostnames `yoklama.armikom.com` / `seng403-questions.armikom.com` currently go through Cloudflare tunnels to the
old hosts. At cutover point them (DNS only) at 51.178.103.153, add them to `sis.caddy` and to `APP_LINK_HOSTS`.
