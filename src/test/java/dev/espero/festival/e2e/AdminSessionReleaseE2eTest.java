package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.support.PostgresTestImages;
import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real loopback HTTP checks for the administrator session lifecycle. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
        "server.address=127.0.0.1",
        "festival.admin-auth.jwt-signing-secret=auth-session-e2e-signing-secret-32-bytes",
        "festival.admin-auth.allowed-origin=https://admin.auth-e2e.test",
        "festival.admin-auth.bootstrap-username=auth-e2e-admin",
        "festival.admin-auth.bootstrap-password=auth-e2e-password",
        "spring.flyway.enabled=false",
        "festival.cleanup.schedule-enabled=false",
        "festival.cleanup.dry-run=true",
        "festival.cleanup.datasource.url=",
        "festival.cleanup.datasource.username=",
        "festival.cleanup.datasource.password=",
        "festival.cleanup.datasource.role=",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
            + "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration"
    }
)
@ActiveProfiles("db")
@Testcontainers
@Import(AdminSessionReleaseE2eTest.DataSourceConfiguration.class)
class AdminSessionReleaseE2eTest {

    private static final String ORIGIN = "https://admin.auth-e2e.test";
    private static final String COOKIE = "__Host-festival-admin-refresh";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        migrate();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.hikari.username", POSTGRES::getUsername);
        registry.add("spring.datasource.hikari.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.festivalId", () ->
            "ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    }

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder().build();

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void userSessionLifecycleRotatesAndRevokesRefreshCredentials() throws Exception {
        HttpResponse<String> anonymous = send(get("/api/v2/admin/me").build());
        assertError(anonymous, 401, "UNAUTHORIZED");

        HttpResponse<String> missingRefresh = send(post("/api/v2/admin/sessions/refresh")
            .header("Origin", ORIGIN).build());
        assertError(missingRefresh, 401, "ADMIN_REFRESH_TOKEN_INVALID");

        HttpResponse<String> login = send(post("/api/v2/admin/sessions")
            .header("Origin", ORIGIN)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"auth-e2e-admin\",\"password\":\"auth-e2e-password\"}"
            ))
            .build());
        assertThat(login.statusCode()).isEqualTo(200);
        String access = json(login, "$.data.accessToken");
        String firstCookie = cookie(login);
        assertThat(access).isNotBlank();

        HttpResponse<String> me = send(get("/api/v2/admin/me")
            .header("Authorization", "Bearer " + access).build());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json(me, "$.data.username")).isEqualTo("auth-e2e-admin");

        HttpResponse<String> rotated = send(post("/api/v2/admin/sessions/refresh")
            .header("Origin", ORIGIN).header("Cookie", firstCookie).build());
        assertThat(rotated.statusCode()).isEqualTo(200);
        String rotatedAccess = json(rotated, "$.data.accessToken");
        String secondCookie = cookie(rotated);
        assertThat(secondCookie).isNotEqualTo(firstCookie);

        HttpResponse<String> replay = send(post("/api/v2/admin/sessions/refresh")
            .header("Origin", ORIGIN).header("Cookie", firstCookie).build());
        assertError(replay, 401, "ADMIN_REFRESH_TOKEN_INVALID");

        HttpResponse<String> logout = send(delete("/api/v2/admin/sessions/current")
            .header("Origin", ORIGIN)
            .header("Authorization", "Bearer " + rotatedAccess)
            .header("Cookie", secondCookie)
            .build());
        assertThat(logout.statusCode()).isEqualTo(200);
        assertThat(json(logout, "$.data.loggedOut")).isEqualTo("true");
        assertThat(requiredHeader(logout, "Set-Cookie")).contains(COOKIE + "=", "Max-Age=0");

        HttpResponse<String> revoked = send(post("/api/v2/admin/sessions/refresh")
            .header("Origin", ORIGIN).header("Cookie", secondCookie).build());
        assertError(revoked, 401, "ADMIN_REFRESH_TOKEN_INVALID");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void sessionBoundaryRejectsBadOriginCredentialsTokensAndMalformedBodies() throws Exception {
        HttpResponse<String> noOrigin = send(post("/api/v2/admin/sessions")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"auth-e2e-admin\",\"password\":\"auth-e2e-password\"}"
            )).build());
        assertError(noOrigin, 403, "ADMIN_CSRF_INVALID");

        HttpResponse<String> wrongPassword = send(post("/api/v2/admin/sessions")
            .header("Origin", ORIGIN).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"auth-e2e-admin\",\"password\":\"wrong\"}"
            )).build());
        assertError(wrongPassword, 401, "ADMIN_AUTHENTICATION_FAILED");
        assertThat(wrongPassword.headers().allValues("Set-Cookie")).isEmpty();

        HttpResponse<String> malformed = send(post("/api/v2/admin/sessions")
            .header("Origin", ORIGIN).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"username\":")).build());
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed, "$.error.code")).isEqualTo("INVALID_REQUEST");

        HttpResponse<String> malformedToken = send(get("/api/v2/admin/me")
            .header("Authorization", "Bearer not-a-jwt").build());
        assertError(malformedToken, 401, "UNAUTHORIZED");

        HttpResponse<String> logoutWithoutAccess = send(delete("/api/v2/admin/sessions/current")
            .header("Origin", ORIGIN).build());
        assertError(logoutWithoutAccess, 401, "UNAUTHORIZED");
    }

    private HttpRequest.Builder get(String path) {
        return request(path).GET();
    }

    private HttpRequest.Builder post(String path) {
        return request(path).POST(
            HttpRequest.BodyPublishers.noBody());
    }

    private HttpRequest.Builder delete(String path) {
        return request(path).DELETE();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(REQUEST_TIMEOUT);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String cookie(HttpResponse<?> response) {
        String header = requiredHeader(response, "Set-Cookie");
        assertThat(header).contains(COOKIE + "=", "Secure", "HttpOnly", "SameSite=Strict");
        return header.substring(0, header.indexOf(';'));
    }

    private static String requiredHeader(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElseThrow();
    }

    private static String json(HttpResponse<String> response, String path) {
        Object value = JsonPath.read(response.body(), path);
        return String.valueOf(value);
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json(response, "$.error.code")).isEqualTo(code);
    }

    private static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .placeholders(Map.of("festivalId", "ec00912b-763f-4f8f-8f57-4bdfc389ccbf"))
            .load()
            .migrate();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DataSourceConfiguration {

        @Bean(name = "dataSource")
        @Primary
        HikariDataSource e2eDataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
            return dataSource;
        }
    }
}
