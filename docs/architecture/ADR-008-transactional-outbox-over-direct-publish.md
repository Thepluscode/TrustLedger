# ADR-008: Events go through a transactional outbox, never a direct publish

- **Status:** Accepted (recorded 2026-08-04; the decision itself predates this record)
- **Deciders:** Theophilus Ogieva

## Context

Business logic that moves money also needs to tell the outside world it happened. The obvious
implementation — commit the transaction, then call `kafka.send(...)` — has a failure window that
cannot be closed by ordering the two operations differently:

- **Publish inside the transaction, before commit:** the broker accepts an event describing a
  transfer that then rolls back. Consumers act on money that never moved.
- **Publish after commit:** the process dies between commit and send. The money moved and nobody was
  told. Silent, permanent, and invisible — there is no record that the event was owed.
- **Publish inside the transaction and treat a broker failure as fatal:** the broker's availability
  becomes the ledger's availability. A Kafka outage stops payments.

There is no ordering of "write to database" and "write to broker" that makes two systems commit
atomically. The window can only be removed by having one durable write, not two.

## Decision

Business logic writes an **outbox row in the same database transaction** as the state change it
describes. It never calls the broker.

A separate scheduled publisher (`OutboxPublisher`) reads pending rows and sends them, marking a row
`PUBLISHED` **only after the broker acknowledges** — it blocks on the ack rather than assuming
success. Delivery is therefore **at-least-once**, and consumers must be idempotent.

If the publisher dies mid-flight, the row stays pending and is retried. If the broker is down,
payments continue and the backlog drains later. The pending backlog and the age of its oldest entry
are surfaced in the monitoring snapshot, and a stuck outbox raises a reconciliation issue — an
undelivered event is a visible defect, not a silence.

## Options considered

**Direct publish after commit.** Rejected: see the failure window above. This is the default that
looks fine until the first crash between two lines.

**Two-phase commit across PostgreSQL and Kafka (XA).** Rejected: operationally heavy, poorly
supported in this stack, and it makes the broker a participant in every money transaction — the
coupling the outbox exists to avoid.

**Change-data-capture off the WAL (Debezium).** Rejected *for now*, not on principle: it removes the
publisher entirely and is a genuinely good answer at scale, but it adds a connector to operate and
couples event shape to table shape. The outbox row is an explicit contract; a CDC stream of the
`transfers` table is not.

**Exactly-once semantics.** Rejected as a goal: it would require transactional coupling to the
broker. Idempotent consumers are cheaper and more honest than a guarantee that quietly degrades.

## Trade-offs accepted

- **At-least-once means duplicates.** Every consumer must be idempotent; this is a contract, not an
  implementation detail, and it is stated wherever the event stream is documented.
- **Latency**: events are published on a poll interval (2 s default), not instantly. Acceptable
  because nothing on the money path waits for an event.
- One more moving part to monitor, and it is monitored — backlog size, oldest pending age, and a
  reconciliation issue when the backlog is stuck.

## Risks

- A future feature publishes directly "just this once" for latency, reintroducing the window in one
  code path. The rule in `CLAUDE.md` — *"never publish to Kafka inside business logic and hope"* —
  exists to catch that in review.
- The publisher marking rows `PUBLISHED` optimistically (without waiting for the ack) would silently
  convert at-least-once into at-most-once. The current code blocks on the ack; a test asserts real
  delivery through a real broker.

## Reversal conditions

- Outbox publish latency becomes user-visible — move to CDC or a push-based publisher rather than
  shortening the poll interval indefinitely.
- The backlog cannot be drained fast enough at peak, which means the publisher needs partitioning,
  not removal.
- A move to a broker with genuine transactional integration to PostgreSQL would make this ADR worth
  re-examining, though the coupling objection would still stand.

## Evidence

- `OutboxPublisher` — scheduled, blocks on broker ack, marks `PUBLISHED` only on confirmed delivery.
- Testcontainers-Redpanda integration test — proves real delivery against a real broker **and**
  replay-safety.
- Monitoring snapshot — pending count and oldest-pending age per tenant.
- `ReconciliationService` — stuck-outbox check raises a reconciliation issue.
