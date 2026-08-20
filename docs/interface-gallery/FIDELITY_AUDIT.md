# TrustLedger reference-to-interface fidelity audit

Audit date: 5 August 2026

Scope: all 60 authoritative images in `references/interface-images/original/` — 26 desktop,
26 mobile and 8 interaction states — compared against the current Next.js route/component source.

## Evidence boundary

This is a composition and capability audit. It proves that every reference has been inspected and
mapped to an implemented route or state. It does **not** prove pixel equality: there is no complete
set of current rendered screenshots in the repository to image-diff against the references. A green
Next.js build proves renderability and type correctness, not visual parity.

## Conflicts inside the generated references

The reference pack is not a single internally consistent design system. The following differences
appear between reference images themselves:

- logo variants include `TrustLedger`, `Validated TrustLedger`, a triple-bar mark, a cube/ledger mark
  and wordmark-only treatments;
- sidebars use different taxonomies (`Home`/`Transactions`, `Overview`/`Money`, and
  `Payment Operations`/`Settlements`);
- desktop headers alternate between tenant-first, breadcrumb-first and search-first layouts;
- mobile references alternate between a hamburger header, a back-navigation header and no persistent
  product navigation;
- provider and tenant names drift, and some screens contain external trademarks despite the pack's
  instruction to avoid them;
- several images show data or controls the implemented API does not expose, such as statement-level
  aggregate break counts, named certification requesters/approvers, and certification audit events.

Canonical decision: use the dashboard plus the actual route map for shared shell/navigation; use each
route image for that route's information hierarchy; use state images for safety friction. Existing
product behavior and available data win when a bitmap omits a required field or invents a capability.

## Route-by-route comparison

Every row below covers both its desktop and mobile reference.

| Route / reference | Reference composition checked | Current implementation outcome | Remaining fidelity proof |
|---|---|---|---|
| `/login` — Login | Split product story/form, sandbox badge, three distinct proof icons, login-first form, secondary tenant creation, system footer; mobile collapses to one column | Corrected to the canonical triple-bar brand, login-first hierarchy, distinct shield/search/document proofs, secondary tenant creation and responsive footer. Organisation ID remains because the real login API requires it; unsupported password recovery was not invented | Render and diff at desktop and mobile sizes |
| `/dashboard` — Dashboard | Six operational metrics, high-risk/ledger work areas and attention queue; mobile uses stacked metric rows and review cards | Shared shell and dashboard density are mapped; mobile priority rows are cards and the create-transfer action remains prominent | Render/data-populated diff |
| `/onboarding` — Onboarding | Readiness progress, ordered checklist and next actions; mobile uses compact accordion-like cards | Checklist and readiness sections are mapped to available onboarding facts | Mobile circular-progress treatment remains unproved by render |
| `/accounts` — Accounts | Three desktop balances, account creation and account table; mobile emphasizes total/available/reserved and account cards | Balance summaries and account creation use real account data; mobile account rows are labelled cards | Compare the mobile balance-summary proportions |
| `/transfers` — Transfers | Filters, pending-unknown explanation and dense desktop table; mobile uses review-first transfer cards | Filters and risk/status semantics are preserved; mobile table is transformed into labelled cards with score-and-band labels | Render with pending-unknown sample data |
| `/transfers/new` — Create transfer | Stepped details/review flow with risk preview and sticky mobile action | Actual three-step workflow is preserved and styled; risk preview is an evidence card. The bitmap's extra visual steps were not added because the application has no separate corresponding workflow states | Render all three steps; do not claim the bitmap's five-step rail |
| `/transfers/[transactionId]` — Transfer detail | Summary metrics, lifecycle, fraud/risk, balanced debit/credit split and audit timeline; mobile stacks review sections | Summary, lifecycle, balanced ledger proof and audit trail are mapped. Held-for-review now opens the dedicated safety dialog on this route | Render completed, held, MFA/action-required and pending-unknown records |
| `/ledger` — Ledger explorer | Account/master list and transaction detail with debit/credit split; mobile uses entry cards and focused detail | Ledger rows become mobile cards; selected transaction retains explicit balanced totals | Render a multi-entry transaction and verify no horizontal overflow |
| `/reconciliation` — Issue list | Summary metrics, filters, safety explanation and issue table; mobile uses issue cards | Summary/filter structure and labelled mobile cards are mapped; no mismatch is silently marked resolved | Render all severity/status combinations |
| `/reconciliation/[issueId]` — Issue detail | Expected/actual/evidence, recommendation, history and guarded resolution; mobile uses stacked evidence and sticky resolution | Evidence, expected/actual state, source statement link, audit history and typed `RESOLVE` friction are present | Render a statement-linked issue and resolved issue |
| `/reconciliation/statements` — Settlement statements | Upload/ingest area, three summary metrics and statement table; mobile uses upload panel, filters and statement cards | Previously omitted route now has real CSV/JSON file selection, supported ingest fields, three honest metrics and labelled mobile statement cards. PDF support and unavailable match/break aggregates were not invented | Render with multiple providers and a completed ingest |
| `/reconciliation/statements/[id]` — Statement detail | Six metrics, lines/breaks, expanded discrepancy and statement details; mobile stacks summary and line review | Previously omitted route now derives honest matched/break counts from lines, formats gross/fees/net, highlights break rows and adds a details sidebar. It does not fabricate book amount or linked issue fields absent from the API | Render matched and broken statements |
| `/fraud-cases` — Case queue | Priority metrics, signal frequency, case table and focused review detail; mobile uses rich case cards | Risk score/band, severity, signals, actions and mobile case cards are mapped; typed approval/rejection remains audited | Render open/closed cases with expanded signals |
| `/risk-profiles` — Risk profiles | Device/payee/user risk columns with bands and control explanations; mobile uses compact sections | Existing supported risk-profile categories and bands are preserved; mobile tables are labelled cards | Render populated profiles across all bands |
| `/ml` — ML monitoring | Shadow-mode safety banner, model state, disagreement and explanations; mobile emphasizes advisory status | Shadow-only language, model cards and explanation panels are mapped; nothing implies autonomous money movement | Render model disagreement and alert data |
| `/webhooks` — Webhook events | Event metrics/table/payload and duplicate safety; mobile uses expandable event cards | Signature, processed state, payload expansion and duplicate/no-double-posting explanation are preserved; mobile rows are cards | Render valid, invalid and duplicate/replayed events |
| `/certifications` — Provider certifications | Readiness coverage, provider gates and certification runs; mobile uses readiness cards/checklist | Provider readiness and runs are mapped without inventing production certification | Render certified and blocked provider configs |
| `/certifications/[runId]` — Certification run | Overall progress, drill results, dual-control review, evidence and audit timeline; mobile uses drill cards and sticky sign-off | Previously omitted detail route now derives real drill progress, shows drill cards/assertions, independent sign-off state and evidence. Named people and an audit timeline are omitted because the API does not return them | Render pending, failed, passed-awaiting-sign-off and signed-off runs |
| `/production-readiness` — Production readiness | Controlled-pilot warning, provider controls/blockers and bounded rollout; mobile emphasizes evidence and exposure confirmation | Readiness checks, immutable limits, circuit breakers and typed approval/resume confirmation are mapped | Render ready and blocked provider states plus active/paused canaries |
| `/evidence` — Evidence exports | Export summary/generation/list; mobile uses export cards and primary generation action | Checksums, formats, size and download actions are preserved; mobile export rows are labelled cards | Render multiple formats and legal-hold-relevant evidence data |
| `/audit-logs` — Audit logs | Searchable master/detail activity; mobile uses expandable event cards | Immutable event fields, actor, action, resource and correlation ID are mapped to mobile cards | Reference detail drawer has no direct source equivalent; render comparison required |
| `/developer/api-keys` — API keys | Create/list/revoke plus secret-shown-once state; mobile uses key cards and sticky creation | Key scope and lifecycle are preserved; secret is now a focused one-time modal with copy and storage warning | Render create, rotate and revoke states |
| `/monitoring` — Monitoring | Dense operational health metrics; mobile uses sparkline/status cards | Supported monitoring facts are shown as compact cards with semantic alert borders | Reference sparklines are not backed by time-series data and were not fabricated |
| `/admin` — Tenant admin | Plan/usage, fraud policy, provider controls, billing and danger zone; mobile prioritizes thresholds and save action | Existing quotas, thresholds, provider configuration and plan/billing controls are mapped | Render each permission/state combination |
| `/users` — Users and roles | Invite flow, role table and guardrails; mobile uses member cards | Invite, one-time password, role change and anti-lockout behavior are preserved; member rows become cards | One-time password still needs the same focused-modal treatment as API secrets if exact state parity is required |
| `/org-units` — Org units | Hierarchy, create/edit/assign and membership; mobile uses hierarchy/unit/member cards | Hierarchy and assignment controls are mapped; mobile hierarchy rows are cards | Render nested depth and scoped assignments |

## Interaction-state comparison

| State reference | Current mapping | Audit result |
|---|---|---|
| `transfer-risk-preview.png` | Create-transfer risk-preview step | Structure mapped; still needs rendered comparison with low/high-risk examples |
| `transfer-mfa.png` | Create-transfer `MFA_REQUIRED` focused dialog | Safety copy, reserved-funds explanation and code entry mapped |
| `transfer-held-review.png` | Transfer-detail held-review modal | Corrected to the referenced route; explicitly says funds are reserved/not posted and no movement occurs before analyst decision |
| `fraud-approve-confirmation.png` | Fraud case `ConfirmModal` | Typed `APPROVE`/`REJECT`, consequence copy and audit warning mapped |
| `reconciliation-resolve-confirmation.png` | Reconciliation issue `ConfirmModal` | Typed `RESOLVE` and underlying-mismatch warning mapped |
| `api-key-secret.png` | API-key one-time secret modal | Corrected from inline notice to focused modal with copy and secure-storage warning |
| `production-exposure-confirmation.png` | Production readiness `ConfirmModal` | Typed approval/resume with bounded-exposure and circuit-breaker copy mapped |
| `command-palette.png` | Global command palette | Search, keyboard navigation, active result and footer shortcuts mapped |

## Required final verification

Before claiming exact parity, capture the working application at the same desktop and mobile aspect
ratios for every route/state, populate it with deterministic synthetic data, and run image diffs. Each
failure must be classified as shared-shell drift, route composition, missing data, typography/spacing,
semantic colour, or an intentional capability boundary. Until those artifacts exist, status remains
**IN PROGRESS**, not verified.
