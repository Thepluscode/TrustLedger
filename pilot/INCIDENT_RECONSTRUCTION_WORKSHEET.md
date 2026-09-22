# Incident reconstruction worksheet

One sheet per conversation. Fill it **during** the call, in the operator's words, and copy it into
`kill-test-tracker.csv` **before** doing anything else afterwards. A field you did not establish is
`unknown`; it is never blank and never zero. A clean negative is a result.

The opening question, and the only pitch until section 1 is complete:

> "Can you walk me through one recent case where your records and a provider, bank or settlement
> record did not agree?"

Stay on that one incident. Do not show anything.

## 1. The incident (the tracker column is in brackets)

| Field | Ask | Answer, verbatim where possible |
|---|---|---|
| One actual incident (`last_incident`) | "What was the payment, and what disagreed with what?" | |
| Frequency / recurrence (`incident_frequency`, `recurrence_bar`, `recurrence_evidence`) | "How often does this shape of thing happen — last month, this quarter?" | |
| Systems involved (`systems_checked`) | "Which screens, files, logs or people did you open before you knew?" | |
| How it was detected | "Who noticed first, and how — a customer, a report, a job, a bank call?" | |
| Manual investigation effort (`manual_effort`) | "Hands-on hours, and how many people?" | |
| Elapsed resolution time | "From first sign to closed: hours, days, weeks?" | |
| Financial exposure (`financial_exposure`, `exposure_measurable`) | "How much money, in which currency, was in doubt while it was open? Any of it lost?" | |
| Current workaround (`current_workaround`) | "What do you do today so it does not happen again, or so it is found sooner?" | |
| Who owns the problem (`dedicated_operations`) | "Whose job is it when this happens? Is that their whole job?" | |
| Controlled data can be shared? (`data_bar`) | "If I gave you a template, could an anonymised export of one month's records leave the building?" | |
| Budget / willingness to pay (`money_bar`) | "If this were found and evidenced for you each month, is that a line someone here could sign for?" | |
| Verbatim operator language (`exact_quote`) | Write down the sentence they said when describing the worst part. | |

Also capture: providers in production (`providers_count`), currencies or countries
(`multi_currency_or_country`), audit / regulator / enterprise pressure
(`audit_regulatory_or_enterprise_pressure`).

## 2. Only after section 1 — one matching replay

> "I have a synthetic example of a similar failure. Can I show you how I've been thinking about it?"

Pick **one** row from the table in `OPERATOR_EXPOSURE_WORKFLOW.md` (§2) that matches the incident
they described. Show that one. Ask the four non-leading questions there. Record what they said was
wrong or missing (`disconfirmer` if it undercuts the premise).

## 3. Ask for evidence, not enthusiasm

Controlled sample data (`data_bar`) and a named next step with a date (`next_step`). "Interesting"
is not a next step.

## 4. Within one hour of the call

1. Copy every field above into the tracker row. `unknown` where unknown.
2. Set `date_interviewed` **only** if section 1 produced a named incident with at least the
   detection, systems and one of effort / time / exposure established. A call that produced only
   unknowns keeps the date blank, as the Exness row does.
3. `python3 pilot/score_kill_test.py` — report the gate counts, not that a call happened.
4. Write the anonymised reconstruction to `pilot/interviews/<company>-<date>.md` and send it to the
   operator for correction, marking every unknown as unknown.

## Queue after PalmPay

By operator accessibility and incident proximity, not company size: ThinkMarkets (public role
"Payments & Reconciliations Analyst"), Paynovate (two simultaneous finance / payments-control
roles), Lemonway (structurally right; previous route contaminated by the subject-line incident,
so a fresh operator-level contact only). Roles to search for: payments & reconciliations analyst,
settlement operations, payment operations, treasury operations, finance operations in a
multi-provider business.
