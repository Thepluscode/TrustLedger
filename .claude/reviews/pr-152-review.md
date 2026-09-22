# PR Review: #152 — Read-only cross-provider reconciliation casework (pilot slice)

**Reviewed**: 2026-09-22
**Author**: Thepluscode
**Branch**: feat/recon-casework (de87f69) → main
**Decision**: COMMENT (draft). Would be REQUEST CHANGES for the one HIGH below.

**Independence caveat.** The reviewer wrote this PR. A second read by the same model is correlated
analysis, not an independent verifier. The findings below were each *tried* against a running system,
not inferred from the diff; the negative results (things looked at and found fine) carry less weight.

## Summary
The money-path boundary, tenant scoping, atomicity and determinism claims hold up on re-reading and on
probing. One functional gap makes the console unusable for the role it was built for; three medium gaps
are completeness, not safety. `main` moved one commit (`83cb8d6`) with no overlapping files.

## Findings

### CRITICAL
None. No unscoped query reaches tenant data by id alone (verified every `CaseworkStore` query and both
controllers); the only unscoped store methods (`currencyTotals`, `insertCurrencyTotals`, `insertRunTotals`)
take ids that came out of a tenant-scoped row. No path from `reconciliation.casework` to the ledger, a
rail or the outbox (`CaseworkBoundaryTest`). Storage keys are built from tenant id and SHA-256, never from
a filename. Comment and evidence bodies are bounded and rendered as text.

### HIGH
1. **`RECON_OPERATOR` cannot assign from the console.** `frontend/app/reconciliation/[issueId]/page.tsx`
   treats the role as `canManage` and calls `api.listUsers()`, but `GET /api/v1/users` requires
   `USER_MANAGE`, which the role does not hold. **Probed:** `RECON_OPERATOR GET /users -> 403`. Result:
   every issue page shows "Unable to load assignable owners" and the owner dropdown contains only
   "Unassigned" for the role the slice was built for. The lifecycle test assigned via the API with a
   known user id, so it never saw this. Fix: a read-only assignee list endpoint gated on
   `RECON_ISSUE_WORK` (id, email, role), or grant the role `USER_VIEW` if such a permission exists.

### MEDIUM
2. **Attached issue evidence cannot be retrieved.** `addEvidence` stores the file and the activity cites
   its storage key, but no endpoint serves it back; the only `retrieve` path is evidence *exports*. A
   reviewer of a WRITTEN_OFF decision can see that a file was attached and its hash, not the file.
   Needs a tenant- and issue-scoped download (`GET /issues/{id}/evidence/{seq}`), with a content-type
   that forces download.
3. **`ReconMetrics.tenantDenied()` is never called.** The brief asks for a tenant-boundary-denial
   metric; the counter exists and stays at zero forever. Either wire it where scoped lookups return
   empty for a present-but-foreign id (which requires an unscoped existence check, so probably not) or
   delete it and say the metric is not provided.
4. **`overdue=true` list filter has no test.** **Probed:** the JPQL `cast(:overdueAt as timestamp)`
   branch executes (200, filters correctly) so it is not a defect, but no test would notice if it broke.
   Add a positive twin (an issue with `dueAt` in the past appears; one in the future does not).

### LOW
5. `CaseworkStore` javadoc says every query carries `tenant_id`; three do not (see CRITICAL note). Fix the sentence.
6. `ReconciliationResolutionService.assign` with `ownerUserId = null` on an issue that is already OPEN and
   unowned writes an `UNASSIGNED` activity and audit row for a no-op. Refuse with 409 or return unchanged.
7. `Fields.instant` rejects offset timestamps (`2026-08-03T10:15:00+01:00`); real provider exports use
   them. The rejection message is clear, so this is a scope limit, not a bug. Document in the profile table.
8. `CaseBundleService` lists up to 200,000 rejected rows and matches inline; a large case can yield a
   bundle well past what a browser download or the 16 MB evidence path is comfortable with. Add a size
   guard or a summarised mode before a customer with 100k-row files.
9. `cases/page.tsx` navigates with `window.location.href`; the rest of the console uses the router.

## Validation Results (CI run on de87f69, same SHA as local HEAD)

| Check | Result |
|---|---|
| Backend `mvn test` (608 tests, 129 classes, 0 skipped) | Pass |
| Migrations over existing data (V38 baseline, V54 backfill asserted) | Pass |
| Frontend typecheck / Vitest (9) / build | Pass |
| gitleaks / Trivy / SBOM / IaC / repo validation | Pass |
| Review probes (overdue filter, RECON_OPERATOR user list) | Run locally on PostgreSQL; see findings 1 and 4 |

## Files Reviewed
Read in full: ImportService, CaseworkStore, CaseService, RunService, CaseBundleService,
ReconciliationResolutionService, ReconciliationCaseController, ReconciliationController,
ReconciliationEngine, Fields, ProviderTransactionsV1, InternalExpectedV1 (provider lowercasing),
V50–V55, RestExceptionHandler diff, RolePermissions, frontend issue/case/list pages, recon.ts.
Skimmed: tests, docs, scripts.
