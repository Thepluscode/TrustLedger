# Demo: a cross-provider reconciliation case, start to evidence (about 10 minutes)

**What this is.** A walkthrough of the read-only reconciliation slice on synthetic data. **What it is
not.** A customer result, a production deployment, or an integration with any real provider. Say so at the
start. The providers are called `provider-a` and `provider-b` so nothing reads as a claim about a real one.

## Before the call

```bash
# backend (needs Postgres) with the slice switched on, and the console
RECON_CASEWORK_ENABLED=true mvn -f backend/pom.xml spring-boot:run
(cd frontend && npm run build && npx next start -p 3000)

# load the case but do not run it; sign in with the printed tenant / email / password
API=http://localhost:8080 ./scripts/seed_acme_case.sh
```

Open **Money → Recon cases → ACME-2026-08**.

## The story

> ACME takes payments through two providers. Month end: the ledger says one thing, the providers another,
> and the settlement file a third. Which payments are actually wrong, how much is at stake in each
> currency, and can you prove what you did about it?

| Step | Do | Say |
|---|---|---|
| 1 | Show the four imported files | Each file was stored unchanged, with its SHA-256, before we read a single row. Totals are per currency. |
| 2 | **View rejected** on provider-b transactions | Row 7 has an amount of `12.3.4`. We rejected that row and kept the rest, and we tell you why. |
| 3 | Point at the blocker, then **Acknowledge** | You cannot reconcile past a rejected row without someone owning that decision. It is recorded. |
| 4 | **Run reconciliation** | Deterministic rules only. No AI decides what matched. Same files, same ruleset, same answer. |
| 5 | Read the cards | 30 records, 10 of 11 matched, 7 exceptions. **£50.60** and **NGN 45,000.00** at stake, shown separately; we never add currencies together. |
| 6 | Point at settlement coverage | provider-a gave us no settlement file, so we did **not** check its settlement, and we say so instead of implying it is clean. |
| 7 | Click **fee mismatch** | Expected 1.80 under the agreed 150 bps, charged 2.40. The rule and its version are on the exception. The source row is cited by file hash and row number. |
| 8 | Assign → Move to investigating → add a comment | Every step is appended to a history that cannot be edited, even by us. |
| 9 | Pick *Provider corrected*; show the button is blocked | Some decisions need evidence. Attach the credit note, select it, explain, confirm. |
| 10 | Back to the case → **Export bundle** | INTERIM because six exceptions are still open. It becomes FINAL when the case is closed. |
| 11 | Download, then run the verifier | `python3 scripts/verify_recon_bundle.py bundle.json backend/src/test/resources/fixtures/acme-2026-08/*.csv` — a 50-line script with none of our code. It reproduces the hash and confirms the files. Edit one number in the JSON and it fails. |
| 12 | Open the `limitations` block in the JSON | It says what this is not: not an audit opinion, not a certification, not verified against the provider. |

## If asked

- **Does it connect to our providers?** No. It reads files you export. Provider connections come after a pilot proves the files are worth reconciling.
- **Does it move money?** No. There is no code path from this module to a ledger posting, a transfer or a payment rail, and a test fails the build if one appears.
- **Is this in production anywhere?** No. Tested locally and in CI on synthetic data. No customer data has been through it.
- **How big a file?** 25 MB and 200,000 rows per file. The engine reconciled 199,000 records in about 3 seconds on a laptop.
