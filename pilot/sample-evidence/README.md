# Sample Evidence Packs

Representative outputs of the TrustLedger evidence engine (`backend/.../app/EvidenceService.java`).
These match the **exact schema** the API produces — they are illustrative values, not a live export.

- `fraud-case-evidence.sample.json` — a fraud-case pack: case summary, **fraud signals**, linked
  cases (same-beneficiary), and the held transfer. (`POST /api/v1/evidence/fraud-cases/{caseId}`)
- `ledger-evidence.sample.json` — a ledger proof: every debit/credit entry plus `totalDebits`,
  `totalCredits`, and `balanced: true` — proving **debits == credits**.
  (`POST /api/v1/evidence/ledger/{ledgerTxId}`)

## Integrity
The real export is **SHA-256 checksummed**. The checksum is stored on the export record and returned
in the `X-Evidence-Checksum` response header on download; it is verifiable against the downloaded
bytes (tested: `EvidenceExportIntegrationTest`). Exports are tenant-scoped, audited, and can be placed
under **legal hold**, which blocks deletion (also tested).

To produce a real one against a running instance:
```bash
# (analyst with EVIDENCE_EXPORT permission, on a real fraud case)
curl -X POST "$BASE/api/v1/evidence/fraud-cases/$CASE_ID" -H "Authorization: Bearer $TOKEN"
curl -OJ "$BASE/api/v1/evidence/exports/$EXPORT_ID/download" -H "Authorization: Bearer $TOKEN"  # X-Evidence-Checksum header
```
