// TrustLedger dev-hardware baseline — fills the FEATURE_TRACKER measurement table.
// Rule 3: numbers here are MEASURED, and the environment is named so nobody reads a
// laptop figure as a production SLO.
//
// Run (backend up on :8080 with the rate limiter raised, see tracker):
//   k6 run -e TOK=<jwt> -e A1=<uuid> -e A2=<uuid> backend/loadtest/baseline.js
//
// Scenarios, sequential so they never contend with each other:
//   health   GET  /actuator/health          — transport+container floor
//   read     GET  /api/v1/accounts          — auth'd tenant-scoped DB read
//   write    POST /api/v1/transfers         — full money path: fraud scoring,
//            reservation, persistence, audit (fresh tenant ⇒ MFA_REQUIRED outcome)
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const TOK = __ENV.TOK;
const AUTH = { headers: { Authorization: `Bearer ${TOK}` } };

export const options = {
  scenarios: {
    health: { executor: 'constant-vus', vus: 20, duration: '30s', exec: 'health', startTime: '0s' },
    read:   { executor: 'constant-vus', vus: 20, duration: '30s', exec: 'read',   startTime: '35s' },
    write:  { executor: 'constant-vus', vus: 10, duration: '30s', exec: 'write',  startTime: '70s' },
  },
  // Empty threshold entries exist ONLY to make k6 print per-endpoint submetrics —
  // this is a measurement, not a gate. A real gate can be added once a target exists.
  thresholds: {
    'http_req_duration{ep:health}': [],
    'http_req_duration{ep:accounts}': [],
    'http_req_duration{ep:transfers}': [],
    'http_reqs{ep:health}': [],
    'http_reqs{ep:accounts}': [],
    'http_reqs{ep:transfers}': [],
    'http_req_failed{ep:transfers}': [],
  },
};

export function health() {
  const r = http.get(`${BASE}/actuator/health`, { tags: { ep: 'health' } });
  check(r, { 'health 200': (x) => x.status === 200 });
}

export function read() {
  const r = http.get(`${BASE}/api/v1/accounts`, Object.assign({ tags: { ep: 'accounts' } }, AUTH));
  check(r, { 'read 200': (x) => x.status === 200 });
}

export function write() {
  const body = JSON.stringify({
    sourceAccountId: __ENV.A1, destinationAccountId: __ENV.A2,
    amount: 1.0, currency: 'GBP', reference: 'k6-baseline',
    deviceId: 'k6', currentCountry: 'GB',
  });
  const r = http.post(`${BASE}/api/v1/transfers`, body, {
    headers: {
      Authorization: `Bearer ${TOK}`,
      'Content-Type': 'application/json',
      // unique per request: idempotency must never dedupe the load away
      'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}`,
    },
    tags: { ep: 'transfers' },
  });
  check(r, { 'write 2xx': (x) => x.status >= 200 && x.status < 300 });
}
