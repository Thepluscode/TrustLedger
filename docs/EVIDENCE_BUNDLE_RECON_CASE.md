# Reconciliation case bundle (`recon-case-bundle/2`)

The resolution bundle for one reconciliation case. It extends the existing evidence-export path
(`EvidenceService.persist`): the same checksum, the same optional Ed25519 signature over the stored bytes, the
same download and verify endpoints. Only the content is new.

Status: **IMPLEMENTED, tested on PostgreSQL** (`CaseBundleIntegrationTest` 12/12). Not production-observed.

## Produce, download, verify

| Step | Call |
|---|---|
| Export | `POST /api/v1/reconciliation/cases/{caseId}/bundle` → `exportId`, `bundleStatus`, `contentHash` |
| Download | `GET /api/v1/evidence/exports/{exportId}/download` |
| Download with original sources | `GET /api/v1/evidence/exports/{exportId}/package` → ZIP containing `bundle.json` and `sources/{fileSha256}` |
| Checksum and signature | `GET /api/v1/evidence/exports/{exportId}/verify` |
| Offline from one file | `python3 scripts/verify_recon_bundle.py trustledger-proof-{exportId}.zip` |
| Offline, with no TrustLedger code | `python3 scripts/verify_recon_bundle.py bundle.json [source-file ...]` (see [Re-deriving the findings](#re-deriving-the-findings)) |

Export needs `EVIDENCE_EXPORT` and `RECON_VIEW`. A case with no current run is refused (409).
The package download needs `EVIDENCE_EXPORT` and the export's tenant scope (reconciliation cases are
tenant-wide). It checks the
stored export checksum and each original source's length and SHA-256 against the recorded import before
returning the archive. Missing or changed source bytes fail closed (409); a package over 100 MiB of
uncompressed bundle and distinct source bytes is refused (409). The ZIP uses digest names, so original
filenames cannot create archive paths. The verifier refuses missing, extra, renamed or duplicate entries.
This packages customer-supplied files; it does not authenticate them with a bank or provider. The separate
`/verify` endpoint checks the stored export's optional signature; the offline replay checks content and
source derivation, not the export's detached signature.

## Shape

```
kind            RECONCILIATION_CASE_EVIDENCE
bundleVersion   recon-case-bundle/2
bundleStatus    INTERIM while the case is open, FINAL once it is closed
contentHash     sha256 of `content`
content
  case                     ref, title, period, settlement SLA, status, creator
  sources[]                one per imported file: source type, identity, filename, file SHA-256, profile and
                           version, counts (record / accepted / rejected / duplicate), per-currency totals,
                           actor, correlation id, timestamp, and every rejected row with its reason
  run                      run key, ruleset version, counts, summary (exceptions by type, matches by rule,
                           settlement coverage), unresolved value per currency when the run completed
  runProofs[]              since V60: one write-once, checksummed snapshot per run of its source membership,
                           case settings, fee terms and complete findings, including findings seen in earlier runs
  runsWithoutProof[]       legacy runs for which exact historical inputs were not preserved
  matches[]                left and right record keys, rule id, rule version, stage, what was compared
                           (at most 20,000 inline; `matchesTruncated` says whether more exist; each source
                           likewise carries `rejectedRowsOmitted`)
  exceptions[]             type, classification, expected, actual, exposure, rule and version, links to the
                           source rows (file hash, row hash, row number), full working history, and the
                           closing decision (reason code, explanation, evidence reference, actor, time)
  findingInputs            (since /2) what every exception was computed from:
    records[]              each record an exception names: source type and identity, file SHA-256, row number,
                           rowSha256, the raw row text, and the values the rules read (event type, provider,
                           references, times, currency, gross, fee, net, statuses)
    feeSchedules[]         the latest run's preserved fee terms (legacy runs use terms on record at export):
                           provider, currency, basis points, flat fee, cap, tolerance, effective from
  unresolvedNowByCurrency  what is still undecided at export time
  limitations[]            what this bundle is not
export          exportedAt, exportedBy, correlationId   (outside the hash)
```

## Determinism

`content` is a pure function of stored data. Field order is fixed in code, every list is sorted by a stable
key, and every amount is a string, so no number formatting can differ between serialisers. Exporting the
same case state twice gives the same `contentHash` with a different `exportId`. Any operator action changes
it, because the working history is inside the hash.

To verify by hand: serialise `content` compactly, keys in document order, UTF-8, and SHA-256 it.

## Re-deriving the findings

`scripts/verify_recon_bundle.py` shares no code with TrustLedger: it is the ruleset (`recon-rules/1.2.0`)
written a second time, in Python, from its specification. For a `/2` bundle it checks, beyond the hash:

| Check | Fails when |
|---|---|
| Each listed row hashes to its `rowSha256` (SHA-256 of source type, source identity and raw row, each followed by U+001F) | the row text was changed |
| Each row's file is one of the bundle's sources, and the row is at its cited CSV record number (or is the single JSON event) in the supplied source file | a row, its hash or its cited row number was rewritten (needs the file) |
| Each record's currency equals the profile's currency column, found by name in the supplied file's header (TrustLedger reads columns by name, so their order may vary); without the file, the code must at least appear in the row | a record and its finding were rewritten together with a different currency (exit 1 with the file, 3 without) |
| For `internal-expected`, `provider-transactions`, and `provider-settlement` v1, each cited record's references, status, times, gross, fee and net agree with the named columns in its supplied CSV row | a canonical value was replaced by a different field from the same row |
| For those three profiles, the run's import IDs and file hashes agree with the source manifests; every CSV record is accounted for, including listed rejections; payment and simple settlement matching are replayed over the full source files | a missing payment or settlement finding has a counterpart in the supplied run |
| A payment mismatch cites the exact pair selected by R1/R2/R4; a fee mismatch cites a representative charge; a settlement comparison cites its unique paired line and charge | two unrelated rows happen to produce the claimed arithmetic or timing |
| A duplicate-delivery finding cites every delivery of its provider event; a duplicate-financial-effect finding cites every distinct successful charge after delivery deduplication | an extra delivery was omitted, or two deliveries were mistaken for two charges |
| For simple CSV runs, a status-transition finding cites adjacent charges in the full deduplicated history after a terminal state, and takes its exposure from the representative charge | pending becomes success, nonadjacent events are cited, or the amount comes from the wrong event |
| For simple CSV runs, a two-record `D-REFUND` mismatch has exactly one possible internal and provider refund allocation under the reference rules | the rows are unrelated or multiple allocations fit; ambiguity returns exit 3 |
| For a case with one run, the exported exception count, types, and exposure totals by currency agree with the run inventory; each exception ID is unique and its run and ruleset references agree | a finding is removed, duplicated, retyped, or attributed to another run while its amount remains plausible |
| For a single run using the three generic CSV profiles, canonical JSON provider events, and supported Adyen Settlement Details v1 or Checkout.com Financial Actions v2, an independent Python pass enumerates every finding from all accepted source rows and compares type, rule, currency, amount, and exact source rows with the bundle | a finding is omitted together with its recorded count, type, and exposure totals; a new unrelated discrepancy exists in the source but not the bundle; a false clean run is declared |
| For `provider-event-json/1`, each one-body import is parsed using the provider transaction field contract; an accepted event must produce its canonical record and a rejected event must remain unparseable | an event value was changed, a valid event was hidden as rejected, or a malformed event was claimed as accepted |
| For Adyen Settlement Details v1, every accepted raw report row, monetary component, journal identity, timestamp, FX rate and unreconciled reason agrees with `providerEvidence`; gross, fee and net are independently summed and rounded once in the charge currency | a composite fee or settlement line was built from different rows or amounts, or a report row was omitted |
| For Checkout.com Financial Actions v2, every accepted raw report row, component, action grouping, payout and unreconciled reason agrees with `providerEvidence`; gross, fee and net are independently summed at source precision and rounded once | a reported settlement, refund or chargeback was built from different rows or amounts, or a report row was omitted |
| With Checkout.com v2, the complete finding replay also checks settled refund allocation, refund settlement absence and full chargeback/reversal history | a refund event has no payout, a dispute debit was concealed, or a related finding was removed with its run counts and totals |
| For profiles without a complete source replay, each record amount appears as a number among its row's fields | not a failure: reported, exit 3. Composite amounts require a supported source replay |
| Every exception's records are listed, and its currency and amount are recomputed from them and the fee schedules | an exception, a record value or a schedule was changed |
| For source-replayable runs, `expected`, `actual`, and severity are independently reconstructed from source rows and the fee terms of that run | a rehashed edit to the issue and its run proof agrees internally but states a false fee expectation or outcome |

Exit 0: every declared source file was supplied, its listed rows were checked, and every exception was
reproduced. Exit 1: something failed. Exit 3: nothing failed, but proof is incomplete: a source file was
not supplied, a type or ruleset is unsupported, the full status history cannot be replayed, or an amount
was not found verbatim in its row. A `/2` bundle without
`findingInputs` fails; a `/1` bundle is checked for integrity only and returns exit 3.

For simple CSV and supported provider-report runs, a singleton `REFUND_MISMATCH` is also checked against the full input set when no
plausible refund counterpart exists. A two-record `D-REFUND` mismatch is confirmed only when its internal and
provider rows uniquely select each other; competing candidates return exit 3. `MISSING_SETTLEMENT` checks the representative successful charge, settlement coverage, due date,
and absence of a matching line. `UNMATCHED_SETTLEMENT_ITEM` checks that no charge exists for the line;
a possible duplicate settlement line returns exit 3 because line ordering is not replayed.
For Checkout.com v2, `MISSING_REFUND_SETTLEMENT`, `D-SETTLED-REFUND` and chargeback findings are replayed
from every supplied source row. Adyen v1 rows with duplicates or rejected rows, a truncated row inventory,
or unsupported timestamp zones remain incomplete. Canonical JSON event imports with a row marked `DUPLICATE` remain incomplete. Checkout.com reports with duplicate/rejected rows, a truncated row inventory,
or timestamp zone evidence (account setting, zone column, UTC column or offset) remain incomplete. The source files are the verifier's trust boundary: their hashes
show consistency with the bundle, not that a bank or provider independently vouched for the files.

A case with earlier runs returns exit 0 only when every run has a V60 immutable proof and its source profile
can be fully replayed. The verifier reconstructs every run from that run's source membership and fee terms,
compares its complete finding set, and binds each case issue to the first run that found its type and source
records. A recurring finding stays one issue even if a later run computes a different amount; new findings
must have their own issues. Any pre-V60 run without a snapshot remains incomplete (exit 3). For a single run,
the count and type checks bind the list to the
recorded run inventory and the per-currency exposure totals. For the three generic CSV profiles, the full
source replay closes the coordinated-omission gap: it derives the expected finding set without using the
bundle's exception list or run totals. It refuses ambiguous refund allocation and duplicate settlement-line
ordering, returning exit 3. Supported Adyen Settlement Details v1, Checkout.com Financial Actions v2,
and canonical JSON provider events have the same full finding replay.

Controls: `python3 scripts/test_verify_recon_bundle.py` checks sixty-eight cases, including edits to a real
export of the SYNTHETIC ACME-2026-08 case (`scripts/fixtures/acme-interim-bundle.FIXTURE.json`). It recomputes
the hash for each edit and requires the change to be caught. `CaseBundleIntegrationTest` runs the verifier against fresh
PostgreSQL exports and requires all seven ACME exceptions, a terminal-status transition, a unique refund mismatch,
a truly clean case, four payment comparison/status findings, and three settlement net/absence/orphan findings
to reproduce. A fresh V60 two-run PostgreSQL case also proves 7 first-run findings, 12 second-run findings,
five newly raised issues and seven recurring ones; removal of an issue or a run finding fails even after
recomputing hashes. `ProviderReportImportIntegrationTest` additionally exports
Checkout.com v2 bundles and proves a settled refund, a missing refund payout, a refund amount mismatch and
an unreturned chargeback from the 55-row published sample and a 54-row variant. Rehashed edits to a derived
chargeback amount or a provider component, and a coordinated omission of the chargeback finding, are rejected.
The Adyen batch 134 sample similarly replays 63 rows into 58 lines and 56 findings, including fees summed
from several columns. Rehashed edits to a derived fee or provider component, and a coordinated omitted finding,
are rejected.
The canonical event feed integration case supplies three CSV files and seven JSON delivery bodies (six accepted,
one rejected) to the verifier. It reproduces all seven findings and rejects rehashed changes to an accepted
event amount or the malformed event's rejected status. A second case gives two feeds identical event bytes;
the same file hash names two imports, so each cited record is bound by import ID and both findings remain
independently replayable.

## Verifier code boundary

`verify_recon_bundle.py` exceeds 1,200 lines because it still owns the offline CLI,
source provenance checks and verdict composition. Money finding replay lives in
`replay_simple_recon_findings.py`, `replay_adyen_report.py` and `replay_checkout_report.py`;
statement reconstruction has its own `replay_recon_statements.py` boundary.
On the next material verifier change, extract CSV/JSON source parsing and run membership into a
`verify_recon_sources.py` module, keeping this file as the CLI and verdict coordinator. Preserve the
positive and tampering controls before changing that boundary.

## What the bundle does not claim

The `limitations` block ships inside every bundle. In short: it records what was computed from files the
customer supplied; it does not verify those files against the provider or the bank; matching is
deterministic and rule-based, with no AI; a provider with no settlement file was not checked for
settlement; currencies are never combined; the independent verifier reconstructs financial statements
but does not independently regenerate the incident's explanatory prose or authenticate its operator actions;
and it is operational evidence, **not an audit opinion, a
certification, or a statement of regulatory compliance**.

## Closing a case

`POST /api/v1/reconciliation/cases/{caseId}/close` succeeds only when the case is reconciled and none of its
exceptions is open. A closed case refuses further imports and runs, which is what allows its bundle to be
called FINAL.
