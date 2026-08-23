#!/usr/bin/env bash
# Seed the first-sale, read-only reliability walkthrough through the real API.
# Creates a fresh tenant, ingests one synthetic provider settlement row with no internal match,
# verifies the resulting priced exception, and assigns it to the tenant owner.
# Requires: curl + python3. No payment is initiated, routed, retried, reversed or moved.
#
# Usage: BASE=http://localhost:8090 CONSOLE=http://localhost:3010 ./pilot/demo-wedge-seed.sh
set -euo pipefail

BASE="${BASE:-http://localhost:8090}"
CONSOLE="${CONSOLE:-http://localhost:3010}"
CONSOLE="${CONSOLE%/}"
SUFFIX="$(python3 -c 'import uuid;print(uuid.uuid4().hex[:8])')"
EMAIL="wedge-owner-${SUFFIX}@trustledger.local"
PASSWORD="DemoPass!2026"
STATEMENT_REF="LEAD-WALKTHROUGH-${SUFFIX}"
PROVIDER_REF="UNMATCHED-${SUFFIX}"

jget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

echo "→ checking the API"
curl -fsS "$BASE/api/health" | python3 -c \
  'import json,sys; d=json.load(sys.stdin); assert d.get("status") == "ok", d'

echo "→ registering a fresh read-only walkthrough tenant"
REG=$(curl -fsS -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"tenantName\":\"Wedge Demo ${SUFFIX}\",\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")
TOKEN=$(echo "$REG" | jget "['token']")
TENANT=$(echo "$REG" | jget "['tenantId']")
OWNER=$(echo "$REG" | jget "['userId']")
AUTH="Authorization: Bearer ${TOKEN}"

echo "→ ingesting one synthetic provider settlement break"
INGEST=$(curl -fsS -X POST "$BASE/api/v1/tenant/reconciliation/statements/csv" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"provider\":\"SANDBOX\",\"currency\":\"GBP\",\"statementRef\":\"${STATEMENT_REF}\",\"periodStart\":\"2026-08-19T00:00:00Z\",\"periodEnd\":\"2026-08-20T00:00:00Z\",\"linesCsv\":\"providerReference,amount,fee,status\\n${PROVIDER_REF},125.00,0.00,SETTLED\",\"declaredTotalAmount\":125.00,\"declaredTotalFees\":0.00}")
STATEMENT_ID=$(echo "$INGEST" | jget "['statement']['id']")
echo "$INGEST" | python3 -c \
  'import json,sys; d=json.load(sys.stdin); assert d["unmatched"] == 1 and not d["alreadyIngested"], d'

ISSUES=$(curl -fsS "$BASE/api/v1/reconciliation/issues" -H "$AUTH")
ISSUE_ID=$(echo "$ISSUES" | python3 -c \
  'import json,sys; d=json.load(sys.stdin); xs=[i for i in d["items"] if i["type"] == "SETTLEMENT_LINE_UNMATCHED"]; assert len(xs) == 1, d; print(xs[0]["id"])')

echo "→ assigning the detected break to the tenant owner"
ASSIGNED=$(curl -fsS -X POST "$BASE/api/v1/reconciliation/issues/${ISSUE_ID}/assign" \
  -H "$AUTH" -H 'Content-Type: application/json' -d "{\"userId\":\"${OWNER}\"}")
echo "$ASSIGNED" | python3 -c \
  'import json,sys,decimal; d=json.load(sys.stdin); assert d["status"] == "OPEN", d; assert d["ownerUserId"] == sys.argv[1], d; assert d["exposureCurrency"] == "GBP", d; assert decimal.Decimal(str(d["exposureAmount"])) == decimal.Decimal("125"), d' \
  "$OWNER"

AUDIT=$(curl -fsS "$BASE/api/v1/reconciliation/issues/${ISSUE_ID}/audit" -H "$AUTH")
echo "$AUDIT" | python3 -c \
  'import json,sys; d=json.load(sys.stdin); assert any(x["action"] == "RECONCILIATION_ISSUE_ASSIGNED" for x in d), d'

LOGIN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"${TENANT}\",\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")
echo "$LOGIN" | jget "['token']" >/dev/null

cat <<EOF

✅ Read-only wedge walkthrough ready.
   Console login : ${EMAIL} / ${PASSWORD}
   Tenant ID     : ${TENANT}
   Statement     : ${STATEMENT_REF} (${STATEMENT_ID})
   Open exception: ${ISSUE_ID}
   Owner          : ${OWNER}
   Exposure       : GBP 125.00

   Statement URL : ${CONSOLE}/reconciliation/statements/${STATEMENT_ID}
   Exception URL : ${CONSOLE}/reconciliation/${ISSUE_ID}
   The statement was ingested through the real API, the deterministic settlement matcher raised
   the exception, and the assignment was audited. No money moved.
EOF
