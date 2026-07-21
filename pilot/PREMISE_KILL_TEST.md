# Premise Kill-Test — Reconciliation Wedge

> **Rule 0.** Name the single assumption that makes the whole thing worthless if false,
> kill-test *that* first with the cheapest falsifiable check, and **pre-commit to the
> STOP threshold before running the test** — so a bad result can't be rationalised into
> a good one. No further infrastructure until this passes.
>
> Status: **UNVALIDATED.** The engine is built ahead of this test. This gate decides
> whether the commercial thesis under it is real.

---

## The single premise

Everything in the steelman — the moat, the expansion table, the £30M+£30M ARR math —
is downstream of one fact. If it's false, correct architecture does not save it:

> **A reachable segment is losing enough money and finance/ops labour to cross-provider
> reconciliation *today* that they will (a) pay enterprise-SaaS prices to fix it AND
> (b) grant a third party read-access to their settlement data to do so.**

Three testable numbers sit inside it:

1. **Labour** — finance + ops hours/week spent investigating settlement/reconciliation exceptions.
2. **Leakage** — money written off or left unrecovered per quarter as "unreconciled" (missing, duplicated, delayed, or mismatched payouts/settlements).
3. **Data trust** — willingness to grant read-access to settlement files / provider APIs to an external tool under NDA/pilot terms.

Number 3 is the real gate. Most reconciliation plays die there, not on pain.

---

## Who to talk to (the ideal-customer filter)

Not "African businesses." The only segment where the pain *and* the price survive:

- African **marketplaces, lenders, remittance platforms, regulated fintechs**
- Processing **meaningful volume through ≥2 payment providers**
- With a **dedicated finance or ops function** already investigating exceptions manually
- Under **compliance/audit pressure** (regulated or enterprise-facing)

Explicitly **exclude** single-provider, low-volume, no-finance-team merchants. They can't
pay enterprise prices and don't have the pain — including them will produce a false read.

Target **N = 18 interviews** across ≥3 sub-segments. Reach via: existing network,
fintech operator communities, PSP-partner intros, LinkedIn heads of finance/ops.

---

## Pre-committed thresholds (set BEFORE any interview)

Score each interview against **both** bars:

- **Pain bar** — reports EITHER ≥1 finance/ops **day per week** on exceptions, OR a
  **quantifiable quarterly leakage** they cannot currently see with provider dashboards.
- **Trust bar** — will **grant read-access** to settlement data under a pilot/NDA.

| Interviews clearing BOTH bars (of 18) | Verdict |
|---|---|
| **≥ 8 (≈45%)** | **GO** — premise holds; proceed to a paid design-partner pilot. |
| **4–7** | **MURKY** — the segment is too broad. Re-cut to the sharpest sub-segment and re-test; do not build. |
| **< 4** | **STOP** — premise failed. The pain is not material or the data won't be shared. Do not add infrastructure. |

**Hard kill signal (overrides the count):** if the dominant answer is *"our provider
dashboard plus a spreadsheet handles this fine,"* the premise is false regardless of
volume. Reconciliation is a vitamin, not a painkiller, for that buyer.

---

## Discovery script (ordered — pain before pitch, never lead with the product)

Do **not** describe the product until Q9. Leading with the pitch contaminates the pain read.

1. Walk me through what happens when a payout or settlement doesn't match your records. Who touches it, and what do they do?  *(listen for: a named person, a manual process)*
2. How many providers/rails/banks do you settle across today? *(qualifier — <2 = out of segment)*
3. In a normal week, roughly how many hours does finance + ops spend chasing settlement exceptions? *(→ Number 1)*
4. Last quarter, how much money did you write off or stop chasing because you couldn't reconcile it? *(→ Number 2; if "we don't track that" — that itself is a signal)*
5. What do you use today — provider dashboards, exports, a spreadsheet, a build? What does it *not* tell you?
6. When a provider settles late or short, how do you find out — and how long after? *(delayed-settlement pain)*
7. Last time an auditor or your board asked "prove what happened to this payment," how long did assembling that take?
8. Which of these has actually cost you money in the last 6 months: missing payout / duplicate payout / amount mismatch / silent delay? *(concrete loss events)*
9. *(Now, one sentence:)* If a neutral tool ingested all providers, matched every transaction against your records, flagged the breaks, and kept the audit trail — what's the first thing you'd want it to catch?
10. **Trust probe:** to do that it needs read-access to your settlement files / provider API keys. Under an NDA and a scoped pilot — is that a conversation you can have, or a hard no? *(→ Number 3, the real gate)*
11. **Price anchor:** if it recovered £[10–20× the price] a year and saved [hours from Q3], what would you expect to pay — and who signs off? *(WTP + buying process)*

---

## What would prove me WRONG (pre-registered disconfirmers)

Log these honestly; they are the point of the exercise:

- Pain is real but **the spreadsheet is genuinely good enough** — no urgency, no budget.
- Pain is real but **data will not leave the building** — trust bar fails across the board.
- The money leakage is **too small to justify a £25k+ contract** even when labour is high.
- Every buyer wants it but **no one owns the budget** — no repeatable buying process.
- The pain is real but **concentrated in one provider**, so the provider's own tooling fixes it and neutrality adds nothing.

Any two of these appearing consistently = STOP, not "iterate the pitch."

---

## Cost & timebox

~18 conversations, **one to two weeks**, zero new code, zero infrastructure. This is the
cheapest falsifiable check that exists for this premise. It runs entirely in parallel with,
and gates, any further build on the commercial thesis.
