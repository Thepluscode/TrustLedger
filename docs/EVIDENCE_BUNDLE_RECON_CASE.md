# Reconciliation case bundle (`recon-case-bundle/1`)

The resolution bundle for one reconciliation case. It extends the existing evidence-export path
(`EvidenceService.persist`): the same checksum, the same optional Ed25519 signature over the stored bytes, the
same download and verify endpoints. Only the content is new.

Status: **IMPLEMENTED, tested on PostgreSQL** (`CaseBundleIntegrationTest`). Not production-observed.

## Produce, download, verify

| Step | Call |
|---|---|
| Export | `POST /api/v1/reconciliation/cases/{caseId}/bundle` → `exportId`, `bundleStatus`, `contentHash` |
| Download | `GET /api/v1/evidence/exports/{exportId}/download` |
| Checksum and signature | `GET /api/v1/evidence/exports/{exportId}/verify` |
| Offline, with no TrustLedger code | `python3 scripts/verify_recon_bundle.py bundle.json [source-file ...]` |

Export needs `EVIDENCE_EXPORT` and `RECON_VIEW`. A case with no current run is refused (409).

## Shape

```
kind            RECONCILIATION_CASE_EVIDENCE
bundleVersion   recon-case-bundle/1
bundleStatus    INTERIM while the case is open, FINAL once it is closed
contentHash     sha256 of `content`
content
  case                     ref, title, period, settlement SLA, status, creator
  sources[]                one per imported file: source type, identity, filename, file SHA-256, profile and
                           version, counts (record / accepted / rejected / duplicate), per-currency totals,
                           actor, correlation id, timestamp, and every rejected row with its reason
  run                      run key, ruleset version, counts, summary (exceptions by type, matches by rule,
                           settlement coverage), unresolved value per currency when the run completed
  matches[]                left and right record keys, rule id, rule version, stage, what was compared
                           (at most 20,000 inline; `matchesTruncated` says whether more exist; each source
                           likewise carries `rejectedRowsOmitted`)
  exceptions[]             type, classification, expected, actual, exposure, rule and version, links to the
                           source rows (file hash, row hash, row number), full working history, and the
                           closing decision (reason code, explanation, evidence reference, actor, time)
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

## What the bundle does not claim

The `limitations` block ships inside every bundle. In short: it records what was computed from files the
customer supplied; it does not verify those files against the provider or the bank; matching is
deterministic and rule-based, with no AI; a provider with no settlement file was not checked for
settlement; currencies are never combined; and it is operational evidence, **not an audit opinion, a
certification, or a statement of regulatory compliance**.

## Closing a case

`POST /api/v1/reconciliation/cases/{caseId}/close` succeeds only when the case is reconciled and none of its
exceptions is open. A closed case refuses further imports and runs, which is what allows its bundle to be
called FINAL.
