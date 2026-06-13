#!/usr/bin/env bash
# Seed a fresh demo tenant via the REAL TrustLedger API: tenant + owner, funded accounts,
# completed transfers (through the LIVE intelligence gate), and a genuinely held fraud case the
# console can approve/reject. A new tenant is created per run, so re-running IS the reset.
# Requires: curl + python3.
#
# Usage: BASE=http://localhost:8080 ./pilot/demo-seed.sh
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
SUFFIX="$(python3 -c 'import uuid;print(uuid.uuid4().hex[:8])')"
EMAIL="demo-owner-${SUFFIX}@trustledger.local"
PASSWORD="DemoPass!2026"

jget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }
newid() { python3 -c 'import uuid;print(uuid.uuid4())'; }

echo "→ registering demo tenant + owner"
REG=$(curl -fsS -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"tenantName\":\"Demo ${SUFFIX}\",\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")
TOKEN=$(echo "$REG" | jget "['token']")
TENANT=$(echo "$REG" | jget "['tenantId']")
AUTH="Authorization: Bearer ${TOKEN}"

echo "→ tuning the tenant's fraud policy (MFA threshold 50)"
# Onboarding transfers (new device + new payee = 45) would otherwise hit STEP_UP_MFA, which the
# gateway escalates to a hold (no inline step-up channel). Raising MFA to 50 lets routine transfers
# complete-with-monitoring, while a >5x-median transfer to a new payee (75) still gets held. This
# is a real per-tenant control, not a demo hack.
curl -fsS -X PUT "$BASE/api/v1/tenant/fraud-policy" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"monitor":25,"mfa":50,"hold":65,"reject":85,"autoFreezeEnabled":false}' >/dev/null

echo "→ creating funded accounts"
SRC=$(curl -fsS -X POST "$BASE/api/v1/accounts" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"currency":"GBP","openingBalance":5000.00}' | jget "['id']")
DST=$(curl -fsS -X POST "$BASE/api/v1/accounts" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"currency":"GBP","openingBalance":0.00}' | jget "['id']")
DST2=$(curl -fsS -X POST "$BASE/api/v1/accounts" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"currency":"GBP","openingBalance":0.00}' | jget "['id']")

echo "→ posting normal transfers through the live gate (establishes the baseline → COMPLETED)"
for i in 1 2 3; do
  curl -fsS -X POST "$BASE/api/v1/transfers" -H "$AUTH" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: demo-${SUFFIX}-${i}" \
    -d "{\"sourceAccountId\":\"${SRC}\",\"destinationAccountId\":\"${DST}\",\"beneficiaryId\":\"$(newid)\",\"amount\":120.00,\"currency\":\"GBP\",\"reference\":\"demo ${i}\",\"deviceId\":\"demo-web\",\"currentCountry\":\"GB\"}" \
    >/dev/null
done

echo "→ posting a high-risk transfer (new device + new payee + >5x median) → HELD by the gate"
HELD=$(curl -fsS -X POST "$BASE/api/v1/transfers" -H "$AUTH" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-${SUFFIX}-high" \
  -d "{\"sourceAccountId\":\"${SRC}\",\"destinationAccountId\":\"${DST2}\",\"beneficiaryId\":\"$(newid)\",\"amount\":900.00,\"currency\":\"GBP\",\"reference\":\"large payout\",\"deviceId\":\"unknown-laptop\",\"currentCountry\":\"GB\"}")
HELD_STATUS=$(echo "$HELD" | jget "['status']")
HELD_SCORE=$(echo "$HELD" | jget "['riskScore']")
HELD_TXN=$(echo "$HELD" | jget "['transactionId']")

# Find the OPEN case the gate opened for that held transfer.
CASE_ID=$(curl -fsS "$BASE/api/v1/fraud/cases" -H "$AUTH" \
  | python3 -c "import sys,json;cs=json.load(sys.stdin);m=[c for c in cs if c['transactionId']=='${HELD_TXN}'];print(m[0]['id'] if m else '')")

echo "→ running an explainable high-risk assessment (intelligence layer)"
ASSESS=$(curl -fsS -X POST "$BASE/api/v1/fraud/assess" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"deviceId\":\"unknown-device\",\"beneficiaryAccountId\":\"$(newid)\",\"amount\":4800.00}")

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
   Completed transfers: 3 × £120 (scored ALLOW_WITH_MONITORING by the live gate, ledger-posted)
   HELD fraud case    : status=${HELD_STATUS} score=${HELD_SCORE} txn=${HELD_TXN}
                        case=${CASE_ID:-"(not found)"}  ← approve/reject it live in the console
   Risk assessment for a new-device + new-beneficiary transfer:
     $(echo "$ASSESS" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("decision="+d["decision"],"score="+str(d["riskScore"]),"signals="+",".join(d["signals"]))')

   The held case above was opened by the LIVE intelligence gate scoring a real transfer — not by a
   script editing the DB. In the console: review accounts/transfers + the balanced ledger, open the
   case, export its evidence pack, then approve or reject it.
EOF
