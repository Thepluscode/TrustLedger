# Ledger Engine

## Core model

The ledger is built around two tables/classes:

- `LedgerTransaction`
- `LedgerEntry`

A `LedgerTransaction` groups immutable `LedgerEntry` rows. Every posted transaction must balance.

## Invariants

```text
Every transaction has >= 2 entries.
Every entry has a positive amount.
Every transaction uses one currency.
Sum(DEBIT) == Sum(CREDIT).
Ledger entries are append-only.
Corrections use reversal entries.
```

## Internal transfer

```text
Debit:  Source account       £100
Credit: Destination account  £100
```

## Transfer with platform fee

```text
Debit:  Sender available account      £101
Credit: Receiver available account    £100
Credit: Platform fee revenue account  £1
```

## Reservation flow

High-risk transfer:

```text
available -> pending reservation
fraud case opens
analyst approves or rejects
```

Approval:

```text
pending reservation consumed
source posted balance debited
receiver posted and available credited
```

Rejection:

```text
pending reservation released
available balance restored
```

## Reversal flow

Never edit original ledger entries.

```text
Original:
Debit A £100
Credit B £100

Reversal:
Credit A £100
Debit B £100
```

## PostgreSQL locking rule

For real persistence, account rows must be locked during money movement using deterministic lock order. PostgreSQL `SELECT ... FOR UPDATE` locks selected rows against concurrent updates, which is the right primitive for protecting transfer-critical sections.
