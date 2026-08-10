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
| conversations | 0 | No reply from any of the 16 at four days. Verified against Gmail Sent/Inbox on 2026-08-06 and 2026-08-08, not assumed from silence. Note that 0 replies is weaker evidence than it looks while delivery itself is unconfirmed. |
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
