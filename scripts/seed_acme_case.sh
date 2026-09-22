#!/usr/bin/env bash
# Seeds the synthetic ACME-2026-08 reconciliation case into a running TrustLedger, over the public API.
# Everything it loads is test data: providers are named provider-a / provider-b on purpose.
#
#   API=http://localhost:8080 ./scripts/seed_acme_case.sh            # stops before the run, for a live demo
#   API=http://localhost:8080 ./scripts/seed_acme_case.sh --run      # also acknowledges the rejected row and reconciles
#
# Needs: curl, python3. The backend must run with RECON_CASEWORK_ENABLED=true.
set -euo pipefail

API="${API:-http://localhost:8080}"
HERE="$(cd "$(dirname "$0")" && pwd)"
FIX="$HERE/../backend/src/test/resources/fixtures/acme-2026-08"
EMAIL="${EMAIL:-ops-$(date +%s)@acme.example}"
PASSWORD="${PASSWORD:-Password!1}"

field() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }
post() { curl -sS --fail-with-body -X POST "$API$1" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "${2:-{\}}"; }
upload() { # source-type identity profile file
  curl -sS --fail-with-body -X POST "$API/api/v1/reconciliation/cases/$CASE/imports" -H "Authorization: Bearer $TOKEN" \
    -F "sourceType=$1" -F "sourceIdentity=$2" -F "profile=$3" -F "file=@$FIX/$4;type=text/csv" \
    | field "['manifest']['originalFilename'] + ': ' + str(d['manifest']['acceptedCount']) + ' accepted, ' + str(d['manifest']['rejectedCount']) + ' rejected'"
}

REG="$(curl -sS --fail-with-body -X POST "$API/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"tenantName\":\"ACME Demo $(date +%s)\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")"
TOKEN="$(echo "$REG" | field "['token']")"
TENANT="$(echo "$REG" | field "['tenantId']")"

post /api/v1/tenant/fee-schedules '{"provider":"provider-b","currency":"GBP","percentageBps":150,"flatFee":"0.00","tolerance":"0.01","effectiveFrom":"2026-01-01T00:00:00Z"}' >/dev/null
CASE="$(post /api/v1/reconciliation/cases '{"caseRef":"ACME-2026-08","title":"August 2026 cross-provider reconciliation","periodStart":"2026-08-01T00:00:00Z","periodEnd":"2026-09-01T00:00:00Z","settlementSlaDays":2}' | field "['reconciliationCase']['id']")"

upload INTERNAL acme-ledger internal-expected internal-expected.csv
upload PROVIDER_TRANSACTION provider-b provider-transactions provider-b-transactions.csv
upload PROVIDER_TRANSACTION provider-a provider-transactions provider-a-transactions.csv
upload SETTLEMENT provider-b provider-settlement provider-b-settlement.csv

if [ "${1:-}" = "--run" ]; then
  for IMPORT in $(curl -sS "$API/api/v1/reconciliation/cases/$CASE" -H "Authorization: Bearer $TOKEN" \
      | python3 -c "import json,sys; print(' '.join(i['manifest']['id'] for i in json.load(sys.stdin)['imports'] if i['manifest']['rejectedCount'] > 0))"); do
    post "/api/v1/reconciliation/cases/$CASE/imports/$IMPORT/acknowledge-rejections" >/dev/null
  done
  post "/api/v1/reconciliation/cases/$CASE/runs" | python3 -c "
import json,sys
v=json.load(sys.stdin); r=v['run']
print('run: %d records, %d of %d matched (rate %s), %d exceptions' % (r['recordsProcessed'], r['internalMatched'], r['internalPayments'], v['matchRate'], r['exceptionCount']))
for t in v['unresolvedByCurrency']: print('  unresolved %s %s' % (t['currency'], t['unresolvedAmount']))"
fi

echo
echo "tenant   $TENANT"
echo "email    $EMAIL"
echo "password $PASSWORD"
echo "case     $CASE"
