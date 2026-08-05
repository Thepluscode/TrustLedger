# ADR-009: Certification requires dual control, and drills never touch real tenant money

- **Status:** Accepted (recorded 2026-08-04; the decision itself predates this record)
- **Deciders:** Theophilus Ogieva

## Context

Provider certification is the gate a tenant/provider configuration must pass before it can move
production money. It runs a catalogue of drills — signed webhook, ambiguous recovery, reconciliation
proof, failure release, OTP finalisation, reversal accounting, credential rotation, emergency stop —
and produces a checksummed evidence pack.

Two questions had to be answered before that gate could mean anything.

**Who can declare a certification good?** A gate that the person running it can also approve is not a
gate; it is a formality with a signature on it. The same logic already applies to production canaries
(requester ≠ approver) and to held-transfer approval.

**What money do the drills move?** The drills exercise real code paths — the real webhook inbox, the
real submission boundary, real ledger postings. The tempting shortcut is to run them against whatever
tenant data happens to exist, which is fastest and reads as most "realistic".

## Decision

**Dual control on sign-off.** `ProviderCertificationService.signOff` refuses when the signer is the
run's initiator, refuses to sign anything that is not `PASSED`, and refuses a second sign-off on a
run already signed. Certification is only *current-valid* for the production gate when a PASSED run
carries a sign-off and has not expired.

**Drills use synthetic fixtures only.** Every drill creates its own accounts, transfers and provider
records inside the run and asserts against those. No drill reads or mutates a tenant's real payment
data.

## Why synthetic fixtures, specifically

- A drill that moves real money to prove that moving money works has **caused** the event it was
  meant to certify. Reversal accounting and failure-release drills deliberately produce failures and
  reversals; doing that to a customer's payment is not a test, it is an incident.
- Drills run repeatedly and on demand. Anything they touch must be safe to touch again five minutes
  later, which real payment history is not.
- Evidence packs are exported and handed to third parties. Fixtures guarantee a pack contains no
  customer data by construction rather than by redaction — redaction is a filter you can forget to
  apply, and it fails silently when it does.
- A drill asserting against live data would be **non-deterministic**: it would pass or fail depending
  on what the tenant happened to be doing, which is the definition of a test that teaches people to
  re-run it rather than read it.

The cost is honest and stated: drills prove the **code paths** behave correctly. They do **not** prove
a specific real payment succeeded, and the certification pack must never be read as evidence about a
particular customer transaction.

> **Correction (2026-08-05).** As first written, this ADR said drills prove the code paths behave
> "against a provider's sandbox contract". **That was wrong — it overstated the guarantee.**
> `DrillContext` carries the provider config's *id* but never its provider *name*, no drill reads
> `getProvider()`, and `CertificationSyntheticFixtures` hardcodes `SandboxPaymentRailAdapter.RAIL`.
> So certifying Paystack exercises the **sandbox adapter's** webhook verification, status
> normalisation and ambiguity handling — not Paystack's.
>
> A certification pass is evidence about the **orchestration** — reservation, the durable submission
> boundary, the webhook inbox, reconciliation, ledger posting, and the governance controls — not about
> the adapter for the provider named on the pack. That is real and worth having; it is not what
> "certified PAYSTACK" sounds like.
>
> Demonstrated by `CertificationProviderCoverageTest`, whose assertions are written to fail
> deliberately once the gap is closed. Tracked in `FEATURE_TRACKER.md`. **Do not describe
> certification as provider-specific until it is closed.**

## Options considered

**Self-sign-off with an audit record.** Rejected: an audit trail tells you afterwards who approved
their own work. It does not stop them.

**Drills against a read-only snapshot of real data.** Rejected: it removes the mutation hazard but
keeps customer data in exported evidence packs and keeps the non-determinism.

**A dedicated "drill tenant" holding realistic seeded data.** Not rejected on principle — this is the
natural next step if fixtures ever prove too thin. Deferred because per-run fixtures are simpler and
have not yet been the limiting factor.

## Trade-offs accepted

- Certification cannot be completed by a single operator, which is friction by design and is felt
  most by small teams.
- A drill catalogue built on fixtures can drift from real provider behaviour. This is mitigated by
  the catalogue carrying a SHA-256 **catalogue version stamp** in every evidence pack, so a pack
  states exactly which drill set produced it.

## Risks

- Fixtures encode our belief about a provider, not the provider's actual behaviour. Real
  provider-sandbox drills are a known, recorded residual.
- A future drill added carelessly could reach for a repository that is not fixture-scoped. A review
  already caught exactly this: a reconciliation-proof drill originally ran the **global** cross-tenant
  reconciliation sweep, making live provider calls for other tenants. It was fixed to a tenant-scoped
  check before merge.

## Reversal conditions

- A provider's real behaviour diverges from the fixture in a way that certification missed — then the
  answer is real provider-sandbox drills, not live-tenant drills.
- A regulator or customer requires certification evidence tied to a specific real transaction, at
  which point that is a **different artefact** from a certification pack and should be built as one.

## Evidence

- Four sign-off tests: self-sign-off refused, non-PASSED refused, double sign-off refused, valid
  sign-off accepted.
- `CertificationGateIntegrationTest` — production activation blocked (`production_not_certified`),
  allowed after certification plus sign-off, blocked again on expiry, and enforced per configuration.
- `CertificationApiIntegrationTest` — asserts no secrets appear in an exported pack, and cross-tenant
  access is denied.
- The drill suite, including the review-driven fix from global to tenant-scoped reconciliation.
