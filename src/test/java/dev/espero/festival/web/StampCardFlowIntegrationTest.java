package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.support.PostgresTestImages;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Booth stamps end to end: an anonymous participant cookie, a START every
 * festival day, one stamp per
 * booth per day, four per day, dated tokens, the daily reset and a reward
 * that needs a full card and is claimed once a day.
 */
@SpringBootTest(properties = "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf")
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class StampCardFlowIntegrationTest {

    private static final UUID REVISION_ID = UUID.fromString("f109dca2-8b28-4e09-8114-beebc2bd3ea2");
    private static final String COOKIE = StampCardController.PARTICIPANT_COOKIE;
    private static final String RECEIPT_CODE = "482913";
    private static final List<String> BOOTHS = List.of("likelion", "photo", "tarot", "career", "starbucks");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("festival.stamp-receipt.code-sha256", () -> StampCardService.sha256(RECEIPT_CODE));
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @MockitoBean
    private CatalogSnapshotProvider snapshots;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        clock.set(OffsetDateTime.parse("2030-10-01T12:00:00+09:00"));
        when(snapshots.required()).thenReturn(new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext("festival-stamps", REVISION_ID, 1),
            List.of(), List.of(), List.of(), Map.of(), null
        ));
        when(snapshots.publishedLocales()).thenReturn(List.of("ko"));
        for (String table : List.of("stamp_rewards", "stamp_collections", "stamp_participant_days", "stamp_participants",
            "stamp_booth_tokens", "stamp_booths")) {
            jdbc.update("DELETE FROM " + table, Map.of());
        }
        for (int index = 0; index < BOOTHS.size(); index++) {
            String booth = BOOTHS.get(index);
            jdbc.update("""
                INSERT INTO stamp_booths (festival_revision_id, id, name, sort_order)
                VALUES (:revisionId, :id, :name, :sortOrder)
                """, new MapSqlParameterSource()
                .addValue("revisionId", REVISION_ID)
                .addValue("id", booth)
                .addValue("name", booth + " 부스")
                .addValue("sortOrder", index + 1));
            insertToken(booth, null, token(booth));
        }
        jdbc.update("""
            INSERT INTO stamp_booths (festival_revision_id, id, name, sort_order)
            VALUES (:revisionId, 'dated', '날짜 부스', 99)
            """, new MapSqlParameterSource("revisionId", REVISION_ID));
        insertToken("dated", java.time.LocalDate.parse("2030-10-02"), token("dated"));
    }

    @Test
    void startsAnAnonymousCardOnceAndKeepsItForTheSameCookie() throws Exception {
        MvcResult started = mvc.perform(post("/api/v2/stamp-participants"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.date").value("2030-10-01"))
            .andExpect(jsonPath("$.data.dailyLimit").value(4))
            .andExpect(jsonPath("$.data.stamps").isEmpty())
            .andExpect(jsonPath("$.data.rewardClaimed").value(false))
            .andReturn();
        String setCookie = started.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith(COOKIE + "=").contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/");
        Cookie participant = participantCookie(started);

        mvc.perform(post("/api/v2/stamp-participants").cookie(participant))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stamp_participants", Map.of(), Long.class)).isEqualTo(1);
        String storedHash = jdbc.queryForObject("SELECT token_sha256 FROM stamp_participants", Map.of(), String.class);
        assertThat(storedHash).isEqualTo(StampCardService.sha256(participant.getValue()))
            .isNotEqualTo(participant.getValue());

        mvc.perform(get("/api/v2/stamp-card"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("STAMP_NOT_STARTED"));
        mvc.perform(get("/api/v2/stamp-card").cookie(new Cookie(COOKIE, "forged-value")))
            .andExpect(status().isNotFound());
    }

    @Test
    void asksForStartAgainOnTheNextDayWithTheSameParticipant() throws Exception {
        Cookie participant = start();
        mvc.perform(get("/api/v2/stamp-card").cookie(participant)).andExpect(status().isOk());
        clock.set(OffsetDateTime.parse("2030-10-01T23:59:59+09:00"));
        mvc.perform(get("/api/v2/stamp-card").cookie(participant)).andExpect(status().isOk());

        clock.set(OffsetDateTime.parse("2030-10-02T00:00:00+09:00"));
        expectError(mvc.perform(get("/api/v2/stamp-card").cookie(participant)), 404, "STAMP_NOT_STARTED");
        expectError(mvc.perform(collect(participant, token("likelion"))), 404, "STAMP_NOT_STARTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stamp_collections", Map.of(), Long.class)).isZero();

        MvcResult restarted = mvc.perform(post("/api/v2/stamp-participants").cookie(participant))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.date").value("2030-10-02"))
            .andReturn();
        assertThat(participantCookie(restarted).getValue()).isEqualTo(participant.getValue());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stamp_participants", Map.of(), Long.class)).isEqualTo(1);
        mvc.perform(post("/api/v2/stamp-participants").cookie(participant))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(collect(participant, token("likelion"))).andExpect(status().isOk());
    }

    @Test
    void collectsOneStampPerBoothPerDayAndFourADay() throws Exception {
        mvc.perform(collect(null, token("likelion")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("STAMP_NOT_STARTED"));
        Cookie participant = start();

        mvc.perform(collect(participant, token("likelion")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.stamps.length()").value(1))
            .andExpect(jsonPath("$.data.stamps[0].boothId").value("likelion"))
            .andExpect(jsonPath("$.data.stamps[0].boothName").value("likelion 부스"));
        expectError(mvc.perform(collect(participant, token("likelion"))), 409, "STAMP_ALREADY_COLLECTED");
        expectError(mvc.perform(collect(participant, "not-a-real-booth-token")), 422, "INVALID_STAMP_TOKEN");
        expectError(mvc.perform(collect(participant, "short")), 422, "INVALID_STAMP_TOKEN");
        expectError(mvc.perform(collect(participant, token("dated"))), 422, "INVALID_STAMP_TOKEN");

        for (String booth : List.of("photo", "tarot", "career")) {
            mvc.perform(collect(participant, token(booth))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v2/stamp-card").cookie(participant))
            .andExpect(jsonPath("$.data.stamps.length()").value(4));
        expectError(mvc.perform(collect(participant, token("starbucks"))), 409, "STAMP_CARD_FULL");

        clock.set(OffsetDateTime.parse("2030-10-02T00:00:05+09:00"));
        start(participant);
        mvc.perform(collect(participant, token("likelion")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.date").value("2030-10-02"))
            .andExpect(jsonPath("$.data.stamps.length()").value(1));
        mvc.perform(collect(participant, token("dated"))).andExpect(status().isOk());
    }

    @Test
    void grantsTheRewardOnlyForAFullCardAndOnceADay() throws Exception {
        Cookie participant = start();
        expectError(mvc.perform(receipt(null, RECEIPT_CODE)), 409, "STAMP_CARD_INCOMPLETE");
        for (String booth : List.of("likelion", "photo", "tarot")) {
            mvc.perform(collect(participant, token(booth))).andExpect(status().isOk());
        }
        expectError(mvc.perform(receipt(participant, RECEIPT_CODE)), 409, "STAMP_CARD_INCOMPLETE");
        mvc.perform(collect(participant, token("career"))).andExpect(status().isOk());

        expectError(mvc.perform(receipt(participant, "000000")), 422, "INVALID_RECEIPT_CODE");
        mvc.perform(receipt(participant, RECEIPT_CODE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.verified").value(true));
        mvc.perform(get("/api/v2/stamp-card").cookie(participant))
            .andExpect(jsonPath("$.data.rewardClaimed").value(true));
        expectError(mvc.perform(receipt(participant, RECEIPT_CODE)), 409, "STAMP_REWARD_CLAIMED");
        expectError(mvc.perform(collect(participant, token("starbucks"))), 409, "STAMP_REWARD_CLAIMED");

        clock.set(OffsetDateTime.parse("2030-10-02T10:00:00+09:00"));
        start(participant);
        mvc.perform(get("/api/v2/stamp-card").cookie(participant))
            .andExpect(jsonPath("$.data.stamps").isEmpty())
            .andExpect(jsonPath("$.data.rewardClaimed").value(false));
    }

    private Cookie start() throws Exception {
        return participantCookie(mvc.perform(post("/api/v2/stamp-participants"))
            .andExpect(status().isCreated()).andReturn());
    }

    /** Today's START for a returning participant. */
    private void start(Cookie participant) throws Exception {
        mvc.perform(post("/api/v2/stamp-participants").cookie(participant)).andExpect(status().isCreated());
    }

    private static Cookie participantCookie(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        return new Cookie(COOKIE, value);
    }

    private static org.springframework.test.web.servlet.RequestBuilder collect(Cookie participant, String token) {
        var request = post("/api/v2/stamp-collections").contentType("application/json")
            .content("{\"token\":\"" + token + "\"}");
        return participant == null ? request : request.cookie(participant);
    }

    private static org.springframework.test.web.servlet.RequestBuilder receipt(Cookie participant, String code) {
        var request = post("/api/v2/stamp-receipt-verifications").contentType("application/json")
            .content("{\"code\":\"" + code + "\"}");
        return participant == null ? request : request.cookie(participant);
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    /** A fixed 22-character token per booth, as the generator produces. */
    private static String token(String booth) {
        return (booth + "-token-0123456789abcdefgh").substring(0, 22).replace('_', '-');
    }

    private void insertToken(String booth, java.time.LocalDate validDate, String token) {
        jdbc.update("""
            INSERT INTO stamp_booth_tokens (festival_revision_id, booth_id, valid_date, token_sha256)
            VALUES (:revisionId, :boothId, :validDate, :hash)
            """, new MapSqlParameterSource()
            .addValue("revisionId", REVISION_ID)
            .addValue("boothId", booth)
            .addValue("validDate", validDate)
            .addValue("hash", StampCardService.sha256(token)));
    }

    @TestConfiguration
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    static final class MutableClock extends Clock {

        private volatile Instant instant = Instant.EPOCH;

        void set(OffsetDateTime time) {
            instant = time.toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
