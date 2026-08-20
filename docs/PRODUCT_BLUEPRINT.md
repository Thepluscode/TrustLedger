# TrustLedger — Project Goal and Detailed Feature Blueprint

> **Canonical framing:** [`CANONICAL_PRODUCT_DOCTRINE.md`](CANONICAL_PRODUCT_DOCTRINE.md) defines
> the current problem statement, role model and market/product gates. This blueprint must not be
> interpreted to bypass those gates.

## 1. Project goal

**TrustLedger is the Payment Reliability OS: a payment-operations control system for companies running money across multiple payment providers, bank accounts, internal ledgers and settlement processes.**

The primary customer question is:

> **What actually happened to the money?**

Canonical problem statement:

> Payment teams need a fast, defensible way to determine what happened to money when provider,
> bank, settlement, webhook and internal records disagree, because today they reconstruct the truth
> manually across fragmented systems, delaying recovery and leaving financial exposure difficult
> to explain.

Its job, stated in one sentence:

> Detect where a payment broke, reconstruct what happened, identify who must act, and preserve the evidence required to resolve it.

The thing being sold is not a dashboard and not an AI feature. It is the **daily operational control point** where a critical workflow happens and where proprietary operational intelligence compounds.

What a buyer hears:

> TrustLedger detects missing, delayed, duplicated and mismatched payments across your providers, bank settlements and internal ledger. It shows your team exactly what happened, what money is exposed and what must happen next.

### 1.1 The wedge: sell the read-only reliability layer first

The commercial entry point is a **read-only** layer that does not move or route customer money. It connects to payment providers, banks and settlement files, merchant transaction databases, internal accounting/ledger systems and webhook infrastructure, and answers:

* Did the provider process the payment?
* Did the merchant receive the webhook?
* Did the internal system record the transaction?
* Did the payment settle?
* Were the expected fees and FX rates applied?
* Do all systems agree on the state?
* Is money missing, delayed, duplicated or disputed?
* What action should the operator take next?

**Honest note on the codebase.** TrustLedger already contains a tested execution engine — a double-entry ledger, a fraud gate that holds and approves transfers, and external-rail submission. The read-only framing is a *deployment and pilot posture*, not a claim that the engine cannot execute. A read-only pilot deploys the ingestion, reconciliation, exception and evidence path with the execution surface switched off. Everything below §5 describing routing, payouts and canaries is real, retained, and deliberately outside the first sale.

The first engineering milestone is one end-to-end vertical slice:

```text
Provider webhook
→ canonical event
→ immutable evidence
→ internal-ledger comparison
→ reconciliation mismatch
→ operator exception
→ resolution audit trail
```

Until that runs against real customer data and finds a problem worth money, everything else is architecture cosplay.

### 1.2 MVP boundary

**Build:** two provider connectors · CSV settlement-file importer · internal ledger import · canonical payment model · unified transaction timeline · deterministic reconciliation · exception queue · evidence viewer · daily reconciliation report · tenant isolation · RBAC · immutable audit history · alerting · basic provider scorecard.

**Do not build yet:** autonomous provider routing · custody · payment initiation · AI reconciliation decisions · thirty connectors · a fraud-detection platform · crypto · ERP replacement · general accounting · a consumer payment app · a microservice estate.

### 1.3 Deterministic truth

The reconciliation engine — not an LLM — determines financial truth. Results are a closed taxonomy:

```text
MATCHED
MISSING_PROVIDER_RECORD
MISSING_INTERNAL_RECORD
AMOUNT_MISMATCH
CURRENCY_MISMATCH
FEE_MISMATCH
DUPLICATE_TRANSACTION
MISSING_SETTLEMENT
LATE_SETTLEMENT
INVALID_STATE_TRANSITION
UNKNOWN
```

The underlying safety promise still holds wherever execution is enabled:

> No money movement should occur without a deterministic decision, a durable ledger record, a known provider state and evidence explaining exactly what happened.

---

# 2. The problem TrustLedger solves

Companies integrating payment providers often end up with fragmented systems:

* each provider has different APIs and statuses;
* duplicate requests can create duplicate payouts;
* timeouts leave transactions in uncertain states;
* application balances drift from provider balances;
* webhook retries create duplicate processing;
* credentials are stored or rotated inconsistently;
* risk decisions are separated from payment execution;
* production access can be enabled without sufficient governance;
* reversals and failed payouts are poorly accounted for;
* audit teams cannot reconstruct the full transaction history.

TrustLedger provides one consistent operating model across all providers.

Instead of every business implementing payment safety independently, TrustLedger becomes the reusable infrastructure layer that enforces it.

---

# 3. Primary customers

Start where payment uncertainty causes measurable financial or operational damage. Qualification is operational, never geographic:

1. Fintechs using several payment providers
2. Marketplaces splitting money between buyers and sellers
3. Remittance and cross-border payment companies
4. Subscription businesses with high payment volumes
5. PSPs and payment aggregators
6. Large merchants operating across several countries or currencies

**Users:** Head of Payment Operations · Reconciliation Manager · Finance Operations Analyst · Treasury Operations · Payments Engineering · Risk and Compliance · Customer Support escalation.

**Budget owner:** COO, CFO, Head of Payments or CTO.

The sections below describe the wider surface the platform can serve once the reliability wedge is established.

## Fintech companies

TrustLedger can power:

* digital wallets,
* neobanks,
* savings applications,
* lending platforms,
* remittance businesses,
* payroll systems,
* expense-management platforms,
* treasury products.

## Marketplaces and platforms

It can manage:

* seller payouts,
* contractor payments,
* creator payouts,
* merchant settlements,
* marketplace refunds,
* escrow-like transaction flows.

## Enterprises

It can control:

* supplier payments,
* internal fund transfers,
* expense disbursements,
* treasury operations,
* multi-entity payment workflows.

## Financial institutions

It can provide:

* provider orchestration,
* payment-operation tooling,
* fraud decisioning,
* reconciliation,
* audit evidence,
* controlled introduction of new payment rails.

---

# 4. Core product principles

## Ledger first

The financial ledger is the source of truth. Provider callbacks do not directly redefine the company’s balances without validated accounting transitions.

## Fail closed

When the system cannot prove that a transaction is safe, authorised or eligible, it does not execute it.

## Idempotent by default

Repeated client requests, job retries and webhook redelivery must not create duplicate financial effects.

## Provider independent

Business logic should not depend directly on Paystack or another provider. Provider-specific behaviour is isolated behind adapters.

## Financial truth over provider convenience

A timeout does not mean failure. An HTTP success response does not necessarily mean settlement.

## Evidence attached to every decision

Routing, fraud, review, approval, settlement, reversal and reconciliation decisions produce audit evidence.

## Production is governed

Live money movement requires explicit controls, independent approvals, exposure limits and circuit breakers.

---

# 5. Detailed feature map

## 5.1 Multi-tenant platform

TrustLedger is designed to serve multiple organisations from one platform while maintaining strict isolation.

### Features

* Tenant registration and onboarding
* Tenant-specific users and roles
* Tenant-specific provider configurations
* Tenant-specific fraud policy
* Tenant-specific transaction limits
* Tenant-specific quotas and billing plans
* Tenant-scoped accounts, transfers and audit logs
* Tenant-scoped API credentials
* Tenant-scoped provider credentials
* Tenant-scoped production canaries
* Cross-tenant access prevention

Every important database entity carries a tenant identifier so one customer’s records cannot affect another customer.

---

## 5.2 Identity and access control

The platform supports role-based access to financial and administrative functions.

### Example roles

* Owner
* Tenant administrator
* Payment operator
* Provider configuration operator
* Fraud analyst
* Auditor
* Developer
* Standard user

### Permissions include

* create transfers,
* approve held payments,
* reject suspicious transactions,
* configure providers,
* request production canaries,
* approve production canaries,
* pause production activity,
* manage users,
* inspect audit evidence,
* manage API keys.

Sensitive operations are checked on the backend even when the frontend hides unavailable controls.

---

## 5.3 Accounts and balances

TrustLedger maintains internal financial accounts for users, organisations or system purposes.

### Features

* Multi-currency accounts
* Available balance
* Pending balance
* Posted balance
* Account status management
* Frozen-account controls
* Account-level ledger exploration
* Balance validation
* Currency consistency checks
* Prevention of unauthorised overdrafts
* Account ownership and tenant validation

Balances are not simply incremented or decremented fields. They are derived and protected through ledger operations.

---

## 5.4 Double-entry ledger

The ledger is the foundation of TrustLedger.

Every financial event creates balanced debit and credit entries.

### Example payout lifecycle

When a payout is prepared:

```text
Debit: customer available funds
Credit: customer pending payout funds
```

When the payout settles:

```text
Debit: pending payout funds
Credit: provider settlement / external clearing account
```

When the payout fails:

```text
Debit: pending payout funds
Credit: customer available funds
```

When a settled payout is reversed:

```text
Debit: provider reversal / receivable account
Credit: customer or recovery account
```

### Ledger features

* Double-entry journal
* Immutable ledger transactions
* Debit and credit validation
* Currency-level balancing
* Transaction references
* Ledger entry classification
* Financial-state transition services
* Posted, pending and available balances
* Reversal entries rather than destructive mutation
* Historical ledger inspection
* Ledger-to-provider reconciliation
* Prevention of unbalanced posting

---

## 5.5 Internal transfers

TrustLedger supports transfers between accounts held within the platform.

### Features

* Source and destination account validation
* Same-tenant validation
* Currency validation
* Balance reservation
* Fraud scoring
* MFA challenge where required
* Manual-review hold
* Approval and rejection workflows
* Idempotency protection
* Ledger settlement
* Complete audit trail

Internal transfers can settle without an external payment provider but still use the same fraud, ledger and evidence standards.

---

## 5.6 External payouts

External payouts send funds through providers such as Paystack.

### Features

* Beneficiary selection
* Payout-instrument management
* Bank-account or destination-token mapping
* Provider-recipient creation
* Provider-recipient reuse
* Payout route selection
* Durable provider attempt records
* Submission worker
* Provider reference tracking
* OTP or action-required flows
* Timeout handling
* Webhook settlement
* Provider-status polling
* Failure release
* Reversal accounting
* Retry protection
* No speculative resubmission after ambiguity

A payout is represented by both:

1. the business transfer, and
2. one or more controlled provider attempts.

This separation allows the system to reason safely about retries and provider failures.

---

## 5.7 Idempotency and duplicate-payment protection

Duplicate execution is one of the highest-risk problems in payment infrastructure.

TrustLedger protects against it using:

* client-supplied idempotency keys,
* tenant-scoped idempotency records,
* deterministic request hashes,
* duplicate-request detection,
* request-body mismatch rejection,
* unique provider references,
* webhook event deduplication,
* one canary reservation per transfer,
* durable submission-attempt state.

The request hash includes significant transaction fields such as:

* tenant,
* user,
* source account,
* beneficiary,
* amount,
* currency,
* destination country,
* selected provider,
* device,
* scenario,
* channel.

If the same idempotency key is reused with different transaction details, the request is rejected.

---

## 5.8 Fraud and risk engine

Every transfer can be assessed before money is moved.

### Risk inputs may include

* transfer amount,
* account history,
* device identity,
* device trust,
* beneficiary history,
* unusual geography,
* transfer velocity,
* amount deviation,
* previous fraud associations,
* user behaviour,
* provider corridor,
* model score.

### Decision bands

```text
ALLOW
MONITOR
MFA
HOLD
REJECT
```

### Features

* Tenant-specific fraud thresholds
* Risk-score generation
* Explainable risk signals
* Fraud-case creation
* Manual analyst review
* Approve or reject held transactions
* Device-risk profiles
* User-risk profiles
* Beneficiary-risk profiles
* Trusted-device progression
* Automatic account freezing
* Fraud-policy impact preview
* ML shadow-mode support
* Model-version evidence

The risk engine controls friction without directly changing ledger truth.

---

## 5.9 Payment-provider abstraction

All payment providers implement a common rail interface.

### Adapter responsibilities

* create or resolve recipients,
* submit payouts,
* query payout status,
* finalise OTP or provider challenges,
* verify webhook signatures,
* translate provider statuses,
* expose provider capabilities,
* declare aliases and routing priority.

### Benefits

* New providers can be added without rewriting payment logic.
* Provider-specific statuses are mapped into one internal lifecycle.
* Webhook verification remains provider-owned.
* Reconciliation queries use the provider that originated the attempt.
* Tenants can use different providers without changing their application code.

---

## 5.10 Provider routing engine

TrustLedger determines which provider is eligible for each payout.

### Routing inputs

* tenant,
* provider configuration,
* environment,
* currency,
* destination country,
* amount,
* corridor,
* provider availability,
* credentials,
* operational status,
* compliance approval,
* transaction limits,
* production canary state,
* preferred provider.

### Routing process

```text
Registered providers
        ↓
Capability filtering
        ↓
Tenant eligibility filtering
        ↓
Credential and operational checks
        ↓
Production-control checks
        ↓
Deterministic provider selection
```

### Safety rules

* A preferred provider is used only when eligible.
* TrustLedger does not silently fall back when the client explicitly requested an ineligible provider.
* If no provider is eligible, the transaction fails before funds are reserved.
* Routing decisions are persisted as evidence.
* The exact provider and configuration are retained for reconciliation.

---

## 5.11 Tenant provider governance

Each tenant manages its own payment-provider accounts.

### Provider configuration fields

* Provider name
* Environment: sandbox or production
* Enabled status
* Compliance status
* Operational status
* Emergency-disabled status
* Allowed currencies
* Allowed destination countries
* Minimum transaction amount
* Maximum transaction amount
* Credential readiness
* Webhook-secret readiness

### Important rule

Production provider configurations are not executable merely because they exist.

They must pass compliance, credentials, operational and canary controls.

---

## 5.12 Credential governance

Provider secrets must not be stored casually in application records.

### Features

* Secret references rather than raw secrets
* Environment or secret-manager resolution
* Separate execution and webhook credentials
* Credential activation
* Credential revocation
* Credential rotation
* Historical credential metadata
* No secret values returned by administrative APIs
* No secret material displayed in the frontend
* Audit records for credential changes

The long-term production design should integrate with AWS Secrets Manager, Azure Key Vault, HashiCorp Vault or another managed secret platform.

---

## 5.13 Production payout canaries

TrustLedger introduces live payment access through controlled canary plans rather than unlimited activation.

### Canary plan fields

* Tenant provider configuration
* Requesting operator
* Independent approver
* Start time
* Expiry time
* Maximum amount per transaction
* Maximum cumulative value
* Maximum number of transactions
* Failure threshold
* Ambiguous-outcome threshold
* Reversal threshold

### Required conditions

A production payout is eligible only when:

```text
Global platform switch is enabled
AND
Provider configuration is approved
AND
Provider is operational
AND
Credentials are active
AND
Emergency stop is clear
AND
An approved canary is active
AND
The payout fits within canary limits
AND
The circuit breaker is not paused
```

### Dual control

* A provider operator requests the canary.
* A different tenant administrator approves it.
* Requesters cannot approve their own canary.
* Approval is tied to the exact provider configuration.
* Multiple active canaries for the same provider configuration are prevented.

### Concurrent exposure protection

The canary plan is locked transactionally before exposure is reserved.

If two payments compete for the final permitted canary slot, only one succeeds.

### Conservative exposure accounting

Exposure is not restored merely because a payment failed.

A failed production payment still represents:

* provider exposure,
* operational risk,
* fraud risk,
* reconciliation work,
* incident potential.

New capacity requires another approved canary.

---

## 5.14 Automatic provider circuit breakers

Canaries automatically pause when defined risk thresholds are reached.

### Circuit-breaker events

* Authoritative provider failure
* Ambiguous timeout
* Pending-unknown result
* Cancellation
* Returned payment
* Provider reversal
* Reconciliation incident

### Behaviour

When a threshold is reached:

* the canary becomes paused,
* future production routing fails closed,
* an audit record is created,
* an outbox event is emitted,
* the pause reason is displayed to operators.

### Cross-generation protection

A late reversal from an older canary can pause the currently active canary for the same provider configuration.

This prevents operators from escaping unresolved risk simply by creating a new rollout plan.

---

## 5.15 Production readiness console

The operator console provides a safe interface for production governance.

### Features

* Display the actual backend global-switch status
* Show whether an active canary is required
* List production provider configurations
* Display readiness checks
* Identify blocked controls
* Request a new canary
* Configure transaction and cumulative limits
* Configure automatic abort thresholds
* Approve pending canaries
* Pause active canaries
* Resume eligible paused canaries
* Require typed confirmation for approval and resume
* Require a written reason for manual pause
* Display exposure-consumption bars
* Display settlement, failure, ambiguity and reversal counts
* Display requester and approver identities
* Display approval timestamps
* Display pause reasons
* Display historical rollout generations

The console does not expose provider secrets or credential references.

---

## 5.16 Webhook processing

Webhooks are treated as untrusted external events until verified.

### Features

* Provider-specific signature verification
* Raw-body verification
* Provider alias resolution
* Canonical provider naming
* Event-ID deduplication
* Provider-reference lookup
* Idempotent processing
* Settlement transition
* Failure transition
* Reversal transition
* Invalid-signature rejection
* Processed/unprocessed tracking
* Webhook-event inspection
* Audit and operational evidence

A webhook cannot settle an unrelated transfer simply by presenting a reference from another tenant or provider.

---

## 5.17 OTP and action-required payments

Some provider payouts require customer or operator interaction.

### Features

* `ACTION_REQUIRED` payment state
* Provider challenge metadata
* OTP finalisation endpoint
* Invalid-OTP handling
* Retry without releasing funds prematurely
* Redaction of sensitive OTP evidence
* Transition to provider-pending after successful OTP
* Timeout and reconciliation protection
* Idempotent completion

Invalid OTP attempts must not cause the transfer to be incorrectly marked as a final failure when the provider still permits another attempt.

---

## 5.18 Payment state machine

TrustLedger uses an explicit payment lifecycle rather than loosely changing status fields.

Possible states include:

```text
CREATED
RISK_REVIEW
MFA_REQUIRED
HELD_FOR_REVIEW
READY_TO_SUBMIT
SUBMITTING
ACTION_REQUIRED
PENDING_PROVIDER
PENDING_UNKNOWN
PENDING_SETTLEMENT
SETTLED
FAILED
CANCELLED
RETURNED
REVERSED
```

Only defined transitions are allowed.

For example:

* `SETTLED → READY_TO_SUBMIT` is forbidden.
* `FAILED → SUBMITTING` requires a controlled retry policy.
* `PENDING_UNKNOWN` must be reconciled before another provider attempt is created.
* `SETTLED → REVERSED` creates compensating ledger entries.

---

## 5.19 Reconciliation engine

Reconciliation ensures TrustLedger’s financial truth agrees with external providers.

### Features

* Scheduled provider-status checks
* Provider-specific query routing
* Attempt-status comparison
* Ledger-state comparison
* Balance comparison
* Missing-provider-event detection
* Mismatch creation
* Severity classification
* Reconciliation issue queue
* Issue investigation
* Manual resolution recording
* Reconciliation evidence
* Per-attempt failure isolation

A failure querying one provider attempt does not abort the entire reconciliation sweep.

---

## 5.20 Canary telemetry repair

Circuit-breaker telemetry runs independently from ledger settlement so an operational-metrics failure cannot block financial truth.

However, missing telemetry must still be repaired.

### Repair worker behaviour

* Reads durable provider-attempt status
* Reads the associated canary reservation
* Detects status drift
* Replays only missing canary accounting
* Uses idempotent outcome recording
* Does not alter balances
* Does not invent provider status
* Does not replace reconciliation
* Repairs eventually consistent operational evidence

---

## 5.21 Audit and evidence system

Every important operation should be reconstructable.

### Audited actions

* User authentication
* Transfer creation
* Fraud decisions
* MFA results
* Manual approvals and rejections
* Provider routing
* Provider submission
* Webhook processing
* Settlement
* Failure
* Reversal
* Provider configuration changes
* Credential rotation
* Canary request
* Canary approval
* Canary pause
* Circuit-breaker activation
* Billing-plan changes
* User and role changes

### Evidence exports

* Fraud-case evidence packs
* Transaction histories
* Ledger entries
* Audit timeline
* Provider routing evidence
* Reconciliation findings
* Checksums for exported evidence
* Downloadable JSON or report formats

---

## 5.22 Outbox and event-driven integration

TrustLedger uses a transactional outbox to ensure important domain events are not lost.

### Events may include

* Transfer created
* Transfer settled
* Transfer failed
* Fraud case opened
* Provider selected
* Provider submission requested
* Webhook processed
* Reconciliation issue created
* Canary approved
* Canary paused
* Credential rotated
* Billing plan changed

The outbox separates database truth from asynchronous publishing to Kafka or other event systems.

---

## 5.23 Monitoring and operational health

The platform includes an operational monitoring surface.

### Signals

* Database availability
* API latency
* Fraud-scoring latency
* Outbox backlog
* Oldest unpublished event
* Invalid webhook signatures
* Unprocessed webhook events
* Reconciliation issue count
* Pending-provider payments
* Database lock waits
* Overall platform status

Future production monitoring should include:

* provider-specific acceptance rates,
* webhook-delivery latency,
* settlement latency,
* ambiguous-payment rate,
* reversal rate,
* reconciliation drift,
* canary exposure remaining,
* circuit-breaker alerts.

---

## 5.24 Developer platform

TrustLedger is designed to be integrated through APIs.

### Features

* REST APIs
* JWT authentication
* API keys
* Key scopes
* API-key rotation
* API-key revocation
* Idempotency headers
* Consistent error responses
* Provider-neutral transaction models
* Webhook inspection
* Sandbox environment
* Integration documentation
* Test data and simulated scenarios

Long-term developer features should include:

* SDKs for JavaScript, Java, Python and Go,
* OpenAPI specification,
* webhook replay,
* test clocks,
* sandbox scenario controls,
* integration health checks,
* request tracing.

---

## 5.25 Tenant administration and billing

### Features

* Tenant plan selection
* Usage tracking
* Quotas
* Provider-configuration count
* Billing-event generation
* User management
* Role management
* Fraud-policy management
* Provider administration
* Audit logging of plan changes

Potential commercial plans:

```text
FREE_SANDBOX
PILOT
PROFESSIONAL
ENTERPRISE
INTERNAL
```

---

# 6. End-to-end payment flow

A normal external payout follows this sequence:

```text
1. Client submits payout with an idempotency key
2. TrustLedger authenticates user and tenant
3. Request hash and idempotency are validated
4. Source account, beneficiary and payout instrument are validated
5. Fraud engine scores the transaction
6. Transaction is allowed, challenged, held or rejected
7. Eligible providers are filtered
8. Tenant provider policy is evaluated
9. Production switch and canary policy are checked
10. Canary exposure is transactionally reserved where required
11. Funds move from available to pending in the ledger
12. A durable provider attempt is created
13. Submission worker calls the selected provider
14. Provider response is normalised
15. OTP/action-required flow is handled where necessary
16. Webhooks and status polling update authoritative provider state
17. Ledger funds are settled, released or reversed
18. Reconciliation verifies provider and ledger agreement
19. Audit, evidence and outbox records are retained
20. Canary circuit-breaker metrics are updated
```

---

# 7. Current implementation status

The current TrustLedger implementation already includes major components of this vision:

## Implemented or substantially implemented

* Multi-tenant backend
* Authentication and roles
* Accounts and balances
* Double-entry ledger
* Internal transfers
* External transfers
* Idempotency protection
* Fraud scoring and decision bands
* Manual fraud-case review
* Provider abstraction
* Provider routing
* Tenant provider eligibility
* Paystack integration
* Recipient resolution
* Provider webhooks
* OTP completion
* Reversal accounting
* Reconciliation
* Audit logs
* Evidence exports
* Outbox events
* API-key management
* Provider credential rotation
* Production canary control plane
* Automatic circuit breakers
* Canary telemetry repair
* Production readiness console
* Monitoring dashboard
* Flyway migrations
* Testcontainers integration testing
* Next.js operations interface
* Docker, Kubernetes, Helm and Terraform validation

---

# 8. Next major feature groups

Sequenced around the wedge. §8.1 is the named next increment; everything after it is retained
capability work that must not jump the queue.

## 8.1 TrustLedger Settlement Watch — the first post-gate increment

TrustLedger should not merely record financial truth. It should continuously defend it. Settlement
Watch is the first expression of that: a **read-only** service that

1. ingests provider transactions and settlement reports;
2. matches expected against actual settlements;
3. detects missing, delayed or incorrect payments;
4. classifies each break using the closed taxonomy (§1.3);
5. creates an exception with supporting evidence, financial exposure and an owner;
6. alerts the responsible operator;
7. records every observation and investigation step in the immutable audit trail.

This is not a new subsystem. It is scheduled watchers over data the platform already holds —
statement ingestion and line matching (V32), the reconciliation worker, the evidence store — plus
the taxonomy and the exception-ops upgrade (owner, exposure, deadline, activity history).

The current market gate is still unvalidated, so this section is the implementation order after
market `GO` and a passed read-only product pilot—not authority to expand the platform now.

### Exception-operations contract after both gates pass

Extend `reconciliation_issues`; do not create a parallel case system:

- fields: named owner, decimal financial exposure plus currency, priority, deadline, lifecycle
  status, queryable resolution outcome and resolution timestamp;
- lifecycle: `OPEN → IN_PROGRESS → BLOCKED/ESCALATED → RESOLVED`;
- append-only tenant-scoped activity: assignment, notes, evidence, escalation, transitions,
  resolution proposals, approvals and closure;
- filters: status, owner, severity, classification, exposure and overdue state;
- views: operator queue, engineering evidence/escalation and management exposure/SLA;
- permissions: `RECONCILIATION_VIEW`, `RECONCILIATION_INVESTIGATE`,
  `RECONCILIATION_ASSIGN`, `RECONCILIATION_RESOLVE` and `RECONCILIATION_WRITE_OFF`.

Finance operators may claim, investigate and resolve non-write-off outcomes; engineers may add
technical evidence but not make financial resolutions; owners/admins may assign, reprioritise,
configure SLAs and approve write-offs; auditors remain read-only. Tenant-scoped APIs take actor
identity only from authentication and support claim/assign, activity, escalation, resolution
proposal, write-off approval and pilot-measurement export.

Before release, prove tenant isolation at every repository read/lock/mutation; deterministic
concurrent claim/assignment/escalation/resolution; append-only attribution; decimal and currency-safe
exposure; controlled-clock SLA calculations; unknown preservation; suggestion non-authority;
write-off elevation; and duplicate/delayed-input idempotency on desktop and mobile. Financial
remediation remains a separate human-authority and safety gate.

### The Financial Operations Agents module

Settlement Watch is the first of a family of continuous watchers:

```text
Financial Operations Agents
├── Settlement Watcher          (missing / late / short settlements)
├── Webhook Health Agent        (delivery gaps, duplicate rate, signature failures)
├── Reconciliation Investigator (gathers the evidence for a break, proposes a cause)
├── Fee and FX Monitor          (expected vs charged fees and rates)
└── Evidence Pack Generator     (assembles the audit bundle for a case)
```

Rules that keep this a moat rather than a gimmick:

* **Every agent action passes through the shared control plane** — identity (who is the agent
  acting for), policy (what it may inspect and enforce), audit (what it observed and concluded),
  observability (is it operating correctly), evidence (what proves the alert).
* **Agents observe and propose; the deterministic engine decides.** The reconciliation engine —
  never an agent, never an LLM — determines financial truth (§1.3). Agents watch, classify against
  the closed taxonomy, and assemble evidence.
* **The agent is replaceable. The governed financial history, policies, evidence and integrations
  are not.** That is where the value accumulates.

### The commercial ladder this unlocks

Each step increases revenue on the same infrastructure — no throwaway consulting, no side products:

```text
manual reconciliation assessment
→ read-only TrustLedger pilot
→ continuous exception monitoring   (Settlement Watch — this increment)
→ automated investigation
→ human-approved remediation
→ policy-controlled autonomous action
```

Every engagement compounds the same assets: provider connectors, reconciliation rules, exception
taxonomy, financial policies, evidence schemas, audit history, operational benchmarks. Over time
that data powers provider reliability scoring, anomaly detection, settlement-risk prediction and
automated root-cause analysis — which is the §10 defensibility, not a separate AI product.

**Explicitly not this:** a generic AI-agent platform, a chatbot attached to TrustLedger, a local-AI
hardware product, an automation consultancy, or another repository. Those dilute the company.

## 8.2 Provider certification system

Automate the evidence required before a provider can move production money:

* sandbox payout tests,
* webhook-signature tests,
* OTP tests,
* timeout tests,
* settlement tests,
* failure-release tests,
* reversal tests,
* reconciliation tests,
* ledger-invariant checks,
* credential-rotation drills,
* emergency-stop exercises.

## 8.3 Certification evidence packs

Generate a signed report containing:

* tested provider,
* configuration,
* environment,
* executed scenarios,
* results,
* ledger evidence,
* webhook evidence,
* reconciliation evidence,
* approving users,
* timestamps,
* checksums,
* unresolved risks.

## 8.4 Additional payment providers

Potential providers:

* Flutterwave
* Monnify
* Stripe
* Adyen
* Checkout.com
* Wise Platform
* Open Banking providers
* Direct bank APIs
* Mobile-money operators

Each new provider should use the existing adapter and governance contracts.

## 8.5 Advanced reconciliation

* Statement ingestion
* Automated matching
* Fee reconciliation
* Settlement-batch matching
* Expected-versus-received settlement
* Provider-balance reconciliation
* Break investigation
* Automated evidence generation

## 8.6 Enterprise controls

* Maker-checker approval
* Multi-stage approvals
* Approval limits
* Transaction signing
* Policy-as-code
* IP allowlists
* SSO and SAML
* SCIM provisioning
* Hardware-key support
* Data-residency controls
* Legal-entity separation

## 8.7 Reliability and scale

* Multi-region deployment
* Disaster recovery
* Read replicas
* Database partitioning
* Provider-specific worker queues
* Rate-limit coordination
* Distributed tracing
* SLOs and error budgets
* Replayable event streams
* Load testing
* Chaos testing
* Automated failover

---

# 9. Commercial product positioning

Sell the reliability wedge, not the platform. Do not call the MVP a global financial control plane — that description is too broad and too early.

> TrustLedger detects missing, delayed, duplicated and mismatched payments across your providers, bank settlements and internal ledger. It shows your team exactly what happened, what money is exposed and what must happen next.

## Commercial packaging

**Paid discovery — £5,000–£15,000.** Payment-flow mapping, failure taxonomy, reconciliation assessment, data-access plan, estimated financial exposure, pilot architecture.

**Read-only pilot — £15,000–£40,000 over 30–60 days.** Two integrations, transaction timeline, reconciliation, exception detection, weekly evidence report, quantified operational findings.

**Production contract.** Priced on some combination of base platform fee, payment volume observed, number of providers, legal entities and currencies, evidence-retention period, premium workflow modules and enterprise deployment requirements.

An early annual contract target of **£30,000–£150,000** is a working assumption, not validated market pricing. Nothing here has been tested against a signed deal.

## Defensibility

The dashboard is not the moat. These are, in the order they accumulate:

1. **Connector depth** — provider-specific failures, inconsistencies and settlement behaviour.
2. **Canonical payment ontology** — a mature model over provider events, internal states, exceptions and resolutions.
3. **Failure taxonomy** — which symptoms correspond to which operational causes.
4. **Resolution dataset** — what action actually resolved each class of exception.
5. **Workflow lock-in** — teams investigate, assign, approve and resolve inside TrustLedger.
6. **Audit trust** — customers rely on TrustLedger records for disputes, audits and regulators.
7. **Benchmark intelligence** — aggregated provider reliability by corridor, method and currency, subject to contractual and privacy constraints.

Retained engineering advantages that support the above: strong money-safety invariants, provider-neutral architecture, reconciliation integrated into the payment lifecycle, complete evidence generation, safe handling of ambiguous provider outcomes, repeatable provider certification.

---

# 10. Ultimate product vision

Not a collection of fintech features, and not merely reconciliation software:

> **A governed payment reliability network that understands how money moves, where it fails and how organisations recover it.**

The expansion sequence — each rung earns the next, and none may be skipped:

```text
account for money
→ explain exceptions
→ govern actions
→ control movement
→ optimise the network
```

The execution capabilities already in this repository (one ledger, one transfer lifecycle, one fraud model, one provider interface, one audit trail, one production-control process) are rungs four and five. They are built and tested; they are not what gets sold first.
