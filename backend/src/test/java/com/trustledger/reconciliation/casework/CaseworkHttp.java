package com.trustledger.reconciliation.casework;

import com.trustledger.api.AuthDtos.AuthResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HTTP helpers shared by the casework integration tests, which all drive the real endpoints. */
final class CaseworkHttp {

    static final String FIXTURE = "/fixtures/acme-2026-08/";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json;
    private final int port;

    CaseworkHttp(ObjectMapper json, int port) {
        this.json = json;
        this.port = port;
    }

    URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    AuthResponse register() throws Exception {
        String body = json.writeValueAsString(Map.of("tenantName", "T-" + UUID.randomUUID(),
            "email", "o-" + UUID.randomUUID() + "@x.com", "password", "Password!1"));
        return json.readValue(post("/api/v1/auth/register", null, body).body(), AuthResponse.class);
    }

    AuthResponse inviteAndLogin(AuthResponse owner, String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@x.com";
        JsonNode invited = json.readTree(post("/api/v1/users/invite", owner.token(),
            json.writeValueAsString(Map.of("email", email, "role", role))).body());
        String login = json.writeValueAsString(Map.of("tenantId", owner.tenantId().toString(), "email", email,
            "password", invited.get("temporaryPassword").asString()));
        return json.readValue(post("/api/v1/auth/login", null, login).body(), AuthResponse.class);
    }

    HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode tree(HttpResponse<String> r) { return json.readTree(r.body()); }

    UUID createCase(String token, String caseRef) throws Exception {
        HttpResponse<String> r = post("/api/v1/reconciliation/cases", token, caseBody(caseRef));
        if (r.statusCode() != 201 && r.statusCode() != 200) throw new IllegalStateException(r.statusCode() + " " + r.body());
        return UUID.fromString(tree(r).get("reconciliationCase").get("id").asString());
    }

    String caseBody(String caseRef) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseRef", caseRef);
        m.put("title", "August 2026 cross-provider reconciliation");
        m.put("periodStart", "2026-08-01T00:00:00Z");
        m.put("periodEnd", "2026-09-01T00:00:00Z");
        m.put("settlementSlaDays", 2);
        return json.writeValueAsString(m);
    }

    HttpResponse<String> upload(String token, UUID caseId, String sourceType, String sourceIdentity, String profile,
                                String filename, byte[] content) throws Exception {
        return multipart("/api/v1/reconciliation/cases/" + caseId + "/imports", token,
            Map.of("sourceType", sourceType, "sourceIdentity", sourceIdentity, "profile", profile), filename, content);
    }

    HttpResponse<String> multipart(String path, String token, Map<String, String> fields, String filename,
                                   byte[] content) throws Exception {
        String boundary = "----tl" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (var e : fields.entrySet()) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n"
                + e.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
            + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }

    static byte[] fixture(String name) throws IOException {
        try (InputStream in = CaseworkHttp.class.getResourceAsStream(FIXTURE + name)) {
            if (in == null) throw new IOException("fixture missing: " + name);
            return in.readAllBytes();
        }
    }

    /** Uploads the four ACME files in a fixed order. */
    void uploadAcme(String token, UUID caseId) throws Exception {
        expect2xx(upload(token, caseId, "INTERNAL", "acme-ledger", "internal-expected", "internal-expected.csv", fixture("internal-expected.csv")));
        expect2xx(upload(token, caseId, "PROVIDER_TRANSACTION", "provider-b", "provider-transactions", "provider-b-transactions.csv", fixture("provider-b-transactions.csv")));
        expect2xx(upload(token, caseId, "PROVIDER_TRANSACTION", "provider-a", "provider-transactions", "provider-a-transactions.csv", fixture("provider-a-transactions.csv")));
        expect2xx(upload(token, caseId, "SETTLEMENT", "provider-b", "provider-settlement", "provider-b-settlement.csv", fixture("provider-b-settlement.csv")));
    }

    static void expect2xx(HttpResponse<String> r) {
        if (r.statusCode() / 100 != 2) throw new IllegalStateException(r.statusCode() + " " + r.body());
    }
}
