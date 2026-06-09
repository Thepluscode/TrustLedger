# Testing Strategy

## Local validation already included

```bash
bash scripts/run_domain_validation.sh
python3 scripts/validate_repo.py
```

## Unit tests to enforce

- double-entry balancing
- idempotent replay
- idempotency payload mismatch rejection
- insufficient funds rejection
- fraud score aggregation
- high-risk hold workflow
- reservation approval consumes pending funds
- reservation rejection releases pending funds
- reversal creates opposite entries
- invalid state transitions rejected

## Integration tests for full Spring/Maven environment

- PostgreSQL account locking with `SELECT FOR UPDATE`
- Redis-backed idempotency cache
- Redpanda outbox publisher
- OpenSearch audit/fraud search indexing
- MinIO evidence export storage
- Docker Compose smoke test

## Failure tests

- duplicate transfer request
- concurrent double-spend attempt
- provider timeout -> PENDING_UNKNOWN
- Kafka unavailable -> outbox remains pending
- admin approves same case twice
- ledger imbalance detection
