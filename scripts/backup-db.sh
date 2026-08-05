#!/usr/bin/env bash
#
# Read-only logical backup of the JobPilot database.
#
#   POSTGRES_DB=jobpilot POSTGRES_USER=jobpilot ./scripts/backup-db.sh
#
# Writes three files to BACKUP_DIR:
#   <db>_<utc>.dump          pg_dump custom format (-Fc)
#   <db>_<utc>.dump.sha256   checksum, verified by restore-db.sh
#   <db>_<utc>.dump.meta     timestamp, schema version, app commit, row counts
#
# Never writes to the database, never touches a volume, never runs `compose down`.
# POSTGRES_PASSWORD is deliberately not read: pg_dump runs inside the container
# over the local socket, so no credential is ever placed on a command line.

set -euo pipefail

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"
BACKUP_DIR="${BACKUP_DIR:-backups}"
APP_HEALTH_URL="${APP_HEALTH_URL:-http://127.0.0.1:8080/health}"

[ -f "$COMPOSE_FILE" ] || { echo "compose file not found: $COMPOSE_FILE" >&2; exit 1; }

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }
psql_at() { compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At "$@"; }
checksum() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

mkdir -p "$BACKUP_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
dump="${BACKUP_DIR}/${POSTGRES_DB}_${stamp}.dump"
partial="${dump}.partial"
trap 'rm -f "$partial"' EXIT

echo "Dumping ${POSTGRES_DB} -> ${dump}"
compose exec -T postgres \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner --no-privileges > "$partial"
[ -s "$partial" ] || { echo "pg_dump produced an empty file" >&2; exit 1; }
mv "$partial" "$dump"
trap - EXIT

( cd "$BACKUP_DIR" && checksum "$(basename "$dump")" > "$(basename "$dump").sha256" )

schema_version="$(psql_at -c \
  "select version || ' (' || description || ')'
     from flyway_schema_history where success order by installed_rank desc limit 1;")"

counts="$(psql_at -c \
  "select 'jobs=' || count(*) from jobs
   union all select 'job_scores=' || count(*) from job_scores
   union all select 'job_requirements=' || count(*) from job_requirements
   union all select 'source_fetch_logs=' || count(*) from source_fetch_logs
   union all select 'source_tenant_fetch_logs=' || count(*) from source_tenant_fetch_logs
   union all select 'source_fetch_logs_running=' || count(*)
     from source_fetch_logs where status = 'RUNNING';")"

# Build identity of the app that owns this data. Absent if the app is stopped,
# which is normal for the quiesced dump taken during cutover.
app_commit="$(curl -fsS --max-time 5 "$APP_HEALTH_URL" 2>/dev/null \
  | sed -n 's/.*"commit":"\([^"]*\)".*/\1/p')"

{
  echo "database=${POSTGRES_DB}"
  echo "taken_at_utc=${stamp}"
  echo "schema_version=${schema_version:-unknown}"
  echo "app_commit=${app_commit:-unavailable}"
  echo "dump_sha256=$(cut -d' ' -f1 < "${dump}.sha256")"
  echo "dump_bytes=$(wc -c < "$dump" | tr -d ' ')"
  echo "$counts"
} > "${dump}.meta"

echo "--- ${dump}.meta ---"
cat "${dump}.meta"
