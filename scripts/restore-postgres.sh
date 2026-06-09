#!/usr/bin/env bash
# Restore a TrustLedger dump into a database (drops existing objects first).
# Usage: PGHOST=.. PGPORT=.. PGUSER=.. PGPASSWORD=.. ./restore-postgres.sh <dump> [database]
set -euo pipefail

DUMP="${1:?usage: restore-postgres.sh <dump> [database]}"
DB="${2:-${PGDATABASE:-trustledger}}"
pg_restore --clean --if-exists --no-owner \
  -h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" \
  -U "${PGUSER:-trustledger}" -d "$DB" "$DUMP"

echo "Restored $DUMP into database '$DB'"
