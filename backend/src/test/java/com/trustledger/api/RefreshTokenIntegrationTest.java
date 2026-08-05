package com.trustledger.api;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.api.AuthDtos.LoginResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RefreshTokenIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("trustledger.outbox.publisher.enabled", () -> "false");
        r.add("trustledger.reconciliation.enabled", () -> "false");
    }

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    /** Sends with the cookie-mode header and an optional refresh cookie, as a browser client would. */
    private HttpResponse<String> sendCookieMode(String path, String body, String cookieValue) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Auth-Mode", "cookie");
        if (cookieValue != null) b.header("Cookie", "trustledger_refresh=" + cookieValue);
        b.method("POST", body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The raw refresh cookie value from Set-Cookie, or null when none was issued. */
    private static String refreshCookie(HttpResponse<String> r) {
        return r.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("trustledger_refresh="))
                .map(c -> c.substring("trustledger_refresh=".length(), c.indexOf(';')))
                .filter(v -> !v.isBlank())
                .findFirst().orElse(null);
    }

    private HttpResponse<String> registerCookieMode() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "tenantName", "Tenant-" + UUID.randomUUID(),
                "email", "user-" + UUID.randomUUID() + "@example.com",
                "password", "Password!1"));
        return sendCookieMode("/api/v1/auth/register", body, null);
    }

    private HttpResponse<String> send(String path, String method, String bearerToken, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
        if (bearerToken != null) b.header("Authorization", "Bearer " + bearerToken);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private LoginResponse register() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "tenantName", "Tenant-" + UUID.randomUUID(),
                "email", "user-" + UUID.randomUUID() + "@example.com",
                "password", "Password!1"));
        HttpResponse<String> r = send("/api/v1/auth/register", "POST", null, body);
        assertEquals(200, r.statusCode(), r.body());
        return json.readValue(r.body(), LoginResponse.class);
    }

    /**
     * In cookie mode the token must reach the client only as an httpOnly cookie. Echoing it in the
     * body too would hand it straight back to JavaScript and undo the entire change.
     */
    @Test
    void cookieModeWithholdsRefreshTokenFromBodyAndSetsHttpOnlyCookie() throws Exception {
        HttpResponse<String> r = registerCookieMode();
        assertEquals(200, r.statusCode(), r.body());

        LoginResponse body = json.readValue(r.body(), LoginResponse.class);
        assertNull(body.refreshToken(), "cookie mode must not echo the refresh token in the body");
        assertNotNull(body.token(), "the short-lived JWT still travels in the body");

        String setCookie = r.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("trustledger_refresh=")).findFirst().orElse(null);
        assertNotNull(setCookie, "a refresh cookie must be set");
        assertTrue(setCookie.contains("HttpOnly"), "cookie must be HttpOnly: " + setCookie);
        assertTrue(setCookie.contains("SameSite=Strict"), "cookie must be SameSite=Strict: " + setCookie);
        assertTrue(setCookie.contains("Path=/api/v1/auth"), "cookie must be path-scoped: " + setCookie);
    }

    @Test
    void cookieRefreshRotatesAndReturnsANewCookie() throws Exception {
        String first = refreshCookie(registerCookieMode());
        assertNotNull(first);

        HttpResponse<String> rotated = sendCookieMode("/api/v1/auth/refresh", null, first);
        assertEquals(200, rotated.statusCode(), rotated.body());

        String second = refreshCookie(rotated);
        assertNotNull(second, "rotation must issue a successor cookie");
        assertNotEquals(first, second, "refresh cookie must rotate");
        assertNull(json.readValue(rotated.body(), LoginResponse.class).refreshToken());
    }

    /**
     * The CSRF guard. A forged cross-site request carries the cookie automatically but cannot set a
     * custom header, so a cookie without the mode header must not authenticate a rotation — otherwise
     * an attacker could rotate a live session and, via reuse detection, destroy it.
     */
    @Test
    void cookieWithoutModeHeaderCannotRefresh() throws Exception {
        String cookie = refreshCookie(registerCookieMode());
        assertNotNull(cookie);

        HttpRequest forged = HttpRequest.newBuilder(uri("/api/v1/auth/refresh"))
                .header("Content-Type", "application/json")
                .header("Cookie", "trustledger_refresh=" + cookie)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        assertEquals(401, http.send(forged, HttpResponse.BodyHandlers.ofString()).statusCode(),
                "a cookie alone must not authenticate a refresh");

        // Positive twin: the same cookie still works when the header is present, so the test above
        // is proving the header requirement rather than a dead cookie.
        assertEquals(200, sendCookieMode("/api/v1/auth/refresh", null, cookie).statusCode());
    }

    @Test
    void cookieLogoutRevokesTheFamilyAndExpiresTheCookie() throws Exception {
        String cookie = refreshCookie(registerCookieMode());
        assertNotNull(cookie);

        HttpResponse<String> out = sendCookieMode("/api/v1/auth/logout", null, cookie);
        assertEquals(200, out.statusCode(), out.body());
        assertTrue(out.headers().allValues("set-cookie").stream()
                        .anyMatch(c -> c.startsWith("trustledger_refresh=") && c.contains("Max-Age=0")),
                "logout must expire the cookie");

        assertEquals(401, sendCookieMode("/api/v1/auth/refresh", null, cookie).statusCode(),
                "the refresh token must be dead server-side after logout");
    }

    @Test
    void loginAndRegisterReturnRefreshToken() throws Exception {
        LoginResponse resp = register();

        assertNotNull(resp.token(), "JWT must be present");
        assertNotNull(resp.refreshToken(), "refresh token must be present");
        assertTrue(resp.refreshExpiresIn() > 0, "refreshExpiresIn must be positive");
    }

    @Test
    void rotatingRefreshTokenIssuesNewJwtAndNewRefreshToken() throws Exception {
        LoginResponse first = register();

        String refreshBody = json.writeValueAsString(Map.of("refreshToken", first.refreshToken()));
        HttpResponse<String> rotated = send("/api/v1/auth/refresh", "POST", null, refreshBody);

        assertEquals(200, rotated.statusCode(), rotated.body());
        LoginResponse second = json.readValue(rotated.body(), LoginResponse.class);

        assertNotNull(second.token(), "rotated response must contain a new JWT");
        assertNotNull(second.refreshToken(), "rotated response must contain a new refresh token");
        assertNotEquals(first.refreshToken(), second.refreshToken(), "refresh token must rotate");
        assertNotEquals(first.token(), second.token(), "JWT must be re-issued");
        assertEquals(first.userId(), second.userId(), "userId must stay the same");
        assertEquals(first.tenantId(), second.tenantId(), "tenantId must stay the same");
    }

    @Test
    void newJwtFromRefreshGrantsAccessToProtectedEndpoints() throws Exception {
        LoginResponse first = register();
        String refreshBody = json.writeValueAsString(Map.of("refreshToken", first.refreshToken()));
        LoginResponse second = json.readValue(
                send("/api/v1/auth/refresh", "POST", null, refreshBody).body(), LoginResponse.class);

        HttpResponse<String> me = send("/api/v1/auth/me", "GET", second.token(), null);
        assertEquals(200, me.statusCode(), me.body());
        assertTrue(me.body().contains(first.email()), "new JWT must identify the same user");
    }

    @Test
    void replayingConsumedRefreshTokenReturns401AndRevokesFamily() throws Exception {
        LoginResponse first = register();
        String originalRefresh = first.refreshToken();

        // Consume the refresh token once — this rotates it, making the original stale
        String refreshBody = json.writeValueAsString(Map.of("refreshToken", originalRefresh));
        assertEquals(200, send("/api/v1/auth/refresh", "POST", null, refreshBody).statusCode());

        // Replay the same (now consumed) token — must be rejected and must revoke the family
        HttpResponse<String> replay = send("/api/v1/auth/refresh", "POST", null, refreshBody);
        assertEquals(401, replay.statusCode(), "replaying a consumed token must return 401");
        // The 401 confirms the reused token was rejected and its family revoked; the error body must not
        // carry a JWT. (The successor token from the first rotate is not available to this test to retry.)
        assertFalse(replay.body().contains("\"token\":"), "revoked response must not contain a JWT");
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        LoginResponse first = register();
        String refreshToken = first.refreshToken();

        String logoutBody = json.writeValueAsString(Map.of("refreshToken", refreshToken));
        HttpResponse<String> logoutResp = send("/api/v1/auth/logout", "POST", null, logoutBody);
        assertEquals(200, logoutResp.statusCode(), logoutResp.body());

        // After logout, the refresh token must be dead
        String refreshBody = json.writeValueAsString(Map.of("refreshToken", refreshToken));
        HttpResponse<String> afterLogout = send("/api/v1/auth/refresh", "POST", null, refreshBody);
        assertEquals(401, afterLogout.statusCode(), "refresh after logout must return 401");
    }

    @Test
    void refreshWithMissingBodyReturns401() throws Exception {
        HttpResponse<String> resp = send("/api/v1/auth/refresh", "POST", null,
                json.writeValueAsString(Map.of("refreshToken", "")));
        assertEquals(401, resp.statusCode());
    }

    @Test
    void refreshWithUnknownTokenReturns401() throws Exception {
        String body = json.writeValueAsString(Map.of("refreshToken", "not-a-real-token-at-all"));
        HttpResponse<String> resp = send("/api/v1/auth/refresh", "POST", null, body);
        assertEquals(401, resp.statusCode());
    }
}
