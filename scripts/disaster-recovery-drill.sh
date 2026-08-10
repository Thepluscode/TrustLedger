#!/usr/bin/env bash
# Disaster-recovery drill: back up -> verify restore into a clean DB -> report.
# Run on a schedule; a backup that is never restored is theatre.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S 2>/dev/null || echo manual)"
DUMP="${DUMP_DIR:-/tmp}/trustledger-${STAMP}.dump"

echo "[1/3] Backing up -> $DUMP"
"$HERE/backup-postgres.sh" "$DUMP"

echo "[2/4] Verifying restore into a clean database"
"$HERE/verify-backup.sh" "$DUMP"

# Row counts are not integrity. Restore into a scratch database and check the financial invariants
# the business actually depends on — a restore that loses referential integrity or unbalances a
# journal has recovered a plausible-looking database, not the business.
echo "[3/4] Validating financial integrity of the restored copy"
INTEGRITY_DB="${INTEGRITY_DB:-trustledger_drill_$$}"
PSQL_ARGS=(-h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" -U "${PGUSER:-trustledger}")
psql "${PSQL_ARGS[@]}" -d postgres -c "DROP DATABASE IF EXISTS $INTEGRITY_DB;" >/dev/null
psql "${PSQL_ARGS[@]}" -d postgres -c "CREATE DATABASE $INTEGRITY_DB;" >/dev/null
trap 'psql "${PSQL_ARGS[@]}" -d postgres -c "DROP DATABASE IF EXISTS $INTEGRITY_DB;" >/dev/null 2>&1 || true' EXIT
pg_restore --no-owner "${PSQL_ARGS[@]}" -d "$INTEGRITY_DB" "$DUMP"
"$HERE/verify-restore-integrity.sh" "$INTEGRITY_DB"

echo "[4/4] Drill complete. Record the outcome in docs/RESTORE_TEST_RECORD.md — an unrecorded drill"
echo "      is indistinguishable from one that never ran. (Object storage: mirror the evidence"
echo "      bucket with 'mc mirror' — see backup-minio.sh.)"
