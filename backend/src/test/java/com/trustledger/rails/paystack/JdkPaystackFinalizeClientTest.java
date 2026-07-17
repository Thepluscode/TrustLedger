package com.trustledger.rails.paystack;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JdkPaystackFinalizeClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void postsTransferCodeAndOtpToFinalizeEndpoint() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/transfer/finalize_transfer", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200,
                "{\"status\":true,\"message\":\"Transfer has been queued\",\"data\":{"
                    + "\"status\":\"pending\",\"reference\":\"paystack_1234567890\","
                    + "\"transfer_code\":\"TRF_native\"}}");
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        JdkPaystackApiClient client = new JdkPaystackApiClient(HttpClient.newHttpClient(),
            new ObjectMapper(), base, Duration.ofSeconds(2));

        PaystackApiClient.PaystackResponse response = client.finalizeTransfer("sk_test_not-real",
            new PaystackApiClient.FinalizeTransferRequest("TRF_native", "123456"));

        assertEquals("/transfer/finalize_transfer", path.get());
        assertTrue(body.get().contains("\"transfer_code\":\"TRF_native\""));
        assertTrue(body.get().contains("\"otp\":\"123456\""));
        assertEquals("pending", response.status());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
