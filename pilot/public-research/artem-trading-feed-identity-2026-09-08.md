# Public research evidence — downstream deduplication and permanent unique record ID

## Source

- **Author:** Artem Ermakov, Senior Technical Product Manager — Core Ledger, Balances &
  Reconciliation; Trading & Execution Infrastructure
- **Platform:** LinkedIn
- **Post:** [A platform is defined by what it refuses to guarantee](https://www.linkedin.com/feed/update/urn:li:activity:7502991720650010624/)
- **Published:** 2026-09-08 07:30:25 UTC, derived from the LinkedIn activity identifier and
  consistent with the platform's `3h` age label when retrieved
- **Retrieved:** 2026-09-08 11:31 UTC through the authenticated LinkedIn post
- **Evidence class:** first-person public architecture narrative; single source
- **Confidence:** high that the author published this account; low as a standalone market or
  commercial signal because it is one uncorroborated public account

The source is linked rather than reproduced in full. The image states the narrower platform
contract directly: “Every record has a permanent, unique ID.” It does not use the term
“immutable identity.”

## What this establishes

- In the FIX/event-based case, the ordinary gateway and drop-copy feed can report the same fill.
- The central deduplicator drops the second report and gives downstream consumers the stronger
  contract that each fill appears once.
- In the derivatives/state-stream case, two active connectors can deliver the same records and
  downstream consumers deduplicate locally by record ID.
- The platform contract narrows from once-per-fill delivery to every record having a permanent,
  unique ID.
- The image names middle office, yield calculation, commission and back-office consumers. It does
  not name developers as a downstream consumer group.

The source does **not** establish that funded internal engineering is the principal commercial
alternative. That may be a hypothesis or come from separate interview evidence, but it must not be
attributed to this post.

## What remains unknown

This post does **not** establish any of the missing Exness interview fields:

- a payment or settlement discrepancy;
- internal-ledger state versus provider, bank or settlement state;
- system sequence, hands-on investigation time or elapsed resolution time;
- incident frequency;
- financial or operational exposure;
- data access, commercial ownership or willingness to pay.

It therefore does not qualify the 2026-08-31 conversation, does not populate
`kill-test-tracker.csv`, and does not advance the 0/3 or 0/25 gates. The anonymised reconstruction
remained a separate obligation and was sent on 2026-09-17; its delivery record is in
[`../interviews/exness-final-reconstruction-2026-09-17.md`](../interviews/exness-final-reconstruction-2026-09-17.md).

## Product implication

The following is a TrustLedger inference from the source, not a claim made by the post. TrustLedger
must not promise universal exactly-once delivery. Its contract is:

```text
SOURCE EVENT
    ↓
stable source identity
    ↓
canonical TrustLedger identity
    ↓
duplicate detection
    ↓
idempotent state transition
    ↓
idempotent financial effect
    ↓
traceable evidence of what happened
```

**Duplicate delivery is acceptable. Duplicate financial effect is not.** Identifiable duplicate
evidence must not silently produce a second state transition or financial mutation, and the
evidence must show every delivery and the one resulting effect. When sources disagree rather than
duplicate one another, TrustLedger must keep the ambiguity explicit until reconciliation resolves
it.

This is architecture input, not buyer validation. It aligns with the accepted transactional-outbox
decision: delivery is at least once and consumers are idempotent.

## Public engagement

**Status: SENT 2026-09-17 at approximately 17:22 BST.** LinkedIn displayed the comment under
Theophilus Ogieva with the platform age `now`, emptied the composer and increased the post count
from two to three comments. The observed comment contained no TrustLedger mention or pitch:

> Strong framing. The architectural change is one part; withdrawing the contract is the expensive
> part. Moving from “each fill appears once” to “every record has a permanent identity” turns a
> fragile global guarantee into a composable local one. How did you verify every consumer had
> adopted idempotent handling before retiring the central deduplicator?

This is verified public engagement evidence only. It remains separate from customer validation and
does not change any interview or market-gate field.
