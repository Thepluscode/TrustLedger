#!/usr/bin/env bash
# Seed a fresh demo tenant via the REAL TrustLedger API: tenant + owner, funded accounts,
# completed transfers, and a live explainable risk assessment. A new tenant is created per run,
# so re-running IS the reset. Requires: curl + python3.
#
# Usage: BASE=http://localhost:8080 ./pilot/demo-seed.sh
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
SUFFIX="$(python3 -c 'import uuid;print(uuid.uuid4().hex[:8])')"
EMAIL="demo-owner-${SUFFIX}@trustledger.local"
PASSWORD="DemoPass!2026"

jget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

echo "→ registering demo tenant + owner"
REG=$(curl -fsS -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"tenantName\":\"Demo ${SUFFIX}\",\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")
TOKEN=$(echo "$REG" | jget "['token']")
TENANT=$(echo "$REG" | jget "['tenantId']")
AUTH="Authorization: Bearer ${TOKEN}"

echo "→ creating funded accounts"
SRC=$(curl -fsS -X POST "$BASE/api/v1/accounts" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"currency":"GBP","openingBalance":5000.00}' | jget "['id']")
DST=$(curl -fsS -X POST "$BASE/api/v1/accounts" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"currency":"GBP","openingBalance":0.00}' | jget "['id']")

echo "→ posting a few normal transfers"
for i in 1 2 3; do
  curl -fsS -X POST "$BASE/api/v1/transfers" -H "$AUTH" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: demo-${SUFFIX}-${i}" \
    -d "{\"sourceAccountId\":\"${SRC}\",\"destinationAccountId\":\"${DST}\",\"beneficiaryId\":\"$(python3 -c 'import uuid;print(uuid.uuid4())')\",\"amount\":120.00,\"currency\":\"GBP\",\"reference\":\"demo ${i}\",\"deviceId\":\"demo-web\",\"currentCountry\":\"GB\"}" \
    >/dev/null
done

echo "→ running an explainable high-risk assessment (intelligence layer)"
ASSESS=$(curl -fsS -X POST "$BASE/api/v1/fraud/assess" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"deviceId\":\"unknown-device\",\"beneficiaryAccountId\":\"${DST}\",\"amount\":4800.00}")

echo "→ verifying the seeded credentials log in"
LOGIN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"${TENANT}\",\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")
echo "$LOGIN" | jget "['token']" >/dev/null && LOGIN_OK="yes" || LOGIN_OK="NO"

cat <<EOF

✅ Demo tenant ready.
   Console login : ${EMAIL} / ${PASSWORD}
   Tenant ID     : ${TENANT}     (the console login form needs this)
   Login verified: ${LOGIN_OK}
   Source account: ${SRC}
   Risk assessment for a new-device + new-beneficiary transfer:
     $(echo "$ASSESS" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("decision="+d["decision"],"score="+str(d["riskScore"]),"signals="+",".join(d["signals"]))')

   Next in the console: review accounts/transfers, open the ML page, export a fraud-case evidence
   pack from a held case (held cases are opened by analysts/ops or by the scoring pipeline).
EOF
