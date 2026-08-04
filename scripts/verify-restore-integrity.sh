#!/usr/bin/env bash
# Prove a RESTORED database is financially intact — not merely that it started.
#
# Doctrine Rule 0.8: "Restores must validate DATA (row counts, foreign keys, tenant boundaries,
# ledger balances, audit-chain continuity), because 'the database started' is not integrity."
#
# Every check below is a financial invariant that must hold in the restored copy exactly as it holds
# in the original. A restore that loses referential integrity, unbalances a journal, or lets an
# account's stored balance drift from its own ledger entries has not recovered the business — it has
# recovered a plausible-looking database.
#
# Exit 0 = PASS. Exit 1 = FAIL (any invariant violated, or too few checks actually ran).
#
# Usage: PGHOST=.. PGPORT=.. PGUSER=.. PGPASSWORD=.. ./verify-restore-integrity.sh <database>
set -euo pipefail

DB="${1:?usage: verify-restore-integrity.sh <database>}"
PSQL=(psql -tA -h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" -U "${PGUSER:-trustledger}" -d "$DB")

# A run that checks nothing passes vacuously. Count the checks and demand the full set — this is the
# single most common way a verification script lies: it finds no rows to check and reports success.
EXPECTED_CHECKS=9
checks=0
failures=0

check() {   # check <name> <sql returning a violation count> <explanation>
  local name="$1" sql="$2" why="$3" n
  n="$("${PSQL[@]}" -c "$sql")"
  checks=$((checks + 1))
  if [ "${n:-1}" != "0" ]; then
    echo "  FAIL  $name: $n violation(s) — $why"
    failures=$((failures + 1))
  else
    echo "  ok    $name"
  fi
}

echo "Restore integrity checks against '$DB':"

# 1. Double-entry: every posted journal balances, per currency. The core financial invariant.
check "ledger balanced per transaction" "
  SELECT count(*) FROM (
    SELECT ledger_transaction_id, currency,
           sum(CASE WHEN direction = 'DEBIT'  THEN amount ELSE 0 END) AS d,
           sum(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END) AS c
      FROM ledger_entries GROUP BY ledger_transaction_id, currency
  ) t WHERE d <> c;" \
  "a restored journal whose debits and credits disagree is corrupt money"

# 2. Stored balances remain consistent with their entries.
#
#    NOTE, and it is a real limitation rather than a weakened test: an account's opening balance is
#    written directly into the balance columns WITHOUT a ledger entry (see AccountController), so
#    posted_balance = opening_balance + net_entries and the opening component is not recorded in the
#    journal. Balances are therefore NOT fully derivable from the ledger, and this check cannot
#    recompute them exactly. What it CAN prove is that the residual is non-negative: since an opening
#    balance can never be negative, posted_balance < net_entries means the restore lost credit
#    entries or gained debits. See docs/RESTORE_TEST_RECORD.md — "Finding: opening balances bypass
#    the ledger" — for why the exact recomputation is unavailable and what would restore it.
check "posted balance is never below its net ledger entries" "
  SELECT count(*) FROM accounts a
  WHERE a.posted_balance < COALESCE((
    SELECT sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END)
      FROM ledger_entries e WHERE e.account_id = a.id), 0);" \
  "a balance below its own net entries means the restore lost credits or gained debits"

# 3. Referential integrity across the money path.
check "no orphan ledger entries (transaction)" "
  SELECT count(*) FROM ledger_entries e
   WHERE NOT EXISTS (SELECT 1 FROM ledger_transactions t WHERE t.id = e.ledger_transaction_id);" \
  "an entry with no transaction cannot be explained to an auditor"

check "no orphan ledger entries (account)" "
  SELECT count(*) FROM ledger_entries e
   WHERE NOT EXISTS (SELECT 1 FROM accounts a WHERE a.id = e.account_id);" \
  "money posted to an account that no longer exists"

# 4. Tenant boundaries survive the restore — invariant 12, and the one a multi-tenant buyer probes.
check "no cross-tenant ledger entries" "
  SELECT count(*) FROM ledger_entries e
    JOIN accounts a ON a.id = e.account_id
   WHERE a.tenant_id <> e.tenant_id;" \
  "an entry attributed to one tenant posting to another tenant's account"

# 5. Idempotency keys survived: without them a replayed request after recovery pays twice.
check "idempotency keys unique per tenant" "
  SELECT count(*) FROM (
    SELECT tenant_id, idempotency_key FROM idempotency_keys
     GROUP BY tenant_id, idempotency_key HAVING count(*) > 1
  ) d;" \
  "duplicate keys let a replayed request execute a second time"

# 6. No transaction executed twice — a restored-then-replayed world's worst outcome.
check "no duplicate ledger transaction idempotency keys" "
  SELECT count(*) FROM (
    SELECT idempotency_key FROM ledger_transactions
     WHERE idempotency_key IS NOT NULL
     GROUP BY idempotency_key HAVING count(*) > 1
  ) d;" \
  "the same business transaction posted more than once"

# 7. Audit rows remain attributable. An audit trail that survives without its actors is a log.
check "audit rows retain an actor" "
  SELECT count(*) FROM audit_logs WHERE actor_type IS NULL OR actor_type = '';" \
  "an audit row with no actor cannot answer 'who did this'"

# 8. Audit checkpoint chain continuity, when the chain exists (V40+). Sequence gaps mean a
#    checkpoint was lost in the restore, which silently reduces what the chain can prove.
if [ "$("${PSQL[@]}" -c "SELECT count(*) FROM information_schema.tables WHERE table_name = 'audit_checkpoints';")" = "1" ]; then
  check "audit checkpoint chain is contiguous" "
    SELECT count(*) FROM (
      SELECT sequence, lag(sequence) OVER (ORDER BY sequence) AS prev FROM audit_checkpoints
    ) s WHERE prev IS NOT NULL AND sequence <> prev + 1;" \
    "a missing checkpoint reduces the span the tamper-evidence chain covers"
else
  echo "  ok    audit checkpoint chain is contiguous (table not present in this schema version)"
  checks=$((checks + 1))
fi

# 9. The dataset is non-trivial. Restoring an empty database passes every invariant above, which is
#    exactly the vacuous pass this check exists to prevent.
non_trivial="$("${PSQL[@]}" -c "SELECT CASE WHEN (SELECT count(*) FROM ledger_entries) >= 2
                                        AND (SELECT count(*) FROM accounts) >= 2 THEN 0 ELSE 1 END;")"
checks=$((checks + 1))
if [ "$non_trivial" != "0" ]; then
  echo "  FAIL  dataset is non-trivial: the restore contains too little data to have proven anything"
  failures=$((failures + 1))
else
  echo "  ok    dataset is non-trivial"
fi

echo "Ran $checks check(s); $failures failure(s)."
if [ "$checks" -lt "$EXPECTED_CHECKS" ]; then
  echo "FAILED: only $checks of $EXPECTED_CHECKS checks ran — a partial verification is not a pass."
  exit 1
fi
if [ "$failures" -ne 0 ]; then
  echo "RESTORE INTEGRITY FAILED."
  exit 1
fi
echo "RESTORE INTEGRITY PASSED ($checks invariants held on the restored copy)."
