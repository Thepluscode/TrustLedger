# Operator Exposure Workflow

Status: **ACTIVE — 0/3 completed conversations.** This is the execution layer between the existing
technical proof and the 25-interview market gate.

## Outcome

Complete three conversations with qualified payment/reconciliation operators. Each conversation
must produce a chronological account of one real discrepancy before TrustLedger is shown. The
showcase is a diagnostic probe after evidence, not the opening pitch.

## Session sequence — 25 minutes

### 1. Reconstruct the last incident — 15 minutes

Use `FIRST_THREE_CONVERSATIONS.md`. Establish:

- the provider, bank, settlement, webhook and internal states;
- every system opened and person involved;
- elapsed and hands-on time;
- financial exposure or leakage, with currency;
- what evidence supported the final conclusion;
- what remained unknown;
- recurrence and the present workaround.

Do not show TrustLedger during this section. Do not ask whether they like the premise.

### 2. Expose one matching control — 5 minutes

Select only the closest synthetic replay in `/showcase`:

| Recorded incident | Replay |
|---|---|
| Missing/late settlement | Missing settlement |
| Duplicate callback or settlement line | Duplicate event |
| Provider/webhook disagreement | State conflict |
| Fee discrepancy | Fee overcharge |
| Audit/evidence challenge | Audit tamper |
| Ambiguous provider response | Provider timeout |

Say:

> “This is synthetic and does not use your data. I am showing it only to test whether the evidence
> structure matches the investigation you just described.”

Ask four non-leading questions:

1. What is wrong or missing compared with the real incident?
2. Which source would you need before this conclusion was supportable?
3. At what point could your team take the next operational action?
4. Which part would you remove because your current process already handles it well?

Do not lead with the full capability register. Open it only if the operator asks how a visible
control is implemented or governed.

### 3. Ask for evidence, not enthusiasm — 5 minutes

If pain is recurring or materially exposed:

- request pseudonymised settlement/provider/internal samples for one read-only exercise;
- identify the data approver and security boundary;
- identify the buyer for paid discovery;
- agree the measurable outcome and next date.

If their provider dashboard plus spreadsheet is sufficient, record the hard kill signal. Do not
rescue the interview by demonstrating more features.

## First live action

Use the already-prepared LinkedIn/warm-introduction note in `INTERVIEW_01_PALMPAY.md` for Friday
Ogungbemi. The message remains operator-first and asks for a recent incident, not a demo. No working
PalmPay email is known; do not guess one.

After that conversation, source the next operator by role rather than sending a third executive
follow-up. Prioritise an active `payments & reconciliations`, `settlement operations`, `payment
operations` or `payments control` practitioner from the evidenced target list.

## Evidence handling

Immediately after the call:

1. complete every post-`notes` field in `kill-test-tracker.csv`;
2. use `unknown`, never zero, for undisclosed exposure;
3. record the exact quote and next action;
4. run `python3 pilot/score_kill_test.py`;
5. report the resulting gate counts, not that a demo occurred.

## Status truth

- Canonical remote main contains the test-backed financial/reconciliation foundation.
- PR #125 remains open; its frontend/API integrations are branch-built, not canonical main.
- `/showcase` is locally verified and anonymous within the local application; it is not deployed to
  a public internet host.
- Customer interviews, data commitments, paid commitments and ROI remain zero/unproven until the
  tracker records otherwise.
