package dev.espero.festival.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.*;

/** Real Tomcat routing and application filters; probe handlers isolate policy selection from storage. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
    "server.servlet.context-path=/festival",
    "festival.admin-auth.allowed-origin=https://admin.test.invalid",
    "festival.rate-limit.trusted-proxy-hops=1",
    "festival.rate-limit.stamp-receipt.capacity=1",
    "festival.rate-limit.stamp-receipt.refill-per-second=0.000001",
    "festival.rate-limit.admin-login.capacity=1",
    "festival.rate-limit.admin-login.refill-per-second=0.000001"
})
@Import(EncodedPathSecurityHttpTest.Probe.class)
class EncodedPathSecurityHttpTest {
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void encodedApiPrefixCannotBypassTheJsonBodyLimit() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/festival/%61pi/v2/stamp-receipt-verifications"))
            .header("Content-Type", "application/json").header("X-Forwarded-For", "192.0.2.40")
            .POST(HttpRequest.BodyPublishers.ofString("\"" + "x".repeat(65536) + "\""));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void encodedReceiptPathsShareTheExhaustedDedicatedBucket() throws Exception {
        assertThat(send("POST", "/api/v2/stamp-receipt-verifications", null, "192.0.2.1").statusCode()).isEqualTo(200);
        for (String path : new String[] {"/api/v2/stamp-receipt-verifications",
            "/api/v2/stamp%2dreceipt-verifications", "/api/v2/stamp%2Dreceipt-verifications",
            "/%61pi/v2/stamp-receipt-verifications"}) {
            var response = send("POST", path, null, "192.0.2.1");
            assertThat(response.statusCode()).isEqualTo(429);
            assertThat(response.body()).contains("RATE_LIMITED");
            assertThat(response.headers().firstValue("Retry-After")).isPresent();
        }
        assertThat(send("POST", "/api/v2/stamp%2dreceipt-verifications", null, "192.0.2.2").statusCode()).isEqualTo(200);
    }

    @Test
    void encodedLoginAndRefreshShareTheLoginBucket() throws Exception {
        assertThat(send("POST", "/api/v2/admin/sessions", "https://admin.test.invalid", "192.0.2.3").statusCode()).isEqualTo(200);
        for (String path : new String[] {"/api/v2/admin/s%65ssions", "/api/v2/%61dmin/sessions/refresh"}) {
            assertThat(send("POST", path, "https://admin.test.invalid", "192.0.2.3").statusCode()).isEqualTo(429);
        }
    }

    @Test
    void encodedCookieRoutesRejectMissingAndHostileOrigins() throws Exception {
        int address = 10;
        for (String path : new String[] {"/api/v2/admin/s%65ssions", "/api/v2/%61dmin/sessions/refresh",
            "/api/v2/admin/sessions/curr%65nt"}) {
            String method = path.contains("curr") ? "DELETE" : "POST";
            for (String origin : new String[] {null, "https://attacker.invalid"}) {
                var response = send(method, path, origin, "192.0.2." + address++);
                assertThat(response.statusCode()).isEqualTo(403);
                assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            }
        }
        assertThat(send("POST", "/api/v2/admin/s%65ssions", "https://admin.test.invalid", "192.0.2.30").statusCode()).isEqualTo(200);
        var protectedResponse = send("GET", "/api/v2/%61dmin/path-probe", null, "192.0.2.31");
        assertThat(protectedResponse.statusCode()).isEqualTo(401);
        assertThat(protectedResponse.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    private HttpResponse<String> send(String method, String path, String origin, String address) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/festival" + path))
            .header("X-Forwarded-For", address).method(method, HttpRequest.BodyPublishers.noBody());
        if (origin != null) request.header("Origin", origin);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @RestController
    static class Probe {
        @PostMapping({"/api/v2/stamp-receipt-verifications", "/api/v2/admin/sessions", "/api/v2/admin/sessions/refresh"})
        String post() { return "reached"; }
        @DeleteMapping("/api/v2/admin/sessions/current")
        String delete() { return "reached"; }
        @GetMapping("/api/v2/admin/path-probe")
        String get() { return "reached"; }
    }
}
