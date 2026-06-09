#!/usr/bin/env bash
# Back up the TrustLedger PostgreSQL database to a custom-format dump.
# Usage: PGHOST=.. PGPORT=.. PGUSER=.. PGPASSWORD=.. PGDATABASE=.. ./backup-postgres.sh <output-file>
set -euo pipefail

OUT="${1:?usage: backup-postgres.sh <output-file>}"
pg_dump --format=custom --no-owner \
  -h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" \
  -U "${PGUSER:-trustledger}" "${PGDATABASE:-trustledger}" \
  --file="$OUT"

echo "Backup written to $OUT ($(wc -c < "$OUT") bytes)"
