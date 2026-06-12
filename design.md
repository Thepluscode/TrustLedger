# TrustLedger Frontend Interface Design

**File:** `design.md`
**Product:** TrustLedger
**Version:** v3.0 UI/UX specification
**Design goal:** Build a modern, user-friendly, enterprise-grade interface for a ledger-first transaction and fraud monitoring platform.

> ## Implementation notes (engineering, 2026-06-12)
>
> This spec is the design target. Implementation follows the build order in §30, in verified
> slices, **live-wired against the real backend only** (no mock layer — same rule as the rest of
> the console). Two deliberate divergences and an honest coverage map:
>
> **1. Stack divergence (§26).** The spec recommends Tailwind + shadcn/ui + TanStack Query/Table +
> Recharts. The existing console is a zero-dependency hand-built system (Next.js + React + one
> `globals.css` design-token sheet + one typed `lib/api.ts`). We implement the spec's visual
> language *in that system* instead of adding ~15 dependencies (doctrine Rule 12: no unjustified
> abstraction; the bundle stays small and there is no second styling system to drift). If the
> console grows past ~20 routes, revisit.
>
> **2. Backend coverage (honesty map).** Screens are only built where a real endpoint exists.
> Current coverage:
>
> | Spec section | Backend support today | Status |
> |---|---|---|
> | §7 Dashboard | `GET /api/v1/dashboard/summary` (6 metrics) | build |
> | §8 Transfers | `POST /transfers`, `POST /transfers/external`, `POST /fraud/assess` — **no list/detail endpoint** | create-flow only; list/detail deferred |
> | §9 Ledger | `GET /accounts/{id}/ledger`, `GET /ledger/transactions/{id}` | build |
> | §10 Fraud cases | list/get/approve/reject + feedback | build |
> | §11 Risk profiles | none | deferred |
> | §12 ML | scores/models/promote/rollback/monitoring | build |
> | §13 Payment rails | OB consents + sandbox webhook + tenant provider-configs | partial (configs + consent submit) |
> | §14 Reconciliation | none (issues counted in dashboard summary only) | deferred |
> | §15 Evidence | exports list/download/create/legal-hold/delete/retention | build |
> | §16 Audit logs | `GET /audit-logs` | build |
> | §17 Tenant admin | usage/quota/plan/billing/provider-configs/fraud-policy | build (no users & roles endpoint yet) |
> | §18 Onboarding | none | deferred |
> | §19 Developer | none (no API-key endpoints) | deferred |
> | §20 Monitoring | `/actuator/prometheus` (scrape, not JSON) | deferred |
> | §23.1 Command palette | n/a (frontend-only) | later slice |
>
> Deferred items are logged in `FEATURE_TRACKER.md`, never faked in the UI.

---

## 1. Product UX mission

TrustLedger helps teams move money safely, detect suspicious transaction behaviour, investigate fraud, reconcile financial state, and export evidence.

The frontend must make complex financial operations feel clear, controlled, and safe.

The UI should answer four questions quickly:

1. **What is happening now?**
2. **What is risky?**
3. **What money moved?**
4. **What action should I take next?**

The interface must not feel like a generic banking dashboard. It should feel like a modern financial operations cockpit.

---

## 2. Core UX principles

### 2.1 Ledger-first clarity

Every money movement must be traceable.

```text
Transfer → Fraud decision → Ledger entries → Audit trail → Evidence export
```

Users should never wonder whether a transaction was merely displayed or actually posted in the ledger.

### 2.2 Risk explanation over raw scoring

Do not show only `Risk score: 87`. Show the score, the band, and **why**:

```text
Risk score: 87 — High
Why:
- New beneficiary added 2 hours ago
- Transfer amount is 9.4× user median
- Device is untrusted
- 5 failed login attempts occurred before transfer
```

### 2.3 Safe actions by default

Dangerous operations require friction: approving high-risk transfers, rejecting fraud cases,
freezing/unfreezing accounts, reversing transactions, changing retention policy, exporting
evidence, changing provider credentials. Use confirmation modals, typed confirmations,
re-authentication, and dual-approval status where appropriate.

### 2.4 No empty-dashboard failure

For demo and pilot mode, seed realistic data (normal transfer, high-risk transfer, held case,
failed provider callback, duplicate webhook, reconciliation issue, reversal, evidence export,
ML shadow disagreement). Smart empty states everywhere else (§23.2).

### 2.5 Analyst-friendly workflows

A fraud case page contains: risk summary, transaction details, fraud signals, model explanation,
ledger state, device/beneficiary history, linked cases, payment rail state, audit trail,
recommended actions — without five page hops.

---

## 3. Primary user roles

| Role | Primary needs | UI emphasis |
|---|---|---|
| Owner | Organisation control, billing, risk posture | Tenant settings, usage, reports |
| Tenant Admin | Users, roles, provider config, policies | Admin console |
| Fraud Manager | Case queues, SLA, approvals, policy tuning | Fraud operations cockpit |
| Fraud Analyst | Investigate cases, approve/reject, notes | Case detail workflow |
| Finance Operator | Transfers, ledger, reconciliation | Ledger and payment operations |
| Auditor | Evidence, audit logs, exports, read-only | Evidence and audit pages |
| Developer/Admin | API keys, webhooks, integrations | Developer and provider config |
| Viewer | Read-only operational visibility | Dashboard and reports |

---

## 4. Information architecture

### 4.1 Main navigation (left sidebar desktop, drawer mobile)

```text
TrustLedger
├── Overview
├── Transfers
├── Ledger
├── Fraud (Cases · Signals · Risk Profiles · ML Monitoring)
├── Payment Rails (Providers · Consents · Webhooks · Reconciliation)
├── Evidence (Evidence Packs · Reports · Exports)
├── Reconciliation
├── Audit Logs
├── Tenants (Organisation · Users & Roles · Policies · Quotas · Usage)
├── Monitoring
├── Developer
└── Settings
```

### 4.2 Sidebar behaviour

Desktop: fixed, collapsible to icons, active section highlighted, grouped, tenant switcher top,
user/security menu bottom. Mobile/tablet: top app bar + hamburger drawer.

---

## 5. Visual design direction

### 5.1 Style

Modern, calm, technical, trustworthy. Avoid childish colours, crypto neon, overloaded tables,
vague analytics cards, generic SaaS templates. Use dark-first, strong spacing, subtle borders,
clear severity colours, readable tables, timeline investigations, card summaries, command palette.

### 5.2 Colour system (semantic, never decorative)

```text
Background primary: deep slate / near-black
Surface primary: dark navy/graphite · Surface elevated: lighter slate
Border: muted slate · Text primary: near-white · Text secondary: cool grey
Accent: blue/cyan · Success: green · Warning: amber · Danger: red · Critical: red/purple · Info: blue
```

| Severity | Colour intent | Usage |
|---|---|---|
| Low | Blue/grey | Informational risk |
| Medium | Amber | Needs attention |
| High | Orange/red | Analyst review likely |
| Critical | Red/purple | Immediate action |

**Never colour alone — always pair with label/icon/text.**

### 5.3 Typography

Clean sans-serif. Page title 28–32 semibold · section 18–22 semibold · card metric 24–32 bold ·
body 14–16 · table 13–14 · metadata 12–13. **Financial values use tabular numerals.**

### 5.4 Layout grid

Desktop: sidebar 260px expanded / 72px collapsed · header 64px · page padding 24–32 · card gap
16–24. Mobile: single column, critical cards first, tables become cards, filter drawer, sticky
primary action.

---

## 6. Global app shell

Header: page title, tenant selector, environment badge (Sandbox blue / Staging amber /
Production red outline + stricter confirmations), global search, notifications, quick Create
Transfer, user menu.

---

## 7. Overview dashboard (`/dashboard`)

Top metrics: money moved today, completed transfers, held transfers, open fraud cases, pending
unknown payments, reconciliation issues. Second row: risk trend, volume, severity charts, rail
status. Third row: high-risk queue, recent ledger postings, recent evidence exports, alerts.
Held-transfers card shows count, value held, oldest hold age, SLA breaches.

---

## 8. Transfers (`/transfers`, `/transfers/new`, `/transfers/[transactionId]`)

List columns: status, txn ID, sender, beneficiary, amount, currency, risk score, rail, created,
updated. Filters: status, risk band, amount/date range, org unit, rail, provider status, decision.

Create flow (multi-step): 1 source account → 2 beneficiary → 3 amount/reference → 4 **risk
preview** → 5 confirm. Before submit show: available balance, beneficiary trust, estimated risk,
MFA warning, client-generated idempotency key.

Detail: summary, visual state machine
`CREATED → VALIDATED → FRAUD_CHECKED → HELD_FOR_REVIEW → APPROVED → POSTED → COMPLETED`,
fraud decision, ledger entries, rail status, evidence, audit trail.

---

## 9. Ledger (`/ledger`, `/ledger/accounts/[accountId]`, `/ledger/transactions/[id]`)

Explorer shows ledger txn ID, related transfer, debit/credit entries, amount, currency,
**balanced status**, posting time, reversal status. Detail shows debits, credits, balance
before/after, related transfer/case/rail event, audit trail, reversal history.

Invariant displayed clearly:

```text
Debits:  £1,000.00   Credits: £1,000.00   Status: Balanced
```

Unbalanced → critical warning + link to reconciliation. Use a **side-by-side debit/credit split
table**, not a flat list.

---

## 10. Fraud cases (`/fraud/cases`, `/fraud/cases/[caseId]`)

List: severity, risk score, title, customer, amount, status, analyst, SLA, created. Priority:
critical severity → SLA breach → high value → unassigned → newest high-risk.

Case detail = analyst workspace. Left: summary + action panel. Main: timeline, signals, evidence.
Right: user/device/beneficiary context. Risk explanation panel lists *why flagged*. Timeline:
login, failed login, beneficiary added, transfer created, scored, MFA, case opened, note,
approve/reject, posted/released, exported. Actions (confirm-gated): approve, reject, request MFA,
request verification, freeze, unfreeze, escalate, mark false positive, export evidence pack.

---

## 11. Fraud intelligence — risk profiles (`/fraud/risk-profiles/...`)

User: usual amount, login countries, trusted devices, transfer hours, failed-login baseline,
beneficiary history, risk trend. Beneficiary: first seen, total received, sender count, linked
cases, mule warning. Device: fingerprint, first/last seen, users, countries, trusted, linkage.

---

## 12. ML-assisted fraud (`/ml/models`, `/ml/monitoring`, `/ml/scores/[transactionId]`)

Registry: name, version, status (Training/Candidate/Shadow/Analyst Assist/Retired/Rollback),
deployment mode, trained date, data window, precision/recall, approved by, rollback. Score
detail: rules vs ML score, agreement, model/feature-set version, top factors, missing features,
latency, shadow flag. Banner: **"ML is running in shadow mode. It cannot directly move or block
money."** Monitoring: score distribution, error rate, latency p95, analyst disagreement, FP
trend, feature-missing rate, drift.

---

## 13. Payment rails (`/payment-rails/...`)

Providers: provider, environment, status, last callback, webhook health, failure rate, pending
unknown. Consents: ID, user, transaction, provider, status (Created/Awaiting/Authorised/
Rejected/Expired/Revoked/Failed), timestamps. Webhooks: event ID, provider ref, signature valid,
processed, duplicate/replay, payload preview. Duplicates show: **"Duplicate safely ignored — no
ledger mutation performed."**

---

## 14. Reconciliation (`/reconciliation`, `/reconciliation/issues/[issueId]`)

Cards: open, critical, pending unknown, expired reservations, outbox stuck, ledger mismatches.
Issue types: balance mismatch, unbalanced ledger txn, provider status mismatch, expired
reservation, pending-unknown-too-long, duplicate webhook anomaly, outbox stuck, missing entry.
Issue detail: expected vs actual, evidence, related entities, recommended action, resolution
history. Actions: assign, investigate, resolve, escalate, export, re-run.

---

## 15. Evidence & reports (`/evidence`, `/evidence/exports`, `/reports/...`)

Exports: ID, type, resource, format (PDF/JSON/CSV/ZIP), generated by/at, checksum, status,
download. Generation flow: select type → resource → format → preview sections → generate →
checksum → download. Types: fraud-case pack, ledger report, reconciliation report, audit export,
rail event report, tenant usage report.

---

## 16. Audit logs (`/audit-logs`)

Columns: timestamp, actor, action, resource, IP, tenant, before/after, risk level. Filters:
actor, resource/action type, date, tenant, IP, sensitive-only, failed-access. Detail drawer:
actor, role, tenant, org unit, action, resource, before/after state, request metadata,
correlation ID. Denied access visually highlighted.

---

## 17. Tenant admin (`/admin/...`)

Tenant dashboard: status, plan, users, transfers/month, cases, exports, storage, quota,
providers, policy warnings. Users & roles: invite, change role, disable, force MFA reset, view
activity. Fraud policy editor: MFA/hold/reject thresholds, device/beneficiary weights, velocity,
dual-approval amount, auto-freeze — with impact preview ("14% more transfers would require MFA").
Retention editor per resource type; dangerous changes confirm-gated + audited.

---

## 18. Onboarding (`/onboarding`)

Create org → environment → invite users → roles → fraud policy → sandbox provider → retention →
seed demo data → test transfer → readiness checklist → completion screen.

---

## 19. Developer (`/developer/...`)

API keys (name, scope, last used, created by, rotated, revoke — never show secret twice),
webhooks, outbox event stream (type, aggregate, status, retries, payload preview; retry failed).

---

## 20. Monitoring (`/monitoring`)

API health, ledger posting latency, fraud scoring latency, outbox lag, provider timeout rate,
webhook failure rate, reconciliation job status, export failure rate, DB lock wait. Status
banner: "All critical systems operational" / "Critical: …".

---

## 21. Component system

Core: AppShell, SidebarNav, TopBar, TenantSwitcher, EnvironmentBadge, MetricCard, RiskBadge,
StatusPill, SeverityPill, DataTable, FilterBar, SearchInput, Timeline, LedgerEntryTable,
DebitCreditSplitView, RiskExplanationPanel, FraudSignalList, AuditTrail, EvidenceExportButton,
ConfirmActionModal, DualApprovalPanel, MfaChallengeModal, EmptyState, ErrorState,
LoadingSkeleton, CommandPalette.

Status pills: COMPLETED, HELD_FOR_REVIEW, PENDING_UNKNOWN, FAILED, REVERSED, SETTLED,
RECONCILIATION_REQUIRED.

**Risk badge bands: Low 0–29 · Medium 30–59 · High 60–84 · Critical 85–100. Always label + score.**

---

## 22. Critical UX flows

**Low-risk transfer:** create → score low → ledger posts → completed → audit written. Success
screen: "Transfer completed · Ledger transaction balanced · Audit log created · View ledger".

**High-risk transfer:** create → score high → funds reserved → case opened → analyst review →
approve/reject. Show amount reserved, hold reason, owner, next action, SLA timer.

**Provider timeout:** submitted → timeout → `PENDING_UNKNOWN` → reconciliation scheduled. Explain:
"The provider did not confirm final status. TrustLedger has not retried the payment blindly.
Reconciliation will check the provider before taking further action."

**Duplicate webhook:** first processed, second ignored safely. Show: "Duplicate webhook detected
and ignored. No duplicate ledger posting occurred."

**Evidence export:** open case → export pack → select format → generate → checksum → audit.

---

## 23. Modern usability details

**Command palette** (Cmd/Ctrl+K): search transfers/cases/accounts, create transfer, open
reconciliation, export report, audit logs, switch tenant.

**Smart empty states** — never "No data." e.g. "No open fraud cases. Run a demo high-risk
transfer or adjust your fraud policy to test case creation."

**Inline explanations** for pending unknown, reversal, dual approval, reconciliation, shadow
mode, webhook replay. **Progressive disclosure**: drawers, expandable rows, tabs, raw JSON
viewers.

---

## 24. Accessibility

WCAG 2.2 AA discipline: keyboard nav, visible focus, contrast, labelled inputs, ARIA on icon
buttons, SR-friendly status changes, no colour-only severity, accessible modals,
skip-to-content, reduced motion.

---

## 25. Responsive

Desktop full. Tablet: collapsible sidebar, stacked cards, scrollable tables, sticky action bar.
Mobile: simplified cards, card-tables, bottom sheets, read/review first; dangerous admin actions
may stay desktop-first.

---

## 26. Frontend technical architecture

Spec recommends Next.js + TypeScript + Tailwind + shadcn-style components + RHF + Zod + TanStack
Query/Table + Recharts/Tremor + Playwright + Vitest/RTL. **See implementation note 1 — current
build keeps the zero-dependency hand-rolled system.** Structure mirrors §26's `app/` route map.

---

## 27. API integration pattern

Typed clients; hooks per domain (`useTransfers`, `useFraudCase`, …). Every call handles loading,
empty, error, permission denied, expired session, tenant mismatch.

---

## 28. Permission-aware UI

Approve transfer → FRAUD_CASE_APPROVE · Export ledger → LEDGER_EXPORT · Change policy →
FRAUD_POLICY_MANAGE · Rotate provider secret → PROVIDER_CONFIG_MANAGE · View audit → AUDIT_VIEW.
Hide if irrelevant, disable with explanation if useful, **never frontend-only enforcement**.

---

## 29. Testing plan

Component: RiskBadge severity, LedgerSplitView balance, FraudSignalList, ConfirmActionModal typed
confirmation, PermissionGate. Page: dashboard summary, transfer filters, transfer detail ledger,
case timeline/actions, evidence checksum, audit filters. E2E: low-risk transfer, high-risk
transfer, approve/reject held, export pack, view ledger txn, pending-unknown, duplicate webhook.

---

## 30. Build order

1 app shell + nav · 2 auth/session · 3 dashboard (seeded) · 4 transfer list/detail · 5 ledger
explorer · 6 fraud queue/detail · 7 analyst actions · 8 evidence · 9 reconciliation ·
10 payment rails · 11 tenant admin · 12 ML monitoring · 13 developer · 14 monitoring ·
15 responsive · 16 accessibility · 17 E2E.

---

## 31. Definition of done

✅ modern app shell · dashboard real metrics · transfer creation works · transfer detail shows
fraud+ledger+audit · ledger explorer balanced debit/credit · fraud workspace supports
investigation · analyst actions permission-aware + safe · rail status/webhooks visible ·
pending-unknown + reconciliation clear · evidence packs download · audit searchable · tenant
admin flows · ML shadow explainable · monitoring exists · responsive · accessibility · tests+E2E.

---

## 32. Final product feel

Stripe-level clarity · Linear-level workflow polish · Datadog-level operational awareness ·
modern fintech-grade safety — without copying any single interface.

> TrustLedger knows where the money moved, why the system flagged risk, who approved action,
> and what evidence proves it.
