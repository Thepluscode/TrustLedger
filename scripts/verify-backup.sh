#!/usr/bin/env bash
# Prove a dump restores into a CLEAN database WITH data. Exit 0 = PASS, 1 = FAIL.
# Usage: PGHOST=.. PGPORT=.. PGUSER=.. PGPASSWORD=.. ./verify-backup.sh <dump>
set -euo pipefail

DUMP="${1:?usage: verify-backup.sh <dump>}"
VERIFY_DB="${VERIFY_DB:-trustledger_verify}"
PSQL=(psql -h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" -U "${PGUSER:-trustledger}")

"${PSQL[@]}" -d postgres -c "DROP DATABASE IF EXISTS $VERIFY_DB;"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE $VERIFY_DB;"
pg_restore --no-owner -h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" \
  -U "${PGUSER:-trustledger}" -d "$VERIFY_DB" "$DUMP"

fail=0
for t in accounts ledger_entries audit_logs; do
  n=$("${PSQL[@]}" -tA -d "$VERIFY_DB" -c "SELECT count(*) FROM $t;")
  echo "  $t: $n rows"
  if [ "$n" -lt 1 ]; then echo "FAIL: $t is empty after restore"; fail=1; fi
done

"${PSQL[@]}" -d postgres -c "DROP DATABASE IF EXISTS $VERIFY_DB;" >/dev/null 2>&1 || true
if [ "$fail" -ne 0 ]; then echo "Backup verification FAILED."; exit 1; fi
echo "Backup verification PASSED (restored into a clean DB with data)."
