# Parking lot — TrustLedger

Discovery is not authorisation. Anything found mid-session that is not the task in
`ACTIVE_WORK.yaml` lands here with the evidence that produced it, and waits.

An entry leaves only by becoming the active task under a switch condition in `ACTIVE_WORK.yaml`.

## Opening balances bypass the ledger

Raised by the 2026-08-04 disaster-recovery drill and recorded in `AGENT_CONTEXT.md`: opening
balances are written straight into `available_balance` / `posted_balance` with **no ledger
entry**. Balances are therefore not fully derivable from the journal, and a rebuild-from-ledger
recovery would be wrong for every funded account.

Filed, not silently patched — posting opening balances as real double-entry movements is a
money-semantics decision for the product owner, not an agent's refactor.

**Resume when:** the product owner rules on the semantics, or a recovery actually needs a
rebuild from the journal.

## Pre-existing defects found during casework, deliberately out of scope

From `FEATURE_TRACKER.md`: `POST /api/v1/transfers` without an `Idempotency-Key` returns 500
instead of 400 (`MissingRequestHeaderException` unmapped), and `EvidenceService
.requireExportInScope` answers 403 for a foreign export id where the casework slice now
answers 404.

Found while doing something else. Both are real; neither is authorised.

**Resume when:** the casework branch lands, or a pilot conversation trips over one.

## Flyway SSL context-start flake

`STILL UNEXPLAINED`. A 60-attempt probe found 0 failures; the port-forwarder race hypothesis is
not supported; it has never appeared in CI. Host memory pressure is the leading suspect and is
unproven. No retry was added, deliberately — a retry would convert an unexplained failure into
a silent one.

**Resume when:** it recurs with a capturable environment, or it appears in CI.

## Data-resilience gaps below pilot grade

Named in `AGENT_CONTEXT.md`: nothing schedules the drill, backups are not stored outside the
primary failure domain, object storage is unexercised, and the lock-execution-before-resume
sequence has never been rehearsed. RPO is still the backup interval — no PITR/WAL archiving.

**Resume when:** real money is in scope, or a pilot requires production recovery. PITR is
required before the first; the drill schedule is the cheapest of the four.

## Market gate is INCOMPLETE — it steers, it does not stop

`pilot/score_kill_test.py` exits 3 at 0 of 25 qualified interviews. It is a standing gate, not a
task: discharged by conversations, not by code, and recorded here so no session mistakes it for
something to build around. ~~Per Rule 0 this blocks new post-gate exception-operations
infrastructure.~~ **Superseded 2026-09-22:** what it blocks is money movement, speculative
expansion, irreversible cost, autonomous financial actions and unsupported claims
(`AGENT_CONTEXT.md` → *The governor*). Essential implementation continues in parallel.

## Imported source rows are not write-once at the database — RESOLVED 2026-10-04

Authorised by the founder on 2026-10-04 and closed by V58 (`recon_import_rows_write_once`,
`recon_import_rows_no_truncate`); `ImportRowsWriteOnceIntegrationTest` is the control. Kept here as
the record of what was found. Originally: found 2026-10-02 while building the payment timeline. `recon_records` (V52) and `recon_provider_rows`
(V57) carry the `trustledger_reject_evidence_mutation` trigger; `recon_import_rows` (V51), which holds
the raw text of every imported row, does not. `PaymentTimelineIntegrationTest
.anEditedSourceRowCannotSupportAConclusion` edits a stored row with a plain `UPDATE` and it succeeds.
The timeline detects the edit (the row no longer matches its hash) and refuses to conclude anything from
it; the database does not refuse the edit itself.

Not fixed here: a trigger on that table is a migration over existing data, and whether acknowledging
rejections or discarding an import ever updates those rows has to be checked first.

**Resume when:** the founder authorises a migration, or before any pilot holds customer files.

## `docs/API.md` describes endpoints that do not exist and omits most that do

Found by the 2026-10-01 feature audit. It lists `POST /api/v1/reconciliation/run` and
`GET /api/v2/payment-rails/payments/{paymentId}`, neither of which is mapped, and it omits the whole
reconciliation-cases API apart from the payment timeline added on 2026-10-02. A developer integrating in
"embedded" mode has no accurate reference.

**Resume when:** a prospect asks for API documentation, or an OpenAPI spec is authorised.

## The other evidence tables refuse UPDATE and DELETE but not TRUNCATE

Found 2026-10-04 while closing the entry above. `evidence_objects` (V50), `recon_records` (V52) and
`recon_provider_rows` (V57) carry row-level `BEFORE UPDATE OR DELETE` triggers only. PostgreSQL does not
fire row-level triggers on `TRUNCATE`, the same hole V40 closed for the audit tables. A plain
`TRUNCATE recon_records` is refused today only because other tables reference it by foreign key;
`TRUNCATE ... CASCADE` would not be. Out of the authorised scope (one table), so parked rather than
quietly widened. The function `trustledger_reject_evidence_truncate` from V58 is ready to attach.

**Resume when:** a migration is authorised; it is three `CREATE TRIGGER` statements and one test.

