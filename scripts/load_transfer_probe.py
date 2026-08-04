#!/usr/bin/env python3
"""TrustLedger transfer-creation load probe — fills the first measured rows of the
FEATURE_TRACKER quality-attributes table. Stdlib only.

Run against a locally booted backend (needs only Postgres; no Kafka):
  docker run -d --name tl-loadpg -e POSTGRES_DB=trustledger -e POSTGRES_USER=trustledger \
    -e POSTGRES_PASSWORD=trustledger -p 55440:5432 postgres:16-alpine
  cd backend && DATABASE_URL="jdbc:postgresql://localhost:55440/trustledger" \
    DATABASE_USERNAME=trustledger DATABASE_PASSWORD=trustledger \
    mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8091 \
      --trustledger.outbox.publisher.enabled=false --trustledger.ratelimit.requests-per-minute=100000000"
  python3 scripts/load_transfer_probe.py

Method: register a tenant, create W (source, destination) account pairs, run a warmup
(JVM JIT + fraud-baseline building), then measure N transfers across W concurrent
workers (each worker owns its own account pair, so no cross-worker row contention —
this measures the pipeline, not single-account lock serialisation).

Every measured request must be 200 COMPLETED; anything else fails the run
(no vacuous pass: asserts the exact expected success count)."""

import json, sys, time, uuid, threading, urllib.request, statistics

BASE = "http://localhost:8091/api/v1"
WORKERS = 10
WARMUP_PER_WORKER = 10
MEASURED = 1000

def call(method, path, body=None, token=None, idem=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token: req.add_header("Authorization", "Bearer " + token)
    if idem: req.add_header("Idempotency-Key", idem)
    data = json.dumps(body).encode() if body is not None else None
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as r:
            return r.status, json.loads(r.read() or b"{}"), (time.perf_counter() - t0) * 1000
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}"), (time.perf_counter() - t0) * 1000

def main():
    run = uuid.uuid4().hex[:8]
    st, reg, _ = call("POST", "/auth/register",
                      {"tenantName": f"loadtest-{run}", "email": f"load-{run}@test.local", "password": "LoadTest!234"})
    assert st in (200, 201), f"register failed: {st} {reg}"
    token = reg.get("token") or reg.get("accessToken")
    assert token, f"no token in register response: {reg}"

    # Cold-start transfers score 45 (new device + new beneficiary) which lands in the default
    # step-up band and pauses at MFA_REQUIRED forever (paused transfers never build a baseline).
    # Raise this tenant's step-up threshold so score-45 transfers land in the monitor band and
    # COMPLETE — the documented tenant-policy route. Fraud scoring still runs on every request.
    st, pol, _ = call("PUT", "/tenant/fraud-policy",
                      {"monitor": 25, "mfa": 60, "hold": 75, "reject": 95,
                       "autoFreezeEnabled": False, "deviceTrustAfter": None}, token)
    assert st == 200, f"policy update failed: {st} {pol}"

    pairs = []
    for _ in range(WORKERS):
        st, src, _ = call("POST", "/accounts", {"currency": "GBP", "openingBalance": "1000000.00"}, token)
        assert st in (200, 201), f"account create failed: {st} {src}"
        st, dst, _ = call("POST", "/accounts", {"currency": "GBP", "openingBalance": "0.00"}, token)
        assert st in (200, 201), f"account create failed: {st} {dst}"
        pairs.append((src["id"], dst["id"]))

    def transfer(src, dst, device):
        return call("POST", "/transfers",
                    {"sourceAccountId": src, "destinationAccountId": dst, "beneficiaryId": None,
                     "amount": "10.00", "currency": "GBP", "reference": "load", "deviceId": device,
                     "currentCountry": "GB"},
                    token, idem=str(uuid.uuid4()))

    # Warmup: JIT + per-device fraud baseline (first transfers may 202 MFA/HELD — allowed here only)
    for i, (src, dst) in enumerate(pairs):
        for _ in range(WARMUP_PER_WORKER):
            st, body, _ = transfer(src, dst, f"loaddev-{i}")
            assert st in (200, 202), f"warmup failed: {st} {body}"

    lat, errors, lock = [], [], threading.Lock()
    per_worker = MEASURED // WORKERS
    def work(idx):
        src, dst = pairs[idx]
        for _ in range(per_worker):
            st, body, ms = transfer(src, dst, f"loaddev-{idx}")
            with lock:
                if st == 200 and body.get("status") == "COMPLETED": lat.append(ms)
                else: errors.append((st, body.get("status"), body))

    t0 = time.perf_counter()
    threads = [threading.Thread(target=work, args=(i,)) for i in range(WORKERS)]
    for t in threads: t.start()
    for t in threads: t.join()
    wall = time.perf_counter() - t0

    assert not errors, f"{len(errors)} non-COMPLETED responses, first: {errors[0]}"
    assert len(lat) == per_worker * WORKERS, f"expected {per_worker*WORKERS} results, got {len(lat)}"
    q = statistics.quantiles(lat, n=100)
    print(f"measured={len(lat)} workers={WORKERS} wall={wall:.1f}s tps={len(lat)/wall:.1f}")
    print(f"latency ms: p50={statistics.median(lat):.1f} p95={q[94]:.1f} p99={q[98]:.1f} max={max(lat):.1f}")

if __name__ == "__main__":
    main()
