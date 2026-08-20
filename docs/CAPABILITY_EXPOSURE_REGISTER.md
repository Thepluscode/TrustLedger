# TrustLedger Capability Exposure Register

This register prevents three different states from collapsing into “built”:

- **Canonical main** — present at `origin/main` and supported by committed repository evidence.
- **Open PR / branch** — implemented but not canonical main; PR #125 is currently open.
- **Local verified** — observed in this worktree, but not committed or present on remote main.
- **External proof missing** — customer, production or commercial evidence does not exist yet.

Current repository state checked 2026-08-14: local branch `main` points at the same commit as
`origin/main` (`b687925`); PR #125, `feat/frontend-api-wiring → main`, remains **OPEN** and mergeable.
The worktree contains uncommitted interface and showcase changes, so those changes are not remote
main merely because they run locally.

## Correct public description

> TrustLedger is a deeply engineered, multi-tenant payment-reliability, financial-control and
> operational-evidence platform with substantial test-backed infrastructure for reconciliation,
> ledger correctness, provider governance, fraud controls, audit provenance, recovery and enterprise
> operations—but its central commercial hypothesis remains unvalidated by real customers.

The first commercial offer remains a read-only reconciliation wedge. Broader engineering depth is
credibility and expansion capacity, not permission to sell TrustLedger as a bank, gateway, custody
provider, autonomous decision-maker or proven production platform.

## Capability and claim boundary

| Area | Current evidence | Safe claim | Boundary that must travel with the claim |
|---|---|---|---|
| Financial core | Double-entry ledger; reserve, consume, release and reverse; decimal money; idempotency; concurrent-spend controls; immutable rows; `PENDING_UNKNOWN` | Financial state transitions and ledger invariants are deeply test-backed | Opening balances can exist outside the journal, so full account-balance reconstruction is not claimed |
| Reconciliation | Provider comparison; settlement ingestion; duplicate/currency/fee breaks; closed taxonomy; SLA escalation; rejected-webhook envelopes | TrustLedger can identify and explain disagreement between payment, provider and settlement records | No live customer-scale recall, investigation-speed or ROI evidence |
| Audit and evidence | PostgreSQL append-only controls; correlation and actor attribution; chained SHA-256 checkpoints; real attack tests; Ed25519 packs and key rotation | Evidence integrity, origin and tamper detection are unusually strong engineering differentiators | External checkpoint anchoring and per-tenant signing keys are not built; tamper-evident is not tamper-proof |
| Tenant security | Tenant RBAC; organisation-unit subtree scope; tenant-scoped locking; cross-tenant integration tests; OIDC tenant/audience validation | Tenant and organisational scope are enforced across financial and evidence workflows | Engineering controls are not a production certification; some frontend/API integrations remain in PR #125 |
| Provider controls | Rail abstraction; Paystack work; credential lifecycle; emergency stop; certification drills; OTP, reversals and disputes | Provider behaviour is isolated behind governed, fail-safe adapters | Stripe proves structural independence only; live Stripe transport is not certified |
| Fraud and ML | Explainable rules and signals; MFA/hold/reject; analyst decisions; reservation while held; ML shadow/governance controls | Risk decisions are reviewable and evidence-bearing | ML never establishes financial truth; TrustLedger is not sold first as a fraud platform |
| Controlled operations | Dual approval; maker/checker; canaries; exposure controls; circuit breakers; legal holds and retention | Sensitive operational actions have explicit approval and audit controls | Presence in code does not prove sustained production operation |
| Recovery | Destructive database restore; financial, tenant, idempotency and audit validation; immutability-trigger survival | Development recovery has been tested by destroying and restoring data | Off-host backup, PITR/WAL, production RTO/RPO and post-restore provider reconciliation remain unproven |
| Observability | Prometheus, funnel/decision logs, outbox/reconciliation health, correlation IDs and local load probe | The system exposes operational and financial-health signals | ~250 TPS and ~51 ms p95 are development baselines, never production claims |
| Interface | Large responsive route/reference surface | A substantial operator console exists | PR #125 is open; the executive showcase is local verified and not remote main |
| Market validation | Pre-registered interview and product gates with mechanical scorers | Commercial validation is disciplined and falsifiable | Interviews 0/25; pain 0/6; data 0/3; paid 0/2; customer ROI unproven |

## Exposure rule

Do not walk an operator through this table first. Reconstruct their last real discrepancy first.
Then expose only the capability that maps to the evidence they just described:

| Their incident | Show | Ask |
|---|---|---|
| Missing or late settlement | Missing-settlement replay | “Which source would still be missing before you could act?” |
| Duplicate provider callback | Duplicate-event replay | “How do you prove today that no second financial effect occurred?” |
| Webhook/provider conflict or timeout | State-conflict / provider-timeout replay | “What state do you use when neither success nor failure is supportable?” |
| Fee leakage | Fee-overcharge replay | “Where is the fee schedule in force for that historical period stored today?” |
| Audit or regulatory proof | Audit-tamper replay | “Who must trust the evidence outside the operations team?” |

Feature praise is not evidence. A useful exposure ends with one of three observable outcomes:

1. the operator corrects the reconstructed problem and introduces another qualified operator;
2. the company grants a scoped sample-data exercise; or
3. an accountable buyer agrees to paid discovery tied to a measurable result.

Anything else is learning, but it does not move the market gate.
