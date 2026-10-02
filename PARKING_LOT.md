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

## Market gate is INCOMPLETE and blocking

`pilot/score_kill_test.py` exits 3 at 0 of 25 qualified interviews. Per Rule 0 this blocks new
post-gate exception-operations infrastructure. It is a standing gate, not a task — it is
discharged by conversations, not by code, and it is recorded here so no session mistakes it
for something to build around.

## Imported source rows are not write-once at the database

Found 2026-10-02 while building the payment timeline. `recon_records` (V52) and `recon_provider_rows`
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

