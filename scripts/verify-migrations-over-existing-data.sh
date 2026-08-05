#!/usr/bin/env bash
# Apply migrations the way a real deployment does: to a database that already has a schema AND rows.
#
# Every Testcontainers test in this repo starts from an EMPTY database, so Flyway replays V1..Vn onto
# nothing. That proves the migrations are internally consistent; it does not prove they survive
# contact with existing data. A CHECK constraint added by a later migration is validated against every
# existing row. A NOT NULL column without a default fails outright. A unique index collides with
# duplicates that were legal before. None of those can fail on an empty table.
#
# This applies migrations up to a baseline version, seeds representative rows, then applies the rest —
# which is the state a production upgrade actually meets.
#
# Usage: PGHOST=.. PGPORT=.. PGUSER=.. PGPASSWORD=.. ./verify-migrations-over-existing-data.sh <db> <baseline-version>
#   e.g. ./verify-migrations-over-existing-data.sh upgrade_check 38
set -euo pipefail

DB="${1:?usage: verify-migrations-over-existing-data.sh <database> <baseline-version>}"
BASELINE="${2:?baseline version required, e.g. 38}"
HERE="$(cd "$(dirname "$0")" && pwd)"
MIGRATIONS="${MIGRATIONS_DIR:-$HERE/../backend/src/main/resources/db/migration}"
PSQL=(psql -v ON_ERROR_STOP=1 -h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" -U "${PGUSER:-postgres}")

version_of() { basename "$1" | sed -E 's/^V([0-9]+)__.*/\1/'; }

"${PSQL[@]}" -d postgres -c "DROP DATABASE IF EXISTS $DB;" >/dev/null
"${PSQL[@]}" -d postgres -c "CREATE DATABASE $DB;" >/dev/null

applied_before=0
applied_after=0

echo "[1/4] Applying migrations up to V$BASELINE (the deployed baseline)"
for f in $(ls "$MIGRATIONS"/V*.sql | sort -V); do
  v="$(version_of "$f")"
  if [ "$v" -le "$BASELINE" ]; then
    "${PSQL[@]}" -q -d "$DB" -f "$f" >/dev/null
    applied_before=$((applied_before + 1))
  fi
done
echo "  applied $applied_before migration(s)"

echo "[2/4] Seeding rows so the new migrations meet data, not an empty schema"
"${PSQL[@]}" -q -d "$DB" <<'SQL' >/dev/null
INSERT INTO accounts (id, tenant_id, user_id, currency, status, available_balance, pending_balance, posted_balance)
SELECT gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'GBP', 'ACTIVE', 1000.0000, 0, 1000.0000
FROM generate_series(1, 50);

INSERT INTO audit_logs (id, tenant_id, actor_type, actor_id, action, resource_type, resource_id, metadata)
SELECT gen_random_uuid(), gen_random_uuid(), 'USER', gen_random_uuid(), 'LEGACY_ACTION',
       'TRANSFER', gen_random_uuid(), '{"legacy":true}'::jsonb
FROM generate_series(1, 5000);
SQL
seeded="$("${PSQL[@]}" -tA -d "$DB" -c "SELECT count(*) FROM audit_logs;")"
echo "  seeded $seeded audit row(s) predating the new migrations"
# A seed that silently inserted nothing would make every check below vacuous.
if [ "${seeded:-0}" -lt 1000 ]; then
  echo "FAILED: seeding did not produce enough rows to prove anything"; exit 1
fi

echo "[3/4] Applying migrations above V$BASELINE — the upgrade under test"
for f in $(ls "$MIGRATIONS"/V*.sql | sort -V); do
  v="$(version_of "$f")"
  if [ "$v" -gt "$BASELINE" ]; then
    echo "  -> V$v $(basename "$f")"
    "${PSQL[@]}" -q -d "$DB" -f "$f" >/dev/null
    applied_after=$((applied_after + 1))
  fi
done
if [ "$applied_after" -lt 1 ]; then
  echo "FAILED: no migrations above V$BASELINE were applied — nothing was tested"; exit 1
fi
echo "  applied $applied_after migration(s) over existing data"

echo "[4/4] Verifying the pre-existing rows survived intact"
fail=0
after="$("${PSQL[@]}" -tA -d "$DB" -c "SELECT count(*) FROM audit_logs;")"
if [ "$after" != "$seeded" ]; then
  echo "  FAIL  audit_logs row count changed during upgrade: $seeded -> $after"; fail=1
else
  echo "  ok    all $after pre-existing audit rows survived"
fi

# Columns added by the upgrade must be NULL on old rows — never back-filled with an invented value.
legacy_with_result="$("${PSQL[@]}" -tA -d "$DB" -c \
  "SELECT count(*) FROM audit_logs WHERE action = 'LEGACY_ACTION' AND result IS NOT NULL;")"
if [ "$legacy_with_result" != "0" ]; then
  echo "  FAIL  $legacy_with_result legacy row(s) gained an invented outcome"; fail=1
else
  echo "  ok    legacy rows carry NULL outcomes rather than a back-filled guess"
fi

echo "Baseline V$BASELINE: $applied_before applied, $seeded rows seeded, $applied_after upgrade migration(s) applied."
if [ "$fail" -ne 0 ]; then echo "MIGRATION UPGRADE CHECK FAILED."; exit 1; fi
echo "MIGRATION UPGRADE CHECK PASSED (upgrade applies cleanly over existing data)."
