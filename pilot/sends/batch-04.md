# Batch 04 — follow-up wave for batches 01–02 (sends 30–49)

> **HELD 2026-08-15 — do not send this wave as written.**
>
> This was drafted 2026-08-05 and due +5 working days after the 2026-08-04 wave, so it is overdue.
> It is being held anyway, because of what the delivery column says: **of 19 sends, exactly 1 is
> confirmed delivered.** 4 bounced and 14 are `SENT-UNCONFIRMED`.
>
> A follow-up to an address that was never confirmed to deliver does not test the follow-up — it
> re-runs an experiment whose result was already ambiguous, and it spends the one follow-up this
> contact is ever owed (see the two-total rule below) on an address that may not exist.
>
> Every address here was data-broker-inferred; `batch-01-recipients.md` says so in its own opening
> paragraph. On 2026-08-15 five of the fourteen were checked against their own legal and imprint
> pages: only **XTB** publishes usable addresses. Trustly, Swissquote, iBanFirst, Freetrade and
> Trade Republic publish none. Large regulated fintechs do not publish inboxes.
>
> **Release condition:** send to a contact here only once that specific address is confirmed
> delivered, or replaced by a company-published one. Until then `batch-05.md` Part A is the live
> channel — it needs no address at all.

**These are not new companies. They are the second touch to the 20 already contacted.**

Batches 1–3 exhaust the evidenced list at 29 names. The booking maths needs ~150–200 sends. That gap
does not close by finding 170 companies — it closes because **most replies come from the second and
third touch, not the first.** A first-touch-only campaign throws away most of its own yield.

> **Send +5 working days after the original.** Sooner reads as pressure; later and they have
> forgotten the first one.
>
> **One follow-up per contact per wave. Two total, ever.** A third is harassment and it will cost
> you the warm-network reputation that channel A depends on.
>
> **Log each as its own row event** — append to `notes` with the date. `date_sent` stays as the
> *first* send date so reply-rate maths against first contact stays honest.

---

## The rule that makes a follow-up work

**A follow-up must add something, not repeat the ask.** "Just bumping this to the top of your inbox"
is why follow-ups get ignored — it asks the recipient to do work you were not willing to do.

Each template below adds one of three things:

1. **A finding** from conversations already had (only once you genuinely have some)
2. **A narrower ask** — a single question by email instead of 20 minutes on a call
3. **A clean exit** — permission to say no, which reliably outperforms another ask

---

## F1 — The finding follow-up *(use once ≥3 interviews are done)*

Highest-performing, but **only honest after you have real conversations.** Do not use it before
then; inventing a finding to make an email work is the same failure as inventing an interview.

> Subject: re: [original subject]
>
> Hi [Name],
>
> Following up on the note below.
>
> One thing that has come up in every conversation so far: nobody finds out about a short settlement
> from their own systems. It is always the counterparty, an auditor, or a customer complaint — and
> usually days later.
>
> Curious whether that matches your experience or not. Genuinely useful either way; a "no, we catch
> those same-day" would be the most interesting answer I have had.
>
> Still happy to do 20 minutes, or just reply to this one line.
>
> [Your name]

---

## F2 — The narrower ask *(use before you have findings)*

Drops the cost of replying from a scheduled call to one sentence. Use for batch 1–2 follow-ups
while the interview count is still low.

> Subject: re: [original subject]
>
> Hi [Name],
>
> Following up once, then I will leave you alone.
>
> If 20 minutes is too much, one question by email is genuinely useful:
>
> **When a payout or settlement doesn't match your records, who finds out first — you, or the other
> side?**
>
> That single answer tells me most of what I am trying to learn.
>
> [Your name]

---

## F3 — The clean exit *(second and final follow-up)*

Counter-intuitively the highest reply rate of the three, because it costs nothing to answer and
removes the obligation.

> Subject: re: [original subject]
>
> Hi [Name],
>
> Last one from me — I do not want to be the person cluttering your inbox.
>
> If cross-provider reconciliation is not a real problem for you, that is genuinely useful for me to
> know and I will note it and move on. A one-word reply is a complete answer.
>
> If it is a problem and the timing is just bad, say so and I will come back in a quarter.
>
> Either way, thanks for reading.
>
> [Your name]

---

## Which template for which contact

| Contact type | Wave 1 (+5 days) | Wave 2 (+12 days) |
|---|---|---|
| Tier A, no reply | F2 — narrower ask | F3 — clean exit |
| Tier A, replied but no call booked | F1 once you have findings, else F2 | do not chase further; they replied |
| Tier B/C, no reply | F2 | F3 |
| Anyone who said "not now" | nothing. Diary it for a quarter. | — |
| Anyone who said no | nothing. Log the disconfirmer. | — |

---

## What a "no" is worth

`PREMISE_KILL_TEST.md` pre-registers five disconfirmers. **Two of them can only be learned from
people who decline**:

- *the spreadsheet is genuinely good enough* — no urgency, no budget
- *data will not leave the building* — the trust bar fails across the board

A follow-up that produces a clear "no, we handle this fine" is **not a failed send**. Log it in
`disconfirmer` and count it. Two of those appearing consistently is a STOP verdict, and reaching a
STOP cheaply is the entire point of running a kill-test rather than building.
