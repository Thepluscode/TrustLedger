# ADR-007: Pessimistic row locks for money movement, optimistic locking for account metadata

- **Status:** Accepted (recorded 2026-08-04; the decision itself predates this record)
- **Deciders:** Theophilus Ogieva

## Context

Two kinds of write land on the same `accounts` row and they have opposite characteristics.

**Money movement** reads a balance, decides whether it is sufficient, and writes a new balance.
Correctness depends on no other transaction interleaving between the read and the write: two
concurrent transfers that both read 1,000 and both debit 800 will both believe they succeeded, and
the account ends at −600. Under contention, retrying is not merely slow — a retry loop around a
balance check is how the same payment gets attempted twice.

**Account metadata** — status, org-unit assignment, currency label — is edited rarely, by humans,
and a lost update is an annoyance rather than a loss of money.

Applying one strategy to both would either serialise metadata edits behind a money lock, or expose
money movement to lost updates.

## Decision

**Money movement takes a pessimistic row lock**: `SELECT … FOR UPDATE`, via
`AccountRepository.findByIdAndTenantIdForUpdate`, inside the same database transaction that posts the
ledger entries. Where more than one account is locked, accounts are locked in a **deterministic
(sorted-id) order** so two transfers touching the same pair cannot deadlock by approaching it from
opposite ends.

**Account metadata uses optimistic locking**: `@Version` on `AccountEntity`. A concurrent edit fails
with an optimistic-lock exception and the caller retries or reports a conflict.

The tenant-scoped lock method is the default; `findByIdForUpdateUnscoped` exists for call sites whose
identifier already came from a tenant-scoped record or a trusted internal queue item, and is named to
make that a visible decision rather than a silent one.

## Options considered

**Optimistic locking for money too.** Rejected: the failure mode is a retry, and a retry around a
balance-check-then-debit is precisely the shape that produces duplicate payments under load. It also
converts a contention problem into an error-rate problem at exactly the moment the system is busiest.

**Pessimistic locks for everything.** Rejected: metadata edits would queue behind in-flight payments
for no correctness benefit, and long-held locks taken by a human-speed UI action are a good way to
stall the money path.

**Application-level or distributed locks (Redis, advisory locks).** Rejected: a lock that lives
outside the transaction that mutates the balance can be released or lost independently of the commit,
which is the one property that must not be optional. The database already offers a lock whose
lifetime is exactly the transaction's.

**Serialisable isolation.** Rejected for now: it moves the failure into serialisation errors that
every caller must handle correctly, and gets the retry-shaped hazard back through a different door.

## Trade-offs accepted

- Money movement serialises per account. Measured: throughput roughly halves when all workers race a
  single account (133 TPS vs 231–271 TPS across distinct accounts, `docs/` measurement rows) with
  **zero deadlocks over 2,000 transfers**. That is the cost of the guarantee, and it is paid per
  account rather than globally.
- Lock ordering is a convention enforced by review and tests, not by the type system. A future call
  site that locks two accounts in arrival order would reintroduce deadlock risk.

## Risks

- Someone "optimises" a hot account by switching it to optimistic locking, and the double-spend
  stress test is the only thing standing between that change and duplicate debits.
- A long-running operation that holds a money lock across a network call would stall every payment on
  that account. Provider calls are deliberately made **outside** the locked transaction for this
  reason (see the durable submission boundary in `ExternalRailSubmissionService`).

## Reversal conditions

Revisit if any of these becomes true:

- Single-account contention becomes a real bottleneck for a real tenant — the answer is likely
  sharding the account (sub-accounts per stream) rather than weakening the lock.
- PostgreSQL row-level security or a different storage engine changes what the lock guarantees.
- A move away from a single database makes transaction-scoped locks unavailable, at which point the
  ledger design, not the lock, is what needs rethinking.

## Evidence

- `concurrentTransfersNeverDoubleSpend` — 8 racing transfers, exactly 4 succeed, balance floors at 0,
  ledger debits equal money moved.
- `HardeningIntegrationTest` — 50 racing transfers, exactly 20 succeed, same invariants.
- Single-account contention measurement: 2,000 transfers all racing one source row, every one
  `COMPLETED`, zero deadlocks.
- `AccountRepository` — the scoped and unscoped lock methods, and the naming that makes the unscoped
  one a deliberate choice (see the tenant-scoped-locking rule in `CLAUDE.md`, level 4).
