# Validation Report

Date: 2026-06-09 (Maven verification run)
Supersedes the 2026-06-08 javac-only report.

## Commands run

```bash
cd backend
mvn -B -ntp compile      # BUILD SUCCESS (35 source files, Spring Boot 4.0.0, Java 17)
mvn -B -ntp test         # BUILD SUCCESS

# Dependency-free harness (still valid):
cd ..
bash scripts/run_domain_validation.sh
python3 scripts/validate_repo.py
```

## Results

```text
Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| Suite | Tests | Covers |
|-------|------:|--------|
| MoneyTest | 5 | scale/rounding, currency-mismatch guards, equality |
| LedgerTransactionTest | 5 | >=2 entries, debits==credits, single-currency, fee split |
| LedgerServiceTest | 5 | internal transfer, insufficient-funds block, reserve/consume/release, reversal |
| IdempotencyServiceTest | 4 | replay returns original, payload-mismatch rejected, tenant/user scoping, sha256 |
| FraudEngineTest | 6 | allow / hard-reject / hold / step-up-MFA bands, impossible travel, score cap |
| TransactionStateMachineTest | 4 | legal transitions, illegal jumps, terminal states |
| TransferOrchestratorTest | 8 | low-risk complete, idempotent no-double-debit, payload mismatch, high-risk hold+reserve+case, approve, reject+release, insufficient funds, balanced postings + audit + outbox |

## Validated (now with real automated tests, not just javac)

- Java domain core compiles and tests under Maven (Spring Boot 4.0.0).
- Low-risk transfer completes and moves money; balances correct.
- Duplicate idempotent retry returns the original transaction and does not double-debit.
- Same idempotency key with a different payload is rejected.
- High-risk transfer is held and funds are reserved; a fraud case is opened.
- Analyst approval consumes the reservation and posts the transfer.
- Analyst rejection releases the reservation and leaves the destination untouched.
- Insufficient funds are blocked.
- Every ledger transaction validates balanced debit/credit entries.
- Audit logs and outbox events are written for core actions.
- Fraud decisions map to the documented score bands and carry explainable signals.

## Still not validated here (honest limits)

- `docker compose up` — container stack not started in this run.
- `npm run build` — Next.js frontend dependencies not installed/built in this run.
- Integration tests against real PostgreSQL/Redpanda via Testcontainers — the test-scope
  deps resolve, but no `@Testcontainers` integration tests exist yet (next increment).
- No persistence/JPA wiring is exercised yet; the verified core is the in-memory domain spine.
