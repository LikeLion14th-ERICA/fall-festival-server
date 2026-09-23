package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.auth.AdminPrincipal;
import dev.espero.festival.cleanup.CleanupTargetContext;
import dev.espero.festival.cleanup.LoveLetterCleanupTarget;
import dev.espero.festival.support.PostgresTestImages;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
    "festival.love-letter.allowed-origin=http://localhost:5173",
    "festival.love-letter.key-base64=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class LoveLetterFlowIntegrationTest {
    private static final UUID ADMIN = UUID.fromString("173c3e4b-bfac-44af-933a-856730581c56");
    private static final String ORIGIN = "http://localhost:5173";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired WebApplicationContext context;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;
    @Autowired LoveLetterCleanupTarget cleanup;
    @Autowired LoveLetterService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        clock.set(OffsetDateTime.parse("2030-10-01T12:00:00+09:00"));
        jdbc.update("DELETE FROM love_letter_participants", Map.of());
        jdbc.update("DELETE FROM love_letter_settings", Map.of());
        jdbc.update("DELETE FROM admin_audit_events WHERE admin_id=:admin", Map.of("admin", ADMIN));
        jdbc.update("DELETE FROM admin_accounts WHERE id=:admin", Map.of("admin", ADMIN));
        jdbc.update("""
            INSERT INTO admin_accounts(id,username,password_hash,authority,enabled,created_at,updated_at)
            VALUES (:id,'love-admin','test-only','ADMIN',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, new MapSqlParameterSource("id", ADMIN));
    }

    @Test
    void loveLetterFlowWaitsOneMinuteAndKeepsResultsPrivate() throws Exception {
        adminPut("/api/v2/admin/love-letters/configuration", """
            {"opensAt":"2030-10-01T09:00:00+09:00","closesAt":"2030-10-03T22:00:00+09:00","consentVersion":"v1"}
            """).andExpect(status().isOk());
        String male = seed("MALE");
        String female = seed("FEMALE");
        assertThat(male).isNotEqualTo(female);
        adminPut("/api/v2/admin/love-letters/settings", "{\"enabled\":true}").andExpect(status().isOk());

        MvcResult start = mvc.perform(post("/api/v2/love-letter-participants").header("Origin", ORIGIN))
            .andExpect(status().isCreated()).andReturn();
        String cookieValue = start.getResponse().getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
        Cookie cookie = new Cookie("__Host-festival-love", cookieValue);
        String csrf = mapper.readTree(start.getResponse().getContentAsString()).path("data").path("csrfToken").asText();
        String body = letter("MALE");

        mvc.perform(post("/api/v2/love-letters").cookie(cookie).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", csrf).header("Idempotency-Key", "love-test-key-0001")
                .contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("WAITING"))
            .andExpect(jsonPath("$.data.drawAvailableAt").exists());
        mvc.perform(post("/api/v2/love-letter-draws").cookie(cookie).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", csrf).header("Idempotency-Key", "draw-test-key-0001"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOVE_WAITING"));
        mvc.perform(get("/api/v2/love-letter-status").cookie(cookie))
            .andExpect(jsonPath("$.data.state").value("WAITING"))
            .andExpect(jsonPath("$.data.canDraw").value(false));
        clock.set(OffsetDateTime.parse("2030-10-01T12:01:00+09:00"));
        mvc.perform(get("/api/v2/love-letter-status").cookie(cookie))
            .andExpect(jsonPath("$.data.state").value("DRAW_READY"))
            .andExpect(jsonPath("$.data.canDraw").value(true));
        MvcResult drawn = mvc.perform(post("/api/v2/love-letter-draws").cookie(cookie).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", csrf).header("Idempotency-Key", "draw-test-key-0001"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SEALED"))
            .andExpect(jsonPath("$.data.contact").doesNotExist()).andReturn();
        String exchange = mapper.readTree(drawn.getResponse().getContentAsString()).path("data").path("exchangeId").asText();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges", Map.of(), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letters", Map.of(), Long.class)).isEqualTo(3L);
        String cipher = jdbc.queryForObject("SELECT contact_cipher FROM love_letters WHERE created_date='2030-10-01' AND author_id IN (SELECT id FROM love_letter_participants WHERE token_sha256 IS NOT NULL)", Map.of(), String.class);
        assertThat(cipher).doesNotContain("@mock-contact");

        mvc.perform(post("/api/v2/love-letter-draws").cookie(cookie).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", csrf).header("Idempotency-Key", "draw-test-key-0001"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.exchangeId").value(exchange));
        mvc.perform(post("/api/v2/love-letter-results/" + exchange + "/open").cookie(cookie)
                .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", csrf))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.contact").value("@mock-contact"));
        mvc.perform(get("/api/v2/love-letter-status").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("OPENED"))
            .andExpect(jsonPath("$.data.contact").value("@mock-contact"))
            .andExpect(jsonPath("$.data.name").doesNotExist());
        mvc.perform(post("/api/v2/love-letter-results/" + exchange + "/open")
                .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", csrf))
            .andExpect(status().isForbidden());
        UUID delivered = jdbc.queryForObject("SELECT letter_id FROM love_letter_exchanges WHERE id=:id",
            Map.of("id", UUID.fromString(exchange)), UUID.class);
        mvc.perform(asAdmin(post("/api/v2/admin/love-letters/" + delivered + "/block")))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v2/love-letter-status").cookie(cookie))
            .andExpect(jsonPath("$.data.state").value("RESULT_BLOCKED"))
            .andExpect(jsonPath("$.data.contact").doesNotExist());
    }

    @Test
    void emptyPoolKeepsRegisteredLetterAndSeededInvitationDrawsOnlyOnce() throws Exception {
        adminPut("/api/v2/admin/love-letters/configuration", """
            {"opensAt":"2030-10-01T09:00:00+09:00","closesAt":"2030-10-03T22:00:00+09:00","consentVersion":"v1"}
            """).andExpect(status().isOk());
        seed("MALE");
        String femaleInvite = seed("FEMALE");
        adminPut("/api/v2/admin/love-letters/settings", "{\"enabled\":true}").andExpect(status().isOk());

        Session first = startSession();
        mvc.perform(post("/api/v2/love-letters").cookie(first.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", first.csrf()).header("Idempotency-Key", "first-draw")
                .contentType("application/json").content(letter("MALE")))
            .andExpect(status().isOk());
        Session second = startSession();
        mvc.perform(post("/api/v2/love-letters").cookie(second.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", second.csrf()).header("Idempotency-Key", "second-draw")
                .contentType("application/json").content(letter("MALE")))
            .andExpect(status().isOk());
        clock.set(OffsetDateTime.parse("2030-10-01T12:01:01+09:00"));
        mvc.perform(post("/api/v2/love-letter-draws").cookie(first.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", first.csrf()).header("Idempotency-Key", "first-draw-result"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v2/love-letter-draws").cookie(second.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", second.csrf()).header("Idempotency-Key", "second-draw-result"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOVE_POOL_EMPTY"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letters", Map.of(), Long.class)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_participation_days WHERE state='COMPLETED'", Map.of(), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_participation_days WHERE state='PENDING'", Map.of(), Long.class)).isEqualTo(1L);

        Session invitedBrowser = startSession();
        MvcResult claimed = mvc.perform(post("/api/v2/love-letter-invitations/claim")
                .cookie(invitedBrowser.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", invitedBrowser.csrf()).contentType("application/json")
                .content("{\"invitationToken\":\"" + femaleInvite + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SEEDED")).andReturn();
        String newCookieValue = claimed.getResponse().getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
        Cookie invitedCookie = new Cookie("__Host-festival-love", newCookieValue);
        String invitedCsrf = mapper.readTree(claimed.getResponse().getContentAsString()).path("data").path("csrfToken").asText();
        mvc.perform(post("/api/v2/love-letter-seeded-draws").cookie(invitedCookie).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", invitedCsrf).header("Idempotency-Key", "seeded-draw"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SEALED"));
        mvc.perform(post("/api/v2/love-letter-invitations/claim")
                .cookie(invitedBrowser.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", invitedBrowser.csrf()).contentType("application/json")
                .content("{\"invitationToken\":\"" + femaleInvite + "\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOVE_INVITATION_INVALID"));
    }

    private Session startSession() throws Exception {
        MvcResult started = mvc.perform(post("/api/v2/love-letter-participants").header("Origin", ORIGIN))
            .andExpect(status().isCreated()).andReturn();
        String cookieValue = started.getResponse().getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
        String csrf = mapper.readTree(started.getResponse().getContentAsString()).path("data").path("csrfToken").asText();
        return new Session(new Cookie("__Host-festival-love", cookieValue), csrf);
    }

    private record Session(Cookie cookie, String csrf) {}

    @Test
    void closedFestivalDeletesEncryptedParticipantTreesAfterSevenDays() throws Exception {
        adminPut("/api/v2/admin/love-letters/configuration", """
            {"opensAt":"2030-10-01T09:00:00+09:00","closesAt":"2030-10-03T22:00:00+09:00","consentVersion":"v1"}
            """).andExpect(status().isOk());
        seed("MALE");
        seed("FEMALE");
        var before = new CleanupTargetContext(jdbc, Instant.parse("2030-10-10T12:59:59Z"), 100, true);
        assertThat(cleanup.run(before).eligibleCount()).isZero();
        var dry = new CleanupTargetContext(jdbc, Instant.parse("2030-10-10T13:00:01Z"), 100, true);
        assertThat(cleanup.run(dry).eligibleCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letters", Map.of(), Long.class)).isEqualTo(2L);
        var deleting = new CleanupTargetContext(jdbc, Instant.parse("2030-10-10T13:00:01Z"), 100, false);
        assertThat(cleanup.run(deleting).deletedCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letters", Map.of(), Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_invitations", Map.of(), Long.class)).isZero();
    }

    @Test
    void concurrentRequestsForLastLetterCommitOnlyOneDailyParticipation() throws Exception {
        adminPut("/api/v2/admin/love-letters/configuration", """
            {"opensAt":"2030-10-01T09:00:00+09:00","closesAt":"2030-10-03T22:00:00+09:00","consentVersion":"v1"}
            """).andExpect(status().isOk());
        seed("MALE");
        seed("FEMALE");
        adminPut("/api/v2/admin/love-letters/settings", "{\"enabled\":true}").andExpect(status().isOk());
        String first = service.start(null).cookie();
        String second = service.start(null).cookie();
        var input = new LoveLetterService.Input("MALE", "별명", "한 줄", "@mock-contact", true, true, "v1");
        service.register(first, input, "first-registration");
        service.register(second, input, "second-registration");
        clock.set(OffsetDateTime.parse("2030-10-01T12:01:01+09:00"));
        var ready = new java.util.concurrent.CountDownLatch(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var jobs = List.of(first, second).stream().map(token -> executor.submit(() -> {
                ready.countDown();
                go.await();
                try { return service.draw(token, UUID.randomUUID().toString()).state(); }
                catch (ApiException error) { return error.code(); }
            })).toList();
            ready.await();
            go.countDown();
            var outcomes = jobs.stream().map(job -> {
                try { return job.get(); } catch (Exception error) { throw new AssertionError(error); }
            }).toList();
            assertThat(outcomes).containsExactlyInAnyOrder("SEALED", "LOVE_POOL_EMPTY");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges", Map.of(), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_participation_days WHERE state='COMPLETED'", Map.of(), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_participation_days WHERE state='PENDING'", Map.of(), Long.class)).isEqualTo(1L);
    }

    private String seed(String gender) throws Exception {
        MvcResult result = mvc.perform(asAdmin(post("/api/v2/admin/love-letters/seeds"))
                .contentType("application/json")
                .content("{\"operatingDate\":\"2030-10-01\",\"consentAt\":\"2030-09-30T12:00:00+09:00\",\"letter\":" + letter(gender) + "}"))
            .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("invitationToken").asText();
    }

    private static String letter(String gender) {
        return "{\"gender\":\"" + gender + "\",\"name\":\"개발용 별명\",\"message\":\"한 줄 쪽지\","
            + "\"contact\":\"@mock-contact\",\"adultConfirmed\":true,\"ownContactConfirmed\":true,\"consentVersion\":\"v1\"}";
    }

    private org.springframework.test.web.servlet.ResultActions adminPut(String path, String body) throws Exception {
        return mvc.perform(asAdmin(put(path)).contentType("application/json").content(body));
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.with(authentication(new UsernamePasswordAuthenticationToken(
            new AdminPrincipal(ADMIN, "love-admin", "ADMIN"), null, List.of(new SimpleGrantedAuthority("ADMIN")))));
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
    }
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.EPOCH;
        void set(OffsetDateTime time) { now = time.toInstant(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
