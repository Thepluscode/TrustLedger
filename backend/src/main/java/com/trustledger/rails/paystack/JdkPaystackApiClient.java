package com.trustledger.rails.paystack;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** JDK HTTP implementation. It never logs or embeds the supplied secret key in exception messages. */
@Component
public class JdkPaystackApiClient implements PaystackApiClient {

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI baseUri;
    private final Duration timeout;

    @Autowired
    public JdkPaystackApiClient(ObjectMapper json,
                                @Value("${trustledger.paystack.base-url:https://api.paystack.co}") String baseUrl,
                                @Value("${trustledger.paystack.timeout-seconds:10}") long timeoutSeconds) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build(), json,
            URI.create(baseUrl), Duration.ofSeconds(timeoutSeconds));
    }

    JdkPaystackApiClient(HttpClient http, ObjectMapper json, URI baseUri, Duration timeout) {
        this.http = http;
        this.json = json;
        this.baseUri = baseUri;
        this.timeout = timeout;
    }

    @Override
    public PaystackResponse initiateTransfer(String secretKey, InitiateTransferRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "balance");
        body.put("amount", request.amountMinor());
        body.put("recipient", request.recipientCode());
        body.put("reference", request.reference());
        body.put("reason", request.reason());
        body.put("currency", request.currency());
        return send("POST", "/transfer", secretKey, write(body), true);
    }

    @Override
    public PaystackResponse verifyTransfer(String secretKey, String reference) {
        String encoded = URLEncoder.encode(reference, StandardCharsets.UTF_8);
        return send("GET", "/transfer/verify/" + encoded, secretKey, null, false);
    }

    private PaystackResponse send(String method, String path, String secretKey, String body,
                                  boolean clientErrorsAreDefinitive) {
        if (secretKey == null || secretKey.isBlank()) throw new IllegalArgumentException("Paystack secret is required");
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(timeout)
            .header("Authorization", "Bearer " + secretKey)
            .header("Accept", "application/json");
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").method(method,
            HttpRequest.BodyPublishers.ofString(body));

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmbiguousPaystackException("Paystack request was interrupted", e);
        } catch (IOException e) {
            throw new AmbiguousPaystackException("Paystack transport failed without an authoritative response", e);
        }

        int code = response.statusCode();
        if (code == 429 || code >= 500) {
            throw new AmbiguousPaystackException("Paystack returned a transient server response (HTTP " + code + ")");
        }
        Map<String, Object> payload = read(response.body());
        String message = text(payload.get("message"));
        Map<String, Object> data = map(payload.get("data"));
        String status = text(data.get("status"));
        String reference = text(data.get("reference"));
        String transferCode = text(data.get("transfer_code"));
        boolean definitiveFailure = clientErrorsAreDefinitive && code >= 400 && code < 500;
        return new PaystackResponse(status, reference, transferCode, message, code, definitiveFailure);
    }

    private String write(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Could not encode Paystack request", e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try { return json.readValue(value, Map.class); }
        catch (Exception e) { throw new AmbiguousPaystackException("Paystack response could not be interpreted", e); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) { return value == null ? null : value.toString(); }
}