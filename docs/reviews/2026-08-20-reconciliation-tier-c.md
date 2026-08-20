# Tier-C adversarial review — reconciliation path (2026-08-20)

Scope: `backend/src/main/java/com/trustledger/reconciliation/ReconciliationService.java`
and the entities/status model it reads. Method: read for the failure modes the tests
don't encode; every absence claim below was second-checked with a repo-wide search.

## What holds

BigDecimal throughout with `compareTo` (scale-safe); dedup only against OPEN issues so a
resolved-then-recurring break re-raises; non-monetary breaks carry `null` exposure rather
than a lying £0; single-legged journals caught by `entries.size() < 2`; provider mutations
delegated to row-locked transitions; tenant-scoped balance check exists so certification
drills cannot touch other tenants' live payments.

## Findings, ranked

### 1. SUBMITTING and all DISPUTE states are invisible to reconciliation — CRITICAL-class blind spot

`RESOLVABLE` covers PENDING_UNKNOWN, PENDING_SETTLEMENT, ACTION_REQUIRED, ACCEPTED,
SUBMITTED; `TERMINAL` covers SETTLED, FAILED, CANCELLED, RETURNED, REVERSED. The status
vocabulary also contains CREATED, READY_TO_SUBMIT, **SUBMITTING**, **DISPUTE_OPENED**,
**DISPUTE_REVIEW**, **DISPUTE_WON**, **CHARGEBACK** — none swept by any scheduled job
(verified: no other `findByStatus` sweep exists).

- A crash between "mark SUBMITTING" and "mark SUBMITTED" strands the attempt forever:
  never queried, never flagged, money possibly in flight.
- Dispute/chargeback state advances **only** via webhooks — in the service whose own
  founding commit (`9342970`) exists because webhooks get lost. A lost CHARGEBACK webhook
  is money moving backwards with no backstop.

### 2. Balance check ignores currency — a mixed-currency journal certifies as balanced — HIGH

`checkLedgerTransactionBalanced` sums debits and credits across entries blind to
`LedgerEntryEntity.currency` (which exists, per the entity). DEBIT 100 USD + CREDIT
100 EUR scores balanced. Posting-time validation may prevent writing such a journal —
but this sweep exists precisely to catch what the write path failed to enforce, and on
this class of corruption it would certify the corruption instead. Entry-vs-transaction
currency mismatch is likewise unchecked.

### 3. Mismatch matrix misses the premature-settlement direction — HIGH

`detectExternalStatusMismatch` flags local=SETTLED only when the provider reports a
release status. Provider reporting **PENDING_SETTLEMENT / ACTION_REQUIRED / PENDING_UNKNOWN**
against our SETTLED — we recorded a settlement the provider does not confirm, the exact
direction that double-pays — stays silent.

### 4. Expired reservations are detected, never released — MEDIUM

The sweep raises HIGH on ACTIVE-past-expiry, and nothing in the codebase transitions a
reservation out of that state (verified: only consents and MFA challenges expire).
Because dedup holds one OPEN issue, it raises once and the funds stay held indefinitely
pending manual action. If manual-only is the intent, the runbook should say so.

### 5. `findAll()` ledger scan every 30s — MEDIUM, scaling ceiling

Unbounded full-table load of every historical ledger transaction per sweep. Needs a
watermark or window before any pilot with real volume.

### 6. Sweep failures log message only — LOW

`scheduledRun` catches Exception and logs `e.getMessage()` without the throwable —
stack trace lost, and a null message logs "null". Pass `e` as the final argument.

## Also noted

Provider-leg reconciliation is status-only: the adapter returns no amount, so a partial
settlement (provider settled less than requested) is structurally undetectable. Larger
than a bug — an adapter API decision to revisit with the first real provider.
