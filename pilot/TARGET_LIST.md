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
| 8 | **Lemonway** | Hiring **Head of Payment Operations** (Paris). **Marketplace payment infrastructure** across EU — collects and disburses on behalf of marketplaces, so fan-out across counterparties is the business model. Sharpest ICP fit found in this sweep. | jobsinforex.com `?q=payment+operations`, retrieved 2026-08-04 | Pain: senior role, named function. Data + Money: untested. **Interview first.** |
| 9 | **Trustly** | Hiring **Director Finance Operations** (London). Open-banking A2A payments across many bank connections and markets — settlement arrives on different clocks per bank. | jobsinforex.com `?q=finance+operations`, retrieved 2026-08-04 | Pain: director-level ownership exists. Data + Money: untested. |
| 10 | **Paynovate** | Hiring **Finance Operations Analyst** AND **Payments Control Manager** (Brussels). Two open finance/payments-control roles at once — the strongest budget signal in the sweep. | jobsinforex.com `?q=payment+operations`, retrieved 2026-08-04 | Pain: two concurrent roles. Data + Money: untested. |
| 11 | **VIALET** | Hiring **Head of Treasury & Finance Operations** (Vilnius). Lithuanian EMI — multi-currency across correspondent banking partners. | jobsinforex.com `?q=finance+operations`, retrieved 2026-08-04 | Pain: role-signal verified. Data + Money: untested. |
| 12 | **ConnectPay** | Hiring **Transactions Monitoring Specialist** (Vilnius). EMI serving online businesses across EU rails. | jobsinforex.com `?q=payment+operations`, retrieved 2026-08-04 | Pain: monitoring ≠ reconciliation — confirm at Q1/Q5 that settlement breaks are in scope. |
| 13 | **Satispay** | Hiring **Project Manager — Finance Transformation** (Milan). Scaling European payment network; 'finance transformation' usually means the manual process is now a project. | jobsinforex.com `?q=finance+operations`, retrieved 2026-08-04 | Pain: indirect signal. Qualify before contacting. |
| 14 | **iBanFirst** | Hiring **Deputy CFO** (Paris). Cross-border B2B payments across banking partners in many currencies. | jobsinforex.com `?q=finance+operations`, retrieved 2026-08-04 | Pain: CFO-level, not an ops-role signal — weaker. Data + Money: untested. |
| 15 | **Zest** | Hiring **Reconciliation Analyst** (Nigeria). Payments processor with a named reconciliation role. | myjobmag.com `?q=reconciliation`, retrieved 2026-08-04 | Pain: role-signal verified. Confirm ≥2 providers at Q2. |
| 16 | **Bybit** | Hiring **Finance Operations Specialist** (Vienna). Fiat on/off-ramps across multiple banking partners plus on-chain settlement — two settlement systems that do not agree on time. | jobsinforex.com `?q=payment+operations`, retrieved 2026-08-04 | Pain: role-signal verified. Data bar likely hard (custody/regulatory). |
| 17 | **OKX** | Hiring **Senior Treasury Manager** (Dubai). Same multi-rail structure at scale. | jobsinforex.com `?q=finance+operations`, retrieved 2026-08-04 | Pain: role-signal verified. Data bar likely hard. |
| 18 | **OSL** | Hiring **Merchant Operations & Growth Manager** (Dubai). Regulated digital-asset platform with a merchant-facing payments arm. | jobsinforex.com `?q=payment+operations`, retrieved 2026-08-04 | Pain: role is growth-weighted — weaker signal. Qualify first. |
| 19 | **MultiBank Group** | Hiring **Treasury Analyst | Crypto** (Dubai). Multi-entity brokerage plus crypto — multi-rail and multi-jurisdiction. | jobsinforex.com `?q=reconciliation+operations+analyst`, retrieved 2026-08-04 | Pain: role-signal verified. ICP fit adjacent. |
| 20 | **ThinkMarkets** | Hiring **Payments & Reconciliations Analyst** (Dubai). The most literal role title in the entire sweep — the job *is* the product's job. | jobsinforex.com `?q=reconciliation+operations+analyst`, retrieved 2026-08-04 | Pain: strongest single role-title match found. Worth interviewing despite adjacent segment. |
| 21 | **Trading 212** | Hiring **Client Asset Operations (CASS) Analyst** (London). CASS = FCA client-money segregation and daily reconciliation — regulatory audit pressure is explicit. | jobsinforex.com `?q=reconciliation+operations+analyst`, retrieved 2026-08-04 | Pain: regulatory driver named. Adjacent segment (client money, not payouts). |
| 22 | **IG Group** | Hiring **Senior Cash Control Analyst** (Krakow). Multi-market broker, client money across banking partners. | jobsinforex.com `?q=reconciliation+operations+analyst`, retrieved 2026-08-04 | Pain: role-signal verified. Adjacent. |
| 23 | **Swissquote** | Hiring **Settlement Officer — Institutional** AND **— Retail** (Gland). Two concurrent settlement roles. | jobsinforex.com `?q=settlement`, retrieved 2026-08-04 | Pain: two concurrent roles. Adjacent (securities settlement). |
| 24 | **XTB** | Hiring **Payments Operations Specialist** (Warsaw). Broker with a *payments*-titled ops role, not securities-titled. | jobsinforex.com `?q=payment+operations`, retrieved 2026-08-04 | Pain: payments-specific title. Adjacent segment. |
| 25 | **Exness** | Hiring **Finance Operations Manager — UAE** (Dubai). High-volume broker, many payment providers per region — PSP fan-out is real here. | jobsinforex.com `?q=finance+operations`, retrieved 2026-08-04 | Pain: role-signal verified. Adjacent, but PSP fan-out is genuinely their problem. |
| 26 | **Deriv** | Hiring **Senior Executive — Settlements** (Kuala Lumpur). Multi-country broker with regional payment methods. | jobsinforex.com `?q=payment+operations`, retrieved 2026-08-04 | Pain: role-signal verified. Adjacent. |
| 27 | **IronFX** | Hiring **Senior Payments Partnership Officer** (Limassol). A role dedicated to managing *payment partners* — implies several. | jobsinforex.com `?q=payment+operations`, retrieved 2026-08-04 | Pain: implies multi-provider by the role's existence. Adjacent. |
| 28 | **CMC Markets** | Hiring **Head of Finance** (Dubai). Multi-jurisdiction broker. | jobsinforex.com `?q=finance+operations`, retrieved 2026-08-04 | Pain: generic finance role — weakest signal here. Qualify or drop. |
| 29 | **Nuvei** | Hiring **Senior Enterprise Architect** (Tel Aviv). Payments platform across many acquiring and alternative-payment connections. | jobsinforex.com `?q=payment+operations`, retrieved 2026-08-04 | Pain: engineering role, **not** an ops-pain signal. Lowest priority; likely builds in-house. |

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

---

## Sweep 2026-08-04 — 22 rows added, and what the sweep taught

Five board queries run (`payment operations`, `finance operations`, `settlement`,
`reconciliation operations analyst`, `reconciliation`). **22 companies promoted, ~35 rejected.**
Two findings that should change how this method runs, both worth more than the rows:

### Finding 1 — "reconciliation" alone is a junk keyword

`myjobmag.com?q=reconciliation` returned a hotel, a gas company, a hospital, a consumer-goods
distributor and several outsourcing agencies. **Every business with a bank account reconciles
something.** The word does not select for the pain; only 2 of 16 hits were payments companies.

**Use instead**, in rough order of precision:
`payments & reconciliations` · `settlement operations` · `payment operations` ·
`treasury operations` · `finance operations` + fintech · `cash control` · `CASS analyst` (UK only —
FCA client-money reconciliation, carries an explicit regulatory driver)

### Finding 2 — the cross-border listicles surface providers, not buyers

Searching for cross-border payment companies returns Wise, Airwallex, Stripe, Checkout.com, Thunes,
Payoneer. **Those are who the ICP settles *through*.** The buyer is the marketplace or lender
*using two of them*. A "top 20 payment companies" list is an anti-target list. It is also why
`RESEARCH_QUEUE.md`'s rule holds: homepage and marketing copy are never sufficient.

### Rejected in this sweep, with reasons

| Rejected | Why |
|---|---|
| Nordea · Royal Bank of Canada · Brown Brothers Harriman · Van Lanschot Kempen · AZQORE · Saxo Bank · Stonehage Fleming | Banks and custodians. Build in-house, buy from incumbents; neutrality adds nothing. |
| Broadridge · Endava · Avetium Consult · ICS Outsourcing · TeamAce · eRecruiter · Pishon and Brooks | Vendors, consultancies and recruitment agencies — not the buyer, sometimes the competitor. |
| DP World · Multipro · May & Baker · MeCure · Coronation Insurance · Lily Hospitals · Blowfish Hotel · Alles Charis Gas · The Concept Group | Generic bank reconciliation, not cross-provider payment settlement. Finding 1 in practice. |
| DRW · StoneX | Proprietary trading. Settlement is securities clearing, a different problem. |
| Adyen · Global Payments | Acquirers. They are the provider whose records the ICP reconciles *against*. |
| Wise | Multi-corridor and genuinely painful, but at their scale this is an internal platform team. Interview for insight; never as a prospect. |
| Thunes · Paysend · BVNK | Architecturally ideal, but the roles posted were engineering/product — no cited *ops-pain* signal. Re-check their own careers pages. |

### The yield ceiling, honestly

The ask was 50. **22 were reachable at the evidence bar**, and this list now holds **29**. The
constraint is source access, not effort: the board that yields is a *forex* board, which
structurally over-samples brokers — hence Tier C being the largest tier and the sharpest
sub-segment (remittance platforms, multi-country marketplaces) being the thinnest.

**To reach 50, in yield order — none of which is reachable from a public search:**

1. **LinkedIn Jobs** with the Finding-1 keywords, filtered to Financial Services + Internet
   Marketplaces. Behind auth; ~20-30 rows in an hour of manual work.
2. **Company careers pages** for the three architecturally-ideal names already rejected for lack of
   an ops-role signal (Thunes, Paysend, BVNK) plus any remittance platform you know by name.
3. **FCA / EMI / PI registers** cross-referenced against public PSP-partner mentions — slow, but it
   is the only source that proves the ≥2-provider criterion *before* the call rather than at Q2.
4. **Warm network.** One intro outranks thirty cold rows, and `INTERVIEW_OUTREACH.md` §A exists
   precisely for this. 29 evidenced rows is already more than enough to start sending.

**Do not wait for 50 to start.** The booking maths in `INTERVIEW_OUTREACH.md` says ~150-200 sends
for 25 interviews; 29 names supports the first ~30 sends, and sourcing continues in parallel. The
failure mode this list exists to prevent is a longer list and still zero interviews.

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
