# Kill-Test Target List

Candidate interviewees for `PREMISE_KILL_TEST.md` (N = 25, three-bar gate).

> **Read this first.** Only rows marked **EVIDENCED** have a cited source establishing the
> qualifying signal. Everything under "Research queue" is a name to *qualify*, not a qualified
> name — the qualification step is written next to each. Do not treat a queue row as a prospect
> until its signal is verified and the source pasted into this file.
>
> Built 2026-07-31 from web research. Nothing here is inferred from company type alone.

---

## The sharpest sub-segment (a research finding, not an assumption)

**Remittance platforms are multi-provider by architecture, not by accident.** The standard
cross-border stack runs *acquiring partners* (collect from the sender), *liquidity providers*
(source local currency, manage FX), and *payout partners* (deliver into recipient wallets) — three
distinct counterparties per corridor, each producing its own settlement record.

That matters for the kill-test: this sub-segment clears the "≥2 payment providers" filter
**structurally**, so screening effort goes into pain and budget rather than into confirming the
setup exists. It is the highest-yield of the four sub-segments in the ICP filter.

Second-highest: **multi-country marketplaces**, where "settlement reports at scale, fee structures
that vary across gateway relationships, and partial payouts and timing differences that create
month-end gaps" is the named operational problem.

---

> **No region filter.** The ICP was corrected on 2026-08-01: geography is a *reachability*
> question, never a qualification one. The qualifying properties are ≥2 payment providers, multi-
> currency or multi-country settlement, a dedicated finance/ops function, and audit pressure. UK,
> EU, US, LATAM, SEA and African companies qualify on identical terms.
>
> The evidenced and candidate rows below are currently Africa-weighted because the original filter
> named a region. That is a **gap in the research, not a definition of the market** — non-African
> rows are missing and should be added first, using the multi-market sourcing table below.

## EVIDENCED

| # | Company | Qualifying signal | Source | Bars to test |
|---|---|---|---|---|
| 1 | **PalmPay** | Pan-African across **7 countries** (Nigeria, Ghana, Kenya, Uganda, Egypt, Côte d'Ivoire, Tanzania) — multi-country implies multi-provider. Actively hiring a **Senior Reconciliation Analyst** in Lagos: reconciling transactions, managing disputes, liaising with banks, daily account reconciliation. | MyJobMag / Glassdoor listing, retrieved 2026-07-31 | Pain: strongly indicated by the role existing. Data + Money: untested. |
| 2 | **Paysafe** | Hiring **Junior Financial Operations Analyst** (Sofia). Multi-brand payments group settling across acquiring partners and alternative payment methods in many markets — multi-provider by architecture. | jobsinforex.com settlement-reconciliation board, retrieved 2026-08-01 | Pain: role-signal verified. ICP fit strong. Data + Money: untested. |
| 3 | **Freetrade** | Hiring **Payments Operations Associate** (Budapest). Retail broker settling across payment providers and banking partners in GBP/EUR. | jobsinforex.com settlement-reconciliation board, retrieved 2026-08-01 | Pain: role-signal verified. Data + Money: untested. |
| 4 | **Alpaca** | Hiring **Settlements Associate** (London). Brokerage infrastructure — settlement across custody and banking counterparties. | jobsinforex.com settlement-reconciliation board, retrieved 2026-08-01 | Pain: role-signal verified. Adjacent segment (securities, not payouts) — confirm at Q2 whether ≥2 *payment* rails apply. |
| 5 | **Blockchain.com** | Hiring **Treasury Operations Analyst** (London). Fiat + crypto rails across multiple banking partners — reconciliation across incompatible settlement clocks is structural. | jobsinforex.com settlement-reconciliation board, retrieved 2026-08-01 | Pain: role-signal verified. Strong multi-rail fit. Data bar likely hard (custody/regulatory). |
| 6 | **Trade Republic** | Hiring **Process Operations Associate — Security Services** (Berlin). Multi-market European broker. | jobsinforex.com settlement-reconciliation board, retrieved 2026-08-01 | Pain: role-signal verified. Adjacent (securities settlement). Lower priority than payments-native rows. |
| 7 | **Equiti Group** | Hiring **Operations Manager** (Dubai). Multi-entity brokerage across regulated jurisdictions — multi-currency, multi-banking-partner. | jobsinforex.com settlement-reconciliation board, retrieved 2026-08-01 | Pain: inferred from multi-entity structure, weaker than a named reconciliation role. Qualify before contacting. |

**Promoted 2026-08-01 by running the sourcing method above**, not by guessing names. Each row's
signal is a *live payment-operations or settlement role posting* — the promotion rule's first
listed signal. What that proves: a finance/ops function exists, the exception work is manual enough
to need a person, and someone has budget. What it does **not** prove: pain magnitude, willingness to
grant data access, or willingness to pay. Those are the interview.

**Deliberately excluded despite appearing on the same board**, with the reason, so the filter stays
honest rather than becoming a name-collection exercise:

| Excluded | Why |
|---|---|
| Royal Bank of Canada · Brown Brothers Harriman · Saxo Bank | Banks and custodians. They build reconciliation in-house and buy from incumbents; the neutrality argument adds nothing. |
| Global Payments Inc. · Broadridge | They *are* the provider / the incumbent vendor. A merchant's multi-provider pain is not their pain. |
| Wise | Multi-corridor and painful, but at their scale this is an in-house platform team, not a purchase. Interview only for insight, never as a prospect. |
| Endava | Consultancy delivering payments projects — not a buyer of their own reconciliation. |
| Thunes · Paysend · BVNK | Architecturally ideal (multi-partner cross-border), but the roles posted were engineering/product, not payment-operations — so no *cited* pain signal yet. Re-run the query on their own careers pages before promoting. |

**Reachability note.** These are UK/EU/UAE-weighted, which closes the gap flagged above — the list
was Africa-only because the original filter named a region. It no longer does.

**Why one row and not twenty.** A qualifying signal has to be *found*, not assumed. One company with
a cited signal is worth more than twenty plausible names, and the sourcing method below generates
more of these faster than I can guess them.

---

## The sourcing method (use this instead of a static list)

**A company advertising a reconciliation or settlement role is proving three things at once:** a
dedicated finance/ops function exists, the exception work is manual enough to need a person, and
somebody has budgeted for it. That is two of the ICP filter's four criteria and a proxy for the
pain bar, from a public posting.

Run these weekly and add hits to the EVIDENCED table:

**Run these in every market.** The qualifying signal is the *role*, never the region — a company
hiring someone to reconcile settlements manually has the pain whether it sits in London, Austin,
São Paulo, Singapore or Lagos.

| Market | Source | Query |
|---|---|---|
| UK / EU | LinkedIn Jobs · Otta · Welcome to the Jungle | `payment operations`, `reconciliation analyst`, `settlement operations` + fintech |
| UK / EU | FCA / EMI / PI licence registers | cross-reference licensees against public payment-partner and PSP mentions |
| US | LinkedIn · Built In · Greenhouse boards | `payment operations`, `settlement analyst`, `payments reconciliation` |
| LATAM | LinkedIn · Get on Board | `conciliación de pagos`, `payment operations` — multi-PSP is the norm (PIX + cards + wallets) |
| SEA | LinkedIn · Glints | `payment operations`, `settlement` — multi-rail by default across the region |
| Africa | MyJobMag NG/KE · Glassdoor Lagos | `reconciliation and settlement analyst`, `reconciliation specialist fintech` |

**Why this list is not ranked by region.** Any company running ≥2 providers across ≥2 currencies has
the problem. The UK and EU additionally carry a regulatory driver Africa does not — FCA operational-
resilience expectations and PSD2 make "prove what happened to this payment" an obligation, not a
preference — which strengthens rather than weakens the case there.

Qualify each hit before it becomes a prospect:
1. Does the JD or the company's docs name **two or more** payment providers / rails / banks?
2. Is there a named finance/ops or treasury lead (LinkedIn)?
3. Regulated or enterprise-facing (audit pressure)?
4. Volume plausibly large enough that a £4–20k engagement is small relative to the leakage?

Reject on any *no*. The kill-test is explicit that including single-provider, low-volume, no-finance-
team merchants "will produce a false read."

---

## Research queue — NOT yet qualified

Names to run the four qualification questions against. **No qualifying signal has been verified for
any of these.** They are here so the sourcing work has a starting point, not because they belong on
a prospect list.

| Sub-segment | Why the segment fits | Qualification needed |
|---|---|---|
| Remittance platforms | Multi-provider by architecture (acquire / liquidity / payout) | Name the specific partners from their docs or a job ad; find the finance/ops lead |
| Multi-country marketplaces | Multi-gateway settlement, month-end timing gaps | Confirm ≥2 gateways; confirm a finance function, not a founder doing it |
| Digital lenders | Disbursement + collection often split across providers | Confirm both sides use different rails; confirm regulated status |
| Payroll / mass-payout platforms | Payout volume across banks and mobile money | Confirm ≥2 payout rails; find who owns failed-payout recovery |

---

## Scoring sheet

One row per interview. Fill the bars from the **answers**, not from impressions.

| # | Company | Role spoken to | Date | Pain (≥1 day/wk **or** quantified leakage) | Data (grants read access) | Money (commits to paid) | Verbatim: last concrete loss | Notes |
|---|---|---|---|---|---|---|---|---|
| 1 | | | | ☐ | ☐ | ☐ | | |

**Running totals — the gate:**

| Bar | Threshold | Current |
|---|---|---|
| Same problem, unprompted | ≥ 6 of 25 | **0** |
| Grants sample data | ≥ 3 of 25 | **0** |
| Commits to paid | ≥ 2 of 25 | **0** |

All three must clear for GO. Pain ≥6 with data <3 or paid <2 is **MURKY — do not build**. Pain <6 is
**STOP**.

**Hard kill signal, regardless of counts:** if the dominant answer is *"our provider dashboard plus a
spreadsheet handles this fine,"* the premise is false for that segment. Log it and re-cut.

---

## Sources

- Multi-processor reconciliation as the named consequence of multi-gateway operation — [Eleo](https://eleo.app/blog/choosing-payment-processor-africa)
- Cross-border stack: acquiring / liquidity / payout partners as distinct counterparties — [The Africa Payments Stack](https://reubenmars.substack.com/p/the-africa-payments-stack-for-global)
- Marketplace settlement-at-scale, varying gateway fee structures, partial payouts and month-end gaps — [Duplo](https://tryduplo.com/blog/cross-border-payments-in-africa-the-complete-guide-to-international-payments-2026/)
- PalmPay Senior Reconciliation Analyst, Lagos; 7-country footprint — [MyJobMag](https://www.myjobmag.com/jobs-by-title/reconciliation-and-settlement-analyst) · [Glassdoor](https://www.glassdoor.com/Job/lagos-financial-operations-analyst-jobs-SRCH_IL.0,5_IC2543876_KO6,34.htm)
