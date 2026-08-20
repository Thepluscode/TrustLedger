# Premise Kill-Test — Reconciliation Wedge

> **Rule 0.** Name the single assumption that makes the whole thing worthless if false,
> kill-test *that* first with the cheapest falsifiable check, and **pre-commit to the
> STOP threshold before running the test** — so a bad result can't be rationalised into
> a good one. No further infrastructure until this passes.
>
> Status: **UNVALIDATED.** The engine is built ahead of this test. Interviews run to date: **0 of 25**
> (verify with `python3 pilot/score_kill_test.py`, never by reading this line — it goes stale). This gate decides
> whether the commercial thesis under it is real.

This is the **market gate**. It precedes the six-week product gate in
[`PILOT_CHECKLIST.md`](PILOT_CHECKLIST.md); investigation-speed results cannot compensate for a
market that will not grant data access or pay.

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

**Corrected 2026-08-01 — geography is not a filter.** This section previously read "African
marketplaces, lenders, remittance platforms, regulated fintechs." Africa was the *initial market
assumption* from early discussions, not a property of the buyer. It was never revised, and every
downstream artefact inherited it — the target list, the sourcing queries, the first draft. The
qualifying properties below are what actually predict the pain; none of them is a region.

The segment where the pain *and* the price survive — **anywhere**:

- **Marketplaces, lenders, remittance and cross-border platforms, payroll and mass-payout
  providers, regulated fintechs** — the business models that fan money out across counterparties
- Processing **meaningful volume through ≥2 payment providers, rails or banking partners**
- **Multi-currency or multi-country**, so settlement arrives on different clocks in different formats
- With a **dedicated finance or ops function** already investigating exceptions manually
- Under **compliance/audit pressure** (regulated or enterprise-facing)

**Geography is a reachability question, not a qualification one.** UK, EU, US, LATAM, SEA and
African companies all qualify on identical terms. Where you start is a matter of who answers your
email — warm network, shared timezone, language, a regulator you can speak to credibly — not of who
has the problem. A UK fintech settling across three PSPs and two banking partners has exactly the
pain this product addresses.

Explicitly **exclude** single-provider, low-volume, no-finance-team merchants. They can't
pay enterprise prices and don't have the pain — including them will produce a false read.

Target **N = 25 interviews** across ≥3 sub-segments (raised from 18 on 2026-07-31 — see the
three-bar gate below). Reach via: existing network, fintech operator communities, PSP-partner
intros, LinkedIn heads of finance/ops. Candidate list: `pilot/TARGET_LIST.md`.

---

## Pre-committed thresholds (set BEFORE any interview)

**Revised 2026-07-31.** The original gate was 18 interviews with ≥8 clearing two bars. It is now 25
interviews against **three** bars, all of which must clear. The change is deliberately stricter in one
specific way: it separates *saying yes* from *paying*, because those are different facts and only one
of them is evidence.

Score each interview against four bars:

- **Pain bar** — reports EITHER ≥1 finance/ops **day per week** on exceptions, OR a
  **quantifiable quarterly leakage** they cannot currently see with provider dashboards.
- **Data bar** — will **grant read-access** to settlement data under a pilot/NDA.
- **Money bar** — commits to **paid discovery or a paid pilot**. Not "we'd be interested." A number
  and a person who signs it.
- **Recurrence bar** — the pain-confirming company experiences the failure monthly or more often,
  or can quantify sufficiently material financial exposure when it occurs. Added 2026-08-12 while
  the completed-interview count was still zero, so no observed result influenced the threshold.

| Bar | Company threshold | Why this number |
|---|---|---|
| Companies describing the same problem unprompted | **≥ 6** | Below six, you are hearing individual company dysfunction, not a market |
| Companies granting sample data or integration access | **≥ 3** | The gate most reconciliation plays actually die at |
| Companies committing to paid discovery or paid pilot | **≥ 2** | The only bar that cannot be politeness |
| Pain-confirming companies with recurring or materially exposed failures | **≥ 4** | Proves this can become a retained operating workflow rather than episodic consulting |

Thresholds count distinct companies, not interview rows. Multiple completed interviews at one
company improve understanding but cannot inflate any company bar. The target sample remains 25
qualified completed interviews across at least three sub-segments. A completed conversation that
fails the operating qualification remains evidence but does not count toward 25.

**Verdict:**

| Result | Verdict |
|---|---|
| **All four thresholds met** | **GO** — premise holds; analyse the incidents, then proceed to a paid design-partner pilot around the dominant workflow. |
| **Pain ≥6 but recurrence <4, data <3 or paid <2** | **MURKY** — the pain is real and recurrence, trust or budget is not. Re-cut to the sharpest sub-segment and re-test; **do not build**. |
| **Pain < 6** | **STOP** — premise failed. Do not add infrastructure. |

**Why all four and not a weighted score.** Pain without recurrence may not retain; pain without data access is a problem you cannot see.
Data access without budget is a science project. Budget without pain is a favour, and it does not
renew. A partial pass is a reason to keep talking, not a reason to build.

## First milestone — three conversations

The 25 interviews are the sample for the market decision, not the first operating target. Complete
three qualified conversations first to test whether the interview and recruitment motion work.
Each must reconstruct one actual incident and populate the expanded evidence fields in
`kill-test-tracker.csv`. Use `FIRST_THREE_CONVERSATIONS.md`; PalmPay is Interview #1 target via
`INTERVIEW_01_PALMPAY.md`.

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

25 conversations, **two to three weeks**, zero new code, zero infrastructure. This is the
cheapest falsifiable check that exists for this premise. It runs entirely in parallel with,
and gates, any further build on the commercial thesis.

*(Corrected 2026-08-01 — this section still said "~18 conversations, one to two weeks" after the
gate was raised to 25 on 2026-07-31.)*

**Running it:**
- Book calls with `pilot/INTERVIEW_OUTREACH.md`. Log every send in `pilot/kill-test-tracker.csv`
  the moment it goes out — the §9.6 scoreboard counts sends, not drafts.
- Score with `python3 pilot/score_kill_test.py`. The thresholds live in that file, in code, because
  a threshold you can renegotiate while looking at results is not a threshold. `--selftest` verifies
  the scorer (11 cases, including that a booked-but-unheld call does not count toward N).
- The scorer fails fast: if pain becomes unreachable even were every remaining interview to clear
  it, it returns STOP before you finish the set.

---

## Send-channel log — read before drawing any conclusion from the reply rate

**As of 2026-08-05:** 17 messages sent (all channel C, cold email, on 2026-08-04). **0 replies.**
Interviews still 0 of 25 — the scorer is the source of truth, not this paragraph.

**0 replies is not yet a signal.** The sends are ~28 hours old; cold outreach to finance and ops
leaders typically replies over 2–5 working days, and a chunk of the first batch went out on a
Tuesday afternoon. Do not re-cut the segment or declare the channel dead before **2026-08-11**.

**What IS a signal, now:** address quality. Every channel-C address was *constructed* from a name
plus a domain pattern, not verified. Confirmed outcomes from mailer-daemon:

| Company | Outcome |
|---|---|
| PalmPay | `samuel.oluyemi@palmpay.co` — 554 address-not-found. No working email route found. |
| Alpaca | primary bounced 550; retry to the founder's legacy domain did not bounce. |
| MultiBank Group | **both** variants bounced. No working email route. |
| Lemonway | original did not bounce; the **recovery** message bounced, so the subject-tag correction never landed. |

**Correction recorded 2026-08-05.** The commit `60d7bfe` states "both retry addresses hold (no
bounce)". That is false: two of those retries bounced, the DSNs arriving up to 30 minutes after the
send. The claim was made from an immediate inbox check. **A bounce is not synchronous — a
non-bounce read within minutes of sending proves nothing.** Re-check delivery at least an hour later
before recording an outcome, and never record "delivered": the honest states are *bounced* and
*not-bounced*, because silent spam-foldering is invisible from the sending side.

**Process defect, unresolved.** Three messages went out with the placeholder tag
`[ADDRESS IS A GUESS — …]` still in the subject line (Lemonway, Trading 212, IG Group). Recovery
messages were sent for all three; Lemonway's bounced. Any future send must be blocked if `[` appears
in the subject.

**Channel implication.** With ~5 bounces across 17 constructed addresses and two companies with no
working email route at all, cold email is carrying an unknown and probably poor delivery rate.
`INTERVIEW_OUTREACH.md` ranks warm intros (A) above LinkedIn (B) above cold email (C), and the
campaign has so far used only C. The next batch should not be more constructed addresses.
