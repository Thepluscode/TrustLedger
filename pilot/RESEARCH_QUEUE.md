# Research Queue — unqualified candidates

Companies to **investigate**, not to contact. Nothing here is a prospect.

> **A company name is not a target.** It becomes one only when evidence demonstrates the problem,
> organisational ownership, and plausible ability to pay. `pilot/TARGET_LIST.md` is canonical and
> holds only evidenced rows; this file is inventory feeding it.
>
> **Promotion rule — one credible source proving a buying signal:** reconciliation / settlement /
> payment-operations hiring · named payment providers, banking partners or rails · a regulatory
> licence plus multi-market operations · public documentation describing reconciliation complexity ·
> a named finance, treasury or operations leader owning the workflow.
> **Homepage copy alone is never sufficient.**

Salvaged 2026-08-01 from commit `8a84a1a`, which existed only as a dangling object after its branch
was reset. **Tier rankings deliberately not preserved** — they were assigned on homepage evidence and
implied a confidence ordering nothing supported.

---

## Evidence missing from every row below

Homepage copy establishes that a company markets cross-border / multi-currency services. It does not
establish any of the six things that actually qualify a buyer:

1. Two or more **active** providers, rails or banking partners
2. Multi-currency or multi-country settlement complexity
3. A dedicated reconciliation or payment-operations **function**
4. Audit or regulatory pressure
5. Transaction volume large enough that £4,000 discovery is small against the leakage
6. A reachable, named budget owner

---

## Queue

| Company | Market | Why it may fit (homepage-level only) | Next research action | Status |
|---|---|---|---|---|
| Curnance | Africa corridors | Multi-currency wallets via regulated settlement partners | Name the settlement partners; find ops/finance lead | Unqualified |
| Betaling | Africa / cross-border | Business payments, institutional liquidity, stablecoin settlement | Confirm fiat *and* stablecoin legs are separate counterparties | Unqualified |
| TranzyPay | UK ↔ Africa | UK–Africa payments, FX, treasury, local collections | UK entity → check FCA register; find treasury owner | Unqualified |
| PAL | Africa | Collections, payouts, multi-currency, reconciliation trails | "Reconciliation trails" in their own copy — verify what it means | Unqualified |
| Sendi | East Africa → global | Supplier-payment corridors, multiple payout methods | Confirm ≥2 payout partners; find who owns failed payouts | Unqualified |
| Rift | Africa + chains | Local currencies, banks, fintechs, several blockchain networks | Confirm fiat rails ≥2; chain legs alone don't qualify | Unqualified |
| Grey | Multi-market | Multi-currency accounts, payout partnerships into new markets | Name the payout partners; find payments lead | Unqualified |
| NALA / Rafiki | Multi-region | Consumer cross-border + B2B payouts/collections API | Rafiki (B2B arm) is the likelier buyer — verify separately | Unqualified |
| LemFi | **UK / US / CA / EU** + 30 countries | Multi-currency wallets, several funding methods | Non-African footprint — name funding partners; find finance ops | Unqualified |
| Leatherback | Global | Global collections, payouts, multi-currency accounts, FX | Check licence registers; find ops owner | Unqualified |
| Chipper Cash | Several markets | Cross-border payments, wallets, API collections/payouts | Large enough to have a real ops function — find its head | Unqualified |
| Verto | Global | Global collections, payouts, expenses, cross-border infra | UK-registered — FCA/EMI check; find treasury owner | Unqualified |

**Source for all rows:** homepage citations gathered in an external research pass, 2026-08-01.
Recorded as one source with one confidence level rather than fifteen, because that is what it was.
**12 live, 3 disqualified** — see below.

---

## DISQUALIFIED — sellers, not buyers (checked 2026-08-01)

Three of the fifteen were mis-scoped. Each **sells** payment infrastructure rather than consuming it,
so its customers are the ICP and it is not. Removed from the queue above rather than left to waste a
discovery call.

| Company | Finding | Verdict |
|---|---|---|
| **Lumepay** | Markets enterprise APIs that "cut reconciliation time", with "automated reconciliation and reporting" across FX, liquidity, collections, disbursements and settlement inside a single licensed perimeter | **Competitor.** Sells the reconciliation value proposition directly. Do not pitch. |
| **Kora** | Licensed payment infrastructure provider; payouts to 250+ commercial and microfinance banks; accepts cards, mobile money, transfers and virtual accounts across NG/KE/GH/ZA | **Provider.** Its merchants are the buyers. |
| **Swifter** | "The invisible technology layer that powers cross-border payments for banks… never touch customer funds"; FX aggregation across multiple liquidity providers, smart routing, 20+ countries | **Provider, and adjacent on routing.** Its bank customers are the buyers. |

**Kora and Swifter are worth keeping as channel candidates, not prospects.** A provider sees which of
its merchants are drowning in exceptions, and an introduction from one carries more weight than a
cold approach. That is a partnership conversation on a different timeline — do not let it displace
direct discovery.

**What this validates:** 3 of 15 — 20% of the queue — could not have bought anything. Every one of
them looked plausible from homepage copy, which is precisely why the queue and the target list are
separate files.

**Also re-check on the same test before contacting:** Rift (blockchain networks may not be payment
*providers* in the sense that matters — fiat rails still required) and Betaling (stablecoin leg,
same question).

---

## Known gap in this queue

**Every row is African or Africa-focused.** That is inherited from an ICP filter that named a region,
corrected on 2026-08-01 — geography is a reachability question, never a qualification one.

Four rows already have substantial non-African footprints the original framing obscured: **LemFi**
(UK/US/CA/EU), **Verto**, **Leatherback** and **Grey**.

**UK, EU, US, LATAM and SEA candidates are missing and should be added first.** The UK and EU carry a
regulatory driver the others don't — FCA operational-resilience expectations and PSD2 make "prove
what happened to this payment" an obligation rather than a preference. Use the multi-market sourcing
table in `pilot/TARGET_LIST.md`; the qualifying signal is the role, never the region.