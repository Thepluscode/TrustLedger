# Batch 06 — three named asks, drafted 2026-08-16

> **These are drafts. None of them is a send.** A send exists when `date_sent` is filled in
> `kill-test-tracker.csv` — nowhere else.

**Why these three, and why now.** The D3 community post (TL-SOC-040, 15 Aug) returned **24
impressions and 3 members reached**. Broadcast recruitment on this profile does not reach anyone —
that is measured, not suspected. The next asks are therefore direct and named.

**Addresses were checked, not inferred.** Of the three targets, only VIALET publishes a usable
address. ConnectPay and OSL obfuscate every address behind Cloudflare email-protection on both
their contact and privacy pages, so there is no plain-text address to verify. They are drafted as
**LinkedIn (channel B)**, which is the verified route rather than the convenient one — four of the
nineteen sends on this campaign hard-bounced from broker-inferred addresses, and that is not
repeated here.

**Every draft obeys the rule:** no product before Q9. None mentions TrustLedger, reconciliation
software, or a demo. All three pass `pilot/check_send.py` (guard selftest 8/8 first).

---

## 1. VIALET — Mantas Staliūnas, CEO · channel C (email)

**To:** `info@vialet.eu` — verified 2026-08-16 at `vialet.eu/contact-us/`, first-party.
**Why the CEO:** VIA Payments UAB is small enough that the CEO is the buyer; the research rates the
name HIGH (quoted as CEO in a 6 Mar 2026 press release, BoL-approved Mar 2024).
**Hook, and what is actually verified:** own BIC `VIPULT22XXX`, EMI licence No. 16 from the Bank of
Lithuania, operations across Lithuania, Latvia and Cyprus. All three facts are published by the
company. The sentence about someone reconciling by hand is stated as *inference from that shape*,
not as a claim about them — do not harden it.

> Subject: how do you find out when a correspondent settles short?
>
> Hi Mantas,
>
> VIALET runs on its own BIC as a Bank of Lithuania EMI, with operations across Lithuania, Latvia
> and Cyprus. In my experience that shape means someone is reconciling across rails that
> periodically disagree, and it is rarely that person's actual job.
>
> I am doing research on that specific problem across about 25 companies running two or more
> payment providers: how a break gets found, who chases it, how long it takes to prove what
> happened, and what quietly leaks in the meantime. I am not selling anything - I am testing
> whether the problem is expensive enough to be worth solving at all.
>
> Twenty minutes, nine questions, no deck. I will send you the anonymised cross-company findings
> either way, including if the answer turns out to be that most teams have this handled fine.
>
> Theophilus Ogieva
> London

---

## 2. ConnectPay — Juan Carmiol, COO · channel B (LinkedIn connection note)

**Why LinkedIn:** no published address exists. Carmiol is named **with his LinkedIn on ConnectPay's
own about page**, so the profile is first-party verified — stronger provenance than any email would
have been.
**Verified hook:** own BIC `CNUALT21XXX`, EMI licence No. 24 from the Bank of Lithuania.
**259 characters** — under LinkedIn's 300 limit.

> Researching how multi-provider payment teams find and prove settlement breaks - not selling.
> ConnectPay runs its own BIC under EMI licence 24, so breaks land somewhere near operations.
> Trade 20 minutes for the anonymised findings across ~25 similar companies?

---

## 3. OSL — Teo Jing Wei, GM of OSL Pay · channel B (LinkedIn connection note)

**Why LinkedIn:** no published address exists.
**Address him as GM of OSL Pay, never CEO.** June 2025 press said CEO of OSL Pay; his LinkedIn now
says GM. Using the stale title is the kind of error that ends a first message.
**248 characters.**

> Researching how teams reconcile when fiat and digital rails disagree - research, not a pitch.
> You run OSL Pay, so settlement breaks presumably surface near you. Trade 20 minutes for the
> anonymised findings across ~25 companies running 2+ providers?

---

## Follow-up once a LinkedIn request is accepted

Per `INTERVIEW_OUTREACH.md` channel B, unchanged:

> Thanks for connecting. Genuinely just research: I'm trying to establish whether cross-provider
> reconciliation is a real, expensive problem or one that spreadsheets handle fine. Both answers
> are useful to me — I'd rather find out it's fine now than after building something nobody needs.
>
> Nine questions, 20 minutes, no deck. Any slot that suits?

---

## Logging

Fill `date_sent` per company when each goes. LinkedIn requests are sends: a connection note is the
message. Record acceptance separately from reply — an accept is not a conversation, and
`date_interviewed` moves only when one actually happens. It has been filled **zero** times across
thirty rows since this gate was written.
