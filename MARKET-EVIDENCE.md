# MARKET EVIDENCE — TrustLedger

Rule 0b: no further significant engineering until every line below is met with evidence.
`market-gate check .`

**Tracker:** pilot/kill-test-tracker.csv
<!-- contacted is COUNTED from this file, bounces excluded via delivery_status.
     Status vocabulary: BOUNCED | SENT-UNCONFIRMED | (blank = not sent).
     Do NOT use a label containing the word "bounce" for a non-bounce — market-gate excludes
     rows by substring match, so "NO-BOUNCE" counts as a bounce and silently scores 0. -->

## Thresholds

| Key | Count | Evidence (names, dates, links — not adjectives) |
|-----|-------|--------------------------------------------------|
| contacted | 16 | 2026-08-04: 18 companies emailed, 16 sent without a bounce, **0 confirmed delivered**. PalmPay and MultiBank bounced on every address tried and have no email route. The other 16 are `SENT-UNCONFIRMED`: no bounce came back, which is not the same as arrival — most addresses were pattern guesses, and a silently-discarded message looks identical to a delivered one. Named recipients, sources and confidence per company in `pilot/sends/batch-01-recipients.md` and `batch-02-03-recipients.md`; Gmail message IDs in the tracker. |
| conversations | 0 | No reply from any of the 16 at four days. Verified against Gmail Sent/Inbox on 2026-08-06 and 2026-08-08, not assumed from silence. Note that 0 replies is weaker evidence than it looks while delivery itself is unconfirmed. **Quantified 2026-08-15: of 19 sends, exactly 1 is confirmed delivered** (PalmPay, on a second address after the first bounced); 4 bounced; 14 remain SENT-UNCONFIRMED. A denominator of one cannot distinguish a bad message from mail that never arrived. |
| confirmed_pain | 0 | |
| requested | 0 | |
| committed | 0 | |

## The problem, in the buyer's own words

<empty. 0 of 25 kill-test interviews have happened, so there are no buyer words. The failure
taxonomy in the outreach drafts is a HYPOTHESIS written by us, not something a buyer said —
do not promote it into this section.>

## Buyer and budget owner

**buyer_identified:** NO
Recipients are researched and named (Group CFOs, COOs, Heads of Payment Operations at Trustly,
XTB, IG, Trade Republic, Swissquote, Deriv, Equiti and others) but *named recipient* is not
*identified buyer*: nobody has confirmed they own this problem, and no budget owner is known.

## Economics

**value_exceeds_cost:** NO
The pre-committed kill-test asks for it directly — the money bar requires a commitment to paid
discovery or a paid pilot, at 0 of 2. Until a buyer states what exceptions cost them, both sides
of the comparison are ours.

## Pain Spend (what the buyer already pays to endure this)

**pain_spend_evidenced:** NO
This is the number the kill-test's pain bar is actually asking for: finance/ops days per week
spent investigating settlement exceptions, plus quarterly leakage written off as unreconciled.
`pilot/PREMISE_KILL_TEST.md` pre-registered both. **Neither has been collected from a buyer.**

## Decision

**Status:** UNTESTED
163 commits in 90 days — the largest engineering investment in the portfolio — against 16
emails that did not bounce, none confirmed delivered, and 0 replies. The premise kill-test (`pilot/PREMISE_KILL_TEST.md`) is the
governing gate and remains **UNVALIDATED at 0 of 25 interviews**, with pre-committed thresholds
of pain ≥6, data ≥3, paid ≥2. Rule 0b: no further significant engineering here until
conversations exist.

## Why delivery is unconfirmed — checked, not assumed (2026-08-15)

`pilot/sends/batch-01-recipients.md` says it outright: *"no company-published email convention was
found for any of them; every email format below is data-broker-inferred and unverified."* The 4
bounces are the predictable cost of that.

The obvious fix — go find the published addresses — was **tested against primary sources rather
than assumed to work**. Five of the fourteen unconfirmed companies, fetched from their own legal,
imprint and contact pages:

| Company | Publishes a usable address? |
|---|---|
| XTB | **yes** — 20 of them, incl. `compliance@xtb.co.uk`, `legal@xtb.com` |
| Trustly | no — contact form only |
| Swissquote | no — none on legal information |
| iBanFirst | no — none on legal page (IBANFIRST SA, Avenue Louise 489, Brussels) |
| Freetrade | no — "select an option below", no address |
| Trade Republic | no address rendered on the imprint |

**1 in 5.** Large regulated fintechs deliberately do not publish inboxes. This ICP is structurally
hard to reach by cold email at all — which is a *channel* finding, not a message finding, and it
was invisible while the reading was "0 replies means the message is wrong."

Compare the same method run the same week against SL2's mid-market HR-tech: 3 of 6 companies
published a real front door. The difference is company size and regulatory posture, not effort.

**Consequence for the plan.** Sending the batch-04 follow-up wave to 14 addresses never confirmed
to deliver repeats the experiment that produced the ambiguous result. `pilot/sends/batch-05.md`
Part A already anticipated this — *"one post reaches more qualifying people than the entire
evidenced list… the only channel where the reply rate is not bounded by how many names were
sourced"* — and needs no address, no inference and no mail delivery at all.
