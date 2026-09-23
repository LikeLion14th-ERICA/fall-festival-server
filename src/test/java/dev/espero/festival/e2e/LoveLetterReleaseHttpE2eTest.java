package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.zaxxer.hikari.HikariDataSource;
import dev.espero.festival.support.PostgresTestImages;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Release-level LOVE HTTP flows on a loopback Spring server and disposable PostgreSQL. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
        "server.address=127.0.0.1",
        "festival.love-letter.allowed-origin=https://love.release-e2e.test",
        "festival.love-letter.key-base64=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "festival.love-letter.key-version=release-test-v1",
        "festival.love-letter.seed-retry-enabled=false",
        "festival.admin-auth.jwt-signing-secret=love-release-e2e-signing-secret-at-least-32-bytes",
        "festival.admin-auth.allowed-origin=https://love.release-e2e.test",
        "festival.admin-auth.bootstrap-username=love-release-admin",
        "festival.admin-auth.bootstrap-password=love-release-test-password",
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
@Import({LoveLetterReleaseHttpE2eTest.DataSourceConfiguration.class,
    LoveLetterReleaseHttpE2eTest.ClockConfiguration.class})
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class LoveLetterReleaseHttpE2eTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final String ORIGIN = "https://love.release-e2e.test";
    private static final String ADMIN_USERNAME = "love-release-admin";
    private static final String ADMIN_PASSWORD = "love-release-test-password";
    private static final String LOVE_COOKIE = "__Host-festival-love";
    private static final Instant OPENING = Instant.parse("2030-10-01T00:00:00Z");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .placeholders(Map.of("festivalId", FESTIVAL_ID.toString()))
            .load()
            .migrate();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.hikari.username", POSTGRES::getUsername);
        registry.add("spring.datasource.hikari.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.festivalId", FESTIVAL_ID::toString);
    }

    @BeforeEach
    void resetLoveLetterDatabaseAndClock() {
        clock.set(OPENING.plus(Duration.ofHours(3)));
        jdbc.update("DELETE FROM love_letter_participants WHERE festival_id = ?", FESTIVAL_ID);
        jdbc.update("DELETE FROM love_letter_settings WHERE festival_id = ?", FESTIVAL_ID);
        jdbc.update("DELETE FROM admin_audit_events WHERE admin_id = (SELECT id FROM admin_accounts WHERE username = ?)",
            ADMIN_USERNAME);
        jdbc.update("DELETE FROM admin_refresh_sessions WHERE admin_id = (SELECT id FROM admin_accounts WHERE username = ?)",
            ADMIN_USERNAME);
    }

    @Test
    void anonymousRegistrationPreassignsAndRevealsOnlyAfterSixtySeconds() throws Exception {
        String admin = loginAdmin();
        HttpResponse<String> disabledGuide = get("/api/v2/love-letter-guide");
        successNoStore(disabledGuide);
        assertThat(jsonBoolean(disabledGuide, "$.data.enabled")).isFalse();

        configure(admin);
        HttpResponse<String> enableWithoutSeedPool = put("/api/v2/admin/love-letters/settings", admin,
            "{\"enabled\":true}");
        errorNoStore(enableWithoutSeedPool, 409, "LOVE_SEED_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT enabled FROM love_letter_settings WHERE festival_id = ?",
            Boolean.class, FESTIVAL_ID)).isFalse();
        assertThat(count("love_letter_participants")).isZero();
        assertThat(count("love_letters")).isZero();
        Seed male = seed(admin, "MALE", "male-seed-private", "seed-male-private-body", "@seed-male-private");
        Seed female = seed(admin, "FEMALE", "female-seed-private", "seed-female-private-body", "@seed-female-private");
        assertThat(male.participantId()).isNotEqualTo(female.participantId());
        enable(admin);

        HttpResponse<String> guide = get("/api/v2/love-letter-guide");
        successNoStore(guide);
        assertThat(jsonBoolean(guide, "$.data.enabled")).isTrue();
        assertThat(jsonInt(guide, "$.data.revealDelaySeconds")).isEqualTo(60);

        HttpResponse<String> invalidStartOrigin = send(request("POST", "/api/v2/love-letter-participants")
            .header("Origin", "https://invalid.release-e2e.test")
            .build());
        forbiddenNoStore(invalidStartOrigin);
        assertThat(count("love_letter_participants")).isEqualTo(2L);
        Session owner = startSession();
        assertThat(owner.start().statusCode()).isEqualTo(201);
        assertThat(owner.cookiePair()).startsWith(LOVE_COOKIE + "=");
        assertThat(owner.start().headers().firstValue("Set-Cookie").orElseThrow())
            .contains("Secure", "HttpOnly", "SameSite=Lax");
        assertNoStore(owner.start());
        successNoStore(get("/api/v2/love-letter-status", owner.cookiePair()));
        assertThat(jsonString(get("/api/v2/love-letter-status", owner.cookiePair()), "$.data.state")).isEqualTo("WRITABLE");

        String privateName = "owner-" + UUID.randomUUID().toString().substring(0, 12);
        String privateMessage = "release-owner-message-" + UUID.randomUUID();
        String privateContact = "@release-owner-" + UUID.randomUUID();
        String payload = letter("MALE", privateName, privateMessage, privateContact);
        HttpResponse<String> invalidRegisterOrigin = registerWithHeaders(owner, "invalid-register-origin", payload,
            "https://invalid.release-e2e.test", owner.csrf());
        forbiddenNoStore(invalidRegisterOrigin);
        HttpResponse<String> invalidRegisterCsrf = registerWithHeaders(owner, "invalid-register-csrf", payload,
            ORIGIN, "wrong-csrf");
        errorNoStore(invalidRegisterCsrf, 403, "LOVE_CSRF_INVALID");
        assertThat(count("love_letters")).isEqualTo(2L);
        assertThat(count("love_letter_exchanges")).isZero();
        assertThat(count("love_letter_requests")).isZero();
        HttpResponse<String> registered = register(owner, "release-registration-key-1", payload);
        successNoStore(registered);
        assertThat(jsonString(registered, "$.data.state")).isEqualTo("WAITING");
        assertThat(jsonString(registered, "$.data.revealAt")).isEqualTo("2030-10-01T03:01:00Z");
        assertJsonMissing(registered, "$.data.exchangeId");
        assertNoStoreContains(registered, List.of(privateName, privateMessage, privateContact));

        UUID exchangeId = jdbc.queryForObject("SELECT id FROM love_letter_exchanges WHERE receiver_id = "
            + "(SELECT id FROM love_letter_participants WHERE token_sha256 = ?)", UUID.class,
            sha256(owner.token()));
        assertThat(exchangeId).isNotNull();
        HttpResponse<String> replay = register(owner, "release-registration-key-1", payload);
        successNoStore(replay);
        assertThat(jsonString(replay, "$.data.letterId")).isEqualTo(jsonString(registered, "$.data.letterId"));
        assertThat(count("love_letters")).isEqualTo(3L);
        assertThat(count("love_letter_exchanges")).isEqualTo(1L);
        HttpResponse<String> keyReuse = register(owner, "release-registration-key-1",
            letter("FEMALE", privateName, privateMessage, privateContact));
        errorNoStore(keyReuse, 409, "LOVE_IDEMPOTENCY_CONFLICT");

        clock.set(OPENING.plus(Duration.ofHours(3)).plusSeconds(59));
        HttpResponse<String> waiting = get("/api/v2/love-letter-status", owner.cookiePair());
        successNoStore(waiting);
        assertThat(jsonString(waiting, "$.data.state")).isEqualTo("WAITING");
        assertJsonMissing(waiting, "$.data.exchangeId");
        assertJsonMissing(waiting, "$.data.contact");
        HttpResponse<String> earlyOpen = open(owner, exchangeId.toString());
        errorNoStore(earlyOpen, 409, "LOVE_WAITING");

        Session stranger = startSession();
        HttpResponse<String> foreignOpen = open(stranger, exchangeId.toString());
        errorNoStore(foreignOpen, 404, "LOVE_RESULT_NOT_FOUND");

        clock.set(OPENING.plus(Duration.ofHours(3)).plusSeconds(60));
        HttpResponse<String> sealed = get("/api/v2/love-letter-status", owner.cookiePair());
        successNoStore(sealed);
        assertThat(jsonString(sealed, "$.data.state")).isEqualTo("SEALED");
        assertThat(jsonString(sealed, "$.data.exchangeId")).isEqualTo(exchangeId.toString());
        assertJsonMissing(sealed, "$.data.contact");
        HttpResponse<String> opened = open(owner, exchangeId.toString());
        successNoStore(opened);
        assertThat(jsonString(opened, "$.data.name")).isEqualTo("female-seed-private");
        assertThat(jsonString(opened, "$.data.message")).isEqualTo("seed-female-private-body");
        assertThat(jsonString(opened, "$.data.contact")).isEqualTo("@seed-female-private");
        HttpResponse<String> openedStatus = get("/api/v2/love-letter-status", owner.cookiePair());
        successNoStore(openedStatus);
        assertThat(jsonString(openedStatus, "$.data.state")).isEqualTo("OPENED");
        assertThat(jsonString(openedStatus, "$.data.contact")).isEqualTo("@seed-female-private");
        assertJsonMissing(openedStatus, "$.data.name");

        Map<String, Object> ciphertext = jdbc.queryForMap("SELECT name_cipher,body_cipher,contact_cipher FROM love_letters "
            + "WHERE author_id = (SELECT id FROM love_letter_participants WHERE token_sha256 = ?)", sha256(owner.token()));
        assertThat(ciphertext.get("name_cipher").toString()).doesNotContain(privateName);
        assertThat(ciphertext.get("body_cipher").toString()).doesNotContain(privateMessage);
        assertThat(ciphertext.get("contact_cipher").toString()).doesNotContain(privateContact);
        assertThat(countWhere("love_letter_requests", "idempotency_key = 'release-registration-key-1'")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges WHERE id = ? AND opened_at IS NOT NULL",
            Long.class, exchangeId)).isEqualTo(1L);

        HttpResponse<String> disabled = put("/api/v2/admin/love-letters/settings", admin, "{\"enabled\":false}");
        successNoStore(disabled);
        assertThat(jsonBoolean(disabled, "$.data.enabled")).isFalse();
        HttpResponse<String> stoppedStatus = get("/api/v2/love-letter-status", owner.cookiePair());
        successNoStore(stoppedStatus);
        assertThat(jsonString(stoppedStatus, "$.data.state")).isEqualTo("CLOSED");
        assertJsonMissing(stoppedStatus, "$.data.exchangeId");
        errorNoStore(open(owner, exchangeId.toString()), 503, "LOVE_CLOSED");
        assertThat(count("love_letters")).isEqualTo(3L);
        assertThat(count("love_letter_exchanges")).isEqualTo(1L);
    }

    @Test
    void lastLetterConcurrentRegistrationsCommitOnlyOneParticipant() throws Exception {
        String admin = loginAdmin();
        configure(admin);
        seed(admin, "MALE", "only-male-pool-name", "only-male-pool-body", "@only-male-pool");
        seed(admin, "FEMALE", "female-pool-name", "only-female-pool-body", "@only-female-pool");
        enable(admin);

        Session first = startSession();
        Session second = startSession();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        String payload = letter("MALE", "racing-name", "racing-message", "@racing-contact");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstJob = executor.submit(() -> concurrentRegister(first, "last-letter-client-one", payload, ready, go));
            var secondJob = executor.submit(() -> concurrentRegister(second, "last-letter-client-two", payload, ready, go));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            HttpResponse<String> firstResponse = firstJob.get(20, TimeUnit.SECONDS);
            HttpResponse<String> secondResponse = secondJob.get(20, TimeUnit.SECONDS);

            assertThat(List.of(firstResponse.statusCode(), secondResponse.statusCode()))
                .containsExactlyInAnyOrder(200, 409);
            HttpResponse<String> winner = firstResponse.statusCode() == 200 ? firstResponse : secondResponse;
            HttpResponse<String> loser = firstResponse.statusCode() == 409 ? firstResponse : secondResponse;
            successNoStore(winner);
            errorNoStore(loser, 409, "LOVE_POOL_EMPTY");
            assertThat(jsonString(winner, "$.data.state")).isEqualTo("WAITING");
        }

        assertThat(count("love_letters")).isEqualTo(3L);
        assertThat(count("love_letter_exchanges")).isEqualTo(1L);
        assertThat(countWhere("love_letter_participation_days", "state = 'COMPLETED'")).isEqualTo(1L);
        assertThat(countWhere("love_letter_participation_days", "state = 'PENDING'")).isZero();
        assertThat(count("love_letter_requests")).isEqualTo(1L);
        assertThat(countWhere("love_letter_participants", "token_sha256 IS NOT NULL")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges e JOIN love_letters l "
            + "ON l.id = e.letter_id WHERE l.gender = 'FEMALE' AND l.author_id <> e.receiver_id", Long.class))
            .isEqualTo(1L);
        java.util.List<String> participantStates = new java.util.ArrayList<>();
        for (Session session : List.of(first, second)) {
            HttpResponse<String> state = get("/api/v2/love-letter-status", session.cookiePair());
            successNoStore(state);
            String participantState = jsonString(state, "$.data.state");
            participantStates.add(participantState);
            if (participantState.equals("WRITABLE")) {
                assertJsonMissing(state, "$.data.exchangeId");
                assertJsonMissing(state, "$.data.contact");
            }
        }
        assertThat(participantStates).containsExactlyInAnyOrder("WAITING", "WRITABLE");
    }

    @Test
    void seedInvitationReportBlockAndRestrictionFollowOwnership() throws Exception {
        String admin = loginAdmin();
        configure(admin);
        Seed malePool = seed(admin, "MALE", "pool-male-name", "pool-male-body", "@pool-male");
        Seed femalePool = seed(admin, "FEMALE", "pool-female-name", "pool-female-body", "@pool-female");
        Seed invited = seed(admin, "MALE", "invited-private-name", "invited-private-message", "@invited-private-contact");
        enable(admin);

        HttpResponse<String> reissued = post("/api/v2/admin/love-letters/participants/" + invited.participantId()
            + "/invitation", admin, "");
        successNoStore(reissued);
        String newInvitation = jsonString(reissued, "$.data.invitationToken");
        assertThat(newInvitation).isNotEqualTo(invited.invitationToken());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_invitations WHERE participant_id = ? "
            + "AND invalidated_at IS NOT NULL", Long.class, UUID.fromString(invited.participantId()))).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_invitations WHERE token_sha256 = ?",
            Long.class, sha256(invited.invitationToken()))).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_invitations WHERE token_sha256 = ?",
            Long.class, invited.invitationToken())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_invitations WHERE token_sha256 = ?",
            Long.class, sha256(newInvitation))).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_invitations WHERE token_sha256 = ?",
            Long.class, newInvitation)).isZero();

        Session ownerBeforeClaim = startSession();
        HttpResponse<String> oldLink = claim(ownerBeforeClaim, invited.invitationToken());
        errorNoStore(oldLink, 409, "LOVE_INVITATION_INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges WHERE receiver_id = ?",
            Long.class, UUID.fromString(invited.participantId()))).isZero();
        HttpResponse<String> claimed = claim(ownerBeforeClaim, newInvitation);
        successNoStore(claimed);
        assertThat(jsonString(claimed, "$.data.state")).isEqualTo("WAITING");
        String claimedCookie = cookiePair(claimed);
        String claimedCsrf = jsonString(claimed, "$.data.csrfToken");
        Session owner = new Session(ownerBeforeClaim.start(), claimedCookie, claimedCsrf);
        assertThat(owner.cookiePair()).startsWith(LOVE_COOKIE + "=");
        assertThat(claimed.body()).doesNotContain(cookieValue(claimedCookie));
        assertThat(jdbc.queryForObject("SELECT token_sha256 FROM love_letter_participants WHERE id = ?",
            String.class, UUID.fromString(invited.participantId()))).isEqualTo(sha256(cookieValue(owner.cookiePair())));
        Session invitationReplay = startSession();
        errorNoStore(claim(invitationReplay, newInvitation), 409, "LOVE_INVITATION_INVALID");

        clock.set(OPENING.plus(Duration.ofHours(3)).plusSeconds(60));
        HttpResponse<String> sealed = get("/api/v2/love-letter-status", owner.cookiePair());
        successNoStore(sealed);
        String exchangeId = jsonString(sealed, "$.data.exchangeId");
        UUID receivedLetterId = jdbc.queryForObject("SELECT letter_id FROM love_letter_exchanges WHERE id = ?",
            UUID.class, UUID.fromString(exchangeId));
        assertThat(receivedLetterId).isNotNull();
        assertNoStoreContains(sealed, List.of("invited-private-name", "invited-private-message", "@invited-private-contact"));

        Session stranger = startSession();
        errorNoStore(open(stranger, exchangeId), 404, "LOVE_RESULT_NOT_FOUND");
        HttpResponse<String> foreignReport = report(stranger, exchangeId);
        errorNoStore(foreignReport, 404, "LOVE_NOT_FOUND");

        HttpResponse<String> reported = report(owner, exchangeId);
        successNoStore(reported);
        assertThat(jsonBoolean(reported, "$.data.reported")).isTrue();
        assertThat(count("love_letter_reports")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT blocked FROM love_letters WHERE id = ?", Boolean.class, receivedLetterId))
            .isFalse();

        HttpResponse<String> anonymousReports = get("/api/v2/admin/love-letters/reports");
        errorNoStore(anonymousReports, 401, "UNAUTHORIZED");
        HttpResponse<String> reportList = getAdmin("/api/v2/admin/love-letters/reports", admin);
        successNoStore(reportList);
        assertThat((List<?>) JsonPath.read(reportList.body(), "$.data")).hasSize(1);
        String reportId = jsonString(reportList, "$.data[0].id");
        HttpResponse<String> reportDetail = getAdmin("/api/v2/admin/love-letters/reports/" + reportId, admin);
        successNoStore(reportDetail);
        assertThat(jsonString(reportDetail, "$.data.letterId")).isEqualTo(receivedLetterId.toString());
        assertThat(jsonString(reportDetail, "$.data.name")).isEqualTo("pool-female-name");
        assertThat(jsonString(reportDetail, "$.data.message")).isEqualTo("pool-female-body");
        assertThat(jsonString(reportDetail, "$.data.contact")).isEqualTo("@pool-female");
        assertThat(jsonBoolean(reportDetail, "$.data.blocked")).isFalse();

        HttpResponse<String> blocked = post("/api/v2/admin/love-letters/" + receivedLetterId + "/block", admin, "");
        successNoStore(blocked);
        assertThat(jsonBoolean(blocked, "$.data.blocked")).isTrue();
        HttpResponse<String> blockedOpen = open(owner, exchangeId);
        errorNoStore(blockedOpen, 403, "LOVE_RESULT_BLOCKED");
        HttpResponse<String> blockedStatus = get("/api/v2/love-letter-status", owner.cookiePair());
        successNoStore(blockedStatus);
        assertThat(jsonString(blockedStatus, "$.data.state")).isEqualTo("RESULT_BLOCKED");
        assertJsonMissing(blockedStatus, "$.data.contact");

        UUID ownerId = UUID.fromString(invited.participantId());
        HttpResponse<String> restricted = put("/api/v2/admin/love-letters/participants/" + ownerId + "/restriction",
            admin, "{\"restricted\":true}");
        successNoStore(restricted);
        assertThat(jsonBoolean(restricted, "$.data.restricted")).isTrue();
        HttpResponse<String> restrictedStatus = get("/api/v2/love-letter-status", owner.cookiePair());
        successNoStore(restrictedStatus);
        assertThat(jsonString(restrictedStatus, "$.data.state")).isEqualTo("RESTRICTED");
        errorNoStore(open(owner, exchangeId), 403, "LOVE_RESTRICTED");
        HttpResponse<String> unrestricted = put("/api/v2/admin/love-letters/participants/" + ownerId + "/restriction",
            admin, "{\"restricted\":false}");
        successNoStore(unrestricted);
        assertThat(jsonBoolean(unrestricted, "$.data.restricted")).isFalse();
        HttpResponse<String> restoredStatus = get("/api/v2/love-letter-status", owner.cookiePair());
        successNoStore(restoredStatus);
        assertThat(jsonString(restoredStatus, "$.data.state")).isEqualTo("RESULT_BLOCKED");

        assertThat(countWhere("admin_audit_events", "admin_id = (SELECT id FROM admin_accounts WHERE username = '"
            + ADMIN_USERNAME + "') AND action LIKE 'LOVE_%'")).isGreaterThanOrEqualTo(9L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_exchanges WHERE id = ? AND letter_id = ?",
            Long.class, UUID.fromString(exchangeId), receivedLetterId)).isEqualTo(1L);
        assertThat(malePool.participantId()).isNotEqualTo(femalePool.participantId());
    }

    private void configure(String admin) throws Exception {
        HttpResponse<String> response = put("/api/v2/admin/love-letters/configuration", admin,
            "{\"opensAt\":\"2030-10-01T09:00:00+09:00\",\"closesAt\":\"2030-10-04T00:00:00+09:00\","
                + "\"consentVersion\":\"release-v1\"}");
        successNoStore(response);
        assertThat(jsonBoolean(response, "$.data.enabled")).isFalse();
        assertThat(jdbc.queryForObject("SELECT enabled FROM love_letter_settings WHERE festival_id = ?",
            Boolean.class, FESTIVAL_ID)).isFalse();
    }

    private void enable(String admin) throws Exception {
        HttpResponse<String> response = put("/api/v2/admin/love-letters/settings", admin, "{\"enabled\":true}");
        successNoStore(response);
        assertThat(jsonBoolean(response, "$.data.enabled")).isTrue();
        assertThat(jdbc.queryForObject("SELECT enabled FROM love_letter_settings WHERE festival_id = ?",
            Boolean.class, FESTIVAL_ID)).isTrue();
    }

    private Seed seed(String admin, String gender, String name, String message, String contact) throws Exception {
        String body = "{\"operatingDate\":\"2030-10-01\",\"consentAt\":\"2030-09-30T12:00:00+09:00\",\"letter\":"
            + letter(gender, name, message, contact) + "}";
        HttpResponse<String> response = post("/api/v2/admin/love-letters/seeds", admin, body);
        successNoStore(response);
        String participant = jsonString(response, "$.data.participantId");
        String invitation = jsonString(response, "$.data.invitationToken");
        assertThat(invitation).matches("[A-Za-z0-9_-]{32,128}");
        assertThat(jdbc.queryForObject("SELECT token_sha256 FROM love_letter_participants WHERE id = ?",
            String.class, UUID.fromString(participant))).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_invitations WHERE token_sha256 = ?",
            Long.class, sha256(invitation))).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM love_letter_invitations WHERE token_sha256 = ?",
            Long.class, invitation)).isZero();
        assertThat(jdbc.queryForObject("SELECT name_cipher FROM love_letters WHERE author_id = ?",
            String.class, UUID.fromString(participant))).doesNotContain(name);
        assertNoStoreContains(response, java.util.List.of(name, message, contact));
        return new Seed(participant, invitation);
    }

    private Session startSession() throws Exception {
        HttpResponse<String> response = send(request("POST", "/api/v2/love-letter-participants")
            .header("Origin", ORIGIN)
            .build());
        successNoStore(response);
        assertThat(response.statusCode()).isEqualTo(201);
        String pair = cookiePair(response);
        String token = cookieValue(pair);
        String csrf = jsonString(response, "$.data.csrfToken");
        assertThat(token).matches("[A-Za-z0-9_-]{32,128}");
        assertThat(response.body()).doesNotContain(token);
        assertThat(csrf).isNotBlank();
        return new Session(response, pair, csrf);
    }

    private HttpResponse<String> register(Session session, String key, String letter) throws Exception {
        return registerWithHeaders(session, key, letter, ORIGIN, session.csrf());
    }

    private HttpResponse<String> registerWithHeaders(Session session, String key, String letter,
        String origin, String csrf) throws Exception {
        return send(request("POST", "/api/v2/love-letters")
            .header("Cookie", session.cookiePair())
            .header("Origin", origin)
            .header("X-Love-Letter-CSRF", csrf)
            .header("Idempotency-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(letter))
            .build());
    }

    private HttpResponse<String> concurrentRegister(Session session, String key, String body,
        CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent registration gate timed out.");
        return register(session, key, body);
    }

    private HttpResponse<String> open(Session session, String exchangeId) throws Exception {
        return send(request("POST", "/api/v2/love-letter-results/" + exchangeId + "/open")
            .header("Cookie", session.cookiePair())
            .header("Origin", ORIGIN)
            .header("X-Love-Letter-CSRF", session.csrf())
            .POST(HttpRequest.BodyPublishers.noBody())
            .build());
    }

    private HttpResponse<String> report(Session session, String exchangeId) throws Exception {
        return send(request("POST", "/api/v2/love-letter-results/" + exchangeId + "/reports")
            .header("Cookie", session.cookiePair())
            .header("Origin", ORIGIN)
            .header("X-Love-Letter-CSRF", session.csrf())
            .POST(HttpRequest.BodyPublishers.noBody())
            .build());
    }

    private HttpResponse<String> claim(Session session, String invitation) throws Exception {
        return send(request("POST", "/api/v2/love-letter-invitations/claim")
            .header("Cookie", session.cookiePair())
            .header("Origin", ORIGIN)
            .header("X-Love-Letter-CSRF", session.csrf())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"invitationToken\":\"" + invitation + "\"}"))
            .build());
    }

    private String loginAdmin() throws Exception {
        HttpResponse<String> response = send(request("POST", "/api/v2/admin/sessions")
            .header("Origin", ORIGIN)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + ADMIN_USERNAME
                + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
            .build());
        assertThat(response.statusCode()).isEqualTo(200);
        String access = jsonString(response, "$.data.accessToken");
        assertThat(access).isNotBlank();
        return access;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(request("GET", path).GET().build());
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        return send(request("GET", path).header("Cookie", cookie).GET().build());
    }

    private HttpResponse<String> getAdmin(String path, String admin) throws Exception {
        return send(request("GET", path).header("Authorization", "Bearer " + admin).GET().build());
    }

    private HttpResponse<String> put(String path, String admin, String body) throws Exception {
        return send(request("PUT", path).header("Authorization", "Bearer " + admin)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpResponse<String> post(String path, String admin, String body) throws Exception {
        HttpRequest.Builder builder = request("POST", path).header("Authorization", "Bearer " + admin);
        if (!body.isEmpty()) builder.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        else builder.POST(HttpRequest.BodyPublishers.noBody());
        return send(builder.build());
    }

    private HttpRequest.Builder request(String method, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(REQUEST_TIMEOUT)
            .method(method, HttpRequest.BodyPublishers.noBody());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long countWhere(String table, String where) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Long.class);
    }

    private static String letter(String gender, String name, String message, String contact) {
        return "{\"gender\":\"" + gender + "\",\"name\":\"" + name + "\",\"message\":\"" + message
            + "\",\"contact\":\"" + contact + "\",\"adultConfirmed\":true,\"ownContactConfirmed\":true,"
            + "\"consentVersion\":\"release-v1\"}";
    }

    private static void successNoStore(HttpResponse<String> response) {
        assertThat(response.statusCode()).isBetween(200, 299);
        assertNoStore(response);
    }

    private static void errorNoStore(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(jsonString(response, "$.error.code")).isEqualTo(code);
        assertNoStore(response);
    }

    private static void forbiddenNoStore(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(403);
        assertNoStore(response);
    }

    private static void assertNoStore(HttpResponse<?> response) {
        assertThat(response.headers().firstValue("Cache-Control")).isPresent();
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private static void assertNoStoreContains(HttpResponse<String> response, Iterable<String> privateValues) {
        assertNoStore(response);
        for (String value : privateValues) assertThat(response.body()).doesNotContain(value);
    }

    private static String cookiePair(HttpResponse<?> response) {
        String header = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(header).contains(LOVE_COOKIE + "=", "Secure", "HttpOnly", "SameSite=Lax", "Path=/");
        return header.substring(0, header.indexOf(';'));
    }

    private static String cookieValue(String cookiePair) {
        return cookiePair.substring(cookiePair.indexOf('=') + 1);
    }

    private static String jsonString(HttpResponse<String> response, String path) {
        return JsonPath.<String>read(response.body(), path);
    }

    private static int jsonInt(HttpResponse<String> response, String path) {
        return ((Number) JsonPath.read(response.body(), path)).intValue();
    }

    private static boolean jsonBoolean(HttpResponse<String> response, String path) {
        return Boolean.TRUE.equals(JsonPath.read(response.body(), path));
    }

    private static void assertJsonMissing(HttpResponse<String> response, String path) {
        Object value;
        try {
            value = JsonPath.read(response.body(), path);
        } catch (PathNotFoundException missing) {
            return;
        }
        assertThat(value).isNull();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Seed(String participantId, String invitationToken) { }

    private record Session(HttpResponse<String> start, String cookiePair, String csrf) {
        private String token() { return cookieValue(cookiePair); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DataSourceConfiguration {
        @Bean(name = "dataSource")
        @Primary
        HikariDataSource loveLetterContainerDataSource() {
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl(POSTGRES.getJdbcUrl());
            source.setUsername(POSTGRES.getUsername());
            source.setPassword(POSTGRES.getPassword());
            return source;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableClock loveLetterReleaseClock() {
            return new MutableClock(OPENING.plus(Duration.ofHours(3)));
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant initial) {
            this.now = initial;
        }

        void set(Instant value) {
            now = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock self = this;
            return new Clock() {
                @Override public ZoneId getZone() { return zone; }
                @Override public Clock withZone(ZoneId other) { return self.withZone(other); }
                @Override public Instant instant() { return self.instant(); }
            };
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
