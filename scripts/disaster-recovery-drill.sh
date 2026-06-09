#!/usr/bin/env bash
# Disaster-recovery drill: back up -> verify restore into a clean DB -> report.
# Run on a schedule; a backup that is never restored is theatre.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S 2>/dev/null || echo manual)"
DUMP="${DUMP_DIR:-/tmp}/trustledger-${STAMP}.dump"

echo "[1/3] Backing up -> $DUMP"
"$HERE/backup-postgres.sh" "$DUMP"

echo "[2/3] Verifying restore into a clean database"
"$HERE/verify-backup.sh" "$DUMP"

echo "[3/3] Drill complete. (Object storage: mirror the evidence bucket with 'mc mirror' — see backup-minio.sh.)"
