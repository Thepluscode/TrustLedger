# First Three Conversations

## Status — updated 2026-09-06

| Question | Answer |
|---|---|
| Interview 1 held (Exness, Artem Ermakov) | **YES** — the scheduled 2026-08-31 call took place |
| Accuracy-check message sent | 2026-09-02 |
| Incident fully reconstructed | **NOT YET** |
| Data access granted | **NO** |
| Counted by `score_kill_test.py` | **NO** — see below |

**Why the scorer still reads 0 of 25.** It only counts a row carrying `date_interviewed`, and it
fails closed: that field may not be set until all twelve `REQUIRED_INTERVIEW_FIELDS` and the four
yes/no `QUALIFICATION_FIELDS` are present. None of the incident content has been recorded yet, so
the row deliberately carries no interview date. **The blank means "evidence not yet recorded", not
"the call did not happen"** — the distinction is written into the tracker's notes so a later reader
cannot mistake one for the other.

This is a real modelling gap and it is stated rather than patched: the tracker has no way to
represent *held, reconstruction pending*. A conversation that happened is currently invisible to
the gate. Do not resolve it by loosening the scorer — that is the threshold-renegotiation the
scorer exists to prevent.

**Standing rule from the founder, 2026-09-06.** If Artem does not reply, keep every missing field
as `unknown` and send the final anonymised reconstruction without inventing details. Save his
response verbatim.

**The deadline has passed.** The corrected reconstruction was due Friday 2026-09-04. Today is
Sunday 2026-09-06, so the fallback above is now the live path, two days overdue.

---

This is the active TrustLedger milestone. It tests whether qualified operators will enter a useful
problem interview before optimising for the full 25-interview market gate.

## Outcome

Three completed conversations, each anchored to one real payment or settlement discrepancy and
recorded in `kill-test-tracker.csv`. A booked call, unanswered outreach, public job description or
plausible company problem does not count.

## Evidence ladder

These states never substitute for one another:

| State | Evidence required | What it proves |
|---|---|---|
| Replied | Exact buyer message and date | The route reached a person |
| Scheduled | Accepted calendar time or written appointment | A future conversation, not learning |
| Completed | One real incident reconstructed and recorded | Workflow evidence |
| Data-qualified | Named approver and a redacted sample or explicit scoped-access agreement | The product can see the problem |
| Paid discovery | Signed £4,000 scope and payment commitment | A buyer will spend money to investigate it |
| Pilot | Signed £10,000 founding or £20,000 standard pilot | A customer will test the operating outcome |

Only `Completed` advances the first-three milestone. Only signed commercial evidence advances the
money bar.

## Opening

> I am researching how payment and reconciliation teams investigate discrepancies. This is not a
> product demo or sales call. Could you walk me through the last payment or settlement discrepancy
> where your internal records and the provider or bank records did not agree?

Ask permission before recording. Written notes are sufficient.

## Reconstruct the incident chronologically

1. What first indicated that something was wrong?
2. What did each source say: provider, bank, settlement file, webhook and internal record?
3. Which systems did you open, and in what order?
4. Who became involved and what did each person need to establish?
5. How long passed before somebody could support a conclusion with evidence?
6. What money was missing, delayed, duplicated, disputed or otherwise exposed?
7. Which evidence was unavailable, ambiguous or difficult to join?
8. How was the discrepancy resolved, and how was that outcome recorded?
9. How often does this class of discrepancy occur?
10. What happens today when the cause remains unknown?

Do not ask whether they would use TrustLedger. Ask what they did, what it cost and what happened.

## Quantify the workflow

- incidents per month;
- elapsed and hands-on resolution hours;
- number and roles of people involved;
- systems opened before a supported conclusion;
- financial exposure or leakage, including currency;
- customer, audit, regulatory or reporting consequence;
- current workaround and any existing spend.

Pain intensity is evidence, not a computed pass by itself:

```text
incidents/month × resolution hours × people × cost/hour
+ financial exposure/leakage
+ customer, audit or regulatory consequence
```

## Close without pitching

1. Summarise the incident and ask the interviewee to correct the reconstruction.
2. Ask whether another operator, payments engineer or finance leader saw a different part of it.
3. If pain is confirmed, ask whether anonymised settlement/provider/internal records could be used
   in a scoped read-only exercise.
4. Ask who would own and approve paid discovery if a measurable exercise were warranted.
5. Record the exact next action, owner and date; “keep in touch” is not a next step.

## Paid-discovery handoff

Offer Discovery only after a real incident establishes material or recurring pain, the data boundary
is plausible, and the person who can approve the work is named. Use the fixed price and scope:

> The next step would be a fixed £4,000 Payment Reliability Discovery. We would map this workflow,
> confirm the data boundary and failure taxonomy, quantify the current exposure, and agree measurable
> pilot success criteria. The fee is credited to a pilot booked within 30 days. Is that something
> [named approver] can authorise, and what would they need to decide?

Do not negotiate a range. Use the £1,500 Diagnostic only when an otherwise qualified buyer cannot yet
share data. Do not offer it as a cheaper Discovery. The first three qualified customers may progress
to the narrower £10,000 Founding Design Partner Pilot; the standard pilot is £20,000. Full boundaries
and participation requirements are in `PRICING.md`.

## Optional post-incident exposure

Only after the chronology, cost and current workaround are recorded, use the matching five-minute
synthetic replay from `OPERATOR_EXPOSURE_WORKFLOW.md`. The purpose is to find missing evidence and
workflow disagreement, not to collect feature praise.

Do not ask “Would you use this?” Ask what is wrong or missing, which source is still required, when
the next operational action becomes safe, and which part their current process already handles well.
The interview still counts when the demo is rejected; it counts only because the real incident was
reconstructed and recorded.

## Evidence entry

Use one row per completed conversation. Populate every post-`notes` field in
`kill-test-tracker.csv`; record `unknown` when the interviewee did not disclose a value. `maybe` is
valid for `data_bar` and `money_bar` but never counts as a pass.

Complete the four qualification fields from interview evidence. The scorer counts the conversation
toward 25 only when it confirms 2+ providers/rails/banks, multi-currency or multi-country operation,
a dedicated operations function, measurable exposure and audit/regulatory pressure. Out-of-segment
calls remain recorded but are excluded from the gate.

Set `recurrence_bar=yes` only when a pain-confirming company reports the failure monthly or more
often, or provides evidence of sufficiently material exposure when it occurs. Put the supporting
frequency or exposure in `recurrence_evidence`.

Run `python3 pilot/score_kill_test.py` immediately after saving the row.

Within one business day of a completed conversation, send a factual reconstruction for correction,
record the named data approver and budget owner, and either issue the fixed Discovery scope or record
why the engagement stopped. A warm conversation with no dated next action is learning but not
commercial progress.
