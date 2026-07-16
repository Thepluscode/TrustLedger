package com.trustledger.rails.paystack;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JdkPaystackApiClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void postsMinorUnitsRecipientReferenceAndBearerCredential() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/transfer", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200,
                "{\"status\":true,\"message\":\"Transfer initiated\",\"data\":{" +
                    "\"status\":\"pending\",\"reference\":\"paystack_1234567890\"," +
                    "\"transfer_code\":\"TRF_test\"}}");
        });
        server.start();

        JdkPaystackApiClient client = client();
        var response = client.initiateTransfer("sk_test_not-real",
            new PaystackApiClient.InitiateTransferRequest(100025L, "RCP_123456789",
                "paystack_1234567890", "TrustLedger payout", "NGN"));

        assertEquals("pending", response.status());
        assertEquals("TRF_test", response.transferCode());
        assertEquals("Bearer sk_test_not-real", authorization.get());
        assertTrue(body.get().contains("\"amount\":100025"));
        assertTrue(body.get().contains("\"recipient\":\"RCP_123456789\""));
        assertTrue(body.get().contains("\"source\":\"balance\""));
    }

    @Test
    void treatsServerFailureAsAmbiguousAndClientRejectionAsDefinitive() throws Exception {
        AtomicInteger responseCode = new AtomicInteger(500);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/transfer", exchange -> respond(exchange, responseCode.get(),
            "{\"status\":false,\"message\":\"provider error\",\"data\":null}"));
        server.start();
        JdkPaystackApiClient client = client();
        var request = new PaystackApiClient.InitiateTransferRequest(10000L, "RCP_test",
            "paystack_1234567890", "TrustLedger payout", "NGN");

        assertThrows(PaystackApiClient.AmbiguousPaystackException.class,
            () -> client.initiateTransfer("sk_test_not-real", request));

        responseCode.set(400);
        var rejection = client.initiateTransfer("sk_test_not-real", request);
        assertTrue(rejection.definitiveFailure());
        assertEquals(400, rejection.httpStatus());
    }

    private JdkPaystackApiClient client() {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new JdkPaystackApiClient(HttpClient.newHttpClient(), new ObjectMapper(), base,
            Duration.ofSeconds(2));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}