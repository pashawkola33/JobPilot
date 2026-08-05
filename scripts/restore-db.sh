#!/usr/bin/env bash
#
# Restore a backup-db.sh dump into an EMPTY database.
#
#   POSTGRES_DB=jobpilot POSTGRES_USER=jobpilot RESTORE_CONFIRM=jobpilot \
#     ./scripts/restore-db.sh backups/jobpilot_20260805T120000Z.dump
#
# Refuses to run unless, in this order:
#   1. every required variable is set,
#   2. RESTORE_CONFIRM equals the target database name,
#   3. the .sha256 sidecar exists and matches the dump,
#   4. the target database has zero tables in the public schema.
#
# This script never drops a database, never removes a volume, and never runs
# `docker compose down` in any form. Making room for a restore is a deliberate,
# separate operator action documented in docs/server-migration-runbook.md.

set -euo pipefail

dump="${1:-}"
[ -n "$dump" ] || { echo "usage: $0 <dump-file>" >&2; exit 2; }
[ -f "$dump" ] || { echo "dump not found: $dump" >&2; exit 2; }

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${RESTORE_CONFIRM:?RESTORE_CONFIRM is required and must equal POSTGRES_DB}"

# 2. explicit confirmation, before anything reaches the database
if [ "$RESTORE_CONFIRM" != "$POSTGRES_DB" ]; then
  echo "RESTORE_CONFIRM does not match the target database name" >&2
  exit 1
fi

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"
[ -f "$COMPOSE_FILE" ] || { echo "compose file not found: $COMPOSE_FILE" >&2; exit 1; }

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }
psql_at() { compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At "$@"; }
checksum() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

# 3. checksum
[ -f "${dump}.sha256" ] || { echo "missing checksum: ${dump}.sha256" >&2; exit 1; }
expected="$(cut -d' ' -f1 < "${dump}.sha256")"
actual="$(checksum "$dump" | cut -d' ' -f1)"
[ "$expected" = "$actual" ] || { echo "checksum mismatch for $dump" >&2; exit 1; }
echo "checksum OK"

# 4. target must be empty
tables="$(psql_at -c \
  "select count(*) from information_schema.tables where table_schema = 'public';")"
if [ "$tables" != "0" ]; then
  echo "refusing to restore: ${POSTGRES_DB} already has ${tables} table(s) in schema public." >&2
  echo "Restore only into an empty database. See docs/server-migration-runbook.md." >&2
  exit 1
fi
echo "target ${POSTGRES_DB} is empty"

echo "Restoring $(basename "$dump") into ${POSTGRES_DB}"
compose exec -T postgres pg_restore \
  -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  --no-owner --no-privileges --exit-on-error < "$dump"

echo "--- post-restore counts ---"
psql_at -c \
  "select 'jobs=' || count(*) from jobs
   union all select 'job_scores=' || count(*) from job_scores
   union all select 'job_requirements=' || count(*) from job_requirements
   union all select 'source_fetch_logs=' || count(*) from source_fetch_logs
   union all select 'source_tenant_fetch_logs=' || count(*) from source_tenant_fetch_logs;"

echo "--- flyway history ---"
psql_at -c \
  "select version || ' ' || description || ' success=' || success
     from flyway_schema_history order by installed_rank;"

failed="$(psql_at -c "select count(*) from flyway_schema_history where not success;")"
[ "$failed" = "0" ] || { echo "flyway history contains ${failed} failed migration(s)" >&2; exit 1; }

echo
echo "Compare the counts above against the source fingerprints before continuing."
echo "Flyway's own validate runs at application startup: start the app and confirm"
echo "/health reports components.schema=READY. Do not enable the scheduler or"
echo "Telegram until the cutover checklist says so."
