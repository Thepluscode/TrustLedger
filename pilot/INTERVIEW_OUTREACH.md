# Kill-Test Interview Outreach

Canonical research question: **What actually happened to the money?** Do not use that line to lead
the interview; ask the pain questions first so respondents describe their current process without
being primed by the product framing.

The only artefact between `TARGET_LIST.md` and `PREMISE_KILL_TEST.md`. Everything else in `pilot/`
is written; nothing books a call.

Active milestone: complete three incident-reconstruction conversations before optimising for the
full sample. Use `FIRST_THREE_CONVERSATIONS.md`. PalmPay is the first target; its verified public
signals, contact path and unsent operator-first draft are in `INTERVIEW_01_PALMPAY.md`.

> **These ask for 20 minutes of opinion, not a meeting about a product.** That is deliberate and it
> is also the truth — Q1–Q8 of the script never mention TrustLedger. A message that hints at a demo
> converts worse *and* contaminates the pain read, which is the one thing this exercise is for.
>
> **Do not attach the one-pager, the demo link, or pricing.** If they ask what you're building,
> answer in one sentence on the call at Q9, as the script says.

---

## Rule: no send is a send until it is in the tracker

Log every send in `kill-test-tracker.csv` the moment it goes out — `date_sent` non-empty. The
scoreboard in `strategy/ai-control-plane-reconciled.md` §9.6 counts sends, not drafts, precisely
because drafts have been the failure mode for nine weeks.

---

## A — Warm intro (highest yield; exhaust this before any cold send)

> Subject: quick favour — 20 min on payment reconciliation
>
> Hi [Name],
>
> I'm researching how companies running more than one payment provider actually handle it when a
> settlement doesn't match their records — who touches it, how long it takes, what it costs.
>
> Not selling anything. I'm trying to work out whether the problem I think exists is real, and the
> only way to find out is to ask people who live it.
>
> Do you know anyone running finance or payment ops at a [marketplace / lender / remittance or
> cross-border platform / payroll or mass-payout provider] settling across two or more providers?
> 20 minutes, their opinion, no follow-up sell.
>
> Happy to share what I learn across all the conversations — it's usually more useful than the
> individual answers.
>
> [Your name]

---

## B — LinkedIn to a Head of Finance / Payment Ops (connection note, <300 chars)

> Researching how multi-provider payment teams handle settlement breaks — not selling. Would you
> trade 20 minutes for the anonymised findings across ~25 similar companies? Happy to go first on
> what I'm seeing.

Follow-up once accepted:

> Thanks for connecting. Genuinely just research: I'm trying to establish whether cross-provider
> reconciliation is a real, expensive problem or one that spreadsheets handle fine. Both answers are
> useful to me — I'd rather find out it's fine now than after building something nobody needs.
>
> Nine questions, 20 minutes, no deck. Any slot that suits?

---

## C — Cold email (use only after A and B are exhausted for that segment)

> Subject: how do you find out when a provider settles short?
>
> Hi [Name],
>
> You're hiring a [exact role title from the posting], which usually means someone is reconciling
> settlements by hand.
>
> I'm doing research on that specific problem across ~25 companies running two or more payment
> providers — how breaks get found, how long they take, what leaks. I'm not selling; I'm testing
> whether the problem is big enough to be worth solving at all.
>
> 20 minutes? I'll send you the anonymised cross-company findings either way.
>
> [Your name]

**Why lead with their job posting.** It is specific, public, and true, and it says you did work
before writing. Generic "I noticed your company processes payments" is why cold email dies.

---

## D — Operator community post (fintech / payment-ops Slack and Discord groups)

> Doing research, not recruiting or selling. If you run finance or payment ops across 2+ providers:
> what actually happens when a settlement doesn't match? Looking for 20-minute conversations —
> I'll publish the anonymised findings back to the group.

---

## Anti-patterns that will invalidate the test

- **Describing the product before Q9.** Contaminates the pain read. The whole test is worthless if
  they are reacting to a pitch instead of describing their week.
- **Only interviewing people who already agree.** Warm network skews positive. At least one of the
  three sub-segments must come from cold or community sourcing.
- **Counting a friendly "sounds useful" as the Money bar.** The bar is a number and a person who
  signs it. Politeness is not evidence — that separation is exactly why data and money are distinct
  bars.
- **Stopping at 6 pain-positives and declaring GO.** All four bars must clear. Pain without
  recurrence, data access and budget is not a retained product workflow.
- **Rewriting the thresholds after seeing results.** They were pre-committed. Changing them
  post-hoc is how a kill-test becomes a formality.

---

## Booking maths — how many sends for 25 interviews

Do not plan for a 100% reply rate; plan for the real one and send accordingly.

| Channel | Realistic reply → call | Sends needed for ~8 interviews |
|---|---|---|
| A — warm intro | 30–50% | ~20 asks |
| B — LinkedIn | 10–20% | ~50–80 |
| C — cold email | 5–15% | ~60–100 |
| D — community | varies wildly | 3–5 posts |

**≈150–200 sends for 25 interviews.** The list currently holds **7 evidenced companies.** That
number, not the interview script, is the binding constraint — keep running the sourcing table in
`TARGET_LIST.md` weekly until the list clears ~60 names.
