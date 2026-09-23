package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    "festival.love-letter.key-base64=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "festival.love-letter.seed-retry-enabled=true",
    "festival.love-letter.seed-initial-delay-ms=3600000"
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
    @Autowired LoveLetterSeedAssignmentScheduler seedAssignmentScheduler;
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
            {"opensAt":"2030-10-01T09:00:00+09:00","closesAt":"2030-10-04T00:00:00+09:00","consentVersion":"v1"}
            """).andExpect(status().isOk());
        String male = seed("MALE");
        String female = seed("FEMALE");
        assertThat(male).isNotEqualTo(female);
        adminPut("/api/v2/admin/love-letters/settings", "{\"enabled\":true}").andExpect(status().isOk());
        mvc.perform(get("/api/v2/love-letter-guide"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.revealDelaySeconds").value(60))
            .andExpect(jsonPath("$.data.dailyOpensAt").value("09:00"))
            .andExpect(jsonPath("$.data.dailyClosesAt").value("24:00"));

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
            .andExpect(jsonPath("$.data.revealAt").exists())
            .andExpect(jsonPath("$.data.exchangeId").doesNotExist())
            .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get("/api/v2/love-letter-status").cookie(cookie))
            .andExpect(jsonPath("$.data.state").value("WAITING"))
            .andExpect(jsonPath("$.data.exchangeId").doesNotExist());
        UUID preassigned = jdbc.queryForObject("SELECT id FROM love_letter_exchanges", Map.of(), UUID.class);
        mvc.perform(post("/api/v2/love-letter-results/" + preassigned + "/open").cookie(cookie)
                .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", csrf))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOVE_WAITING"));
        clock.set(OffsetDateTime.parse("2030-10-01T12:01:00+09:00"));
        MvcResult drawn = mvc.perform(get("/api/v2/love-letter-status").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SEALED"))
            .andExpect(jsonPath("$.data.contact").doesNotExist()).andReturn();
        String exchange = mapper.readTree(drawn.getResponse().getContentAsString()).path("data").path("exchangeId").asText();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges", Map.of(), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letters", Map.of(), Long.class)).isEqualTo(3L);
        var ciphertext = jdbc.queryForMap("""
            SELECT name_cipher,body_cipher,contact_cipher FROM love_letters
            WHERE created_date='2030-10-01' AND author_id IN
              (SELECT id FROM love_letter_participants WHERE token_sha256 IS NOT NULL)
            """, Map.of());
        assertThat(ciphertext.get("name_cipher").toString()).doesNotContain("개발용 별명");
        assertThat(ciphertext.get("body_cipher").toString()).doesNotContain("한 줄 쪽지");
        assertThat(ciphertext.get("contact_cipher").toString()).doesNotContain("@mock-contact");
        assertThat(jdbc.queryForObject("""
            SELECT l.gender FROM love_letter_exchanges e JOIN love_letters l ON l.id=e.letter_id
            WHERE e.id=:id AND l.author_id<>e.receiver_id
            """, Map.of("id", UUID.fromString(exchange)), String.class)).isEqualTo("FEMALE");

        mvc.perform(get("/api/v2/love-letter-status").cookie(cookie))
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
        UUID receiver = jdbc.queryForObject("SELECT receiver_id FROM love_letter_exchanges WHERE id=:id",
            Map.of("id", UUID.fromString(exchange)), UUID.class);
        adminPut("/api/v2/admin/love-letters/participants/" + receiver + "/restriction", "{\"restricted\":true}")
            .andExpect(status().isOk());
        mvc.perform(get("/api/v2/love-letter-status").cookie(cookie))
            .andExpect(jsonPath("$.data.state").value("RESTRICTED"));
        adminPut("/api/v2/admin/love-letters/participants/" + receiver + "/restriction", "{\"restricted\":false}")
            .andExpect(status().isOk());
        mvc.perform(get("/api/v2/love-letter-status").cookie(cookie))
            .andExpect(jsonPath("$.data.state").value("RESULT_BLOCKED"));
        adminPut("/api/v2/admin/love-letters/settings", "{\"enabled\":false}").andExpect(status().isOk());
        mvc.perform(get("/api/v2/love-letter-status").cookie(cookie))
            .andExpect(jsonPath("$.data.state").value("CLOSED"));
        mvc.perform(post("/api/v2/love-letter-results/" + exchange + "/open").cookie(cookie)
                .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", csrf))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void emptyPoolDoesNotChargeParticipationAndSeededInvitationAssignsOnClaim() throws Exception {
        adminPut("/api/v2/admin/love-letters/configuration", """
            {"opensAt":"2030-10-01T09:00:00+09:00","closesAt":"2030-10-04T00:00:00+09:00","consentVersion":"v1"}
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
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOVE_POOL_EMPTY"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letters", Map.of(), Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_participation_days WHERE state='COMPLETED'", Map.of(), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_participation_days WHERE state='PENDING'", Map.of(), Long.class)).isZero();
        mvc.perform(get("/api/v2/love-letter-status").cookie(second.cookie()))
            .andExpect(jsonPath("$.data.canParticipate").value(true));

        Session invitedBrowser = startSession();
        MvcResult claimed = mvc.perform(post("/api/v2/love-letter-invitations/claim")
                .cookie(invitedBrowser.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", invitedBrowser.csrf()).contentType("application/json")
                .content("{\"invitationToken\":\"" + femaleInvite + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("WAITING")).andReturn();
        String newCookieValue = claimed.getResponse().getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
        Cookie invitedCookie = new Cookie("__Host-festival-love", newCookieValue);
        clock.set(OffsetDateTime.parse("2030-10-01T12:01:01+09:00"));
        mvc.perform(get("/api/v2/love-letter-status").cookie(invitedCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SEALED"));
        mvc.perform(post("/api/v2/love-letter-invitations/claim")
                .cookie(invitedBrowser.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", invitedBrowser.csrf()).contentType("application/json")
                .content("{\"invitationToken\":\"" + femaleInvite + "\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOVE_INVITATION_INVALID"));
    }

    @Test
    void seededClaimRetriesAssignmentAfterPoolIsReplenished() throws Exception {
        adminPut("/api/v2/admin/love-letters/configuration", """
            {"opensAt":"2030-10-01T09:00:00+09:00","closesAt":"2030-10-04T00:00:00+09:00","consentVersion":"v1"}
            """).andExpect(status().isOk());
        seed("MALE");
        String femaleInvite = seed("FEMALE");
        adminPut("/api/v2/admin/love-letters/settings", "{\"enabled\":true}").andExpect(status().isOk());

        Session publicWriter = startSession();
        mvc.perform(post("/api/v2/love-letters").cookie(publicWriter.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", publicWriter.csrf()).header("Idempotency-Key", "consume-male-pool")
                .contentType("application/json").content(letter("FEMALE")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("WAITING"));
        Session inviteBrowser = startSession();
        MvcResult claim = mvc.perform(post("/api/v2/love-letter-invitations/claim")
                .cookie(inviteBrowser.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", inviteBrowser.csrf()).contentType("application/json")
                .content("{\"invitationToken\":\"" + femaleInvite + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SEEDED")).andReturn();
        String invitedCookieValue = claim.getResponse().getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
        Cookie invitedCookie = new Cookie("__Host-festival-love", invitedCookieValue);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges", Map.of(), Long.class)).isEqualTo(1L);

        seed("MALE");
        seedAssignmentScheduler.assignPending();
        mvc.perform(get("/api/v2/love-letter-status").cookie(invitedCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("WAITING"));
        clock.set(OffsetDateTime.parse("2030-10-01T12:01:00+09:00"));
        mvc.perform(get("/api/v2/love-letter-status").cookie(invitedCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SEALED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges", Map.of(), Long.class)).isEqualTo(2L);
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
    void dailyWindowOpensAtNineAndClosesAtMidnight() throws Exception {
        adminPut("/api/v2/admin/love-letters/configuration", """
            {"opensAt":"2030-10-01T09:00:00+09:00","closesAt":"2030-10-04T00:00:00+09:00","consentVersion":"v1"}
            """).andExpect(status().isOk());
        seed("MALE");
        seed("FEMALE");
        adminPut("/api/v2/admin/love-letters/settings", "{\"enabled\":true}").andExpect(status().isOk());

        clock.set(OffsetDateTime.parse("2030-10-01T08:59:00+09:00"));
        mvc.perform(get("/api/v2/love-letter-status"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("BEFORE_OPEN"))
            .andExpect(jsonPath("$.data.nextParticipationAt").value("2030-10-01T00:00:00Z"));
        clock.set(OffsetDateTime.parse("2030-10-01T09:00:00+09:00"));
        mvc.perform(get("/api/v2/love-letter-status"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("WRITABLE"));
        clock.set(OffsetDateTime.parse("2030-10-02T00:00:00+09:00"));
        mvc.perform(get("/api/v2/love-letter-status"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("BEFORE_OPEN"))
            .andExpect(jsonPath("$.data.nextParticipationAt").value("2030-10-02T00:00:00Z"));
        clock.set(OffsetDateTime.parse("2030-10-02T08:59:00+09:00"));
        mvc.perform(get("/api/v2/love-letter-status"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("BEFORE_OPEN"));
        clock.set(OffsetDateTime.parse("2030-10-02T09:00:00+09:00"));
        mvc.perform(get("/api/v2/love-letter-status"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("WRITABLE"));
        clock.set(OffsetDateTime.parse("2030-10-04T00:00:00+09:00"));
        mvc.perform(get("/api/v2/love-letter-status"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("CLOSED"));
    }

    @Test
    void nextDayRestoresWritingAndRegistrationReplacesPreviousResult() throws Exception {
        enableWithSeeds();
        Session visitor = startSession();
        mvc.perform(post("/api/v2/love-letters").cookie(visitor.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", visitor.csrf()).header("Idempotency-Key", "day-one-register")
                .contentType("application/json").content(letter("MALE")))
            .andExpect(status().isOk());
        String firstId = jdbc.queryForObject("SELECT id FROM love_letter_exchanges", Map.of(), UUID.class).toString();
        clock.set(OffsetDateTime.parse("2030-10-01T12:01:00+09:00"));
        mvc.perform(get("/api/v2/love-letter-status").cookie(visitor.cookie()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SEALED"))
            .andExpect(jsonPath("$.data.exchangeId").value(firstId));
        mvc.perform(post("/api/v2/love-letter-results/" + firstId + "/open").cookie(visitor.cookie())
                .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", visitor.csrf()))
            .andExpect(status().isOk());
        clock.set(OffsetDateTime.parse("2030-10-02T00:00:00+09:00"));
        mvc.perform(get("/api/v2/love-letter-status").cookie(visitor.cookie()))
            .andExpect(jsonPath("$.data.state").value("BEFORE_OPEN"))
            .andExpect(jsonPath("$.data.canParticipate").value(false))
            .andExpect(jsonPath("$.data.nextParticipationAt").value("2030-10-02T00:00:00Z"))
            .andExpect(jsonPath("$.data.contact").doesNotExist());
        clock.set(OffsetDateTime.parse("2030-10-02T08:59:00+09:00"));
        mvc.perform(get("/api/v2/love-letter-status").cookie(visitor.cookie()))
            .andExpect(jsonPath("$.data.state").value("BEFORE_OPEN"));
        clock.set(OffsetDateTime.parse("2030-10-02T09:00:00+09:00"));
        mvc.perform(get("/api/v2/love-letter-status").cookie(visitor.cookie()))
            .andExpect(jsonPath("$.data.state").value("OPENED"))
            .andExpect(jsonPath("$.data.canParticipate").value(true));
        clock.set(OffsetDateTime.parse("2030-10-02T12:00:00+09:00"));
        mvc.perform(post("/api/v2/love-letters").cookie(visitor.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", visitor.csrf()).header("Idempotency-Key", "day-two-register")
                .contentType("application/json").content(letter("FEMALE")))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v2/love-letter-status").cookie(visitor.cookie()))
            .andExpect(jsonPath("$.data.state").value("WAITING"))
            .andExpect(jsonPath("$.data.contact").doesNotExist());
        String secondId = jdbc.queryForObject("""
            SELECT e.id FROM love_letter_exchanges e JOIN love_letter_requests r ON r.participant_id=e.receiver_id
            WHERE r.idempotency_key='day-two-register' AND e.operating_date=r.operating_date
            """, Map.of(), UUID.class).toString();
        clock.set(OffsetDateTime.parse("2030-10-02T12:01:00+09:00"));
        mvc.perform(get("/api/v2/love-letter-status").cookie(visitor.cookie()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SEALED"))
            .andExpect(jsonPath("$.data.exchangeId").value(secondId));
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(jdbc.queryForObject("""
            SELECT l.gender FROM love_letter_exchanges e JOIN love_letters l ON l.id=e.letter_id
            WHERE e.id=:id AND l.author_id<>e.receiver_id
            """, Map.of("id", UUID.fromString(secondId)), String.class)).isEqualTo("MALE");
        mvc.perform(post("/api/v2/love-letter-results/" + firstId + "/open").cookie(visitor.cookie())
                .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", visitor.csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/love-letter-status").cookie(visitor.cookie()))
            .andExpect(jsonPath("$.data.exchangeId").value(secondId))
            .andExpect(jsonPath("$.data.state").value("SEALED"));
        clock.set(OffsetDateTime.parse("2030-10-04T00:00:00+09:00"));
        mvc.perform(get("/api/v2/love-letter-status").cookie(visitor.cookie()))
            .andExpect(jsonPath("$.data.state").value("CLOSED"));
        mvc.perform(post("/api/v2/love-letter-results/" + secondId + "/open").cookie(visitor.cookie())
                .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", visitor.csrf()))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void anotherBrowserCannotOpenOrReportAndRegistrationRejectsKeyReuse() throws Exception {
        enableWithSeeds();
        Session owner = startSession();
        Session stranger = startSession();
        mvc.perform(post("/api/v2/love-letters").cookie(owner.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", owner.csrf()).header("Idempotency-Key", "register-once")
                .contentType("application/json").content(letter("MALE")))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v2/love-letters").cookie(owner.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", owner.csrf()).header("Idempotency-Key", "register-once")
                .contentType("application/json").content(letter("FEMALE")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOVE_IDEMPOTENCY_CONFLICT"));
        mvc.perform(post("/api/v2/love-letters").cookie(owner.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", "wrong").header("Idempotency-Key", "bad-csrf")
                .contentType("application/json").content(letter("MALE")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("LOVE_CSRF_INVALID"));
        mvc.perform(post("/api/v2/love-letters").cookie(owner.cookie()).header("Origin", "https://invalid.example")
                .header("X-Love-Letter-CSRF", owner.csrf()).header("Idempotency-Key", "bad-origin")
                .contentType("application/json").content(letter("MALE")))
            .andExpect(status().isForbidden());
        clock.set(OffsetDateTime.parse("2030-10-01T12:01:00+09:00"));
        MvcResult ownerStatus = mvc.perform(get("/api/v2/love-letter-status").cookie(owner.cookie()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.exchangeId").exists()).andReturn();
        String exchange = mapper.readTree(ownerStatus.getResponse().getContentAsString())
            .path("data").path("exchangeId").asText();
        mvc.perform(post("/api/v2/love-letter-results/" + exchange + "/open").cookie(stranger.cookie())
                .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", stranger.csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v2/love-letter-results/" + exchange + "/reports").cookie(stranger.cookie())
                .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", stranger.csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v2/love-letter-results/" + exchange + "/reports").cookie(owner.cookie())
                .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", owner.csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.reported").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_reports", Map.of(), Long.class)).isEqualTo(1L);
        MvcResult reports = mvc.perform(asAdmin(get("/api/v2/admin/love-letters/reports")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").exists()).andReturn();
        String reportId = mapper.readTree(reports.getResponse().getContentAsString()).path("data").get(0).path("id").asText();
        mvc.perform(asAdmin(get("/api/v2/admin/love-letters/reports/" + reportId)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.contact").value("@mock-contact"));
        for (int attempt = 0; attempt < 11; attempt++) {
            mvc.perform(post("/api/v2/love-letter-results/" + exchange + "/reports").cookie(owner.cookie())
                    .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", owner.csrf()))
                .andExpect(status().isOk());
        }
        mvc.perform(post("/api/v2/love-letter-results/" + exchange + "/reports").cookie(owner.cookie())
                .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", owner.csrf()))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("LOVE_RATE_LIMITED"));
    }

    @Test
    void failedExchangeInsertRollsBackRegistrationAndAllowsSameKeyRetry() throws Exception {
        enableWithSeeds();
        String token = service.start(null).cookie();
        var input = new LoveLetterService.Input("MALE", "별명", "한 줄", "@mock-contact", true, true, "v1");
        jdbc.getJdbcTemplate().execute("""
            CREATE FUNCTION love_test_fail_exchange() RETURNS trigger AS $$
            BEGIN RAISE EXCEPTION 'forced exchange failure'; END; $$ LANGUAGE plpgsql
            """);
        jdbc.getJdbcTemplate().execute("""
            CREATE TRIGGER love_test_fail_exchange BEFORE INSERT ON love_letter_exchanges
            FOR EACH ROW EXECUTE FUNCTION love_test_fail_exchange()
            """);
        try {
            assertThatThrownBy(() -> service.register(token, input, "rollback-register"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges", Map.of(), Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_participation_days WHERE state='PENDING'", Map.of(), Long.class))
                .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_requests WHERE idempotency_key='rollback-register'", Map.of(), Long.class))
                .isZero();
        } finally {
            jdbc.getJdbcTemplate().execute("DROP TRIGGER love_test_fail_exchange ON love_letter_exchanges");
            jdbc.getJdbcTemplate().execute("DROP FUNCTION love_test_fail_exchange()");
        }
        assertThat(service.register(token, input, "rollback-register").state()).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges", Map.of(), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_participation_days WHERE state='COMPLETED'", Map.of(), Long.class))
            .isEqualTo(1L);
    }

    @Test
    void reissuedInvitationInvalidatesOldLinkAndRejectsExistingDailyRegistration() throws Exception {
        adminPut("/api/v2/admin/love-letters/configuration", """
            {"opensAt":"2030-10-01T09:00:00+09:00","closesAt":"2030-10-04T00:00:00+09:00","consentVersion":"v1"}
            """).andExpect(status().isOk());
        seed("MALE");
        var initial = seedResult("FEMALE");
        String oldToken = initial.path("invitationToken").asText();
        UUID invitedId = UUID.fromString(initial.path("participantId").asText());
        adminPut("/api/v2/admin/love-letters/settings", "{\"enabled\":true}").andExpect(status().isOk());
        MvcResult reissued = mvc.perform(asAdmin(post("/api/v2/admin/love-letters/participants/" + invitedId + "/invitation")))
            .andExpect(status().isOk()).andReturn();
        String newToken = mapper.readTree(reissued.getResponse().getContentAsString())
            .path("data").path("invitationToken").asText();
        assertThat(newToken).isNotEqualTo(oldToken);
        Session registeredBrowser = startSession();
        mvc.perform(post("/api/v2/love-letter-invitations/claim").cookie(registeredBrowser.cookie())
                .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", registeredBrowser.csrf())
                .contentType("application/json").content("{\"invitationToken\":\"" + oldToken + "\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOVE_INVITATION_INVALID"));
        mvc.perform(post("/api/v2/love-letters").cookie(registeredBrowser.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", registeredBrowser.csrf()).header("Idempotency-Key", "occupy-day")
                .contentType("application/json").content(letter("MALE")))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v2/love-letter-invitations/claim").cookie(registeredBrowser.cookie())
                .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", registeredBrowser.csrf())
                .contentType("application/json").content("{\"invitationToken\":\"" + newToken + "\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOVE_ALREADY_PARTICIPATED"));
        Session freshBrowser = startSession();
        mvc.perform(post("/api/v2/love-letter-invitations/claim").cookie(freshBrowser.cookie())
                .header("Origin", ORIGIN).header("X-Love-Letter-CSRF", freshBrowser.csrf())
                .contentType("application/json").content("{\"invitationToken\":\"" + newToken + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("WAITING"));
    }

    @Test
    void claimingAnInvitationInvalidatesThePreviousBrowserTokenForRegistration() throws Exception {
        adminPut("/api/v2/admin/love-letters/configuration", """
            {"opensAt":"2030-10-01T09:00:00+09:00","closesAt":"2030-10-04T00:00:00+09:00","consentVersion":"v1"}
            """).andExpect(status().isOk());
        seed("MALE");
        String invitation = seed("FEMALE");
        seed("FEMALE"); // Remains available if the pre-claim cookie is still accepted after success.
        adminPut("/api/v2/admin/love-letters/settings", "{\"enabled\":true}").andExpect(status().isOk());

        Session browserBeforeClaim = startSession();
        MvcResult claim = mvc.perform(post("/api/v2/love-letter-invitations/claim")
                .cookie(browserBeforeClaim.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", browserBeforeClaim.csrf()).contentType("application/json")
                .content("{\"invitationToken\":\"" + invitation + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("WAITING")).andReturn();
        String claimedCookieValue = claim.getResponse().getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
        Cookie claimedCookie = new Cookie("__Host-festival-love", claimedCookieValue);
        String claimedCsrf = mapper.readTree(claim.getResponse().getContentAsString()).path("data").path("csrfToken").asText();
        long lettersBeforeStaleRequest = jdbc.queryForObject("SELECT count(*) FROM love_letters", Map.of(), Long.class);
        long exchangesBeforeStaleRequest = jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges", Map.of(), Long.class);
        long requestsBeforeStaleRequest = jdbc.queryForObject("SELECT count(*) FROM love_letter_requests", Map.of(), Long.class);
        long participationDaysBeforeStaleRequest = jdbc.queryForObject(
            "SELECT count(*) FROM love_letter_participation_days", Map.of(), Long.class);

        mvc.perform(post("/api/v2/love-letters").cookie(browserBeforeClaim.cookie()).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", browserBeforeClaim.csrf()).header("Idempotency-Key", "stale-after-claim")
                .contentType("application/json").content(letter("MALE")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("LOVE_SESSION_REQUIRED"));
        mvc.perform(get("/api/v2/love-letter-status").cookie(claimedCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("WAITING"));
        mvc.perform(post("/api/v2/love-letters").cookie(claimedCookie).header("Origin", ORIGIN)
                .header("X-Love-Letter-CSRF", claimedCsrf).header("Idempotency-Key", "claim-day-already-used")
                .contentType("application/json").content(letter("MALE")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOVE_ALREADY_PARTICIPATED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letters", Map.of(), Long.class)).isEqualTo(lettersBeforeStaleRequest);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges", Map.of(), Long.class))
            .isEqualTo(exchangesBeforeStaleRequest);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_requests", Map.of(), Long.class))
            .isEqualTo(requestsBeforeStaleRequest);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_participation_days", Map.of(), Long.class))
            .isEqualTo(participationDaysBeforeStaleRequest);
    }

    @Test
    void adminBooleanInputsRejectMissingAndNullValuesWithoutChangingState() throws Exception {
        enableWithSeeds();
        UUID participant = jdbc.queryForObject("SELECT id FROM love_letter_participants ORDER BY created_at LIMIT 1",
            Map.of(), UUID.class);
        adminPut("/api/v2/admin/love-letters/participants/" + participant + "/restriction", "{\"restricted\":true}")
            .andExpect(status().isOk());

        adminPut("/api/v2/admin/love-letters/settings", "{}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("LOVE_INVALID_INPUT"));
        adminPut("/api/v2/admin/love-letters/settings", "{\"enabled\":null}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("LOVE_INVALID_INPUT"));
        adminPut("/api/v2/admin/love-letters/settings", "null")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("LOVE_INVALID_INPUT"));
        assertThat(jdbc.queryForObject("SELECT enabled FROM love_letter_settings", Map.of(), Boolean.class)).isTrue();

        String restrictionPath = "/api/v2/admin/love-letters/participants/" + participant + "/restriction";
        adminPut(restrictionPath, "{}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("LOVE_INVALID_INPUT"));
        adminPut(restrictionPath, "{\"restricted\":null}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("LOVE_INVALID_INPUT"));
        adminPut(restrictionPath, "null").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("LOVE_INVALID_INPUT"));
        assertThat(jdbc.queryForObject("SELECT restricted FROM love_letter_participants WHERE id=:id",
            Map.of("id", participant), Boolean.class)).isTrue();
    }

    private void enableWithSeeds() throws Exception {
        adminPut("/api/v2/admin/love-letters/configuration", """
            {"opensAt":"2030-10-01T09:00:00+09:00","closesAt":"2030-10-04T00:00:00+09:00","consentVersion":"v1"}
            """).andExpect(status().isOk());
        seed("MALE");
        seed("FEMALE");
        adminPut("/api/v2/admin/love-letters/settings", "{\"enabled\":true}").andExpect(status().isOk());
    }

    @Test
    void closedFestivalDeletesEncryptedParticipantTreesAfterSevenDays() throws Exception {
        adminPut("/api/v2/admin/love-letters/configuration", """
            {"opensAt":"2030-10-01T09:00:00+09:00","closesAt":"2030-10-04T00:00:00+09:00","consentVersion":"v1"}
            """).andExpect(status().isOk());
        seed("MALE");
        seed("FEMALE");
        adminPut("/api/v2/admin/love-letters/settings", "{\"enabled\":true}").andExpect(status().isOk());
        String participantToken = service.start(null).cookie();
        var input = new LoveLetterService.Input("MALE", "별명", "한 줄", "@mock-contact", true, true, "v1");
        service.register(participantToken, input, "cleanup-tree-request");
        clock.set(OffsetDateTime.parse("2030-10-01T12:01:00+09:00"));
        var exchangeId = service.status(participantToken).exchangeId();
        assertThat(exchangeId).isNotNull();
        service.report(participantToken, exchangeId);

        var before = new CleanupTargetContext(jdbc, Instant.parse("2030-10-10T14:59:59Z"), 100, true);
        assertThat(cleanup.run(before).eligibleCount()).isZero();
        var exactRetentionBoundary = new CleanupTargetContext(jdbc, Instant.parse("2030-10-10T15:00:00Z"), 100, true);
        assertThat(cleanup.run(exactRetentionBoundary).eligibleCount()).isEqualTo(3);
        var dry = new CleanupTargetContext(jdbc, Instant.parse("2030-10-10T15:00:01Z"), 100, true);
        assertThat(cleanup.run(dry).eligibleCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letters", Map.of(), Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges", Map.of(), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_reports", Map.of(), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_requests WHERE idempotency_key='cleanup-tree-request'",
            Map.of(), Long.class)).isEqualTo(1L);
        var deleting = new CleanupTargetContext(jdbc, Instant.parse("2030-10-10T15:00:01Z"), 100, false);
        jdbc.getJdbcTemplate().execute("""
            CREATE FUNCTION love_test_fail_cleanup() RETURNS trigger AS $$
            BEGIN RAISE EXCEPTION 'forced cleanup failure'; END; $$ LANGUAGE plpgsql
            """);
        jdbc.getJdbcTemplate().execute("""
            CREATE TRIGGER love_test_fail_cleanup BEFORE DELETE ON love_letter_participants
            FOR EACH ROW EXECUTE FUNCTION love_test_fail_cleanup()
            """);
        try {
            assertThatThrownBy(() -> cleanup.run(deleting))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letters", Map.of(), Long.class)).isEqualTo(3L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges", Map.of(), Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_reports", Map.of(), Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_requests WHERE idempotency_key='cleanup-tree-request'",
                Map.of(), Long.class)).isEqualTo(1L);
        } finally {
            jdbc.getJdbcTemplate().execute("DROP TRIGGER love_test_fail_cleanup ON love_letter_participants");
            jdbc.getJdbcTemplate().execute("DROP FUNCTION love_test_fail_cleanup()");
        }
        assertThat(cleanup.run(deleting).deletedCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letters", Map.of(), Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_invitations", Map.of(), Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_participation_days", Map.of(), Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges", Map.of(), Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_reports", Map.of(), Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_requests", Map.of(), Long.class)).isZero();
    }

    @Test
    void concurrentRegistrationsForLastLetterCommitOnlyOneDailyParticipation() throws Exception {
        adminPut("/api/v2/admin/love-letters/configuration", """
            {"opensAt":"2030-10-01T09:00:00+09:00","closesAt":"2030-10-04T00:00:00+09:00","consentVersion":"v1"}
            """).andExpect(status().isOk());
        seed("MALE");
        seed("FEMALE");
        adminPut("/api/v2/admin/love-letters/settings", "{\"enabled\":true}").andExpect(status().isOk());
        String first = service.start(null).cookie();
        String second = service.start(null).cookie();
        var input = new LoveLetterService.Input("MALE", "별명", "한 줄", "@mock-contact", true, true, "v1");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var jobs = List.of(first, second).stream().map(token -> executor.submit(() -> {
                ready.countDown();
                go.await();
                try { return service.register(token, input, UUID.randomUUID().toString()).state(); }
                catch (ApiException error) { return error.code(); }
            })).toList();
            ready.await();
            go.countDown();
            var outcomes = jobs.stream().map(job -> {
                try { return job.get(); } catch (Exception error) { throw new AssertionError(error); }
            }).toList();
            assertThat(outcomes).containsExactlyInAnyOrder("WAITING", "LOVE_POOL_EMPTY");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges", Map.of(), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_participation_days WHERE state='COMPLETED'", Map.of(), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_participation_days WHERE state='PENDING'", Map.of(), Long.class)).isZero();
    }

    private String seed(String gender) throws Exception {
        return seedResult(gender).path("invitationToken").asText();
    }

    private tools.jackson.databind.JsonNode seedResult(String gender) throws Exception {
        MvcResult result = mvc.perform(asAdmin(post("/api/v2/admin/love-letters/seeds"))
                .contentType("application/json")
                .content("{\"operatingDate\":\"2030-10-01\",\"consentAt\":\"2030-09-30T12:00:00+09:00\",\"letter\":" + letter(gender) + "}"))
            .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
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
