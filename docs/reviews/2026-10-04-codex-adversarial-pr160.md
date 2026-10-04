# Codex adversarial review of PR #160 (Payment Truth Timeline)

Run 2026-10-04 with `~/.codex/adversarial-review-prompt.md` against branch `feat/payment-truth-timeline`
at `4b17d29`, model `gpt-5.6-sol` (the configured `gpt-6-sol` is refused on this account), sandbox
`workspace-write`. The reviewer's text below is verbatim. What was done with it:

- **Its finding stands and is accepted.** `CaseworkStore.matchesTouching` was scoped by tenant and run
  id but not by case, so invariant 7 of the specification ("every query carries the tenant and the
  case") was false for one of six queries. No cross-tenant leak followed (the run id came from a
  case-scoped lookup), but the claimed defence in depth did not exist. The reviewer's bounded
  correction (case predicate via a join on `recon_runs`, plus a same-tenant/wrong-case negative
  control) is committed on this branch.
- **The correction was verified outside its sandbox**, where Docker was available:
  `PaymentTimelineIntegrationTest` 8/8 green on PostgreSQL; with the case predicate removed
  (`r.case_id = ? OR TRUE`) the new assertion fails `expected: <0> but was: <2>` and nothing else;
  restored hash-identical; green again.
- **Two "could not check" items are sandbox artefacts, not contradictions.** The reviewer's own runs
  reported `skipped="8"` and `Skipped: 52` because its sandbox denied the Docker socket; the same
  commands with Docker give 8/8 and 136/0 skipped, and CI on PR #160 ran 680 tests with none skipped.
- **The merge-base question is a stale local ref.** The worktree's local `main` was behind;
  against `origin/main` the branch is exactly the two timeline commits, which is what PR #160 shows.
- **Two of its objections are taken as corrections to the claims, not the code:** the "conclusion
  reproducible from the response" test re-derives only `conclusion.state` (the tracker now says so),
  and the mutation harness was not a repository artefact (it now is:
  `backend/scripts/mutate_timeline.py`).

---

MOST DANGEROUS FALSE ASSUMPTION / UNVERIFIABLE CLAIM:
  the claim, quoted

  > “Every query carries the tenant and the case.”

  `docs/PAYMENT_TRUTH_TIMELINE.md`, invariant 7. The tracker makes the related claim that tenant isolation is **VERIFIED** and that each of the six timeline queries was exercised with positive twins.

  why it is unsupported

  Before the bounded correction, `CaseworkStore.matchesTouching` accepted only `(tenantId, runId, recordIds)` and its SQL predicate was `tenant_id = ? AND run_id = ?`. It did not accept or predicate on `caseId`. The integration test checked owner-versus-foreign-tenant results, but it had no same-tenant/wrong-case negative control. The service currently obtains `runId` from a tenant-and-case-scoped `listRuns` call, so this review did **not** demonstrate an exploitable cross-tenant leak; it demonstrated that the claimed query-level case boundary and its asserted defense in depth did not exist.

  Independent repository evidence before correction:

  - `sed -n '463,483p' backend/src/main/java/com/trustledger/reconciliation/casework/CaseworkStore.java`
  - `sed -n '369,386p' backend/src/test/java/com/trustledger/reconciliation/casework/PaymentTimelineIntegrationTest.java`
  - `rg -n 'matchesTouching\(' . --glob '!backend/target/**'`

  what evidence would settle it

  Run `PaymentTimelineIntegrationTest` against PostgreSQL and require all three results for the match query: owner + correct case is non-empty, foreign tenant + correct case is empty, and owner + wrong case is empty. Then remove the case predicate, observe the same-tenant/wrong-case assertion fail, restore it, and re-run green. The exact corrected test exists, but Docker socket access was denied in this sandbox, so its runtime verdict remains unverified here.

  what breaks if it is wrong

  A future call-site error, compromised internal caller, or inconsistent stored run ID can associate matches from the wrong case within one tenant. The timeline can then label records `MATCHED`, link unrelated evidence, and issue a financial conclusion under a case boundary the query never enforced. That is more dangerous than a display defect because it corrupts the explanation of financial truth while still looking tenant-scoped.

CLAIMS CHECKED AND CONFIRMED:      (with the command that confirmed each)

- The branch contains exactly the two named timeline commits above `e8a8799`: `git log --oneline e8a8799..HEAD` returned `a931183` and `4b17d29`. The claim that these are literally “on top of main” is not confirmed; see below.

- The timeline change adds no migration/table. `git diff e8a8799..HEAD --name-only -- backend/src/main/resources/db/migration` printed nothing. The read endpoint is `@Transactional(readOnly = true)`, and inspection of `PaymentTimelineService.timeline` found only case validation and read methods; no insert/update/delete, cache, ledger, transfer, rail, or outbox call is on that path. This confirms repository structure, not database runtime telemetry.

- The endpoint, permission, feature flag, and static error mapping match the specification: `rg -n 'payments/timeline|RECON_VIEW|ConditionalOnProperty' backend/src/main/java/com/trustledger/api/ReconciliationCaseController.java` plus `sed -n '1,120p' backend/src/main/java/com/trustledger/api/RestExceptionHandler.java` show `GET`, `RECON_VIEW`, the casework flag, and 400/404/409 handlers. Runtime response bodies could not be re-exercised here.

- Pure timeline logic is green at the claimed count. `mvn -B -o test -Dtest='PaymentTimelineTest,CaseworkBoundaryTest'` finished `BUILD SUCCESS`; Surefire reports `PaymentTimelineTest tests="14" errors="0" skipped="0" failures="0"` and `CaseworkBoundaryTest tests="1" errors="0" skipped="0" failures="0"`.

- The money-path import guard is non-vacuous for the mechanism it scans. From an immediately green baseline, adding `import com.trustledger.core.ledger.LedgerService;` to `PaymentTimeline.java` made `CaseworkBoundaryTest` fail `1/1`; removing only that line made it pass `1/1` again. The mutation was restored before the bounded correction, and `git status --short` then returned clean.

- Duplicate-delivery selection mirrors the engine comparator in source. `rg -n 'group.sort\(Comparator.comparing\(CanonicalRecord::receivedAt|d.sort\(Comparator.comparing\(\(SourceRecord r\) -> r.record\(\).receivedAt' backend/src/main/java/com/trustledger/reconciliation/casework/{engine/ReconciliationEngine.java,timeline/PaymentTimeline.java}` shows both choose receipt time with nulls last, then record key. The pure test covering that choice passed. PostgreSQL behaviour could not be rerun.

- The implementation does not total timeline amounts across currencies. Inspection of `PaymentTimeline.conclude` shows exposure grouped in a `TreeMap<String, BigDecimal>` keyed by currency; the UI prints the server's decimal strings and one exposure card per currency, with no numeric conversion. `rg -n 'Number\(|parseFloat|reduce\(' 'frontend/app/reconciliation/cases/[caseId]/payments/page.tsx' frontend/app/lib/timeline.ts` returned no conversion or reduction. The pure tests passed; the Adyen runtime scenario was skipped here.

- Frontend static/type/unit/build claims are current: `npx tsc --noEmit` exited 0; `npx vitest run` reported 15/15; `npm run build` compiled and listed `/reconciliation/cases/[caseId]/payments`; `npm run test:public-exposure` printed its pass message. The build-generated change to `frontend/next-env.d.ts` was removed, leaving no unrelated diff.

- The committed evidence images are valid PNGs at the claimed viewports. `sips -g pixelWidth -g pixelHeight -g format docs/interface-gallery/evidence/payment-timeline-{desktop,mobile}.png` reported desktop 1280×1964 and mobile 390×3174. Visual inspection confirmed the desktop shows the final “At risk” wording and the mobile image shows the disclosed older “Unaccounted for” wording; the artifact does not independently prove the final code was the code running when captured.

- The committed provider fixture bytes match the documented digests: `shasum -a 256 backend/src/test/resources/fixtures/providers/{adyen-settlement-detail-batch-134-sample.csv,checkout-financial-actions-by-payout-sample.csv}` returned `d178b561…eb7d74` and `6494c48e…876c4`. `awk 'END {print NR}'` returned 64 and 56 physical CSV lines respectively, i.e. 63 and 55 data rows after the header. Raw-row searches confirmed the specific Adyen and Checkout.com payment references and values used by the tests.

- The specification honestly disclaims production/customer observation, bank evidence, file-level re-reading, import-row write-once enforcement, and latency benchmarking. Repository inspection found no contradictory claim in the reviewed timeline code or tracker section.

CLAIMS THAT COULD NOT BE CHECKED:  (and why)

- The PR description itself. `gh pr view 160 --json body -q .body` failed because `api.github.com` was unreachable. The public web fetch returned no result, and browser access to GitHub was denied. I reviewed every sentence in the tracker section and specification, but I cannot claim coverage of unseen PR-body-only wording.

- The claimed PostgreSQL `PaymentTimelineIntegrationTest` 8/8 runtime evidence, including HTTP 404/409 shape, tenant isolation, edited-row integrity failure, live-feed behaviour, ACME output, Adyen output, and Checkout.com output. With the required Docker variables set, Testcontainers received `java.net.SocketException: Operation not permitted`. Maven exited 0 only because all 8 tests were skipped. The authoritative XML says `tests="8" ... skipped="8"`; that is a failed verification, not 8/8.

- The claimed casework-tree regression `136, skipped 0`. The requested command discovered 136 tests, but 52 PostgreSQL tests were skipped. Surefire's actual result was `Tests run: 136 ... Skipped: 52`, despite `BUILD SUCCESS`. No committed CI/Surefire artifact in this branch independently substantiates the historical `Skipped: 0` result.

- “Conclusion reproducible from the response” as a claim about the entire conclusion. `stateFromItems` independently re-derives only `conclusion.state`; it reads `conclusion.basis` from the conclusion under test and does not independently derive the statement, notes, open/decided type lists, exposure-by-currency, or basis. The pure tests cover examples of those fields, but the tracker sentence is broader than the second-derivation assertion.

- The `33/33 mutants killed` claim. The hand harness and per-mutant outputs are not repository artifacts. I independently mutation-checked only the money-path import guard described above. Repeating 33 undocumented hand mutations would require the exact mutation patches, green baselines, and per-run outputs; prose in the tracker is not that evidence.

- Live-browser behaviour and console cleanliness. The screenshots are inspectable artifacts, but the backend could not run here and a screenshot cannot prove navigation, the unknown-reference response, console state, or which commit supplied the running code.

- “Official” provenance of the Adyen and Checkout.com samples. The checked-in bytes and their digests are confirmed, but no fetched-payload receipt or signed upstream digest is committed, and outbound retrieval was unavailable. A documentation link plus a self-computed digest proves repository identity, not upstream provenance.

- Negative external claims such as “no customer file has been through it” and “not production-observed.” The repository consistently labels those unknown/unobserved, but proving non-occurrence requires deployment, telemetry, and customer-data systems outside this worktree.

- PR base/scope as stated in the review context. `HEAD` is two commits above `e8a8799`, but `git merge-base main HEAD` is `83cb8d6`, `git merge-base origin/main HEAD` is `e8a8799`, and `git diff main...HEAD --stat` spans 125 files rather than only the timeline. Network loss prevented reading the PR's actual base ref, so I cannot resolve whether local refs are stale or PR #160 is stacked rather than based on `main`.

THE ONE BOUNDED CORRECTION:        (only after the above)

- Left uncommitted. `CaseworkStore.matchesTouching` now requires `caseId`, joins `recon_runs`, and predicates on `m.tenant_id`, `r.case_id`, and `m.run_id`. `PaymentTimelineService` supplies the requested case ID. `PaymentTimelineIntegrationTest` now includes a same-tenant/wrong-case negative control in addition to the owner and foreign-tenant checks.

- Files changed: `backend/src/main/java/com/trustledger/reconciliation/casework/CaseworkStore.java`, `backend/src/main/java/com/trustledger/reconciliation/casework/timeline/PaymentTimelineService.java`, and `backend/src/test/java/com/trustledger/reconciliation/casework/PaymentTimelineIntegrationTest.java`.

- Verification achieved: `mvn -B -o test -Dtest='PaymentTimelineTest,CaseworkBoundaryTest'` compiled all main and test sources and passed 15/15 with zero skips; `git diff --check` passed. Verification still missing: the new PostgreSQL negative control could not execute because the sandbox denied Docker socket access. No commit or push was made.
