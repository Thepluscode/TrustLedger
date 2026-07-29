# ADR-003: The core is provider- and geography-neutral; packs are earned, not pre-built

- **Status:** Accepted (recorded 2026-07-29)
- **Deciders:** Theophilus Ogieva

## Context

Two proposals arrived in July 2026: that TrustLedger should be "redesigned as a global payment
control plane" with regional **policy packs** (UK/EU/US/Africa), **provider packs**, **industry
packs** (banking, healthcare, government, SaaS) and multi-region plus i18n from day one.

The premise of that proposal — that the architecture had an Africa assumption baked in — was
checked against the code on 2026-07-29 and is **false**:

| Check | Result |
|---|---|
| `grep -ri "africa\|nigeria" backend/src/main/java` | **zero matches** |
| `NGN` in main | 4 files — 3 certification fixtures, and `PaystackPaymentRailAdapter`, where a Nigerian provider's currency belongs |
| Currency handling | `Money` wraps `java.util.Currency` — any ISO-4217 code |
| Provider coupling | domain depends on the `PaymentRailAdapter` port; `rails/paystack/`, `SandboxPaymentRailAdapter`, `OpenBankingSandboxAdapter` sit behind it |
| Jurisdiction data | `TenantProviderConfigEntity` already carries currency, country, and min/max per config |

Africa was a go-to-market assumption. It was never an architectural boundary.

## Decision

1. **Keep the core provider- and geography-neutral.** The domain depends on the port; provider
   specifics live in `rails/<provider>/`. That already is the "provider pack" pattern.
2. **Do not pre-build regional policy packs, industry packs, multi-region, or i18n.** Build the
   first policy pack when a paying customer's jurisdiction requires it — which is also the first
   moment its actual contents are knowable rather than guessed.

## Options considered

1. **Neutral core, packs on demand** (chosen).
2. **Build the pack framework now** — a jurisdiction-aware policy engine, four regional packs, four
   industry packs, multi-region, i18n, ahead of any customer.
3. **Hardcode one market** and generalise later. Rejected — this is the one option the current code
   already avoids, and reversing into it would be a regression.

Option 2 fails Rule 0 (infrastructure ahead of a passed premise) and Rule 12 (every pack would have
zero consumers on day one). The Stripe analogy offered in support of it argues the other way:
Stripe launched US-only, cards-only, one rail, and earned each subsequent rail with revenue from
the last.

## Trade-offs accepted

The first customer in a new jurisdiction will cost more than if a pack framework already existed.
That is the intended trade: a framework built against a real regulatory requirement will be right,
and one built against four imagined ones will need rewriting for the customer who actually arrives.

## Risks

If several jurisdictions arrive at once, per-customer policy handling could sprawl before anyone
generalises it. Watch for the third jurisdiction — that is when the abstraction becomes evidence-
backed rather than speculative.

## Reversal conditions

Build the policy-pack abstraction when **two paying customers** in different jurisdictions need
materially different policy behaviour and the duplication between them is real rather than
anticipated. Same bar as the shared-module extraction rule.

Multi-region: build it when a customer contract or a data-residency obligation requires it, not
before. `docs/MULTI_REGION.md` records the design thinking; it is not a commitment to build.

## Note on scope

This ADR governs the **architecture** only. Which market to sell into first is a separate, open,
and more consequential decision — tracked in
`theplus-tech-knowledge/strategy/ai-control-plane-reconciled.md` §9.4. Widening the architecture is
free and already done. Widening the market is not.
