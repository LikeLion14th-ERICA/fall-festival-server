package dev.espero.festival.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class AdminAuthApiIntegrationTest {

    private static final String ORIGIN = "https://admin.test.invalid";
    private static final String USERNAME = "bootstrap-admin";
    private static final String PASSWORD = "test-bootstrap-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("festival.admin-auth.bootstrap-username", () -> USERNAME);
        registry.add("festival.admin-auth.bootstrap-password", () -> PASSWORD);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AdminTokenService tokenService;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void bootstrapLoginRefreshRotationMeAndLogoutWorkEndToEnd() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v2/admin/sessions")
                .header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"bootstrap-admin","password":"test-bootstrap-password"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.admin.username").value(USERNAME))
            .andExpect(jsonPath("$.data.admin.authority").value("ADMIN"))
            .andReturn();
        String accessToken = JsonPath.read(login.getResponse().getContentAsString(), "$.data.accessToken");
        String firstRefresh = cookieValue(login);

        mockMvc.perform(get("/api/v2/admin/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value(USERNAME));

        MvcResult refresh = mockMvc.perform(post("/api/v2/admin/sessions/refresh")
                .header("Origin", ORIGIN)
                .cookie(new Cookie("__Host-festival-admin-refresh", firstRefresh)))
            .andExpect(status().isOk())
            .andReturn();
        String rotatedAccess = JsonPath.read(refresh.getResponse().getContentAsString(), "$.data.accessToken");
        String rotatedRefresh = cookieValue(refresh);
        assertThat(rotatedRefresh).isNotEqualTo(firstRefresh);

        mockMvc.perform(post("/api/v2/admin/sessions/refresh")
                .header("Origin", ORIGIN)
                .cookie(new Cookie("__Host-festival-admin-refresh", firstRefresh)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("ADMIN_REFRESH_TOKEN_INVALID"));

        MvcResult logout = mockMvc.perform(delete("/api/v2/admin/sessions/current")
                .header("Origin", ORIGIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rotatedAccess)
                .cookie(new Cookie("__Host-festival-admin-refresh", rotatedRefresh)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.loggedOut").value(true))
            .andReturn();
        assertThat(logout.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

        mockMvc.perform(post("/api/v2/admin/sessions/refresh")
                .header("Origin", ORIGIN)
                .cookie(new Cookie("__Host-festival-admin-refresh", rotatedRefresh)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshRotatesExactlyOnce() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v2/admin/sessions")
                .header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"bootstrap-admin","password":"test-bootstrap-password"}
                    """))
            .andExpect(status().isOk())
            .andReturn();
        String oldRefresh = cookieValue(login);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<MvcResult> results;
        try {
            Future<MvcResult> first = executor.submit(() -> refreshWhenReleased(oldRefresh, ready, start));
            Future<MvcResult> second = executor.submit(() -> refreshWhenReleased(oldRefresh, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertThat(results).extracting(result -> result.getResponse().getStatus())
            .containsExactlyInAnyOrder(200, 401);
        MvcResult success = results.stream()
            .filter(result -> result.getResponse().getStatus() == 200)
            .findFirst()
            .orElseThrow();
        MvcResult failure = results.stream()
            .filter(result -> result.getResponse().getStatus() == 401)
            .findFirst()
            .orElseThrow();
        String newRefresh = cookieValue(success);

        assertThat(failure.getResponse().getContentAsString()).contains("ADMIN_REFRESH_TOKEN_INVALID");
        assertThat(jdbc.queryForObject(
            "SELECT revoked_at IS NOT NULL FROM admin_refresh_sessions WHERE token_hash = ?",
            Boolean.class,
            tokenService.hashRefreshToken(oldRefresh)
        )).isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM admin_refresh_sessions WHERE token_hash = ? AND revoked_at IS NULL",
            Long.class,
            tokenService.hashRefreshToken(newRefresh)
        )).isOne();
    }

    private MvcResult refreshWhenReleased(
        String refreshToken,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent refresh start barrier timed out");
        }
        return mockMvc.perform(post("/api/v2/admin/sessions/refresh")
                .header("Origin", ORIGIN)
                .cookie(new Cookie("__Host-festival-admin-refresh", refreshToken)))
            .andReturn();
    }

    private String cookieValue(MvcResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).contains("Secure", "HttpOnly", "SameSite=Strict");
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }
}
