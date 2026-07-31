# ADR-005: Audit rows are append-only at the data layer

- **Status:** Accepted (2026-07-31)
- **Deciders:** Theophilus Ogieva

## Context

TrustLedger sells operational evidence. Reconciliation and exception management are the wedge, and
what makes them defensible is a record of who did what to which transfer, that a buyer, an auditor
or a regulator can rely on.

`audit_logs` did not have that property. The table was written by 30 services through a plain
`JpaRepository`, whose inherited surface includes `save()` over an existing row, `deleteById()` and
`deleteAll()`. Nothing at the JPA layer, the service layer or the database said no. The record of
who approved a payment was, mechanically, no better protected than a cache entry.

This is the same gap ADR-002 closed for the ledger. `V35__ledger_immutability.sql` enforces
immutability for `ledger_entries` and `ledger_transactions` with a `BEFORE UPDATE OR DELETE` trigger,
on the reasoning that the source of financial truth must not depend on every future code path
behaving. Audit rows are the same category of record. They were simply missed.

The gap was found by running the ThePlus-Tech Engineering Playbook's Pattern Adoption Review for
`patterns/audit/immutable-audit-trail.md` against this codebase — the review is at
`theplus-engineering-playbook/reviews/2026-07-31-trustledger-tamper-evident-audit-trail.md` and
records the full field-by-field gap analysis, including what this ADR does *not* fix.

## Decision

Audit rows are append-only, enforced by a database trigger
(`V37__audit_log_immutability.sql`), not by convention or code review. UPDATE and DELETE on
`audit_logs` raise an exception. INSERT is untouched, so every existing write path keeps working
without modification. A correction is a new audit row describing the correction.

**What this does and does not claim.** This delivers *append-only*. It does not deliver
*tamper-evidence*, and we do not describe it as such to buyers. The trigger stops every actor that
reaches the table through the database — application code, a cleanup job, an admin's ad-hoc SQL —
which is the threat we actually have. It does not stop a role that can `DROP TRIGGER`; such a role
can edit rows and leave nothing behind to detect it. The defensible claim today is "audit rows
cannot be modified or deleted through the application," and nothing stronger.

## Options considered

1. **Database trigger** (chosen). One migration, zero code changes, identical in idiom to V35.
2. **Restricted grants** — a write-only role for the application, DELETE reserved to a DBA role.
   Complementary rather than alternative, and it depends on deployment-time role management that
   does not exist yet. Worth adding later; it does not remove the need for (1), because the
   application role must retain INSERT and would still be able to UPDATE without the trigger.
3. **Hash chain** — each row commits to its predecessor's digest, making privileged edits
   *detectable*. This is the only option that earns the word "tamper-evident". Deferred: it needs a
   chain-verification job, a break-alerting path, and a decision about per-tenant vs. global chains.
   Shipping (1) does not block it; the chain would be added over rows that are already append-only.
4. **A narrower repository interface** — hide `delete*` behind a custom interface. Rejected: it
   constrains one call path and nothing else. Raw SQL, a future repository, and JPA cascades all walk
   straight past it. Convention where an invariant is needed.

## Consequences

### Positive

- The record of who did what cannot be rewritten by any code path, present or future.
- Removes a question we could not previously answer honestly in
  `pilot/SECURITY_QUESTIONNAIRE.md` and `pilot/DUE_DILIGENCE.md`.
- Consistent with ADR-002 — the same enforcement mechanism now protects both categories of
  authoritative record, so there is one rule to remember rather than two.
- Zero migration cost: no service, controller or test changed.

### Negative

- Postgres-specific PL/pgSQL. Pre-existing lock-in, not new.
- A future write path that attempts an update now fails loudly at runtime rather than silently
  corrupting. This is the intended direction, but it is a fail-loud change and will surface as an
  exception rather than a compile error.
- Retention gains friction. Nothing purges `audit_logs` today; if that changes, purging must be an
  explicit, privileged, logged path — dropping the trigger under a recorded migration or an
  archive-then-purge routine — never an ordinary `DELETE`. The friction is deliberate.

## Risks accepted

The trail is protected against application-layer mutation but **not** against a compromised or
careless privileged database role, and such an edit would currently be undetectable. Accepted for
now because production DB credentials are held by one person. This assumption expires the moment a
second person holds them, or the moment a buyer asks whether privileged edits are *detectable* — a
standard question in SOC 2 and financial due diligence, so it should be treated as imminent rather
than hypothetical. At that point the hash chain (option 3) becomes required, not optional.

Secondary, accepted: rejected mutations surface only as exceptions in application logs. There is no
metric or alert. Nothing should ever trigger a rejection, so the first occurrence is a real incident
signal that we would currently learn about by reading logs.

## Rollback or migration path

`DROP TRIGGER audit_logs_append_only ON audit_logs;` — instant, reversible, no data migration, since
the change adds no columns and rewrites no rows. Rolling back restores the previous (unprotected)
behaviour exactly.

## Review trigger

- A buyer, auditor or regulator asks whether privileged edits are detectable → implement the hash
  chain and revisit the "append-only, not tamper-evident" wording above.
- A second person is granted production database credentials.
- A retention requirement needs audit rows purged.
- The deferred audit fields (result, before/after references, correlation ID, policy decision — all
  required by the playbook pattern, all missing here) become load-bearing; expect this at the first
  incident that requires correlating an audit row to a request trace.

## Evidence

`backend/src/main/resources/db/migration/V37__audit_log_immutability.sql` and
`backend/src/test/java/com/trustledger/app/AuditLogImmutabilityIntegrationTest.java`, which asserts
against real Postgres that an INSERT still succeeds while a JPA delete, a raw SQL UPDATE and a bulk
DELETE are all rejected and the row survives unchanged. The test was verified to fail when V37 is
not applied.
