package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.AuthResponse;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real provider settlement reports (the providers' own published samples) through the governed import,
 * into PostgreSQL, and through a reconciliation run. Expected numbers were computed independently from the
 * raw CSV and from the engine's written rules, and are hard-coded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ProviderReportImportIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
        r.add("trustledger.payment-rails.webhook-inbox.worker-enabled", () -> "false");
        r.add("trustledger.reconciliation.casework.enabled", () -> "true");
    }

    private static final String CASES = "/api/v1/reconciliation/cases/";

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    CaseworkHttp http;

    @BeforeEach
    void client() { http = new CaseworkHttp(json, port); }

    private static byte[] sample(String name) throws IOException {
        try (InputStream in = ProviderReportImportIntegrationTest.class.getResourceAsStream("/fixtures/providers/" + name)) {
            return in.readAllBytes();
        }
    }

    private static final String ADYEN = "adyen-settlement-detail-batch-134-sample.csv";
    private static final String CHECKOUT = "checkout-financial-actions-by-payout-sample.csv";

    private HttpResponse<String> uploadReport(String token, UUID caseId, String provider, String profile, String zone,
                                              String file) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>(Map.of("sourceType", "SETTLEMENT", "sourceIdentity", provider, "profile", profile));
        if (zone != null) fields.put("accountTimezone", zone);
        return http.multipart(CASES + caseId + "/imports", token, fields, file, sample(file));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void anAdyenReportBecomesSettlementLinesAndKeepsEveryRow() throws Exception {
        AuthResponse op = http.register();
        UUID caseId = http.createCase(op.token(), "ADYEN-" + UUID.randomUUID());

        HttpResponse<String> r = uploadReport(op.token(), caseId, "adyen", "adyen-settlement-detail", null, ADYEN);
        assertEquals(201, r.statusCode(), r.body());
        JsonNode body = http.tree(r);
        JsonNode m = body.get("manifest");
        assertEquals("COMPLETED", m.get("status").asString());
        assertEquals(63, m.get("recordCount").asInt());
        assertEquals(63, m.get("acceptedCount").asInt());
        assertEquals(0, m.get("rejectedCount").asInt());
        JsonNode p = body.get("provider");
        assertEquals(58, p.get("settlementLines").asInt());
        assertEquals(5, p.get("rowsNotReconciled").asInt());
        assertEquals(0, p.get("rowsWithUnresolvedTime").asInt());
        UUID importId = UUID.fromString(m.get("id").asString());

        assertEquals(58, count("SELECT count(*) FROM recon_records WHERE import_id = ?", importId));
        assertEquals(63, count("SELECT count(*) FROM recon_provider_rows WHERE import_id = ?", importId));
        assertEquals(58, count("SELECT count(*) FROM recon_provider_rows WHERE import_id = ? AND record_id IS NOT NULL", importId));
        Map<String, BigDecimal> gross = new TreeMap<>();
        jdbc.query("SELECT currency, sum(gross_amount) FROM recon_records WHERE import_id = ? GROUP BY currency",
            rs -> { gross.put(rs.getString(1), rs.getBigDecimal(2)); }, importId);
        assertEquals(Map.of("EUR", new BigDecimal("113.0000"), "USD", new BigDecimal("471.0000")), gross);
        // A USD line never carries the EUR fee or net.
        assertEquals(0, count("SELECT count(*) FROM recon_records WHERE import_id = ? AND currency = 'USD' AND (fee_amount IS NOT NULL OR net_amount IS NOT NULL)", importId));

        // The evidence keeps the provider's raw text and the zone evidence, exactly as read.
        String evidence = jdbc.queryForObject("SELECT evidence::text FROM recon_provider_rows WHERE import_id = ? AND payment_ref = 'ZVA14XO8S0GYIJU2'",
            String.class, importId);
        assertTrue(evidence.contains("\"rawValue\": \"4.39\"") || evidence.contains("\"rawValue\":\"4.39\""), evidence);
        assertTrue(evidence.contains("CEST"), evidence);

        // Evidence rows are write-once.
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "UPDATE recon_provider_rows SET not_reconciled_reason = 'x' WHERE import_id = ?", importId));

        // The same bytes again replay: nothing new is written.
        HttpResponse<String> again = uploadReport(op.token(), caseId, "adyen", "adyen-settlement-detail", null, ADYEN);
        assertEquals(200, again.statusCode(), again.body());
        assertEquals(63, count("SELECT count(*) FROM recon_provider_rows WHERE import_id IN (SELECT id FROM recon_imports WHERE case_id = ?)", caseId));
    }

    @Test
    void aCheckoutReportWithoutAZoneKeepsItsTimesUnknownAndConvertsOnlyCaptures() throws Exception {
        AuthResponse op = http.register();
        UUID caseId = http.createCase(op.token(), "CKO-" + UUID.randomUUID());
        HttpResponse<String> r = uploadReport(op.token(), caseId, "checkout", "checkout-financial-actions", null, CHECKOUT);
        assertEquals(201, r.statusCode(), r.body());
        JsonNode p = http.tree(r).get("provider");
        assertEquals(2, p.get("settlementLines").asInt());
        assertEquals(51, p.get("rowsNotReconciled").asInt());
        assertEquals(55, p.get("rowsWithUnresolvedTime").asInt());
        assertEquals(14, p.get("notReconciledByReason").get("NOT_A_CAPTURE_SETTLEMENT: Chargeback (ADJM), Chargeback (ARBW)").asInt());
        UUID importId = UUID.fromString(http.tree(r).get("manifest").get("id").asString());
        // The EUR capture's line cites both of its source rows.
        assertEquals(2, count("""
            SELECT count(*) FROM recon_provider_rows pr JOIN recon_records rr ON rr.id = pr.record_id
             WHERE pr.import_id = ? AND rr.stable_ref = 'pay_ikhluv2i6x0rb1y316n666nkk6' AND rr.currency = 'EUR'""", importId));

        UUID declared = http.createCase(op.token(), "CKO-TZ-" + UUID.randomUUID());
        HttpResponse<String> withZone = uploadReport(op.token(), declared, "checkout", "checkout-financial-actions", "Europe/London", CHECKOUT);
        assertEquals(201, withZone.statusCode(), withZone.body());
        assertEquals(0, http.tree(withZone).get("provider").get("rowsWithUnresolvedTime").asInt());
    }

    @Test
    void aRowRepeatedUnderTheSameProviderIdentityIsADuplicateEvenWithDifferentBytes() throws Exception {
        AuthResponse op = http.register();
        UUID caseId = http.createCase(op.token(), "ADYEN-DUP-" + UUID.randomUUID());
        String text = new String(sample(ADYEN), StandardCharsets.UTF_8);
        String settledRow = text.lines().filter(l -> l.contains("X4J8X927MZNPIFY2")).findFirst().orElseThrow();
        // Same journal entry, one non-identity column changed: different bytes, same Adyen identity.
        String edited = text.stripTrailing() + "\n" + settledRow.replaceFirst(",mc,", ",visa,") + "\n";
        assertNotEquals(settledRow, settledRow.replaceFirst(",mc,", ",visa,"));
        HttpResponse<String> r = http.multipart(CASES + caseId + "/imports", op.token(), Map.of("sourceType", "SETTLEMENT",
            "sourceIdentity", "adyen", "profile", "adyen-settlement-detail"), "dup.csv", edited.getBytes(StandardCharsets.UTF_8));
        assertEquals(201, r.statusCode(), r.body());
        JsonNode m = http.tree(r).get("manifest");
        assertEquals(64, m.get("recordCount").asInt());
        assertEquals(63, m.get("acceptedCount").asInt());
        assertEquals(1, m.get("duplicateCount").asInt());
        assertEquals(58, http.tree(r).get("provider").get("settlementLines").asInt());
        UUID importId = UUID.fromString(m.get("id").asString());
        assertEquals(1, count("SELECT count(*) FROM recon_records WHERE import_id = ? AND stable_ref = 'X4J8X927MZNPIFY2'", importId));
        // Every ACCEPTED row has exactly one evidence row; the duplicate has none.
        assertEquals(63, count("SELECT count(*) FROM recon_provider_rows WHERE import_id = ?", importId));
        assertEquals(0, count("""
            SELECT count(*) FROM recon_import_rows ir LEFT JOIN recon_provider_rows pr ON pr.import_row_id = ir.id
             WHERE ir.import_id = ? AND ir.status = 'ACCEPTED' AND pr.import_row_id IS NULL""", importId));
    }

    @Test
    void misdirectedParametersAreRefusedNotIgnored() throws Exception {
        AuthResponse op = http.register();
        UUID caseId = http.createCase(op.token(), "BAD-" + UUID.randomUUID());
        assertEquals(400, uploadReport(op.token(), caseId, "adyen", "adyen-settlement-detail", "Europe/Amsterdam", ADYEN).statusCode());
        assertEquals(400, uploadReport(op.token(), caseId, "checkout", "checkout-financial-actions", "Mars/Olympus", CHECKOUT).statusCode());
        assertEquals(400, http.multipart(CASES + caseId + "/imports", op.token(), Map.of("sourceType", "PROVIDER_TRANSACTION",
            "sourceIdentity", "adyen", "profile", "adyen-settlement-detail"), ADYEN, sample(ADYEN)).statusCode());
        assertEquals(400, http.multipart(CASES + caseId + "/imports", op.token(), Map.of("sourceType", "SETTLEMENT",
            "sourceIdentity", "p", "profile", "provider-settlement", "accountTimezone", "Europe/London"), "s.csv",
            "batch_id,transaction_ref,currency,gross,fee,net,settled_at\nB1,t1,GBP,1.00,0.10,0.90,2026-08-03\n".getBytes(StandardCharsets.UTF_8)).statusCode());
        assertEquals(0, count("SELECT count(*) FROM recon_imports WHERE case_id = ?", caseId));
    }

    @Test
    void aRunReadsAdyenLinesInTheChargeCurrency() throws Exception {
        AuthResponse op = http.register();
        UUID caseId = http.createCase(op.token(), "ADYEN-RUN-" + UUID.randomUUID());
        CaseworkHttp.expect2xx(http.upload(op.token(), caseId, "INTERNAL", "ledger", "internal-expected", "internal.csv",
            ("internal_ref,provider,provider_ref,event_type,currency,amount,expected_at\n"
                + "I1,adyen,X4J8X927MZNPIFY2,PAYMENT,EUR,5.00,2023-08-01T13:07:16Z\n"
                + "I2,adyen,ZVA14XO8S0GYIJU2,PAYMENT,USD,5.00,2023-07-31T13:47:45Z\n").getBytes(StandardCharsets.UTF_8)));
        CaseworkHttp.expect2xx(http.upload(op.token(), caseId, "PROVIDER_TRANSACTION", "adyen", "provider-transactions", "tx.csv",
            ("event_id,transaction_ref,merchant_ref,event_type,status,currency,gross,fee,net,occurred_at,received_at\n"
                + "e1,X4J8X927MZNPIFY2,,CHARGE,SUCCESS,EUR,5.00,,,2023-08-01T13:07:16Z,\n"
                + "e2,ZVA14XO8S0GYIJU2,,CHARGE,SUCCESS,USD,5.00,,,2023-07-31T13:47:45Z,\n").getBytes(StandardCharsets.UTF_8)));
        assertEquals(201, uploadReport(op.token(), caseId, "adyen", "adyen-settlement-detail", null, ADYEN).statusCode());

        HttpResponse<String> r = http.post(CASES + caseId + "/runs", op.token(), null);
        assertEquals(201, r.statusCode(), r.body());
        JsonNode summary = json.readTree(http.tree(r).get("run").get("summary").asString());
        assertEquals(2, summary.get("matchesByRule").get("R3-SETTLEMENT-BATCH").asInt());
        Map<String, Integer> byType = new TreeMap<>();
        summary.get("exceptionsByType").properties().forEach(e -> byType.put(e.getKey(), e.getValue().asInt()));
        // The USD charge paid out in EUR is not a currency mismatch; the EUR line's arithmetic holds; the
        // other 56 settled payments have no charge in this case.
        assertEquals(Map.of("UNMATCHED_SETTLEMENT_ITEM", 56), byType);
    }
}
