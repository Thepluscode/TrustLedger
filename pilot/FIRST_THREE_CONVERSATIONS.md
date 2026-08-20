# First Three Conversations

This is the active TrustLedger milestone. It tests whether qualified operators will enter a useful
problem interview before optimising for the full 25-interview market gate.

## Outcome

Three completed conversations, each anchored to one real payment or settlement discrepancy and
recorded in `kill-test-tracker.csv`. A booked call, unanswered outreach, public job description or
plausible company problem does not count.

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
