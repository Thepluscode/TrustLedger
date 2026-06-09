# Validation Report

Date: 2026-06-08

## Commands run

```bash
cd /mnt/data/TrustLedger_v2
bash scripts/run_domain_validation.sh
python3 scripts/validate_repo.py
python3 tools/transaction-simulator/simulate.py --scenario large-transfer
```

## Results

```text
Domain acceptance validation passed.
Repository validation passed.
Transaction simulator generated valid scenario payload.
```

## Validated

- Java domain core compiles with `javac`.
- Low-risk transfer completes.
- Duplicate idempotent retry does not double-debit.
- Same idempotency key with different payload is rejected.
- High-risk transfer is held and funds are reserved.
- Analyst approval posts the held transfer.
- Insufficient funds are blocked.
- Ledger transactions validate balanced debit/credit entries.
- Audit logs are written.
- Outbox events are created.

## Not validated here

- Maven build: `mvn` is not installed in this runtime.
- Docker Compose startup: not run in this runtime.
- Next.js build: dependencies were not installed in this runtime.
