# Provider Certification & Production Evidence System — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a certification workflow that runs a fixed catalogue of drills against the deterministic sandbox rail, records checksummed evidence, requires an explicit different-actor sign-off, and enforces a current signed-off PASS certification as a precondition for production payout activation.

**Architecture:** New bounded module `com.trustledger.core.certification` (a `CertificationDrill` sealed-interface catalogue + registry). `ProviderCertificationService` runs the catalogue, reuses `EvidenceService` for the checksummed pack, and records runs/results/sign-offs (migration V31). `TenantPaymentRouteService.rejectionReason` — the existing production AND-chain — gains one `production_not_certified` condition alongside its canary check. Drills exercise the *real* webhook-inbox / transition / reconciliation services against cert-scoped synthetic fixtures, never real tenant funds.

**Tech Stack:** Java 17, Spring Boot 4.0.7, Spring Data JPA, PostgreSQL + Flyway, JUnit 5 + Testcontainers (real Postgres). Local test env: colima + `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

**Spec:** `docs/superpowers/specs/2026-07-18-provider-certification-design.md`

---

## Conventions for every task

- **Money-path discipline:** every entity string column is `VARCHAR`, never `CHAR` (a CHAR-vs-VARCHAR mismatch vs the JPA `String`→VARCHAR mapping broke startup on two prior branches). JSONB columns use `@JdbcTypeCode(SqlTypes.JSON)` on a `String` field.
- **Run tests with:** `cd backend && export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock && mvn -B test -Dtest='<ClassName>' -DfailIfNoSpecifiedTests=false`
- **The full suite is memory-heavy on 6 GB colima** — run single classes during development; the final full-suite run is Task 11.
- **Reserved certification identity:** `CERT_SYSTEM_USER = new UUID(0L, 1L)` (distinct from `SYSTEM_USER = new UUID(0L,0L)` used by clearing accounts). Drill fixtures are owned by this id.

---

## File Structure

**Create:**
- `backend/src/main/resources/db/migration/V31__provider_certification.sql` — 3 tables.
- `backend/src/main/java/com/trustledger/persistence/entity/CertificationRunEntity.java`
- `backend/src/main/java/com/trustledger/persistence/entity/CertificationDrillResultEntity.java`
- `backend/src/main/java/com/trustledger/persistence/entity/CertificationSignOffEntity.java`
- `backend/src/main/java/com/trustledger/persistence/repo/CertificationRunRepository.java`
- `backend/src/main/java/com/trustledger/persistence/repo/CertificationDrillResultRepository.java`
- `backend/src/main/java/com/trustledger/persistence/repo/CertificationSignOffRepository.java`
- `backend/src/main/java/com/trustledger/core/certification/CertificationDrill.java` — interface.
- `backend/src/main/java/com/trustledger/core/certification/DrillContext.java`
- `backend/src/main/java/com/trustledger/core/certification/DrillResult.java` (+ nested `Assertion`).
- `backend/src/main/java/com/trustledger/core/certification/CertificationDrillRegistry.java`
- `backend/src/main/java/com/trustledger/core/certification/CertificationSyntheticFixtures.java`
- `backend/src/main/java/com/trustledger/core/certification/drills/SignedWebhookDeliveryDrill.java`
- `backend/src/main/java/com/trustledger/core/certification/drills/AmbiguousOutcomeRecoveryDrill.java`
- `backend/src/main/java/com/trustledger/core/certification/drills/ReconciliationProofDrill.java`
- `backend/src/main/java/com/trustledger/app/ProviderCertificationService.java`
- `backend/src/main/java/com/trustledger/api/CertificationController.java`
- `backend/src/main/java/com/trustledger/api/CertificationDtos.java`

**Modify:**
- `backend/src/main/java/com/trustledger/app/EvidenceService.java` — add `exportCertification(...)`.
- `backend/src/main/java/com/trustledger/app/TenantPaymentRouteService.java` — add cert AND-condition (near the existing `canaries.rejectionReason` call, ~line 179).

**Test (create):**
- `backend/src/test/java/com/trustledger/core/certification/CertificationPersistenceIntegrationTest.java`
- `backend/src/test/java/com/trustledger/core/certification/SignedWebhookDeliveryDrillIntegrationTest.java`
- `backend/src/test/java/com/trustledger/core/certification/AmbiguousOutcomeRecoveryDrillIntegrationTest.java`
- `backend/src/test/java/com/trustledger/core/certification/ReconciliationProofDrillIntegrationTest.java`
- `backend/src/test/java/com/trustledger/app/ProviderCertificationIntegrationTest.java`
- `backend/src/test/java/com/trustledger/app/CertificationGateIntegrationTest.java` — the load-bearing gate proof.

---

## Task 1: Data model — migration V31, entities, repositories

**Files:**
- Create: the migration, 3 entities, 3 repos (paths above).
- Test: `CertificationPersistenceIntegrationTest.java`

- [ ] **Step 1: Write the migration** `V31__provider_certification.sql`

```sql
CREATE TABLE certification_runs (
    id                          UUID        PRIMARY KEY,
    tenant_id                   UUID        NOT NULL,
    tenant_provider_config_id   UUID        NOT NULL,
    environment                 VARCHAR(32) NOT NULL,
    status                      VARCHAR(24) NOT NULL,
    catalogue_version           VARCHAR(32) NOT NULL,
    initiated_by                UUID        NOT NULL,
    started_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at                TIMESTAMPTZ,
    evidence_export_id          UUID,
    expires_at                  TIMESTAMPTZ,
    CONSTRAINT chk_certification_run_status
        CHECK (status IN ('RUNNING', 'PASSED', 'FAILED')),
    CONSTRAINT fk_certification_run_config
        FOREIGN KEY (tenant_id, tenant_provider_config_id, environment)
        REFERENCES tenant_provider_configs (tenant_id, id, environment)
);

CREATE INDEX idx_certification_runs_gate
    ON certification_runs (tenant_id, tenant_provider_config_id, environment, status, expires_at);

CREATE TABLE certification_drill_results (
    id                   UUID        PRIMARY KEY,
    certification_run_id UUID        NOT NULL REFERENCES certification_runs(id),
    drill_id             VARCHAR(64) NOT NULL,
    drill_version        VARCHAR(32) NOT NULL,
    status               VARCHAR(16) NOT NULL,
    detail               JSONB       NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_drill_result_status CHECK (status IN ('PASS', 'FAIL')),
    CONSTRAINT uq_drill_result UNIQUE (certification_run_id, drill_id)
);

CREATE TABLE certification_signoffs (
    id                   UUID         PRIMARY KEY,
    certification_run_id UUID         NOT NULL UNIQUE REFERENCES certification_runs(id),
    tenant_id            UUID         NOT NULL,
    signed_by            UUID         NOT NULL,
    signed_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    note                 VARCHAR(512)
);
```

- [ ] **Step 2: Write the three entities.** Mirror the columns exactly. All `String` fields → `VARCHAR` (no `columnDefinition="char"`). `detail` uses `@JdbcTypeCode(SqlTypes.JSON)`. `started_at`/`signed_at`/`created_at` use `@Column(insertable=false, updatable=false)` with `@JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)` (DB `DEFAULT now()`), matching `ExternalPaymentAttemptEntity`. Provide a public all-args constructor for the app-set fields + a `protected` no-arg for JPA. Example for the run entity:

```java
@Entity @Table(name = "certification_runs")
public class CertificationRunEntity {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "tenant_provider_config_id", nullable = false) private UUID tenantProviderConfigId;
    @Column(nullable = false, length = 32) private String environment;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "catalogue_version", nullable = false, length = 32) private String catalogueVersion;
    @Column(name = "initiated_by", nullable = false) private UUID initiatedBy;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "started_at", nullable = false, insertable = false, updatable = false) private Instant startedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "evidence_export_id") private UUID evidenceExportId;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "expires_at") private Instant expiresAt;
    protected CertificationRunEntity() {}
    public CertificationRunEntity(UUID id, UUID tenantId, UUID tenantProviderConfigId, String environment,
                                  String status, String catalogueVersion, UUID initiatedBy) {
        this.id = id; this.tenantId = tenantId; this.tenantProviderConfigId = tenantProviderConfigId;
        this.environment = environment; this.status = status; this.catalogueVersion = catalogueVersion;
        this.initiatedBy = initiatedBy;
    }
    // getters for all; setters for status, completedAt, evidenceExportId, expiresAt
}
```

  `CertificationDrillResultEntity`: fields `id, certificationRunId, drillId, drillVersion, status, detail (JSON String), createdAt`. `CertificationSignOffEntity`: fields `id, certificationRunId, tenantId, signedBy, signedAt, note`.

- [ ] **Step 3: Write the three repositories.** `CertificationRunRepository extends JpaRepository<CertificationRunEntity, UUID>` with the gate query:

```java
@Query("""
    select r from CertificationRunEntity r
    where r.tenantId = :tenantId and r.tenantProviderConfigId = :configId
      and r.environment = :environment and r.status = 'PASSED'
      and (r.expiresAt is null or r.expiresAt > :now)
      and exists (select 1 from CertificationSignOffEntity s where s.certificationRunId = r.id)
    order by r.startedAt desc""")
List<CertificationRunEntity> findCurrentValid(@Param("tenantId") UUID tenantId,
    @Param("configId") UUID configId, @Param("environment") String environment, @Param("now") Instant now);
```
  `CertificationDrillResultRepository`: `List<...> findByCertificationRunId(UUID runId)`. `CertificationSignOffRepository`: `Optional<...> findByCertificationRunId(UUID runId)`.

- [ ] **Step 4: Write the failing persistence test** `CertificationPersistenceIntegrationTest` (`@SpringBootTest @Testcontainers`, Postgres 16-alpine, standard `@DynamicPropertySource` from `ExternalPaymentReversalIntegrationTest`). It must (a) insert a `tenant_provider_configs` row (reuse the helper pattern from `TenantProviderGovernanceIntegrationTest` or insert via SQL) so the FK holds, then (b) save a run + result + signoff, (c) assert `findCurrentValid` returns the run only after a signoff exists and while not expired.

- [ ] **Step 5: Run — verify it fails** (entities/migration not yet present or wrong).

Run: `mvn -B test -Dtest='CertificationPersistenceIntegrationTest' ...`
Expected: FAIL (compile error or, if run before entities, red).

- [ ] **Step 6: Make it pass** — with the migration+entities+repos in place, the round-trip succeeds. This step specifically catches a CHAR/VARCHAR or FK mistake (Hibernate `ddl-auto: validate` fails at startup otherwise).

Run: same command. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V31__provider_certification.sql \
  backend/src/main/java/com/trustledger/persistence/entity/Certification*.java \
  backend/src/main/java/com/trustledger/persistence/repo/Certification*.java \
  backend/src/test/java/com/trustledger/core/certification/CertificationPersistenceIntegrationTest.java
git commit -m "feat(certification): data model — runs, drill results, signoffs (V31)"
```

---

## Task 2: The drill contract + registry

**Files:** Create `CertificationDrill.java`, `DrillResult.java`, `DrillContext.java`, `CertificationDrillRegistry.java`. Test: fold into Task 4's first drill test (the contract has no behaviour to test alone; a trivial registry test is optional).

- [ ] **Step 1: Write `DrillResult`** (immutable) with a nested `Assertion`:

```java
public record DrillResult(String drillId, String drillVersion, boolean passed,
                          List<Assertion> assertions, Map<String, Object> observations) {
    public record Assertion(String name, String expected, String actual, boolean ok) {}
    public static DrillResult of(CertificationDrill d, List<Assertion> a, Map<String,Object> obs) {
        return new DrillResult(d.id(), d.version(), a.stream().allMatch(Assertion::ok), List.copyOf(a), Map.copyOf(obs));
    }
}
```

- [ ] **Step 2: Write `CertificationDrill`** interface: `String id();`, `String version();`, `DrillResult run(DrillContext ctx);`.

- [ ] **Step 3: Write `DrillContext`** — a record carrying `UUID tenantId`, `UUID tenantProviderConfigId`, and the service handles the drills need (inject the concrete services: `PaymentWebhookInboxService inbox`, `PaymentWebhookInboxWorker worker`, `ExternalPaymentTransitionService transitions`, the **Spring** `com.trustledger.reconciliation.ReconciliationService reconciliation` — NOT the pure-domain `com.trustledger.core.reconciliation.ReconciliationService`; import the fully-qualified one to avoid the name clash — `WebhookSigner signer`, `CertificationSyntheticFixtures fixtures`, plus the repositories a drill reads: `ExternalPaymentAttemptRepository`, `LedgerEntryRepository`, `AccountRepository`, `ReconciliationIssueRepository`). Keep it a plain carrier — no logic.

- [ ] **Step 4: Write `CertificationDrillRegistry`** — `@Component` taking `List<CertificationDrill>` in its constructor, exposing `List<CertificationDrill> all()` sorted by `id()` for deterministic order and a `String catalogueVersion()` = a hash/join of each drill's `id:version` (so the run records exactly which catalogue executed).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/trustledger/core/certification/CertificationDrill.java \
  backend/src/main/java/com/trustledger/core/certification/DrillResult.java \
  backend/src/main/java/com/trustledger/core/certification/DrillContext.java \
  backend/src/main/java/com/trustledger/core/certification/CertificationDrillRegistry.java
git commit -m "feat(certification): drill contract + registry"
```

---

## Task 3: Synthetic fixtures factory

**Files:** Create `CertificationSyntheticFixtures.java`. Tested via the drills (Tasks 4-6) — no standalone test.

- [ ] **Step 1: Write `CertificationSyntheticFixtures`** (`@Component`). It creates cert-scoped, isolated fixtures under `CERT_SYSTEM_USER = new UUID(0L, 1L)` in the `SANDBOX` provider space: a source account with an opening balance, a clearing account (SANDBOX rail, matching currency), a `TransferEntity` (channel `EXTERNAL`, status `PENDING_SETTLEMENT`), and an `ExternalPaymentAttemptEntity` (provider column = `SandboxPaymentRailAdapter.RAIL` — the rail **id** `"SANDBOX_EXTERNAL"`, not the `"SANDBOX"` alias, since attempts store the canonical rail id; status `PENDING_SETTLEMENT`, a unique `sbx_cert_<uuid>` provider reference). Return a small record `Fixture(sourceAccountId, clearingAccountId, transferId, attemptId, providerReference)`. Reuse the exact entity constructors from `ExternalPaymentReversalIntegrationTest`'s setup (that test is the reference for reserved-user + clearing-account + attempt creation). Amounts small (e.g. 200.0000 NGN). No FK to a real tenant config — these are sandbox-only.

- [ ] **Step 2: Commit** (`feat(certification): synthetic sandbox fixtures factory`). No test yet — proven by Task 4.

---

## Task 4: `SignedWebhookDeliveryDrill`

**Files:** Create `drills/SignedWebhookDeliveryDrill.java`. Test: `SignedWebhookDeliveryDrillIntegrationTest.java`.

- [ ] **Step 1: Write the failing test.** `@SpringBootTest @Testcontainers`, worker disabled (`trustledger.payment-rails.webhook-inbox.worker-enabled=false`). Autowire the drill. Assert: `run(ctx)` returns `passed == true`, with assertions covering "valid signed SETTLED webhook settles exactly once" and "invalid-signature webhook rejected, no state change". Then a negative variant: inject a tampered fixture / force a released reservation and assert `passed == false`.

- [ ] **Step 2: Run — verify it fails** (drill not implemented). Expected: FAIL.

- [ ] **Step 3: Implement the drill.** Using `ctx.fixtures()` build a fixture; build the webhook body `{eventId, providerReference, eventType:"SETTLED"}`; sign with `ctx.signer().sign(body)`; call `ctx.inbox().receive("sandbox", body, sig)`; force `available_at` past + `ctx.worker().runOnce()` (clock-skew-proof, per the inbox tests); assert the attempt is `SETTLED` and exactly one PRINCIPAL debit exists (assertion: expected "1", actual count). Then deliver an invalid-signature webhook and assert the attempt status is unchanged. Build the `List<Assertion>` and return `DrillResult.of(this, assertions, observations)`. `id()="signed_webhook_delivery"`, `version()="1"`.

- [ ] **Step 4: Run — verify it passes.** Expected: PASS.

- [ ] **Step 5: Commit** (`feat(certification): signed-webhook delivery drill`).

---

## Task 5: `AmbiguousOutcomeRecoveryDrill`

**Files:** Create `drills/AmbiguousOutcomeRecoveryDrill.java`. Test: `AmbiguousOutcomeRecoveryDrillIntegrationTest.java`.

- [ ] **Step 1: Write the failing test** — asserts `passed==true` on the good path and `passed==false` when the reservation is (wrongly) released.
- [ ] **Step 2: Run — verify it fails.**
- [ ] **Step 3: Implement.** Submit a sandbox payout with the **`timeout`** scenario (the sandbox adapter raises `PaymentRailTimeoutException` → attempt `PENDING_UNKNOWN`). Assert the attempt is `PENDING_UNKNOWN` and the reservation is still held (source pending unchanged — no double-pay). Then drive verification/reconciliation resolution and assert it settles-or-fails once, never double-posts. `id()="ambiguous_outcome_recovery"`, `version()="1"`. **Note:** use `timeout`, not `connection_reset` (not in the sandbox adapter).
- [ ] **Step 4: Run — verify it passes.**
- [ ] **Step 5: Commit** (`feat(certification): ambiguous-outcome recovery drill`).

---

## Task 6: `ReconciliationProofDrill`

**Files:** Create `drills/ReconciliationProofDrill.java`. Test: `ReconciliationProofDrillIntegrationTest.java`.

- [ ] **Step 1: Write the failing test** — `passed==true` when the ledger is balanced and reconciliation raises no new issues; `passed==false` when an unbalanced transaction is injected.
- [ ] **Step 2: Run — verify it fails.**
- [ ] **Step 3: Implement.** Settle a sandbox payout (balanced double-entry), call `ctx.reconciliation().runReconciliation()`, and assert (a) the settle ledger transaction is balanced and (b) no `UNBALANCED_LEDGER_TRANSACTION` reconciliation issue exists for it (query `ReconciliationIssueRepository`). **Compute the balance check inline** — sum debits vs credits over the settle transaction's ledger entries and require `debits.compareTo(credits) == 0` with `≥2` entries; do NOT try to call `ReconciliationService.checkUnbalancedLedgerTransactions` (it is private). `id()="reconciliation_proof"`, `version()="1"`.
- [ ] **Step 4: Run — verify it passes.**
- [ ] **Step 5: Commit** (`feat(certification): reconciliation-proof drill`).

---

## Task 7: `EvidenceService.exportCertification`

**Files:** Modify `EvidenceService.java`. Tested via Task 8's end-to-end.

- [ ] **Step 1: Add the method**, following `exportFraudCase`/`exportLedgerTransaction` exactly — build a bundle and call the existing private `persist(...)`:

```java
@Transactional
public EvidenceExportEntity exportCertification(UUID tenantId, UUID runId, UUID generatedBy,
                                                Map<String, Object> certificationBundle) {
    Map<String, Object> bundle = new LinkedHashMap<>();
    bundle.put("kind", "CERTIFICATION_EVIDENCE");
    bundle.put("certificationRunId", runId.toString());
    bundle.putAll(certificationBundle); // run status, catalogue version, per-drill results + assertions
    return persist(tenantId, "CERTIFICATION", runId, generatedBy, bundle);
}
```
  (The caller — `ProviderCertificationService` — assembles `certificationBundle` from the run + drill results so `EvidenceService` stays free of certification internals.)

- [ ] **Step 2: Commit** (`feat(certification): evidence export for certification runs`). Compiles; behaviour proven in Task 8.

---

## Task 8: `ProviderCertificationService.run()` — orchestration

**Files:** Create `ProviderCertificationService.java`. Test: `ProviderCertificationIntegrationTest.java` (the run half).

- [ ] **Step 1: Write the failing test** — `run(tenantId, actor, configId)` executes all drills, returns a run with `status=PASSED`, persists one `drill_result` per drill, and stamps a non-null `evidenceExportId` whose export has a SHA-256 checksum. A second variant forces one drill to FAIL → run `FAILED`, all drill results still recorded.
- [ ] **Step 2: Run — verify it fails.**
- [ ] **Step 3: Implement `run()`** (`@Transactional`): create `certification_runs` (RUNNING, `initiatedBy=actor`, `catalogueVersion=registry.catalogueVersion()`); for each `registry.all()` drill, `try { result = drill.run(ctx); } catch (RuntimeException e) { result = failed(drill, e.getClass().getSimpleName()); }` (no secrets in the message), persist a `certification_drill_results` row (detail = JSON of assertions); aggregate `passed = allResults.allMatch(passed)`; assemble the certification bundle; `evidenceExportId = evidenceService.exportCertification(...).getId()`; set status PASSED/FAILED, `completedAt=now`, `expiresAt = passed ? now + validityDays : null` (validity from `@Value("${trustledger.certification.validity-days:90}")`); audit `CERTIFICATION_RUN_COMPLETED`; save + return. **Evidence failure = fail-closed:** if `exportCertification` throws, let the run fail (not usable for gating).
- [ ] **Step 4: Run — verify it passes.**
- [ ] **Step 5: Commit** (`feat(certification): run orchestration with evidence pack`).

---

## Task 9: Sign-off + `currentValidCertification`

**Files:** Modify `ProviderCertificationService.java`. Test: extend `ProviderCertificationIntegrationTest.java`.

- [ ] **Step 1: Write the failing test** — `signOff(tenant, actor2, runId)` on a PASSED run succeeds and `currentValidCertification(tenant, config, "PRODUCTION")` then returns present; `signOff` by the **same** actor as `initiatedBy` throws (dual-control); `signOff` on a FAILED or already-signed run throws; an expired run is not returned by `currentValidCertification`.
- [ ] **Step 2: Run — verify it fails.**
- [ ] **Step 3: Implement** `signOff` (`@Transactional`): load run; require `status==PASSED` (else `IllegalStateException`), `signedBy != initiatedBy` (else `IllegalStateException("Certification initiator cannot sign off")`), no existing signoff; insert `certification_signoffs`; audit `CERTIFICATION_SIGNED_OFF`. Implement `currentValidCertification(tenant, config, env)` = `runs.findCurrentValid(tenant, config, env, Instant.now()).stream().findFirst()`.
- [ ] **Step 4: Run — verify it passes.**
- [ ] **Step 5: Commit** (`feat(certification): dual-control sign-off + current-valid lookup`).

---

## Task 10: Gate integration — the load-bearing precondition

**Files:** Modify `TenantPaymentRouteService.java`. Test: `CertificationGateIntegrationTest.java`.

- [ ] **Step 1: Write the failing test (the proof this whole feature exists for).** Set up a PRODUCTION `tenant_provider_configs` row that passes every existing precondition (global switch enabled, enabled, compliance APPROVED, operational ACTIVE, credentials configured) **and** an approved active canary with available exposure — so the *only* thing missing is certification. Assert `TenantPaymentRouteService.rejectionReason(...)` (via its public routing entry point, or expose a narrow test hook) returns `"production_not_certified"`. Then run + sign off a certification and assert the rejection becomes `null` (allowed). Then expire the certification and assert it returns `"production_not_certified"` again.
- [ ] **Step 2: Run — verify it fails** (no cert check yet → the route is allowed when it should be blocked). Expected: FAIL on the first assertion.
- [ ] **Step 3: Implement.** Inject `ProviderCertificationService` into `TenantPaymentRouteService`. In the private `rejectionReason(adapter, config, ...)` method, in the existing `if ("PRODUCTION".equalsIgnoreCase(config.getEnvironment()))` region, **after** the canary check, add:

```java
if ("PRODUCTION".equalsIgnoreCase(config.getEnvironment())
        && certifications.currentValidCertification(config.getTenantId(), config.getId(), "PRODUCTION").isEmpty()) {
    return "production_not_certified";
}
```
  Keep `ProductionCanaryService` untouched (no coupling). Guard the `certifications` reference against null the same way the code guards `canaries != null`, if that pattern is used.

- [ ] **Step 4: Run — verify it passes** (blocked → allowed after cert+signoff → blocked after expiry). Expected: PASS.
- [ ] **Step 5: Commit** (`feat(certification): enforce certification as a production activation precondition`).

---

## Task 11: REST surface + full-suite green

**Files:** Create `CertificationController.java`, `CertificationDtos.java`. Test: extend/verify via an API integration test; then the full suite.

- [ ] **Step 1: Write the failing API test** — `POST /api/v1/tenant/certifications {tenantProviderConfigId}` returns the run; `POST /api/v1/tenant/certifications/{id}/sign-off {note}` by a second user succeeds; `GET /api/v1/tenant/certifications` and `/{id}` return tenant-scoped views; assert **no** secrets/credential refs/OTPs appear in any response body (grep the JSON).
- [ ] **Step 2: Run — verify it fails.**
- [ ] **Step 3: Implement** the thin controller (delegates to `ProviderCertificationService`, `@PreAuthorize`/auth per the existing tenant controllers, tenant id from the authenticated principal) + request/response DTO records (never expose entities; response carries run id, status, drill results with assertions, evidence export id, timestamps — no secrets).
- [ ] **Step 4: Run — verify it passes.**
- [ ] **Step 5: Run the FULL suite** to confirm no regression (the `jti`/gate/migration changes coexist):

Run: `mvn -B clean test` (with the colima env vars). If it exhausts memory on 6 GB colima, run the certification classes + a sample of existing suites, and rely on CI for the full run. Expected: 0 failures.

- [ ] **Step 6: Commit + push + open PR**

```bash
git add backend/src/main/java/com/trustledger/api/Certification*.java backend/src/test/...
git commit -m "feat(certification): REST surface for certification runs and sign-off"
git push -u origin feat/provider-certification
gh pr create --base main --title "feat: provider certification & production evidence (first slice)" --body "..."
```

---

## Definition of done

- All 6 test classes green locally on real Postgres (or CI if local memory-bound).
- The gate proof (Task 10) demonstrates block → allow → block across cert lifecycle.
- Full backend suite 0 failures in CI.
- No secrets/credentials/OTPs in any certification response or evidence bundle.
- `production_not_certified` is a real, enforced AND-condition in `TenantPaymentRouteService`.

## Residual / explicit non-goals (do NOT expand here)

- Real Paystack test-env drills; the full ~8-drill catalogue; OTP/incident/e-stop/settlement drills; a certification UI console. Each is a later slice with its own spec.
- Synthetic-fixture cleanup/retention policy.
