# Restore Test Record

Evidence for doctrine **Rule 0.8 (Data Resilience)**: *"No system that persists customer or
operational data may be called pilot-ready or production-ready until backup, retention, restoration,
integrity validation and recovery are designed AND TESTED."*

Prior verdict: **BACKUPS CONFIGURED BUT UNPROVEN** — `scripts/disaster-recovery-drill.sh` existed and
its own header said *"a backup that is never restored is theatre"*, but no run had ever been recorded.

---

## Drill: 2026-08-04

| | |
|---|---|
| **Scenario** | Total loss of the primary database (`DROP DATABASE … WITH (FORCE)`) |
| **Destruction proven** | `SELECT count(*) FROM pg_database WHERE datname='trustledger'` → **0** |
| **Dataset** | 20 accounts · 1,100 transfers · 2,200 ledger entries · 3,300 audit rows · 1,100 idempotency keys · 3 audit checkpoints |
| **How seeded** | Real traffic through the live HTTP API (`scripts/load_transfer_probe.py`), not SQL fixtures — so the restored state is state the application actually produced |
| **Backup** | `scripts/backup-postgres.sh` → 819,937-byte custom-format dump, **0.3 s** |
| **Restore** | `scripts/restore-postgres.sh` into a freshly created database, **0.8 s** |
| **Integrity** | `scripts/verify-restore-integrity.sh` → **10/10 invariants held**, exit 0 |
| **Data loss vs backup point** | **Zero** — ledger entries 2,200 → 2,200, audit rows 3,300 → 3,300, ledger sum 22,000.0000 → 22,000.0000 |

### Achieved, measured

- **RTO (restore component): ~1.1 s** for this dataset (0.3 s backup is not on the recovery path;
  0.8 s restore is). **This is not a full RTO.** It excludes failure detection, decision-to-recover,
  provisioning a database host, application restart, and the mandatory
  lock-execution-before-resume sequence below. It scales with data volume; 819 KB is a small database.
- **RPO = the backup interval.** `pg_dump` snapshots only; **no WAL archiving / PITR is configured**,
  so worst-case loss is everything since the last dump. This is a structural property of the current
  setup, not a measurement. Financial state cannot be reconstructed casually, so **PITR is required
  before real money moves** — daily snapshots alone are insufficient.

### Integrity checks that ran (all passed)

Row counts are not integrity. Each of these is a financial invariant that must survive recovery:

1. Every journal balances per transaction and currency
2. No account's posted balance sits below its own net ledger entries
3. No orphan ledger entries (transaction reference)
4. No orphan ledger entries (account reference)
5. No cross-tenant ledger entries — invariant 12 survives the restore
6. Idempotency keys unique per tenant — a replayed request after recovery cannot pay twice
7. No duplicate ledger-transaction idempotency keys — nothing executed twice
8. Audit rows retain an actor — the trail is still attributable
9. Audit checkpoint chain contiguous — the tamper-evidence span is intact
10. Dataset non-trivial — guards against an empty restore passing every check above vacuously

**The validator was proven to fail.** A validator never observed failing is decoration. A restored
copy was deliberately corrupted by deleting **one** CREDIT entry out of 2,200; the drill detected it
(`ledger balanced per transaction: 1 violation`) and exited 1.

**Incidental confirmation:** the first corruption attempt was *rejected by the database* — the V35
ledger-immutability trigger survived `pg_restore` and refused the DELETE. Dropping the trigger first
was required to corrupt the data at all, which is itself evidence that the restore recovers the
guards and not merely the rows.

---

## Finding: opening balances bypass the ledger

**Discovered by this drill, and it outranks the drill result.**

`AccountController` writes an account's opening balance directly into `available_balance` /
`posted_balance` **with no corresponding ledger entry**. Observed in the restored data: an account
with `posted_balance = 998,900.0000` whose net ledger entries total `−1,100.0000` — the 1,000,000
difference is an opening balance that exists nowhere in the journal.

**Why this matters here, specifically.** The project's stated rule is *"the ledger is the source of
financial truth; balances are derived views."* Today they are not fully derived: money enters the
system without a journal entry, so **rebuilding balances from the ledger — the classic ledger-first
recovery story — would produce a wrong balance for every funded account.** It also means this drill
*cannot* perform the exact balance recomputation Rule 0.8 asks for; it proves the weaker but true
property that no balance sits below its net entries.

**Not fixed here, deliberately.** The fix is to post opening balances as real double-entry movements
(debit a funding/equity account, credit the customer account), which is a decision about whether
TrustLedger models external funding at all — a money-semantics change that belongs to the product
owner, not to a recovery drill. Filed rather than silently patched.

---

## Not yet done (Rule 0.8 gaps that remain)

- **Nothing schedules this drill.** It has now run once, by hand. Rule 0.8 treats a drill older than
  90 days as stale, so an unscheduled drill decays back to UNPROVEN.
- **No PITR / WAL archiving** — see RPO above.
- **Backups are not stored outside the primary failure domain.** The dump was written to local disk;
  same host is not disaster recovery.
- **Object storage (evidence packs) was not exercised** — `scripts/backup-minio.sh` exists, unrun.
- **The lock-execution-before-resume sequence was not rehearsed.** Per project doctrine the order is:
  restore → **lock external execution** → validate ledger invariants → reconcile providers → replay
  safe events → classify ambiguity into the exception queue → operator approval → resume. This drill
  covered *restore* and *validate ledger invariants* only. Provider reconciliation after a restore is
  the dangerous, unrehearsed part.

**Honest verdict after this drill: RESTORE TESTED FOR DEVELOPMENT.** Not "controlled pilot ready" —
that needs the drill scheduled, backups off-host, and the resume sequence rehearsed.
