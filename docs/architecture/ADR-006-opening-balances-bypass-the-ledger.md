# ADR-006: Opening balances currently bypass the ledger — decision required

- **Status:** **Proposed — open question, not yet decided.** Recorded 2026-08-04.
- **Deciders:** Theophilus Ogieva
- **Amends:** [ADR-002](ADR-002-ledger-authoritative-over-provider-records.md), whose corollary
  *"balances are derived views over ledger entries, never independently mutable totals"* is **not
  currently true** for the opening balance.

## Context

ADR-002 is Accepted and states as a corollary that balances are derived views over ledger entries.
`CLAUDE.md` puts it more bluntly: *"the ledger is the source of financial truth; balances are derived
views."*

`AccountController.create` writes an account's opening balance straight into `available_balance` and
`posted_balance` (via the `AccountEntity` constructor) and writes **no ledger entry**. The repository
is injected into that controller for reads only.

Measured during the 2026-08-04 disaster-recovery drill (`docs/RESTORE_TEST_RECORD.md`): an account
showing `posted_balance = 998,900.0000` against net ledger entries of `−1,100.0000`. The 1,000,000
difference is an opening balance that exists nowhere in the journal.

So today: `posted_balance = opening_balance + net_entries`, and the first term is invisible to the
ledger.

## Why this is not cosmetic

1. **Rebuilding balances from the journal produces the wrong answer** for every funded account. That
   rebuild is the classic ledger-first recovery story and the thing an auditor asks for when they
   want to know whether the balance is *justified*, not merely *stored*.
2. **The recovery drill cannot perform the exact balance recomputation Rule 0.8 asks for.** It proves
   the weaker property that no balance sits below its net entries. That weakening is caused by this
   decision, not by the drill.
3. **Money enters the system with no double-entry counterpart.** The journal answers "where did this
   money go" but not "where did it come from", so the system as a whole does not balance — only
   individual transactions do.
4. It contradicts a recorded, Accepted ADR. Either the code or ADR-002 is wrong, and leaving the
   contradiction unrecorded is how a documented guarantee quietly becomes untrue.

## Options

**A. Post opening balances as real double-entry movements.** A funding/equity account per tenant and
currency is debited; the customer account is credited. Balances become fully derivable, system-wide
debits equal credits, and the drill can recompute balances exactly.
*Cost:* introduces the concept of a funding source, i.e. TrustLedger starts modelling where money
comes from. Needs a migration to backfill entries for existing accounts, and a decision about what
the contra account represents when the money originated outside the platform.

**B. Keep direct opening balances; amend ADR-002 to say balances are derived *from opening balance
plus entries*.** Cheapest, honest, and keeps account creation trivial.
*Cost:* permanently gives up exact rebuild-from-journal, and the "balances are derived views" line
must be corrected wherever it appears — including buyer-facing material, where it is currently a
stronger claim than the code supports.

**C. Forbid non-zero opening balances entirely.** Accounts start at zero; funding arrives as a
transfer like any other movement.
*Cost:* every fixture, demo seed and test that opens a funded account must change. Cleanest
invariant, largest blast radius, and it may simply be wrong for a platform whose customers arrive
with existing balances to migrate.

## Recommendation

**A**, but only after the product owner decides whether TrustLedger models external funding at all.
That is a product question, not an engineering one, which is why this ADR is Proposed rather than
Accepted and why the finding was filed rather than silently patched during a recovery drill.

If **B** is chosen, the correction to the "balances are derived views" claim should be made
everywhere it appears in the same change — an ADR that records a weaker guarantee while the pitch
keeps the stronger one is worse than no ADR.

## Trade-offs accepted in the meantime

The gap is **measured and visible** rather than assumed away:
`scripts/verify-restore-integrity.sh` asserts the residual is non-negative (an opening balance can
never be negative, so a balance *below* its net entries means lost credits or gained debits) and
documents in-file why the exact recomputation is unavailable.

## Risks

- Someone reads ADR-002's corollary and builds on the assumption that balances can be rebuilt from
  entries — a reconciliation feature, a migration, an audit response. This ADR exists to intercept
  that.
- The longer non-zero opening balances persist in real data, the larger any backfill under option A.

## Reversal conditions

Revisit immediately if any of these becomes true:

- A customer or auditor asks for balances to be reconstructed from the journal.
- A reconciliation or evidence feature needs system-wide debits == credits.
- The first real tenant is onboarded with migrated balances — the backfill cost grows from that day.

## Evidence

- `docs/RESTORE_TEST_RECORD.md` — the drill that found it, with the observed figures.
- `scripts/verify-restore-integrity.sh` — the check that encodes the current, weaker invariant.
- `backend/src/main/java/com/trustledger/api/AccountController.java` — the write path, verified to
  persist an opening balance with no ledger entry.
