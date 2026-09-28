package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.ArtistHypedBatchStore;
import dev.espero.festival.persistence.ArtistHypedStore;
import dev.espero.festival.support.ApiMetaTestFixtures;
import dev.espero.festival.support.PostgresTestImages;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "festival.rate-limit.enabled=true",
    "festival.rate-limit.artist-hyped.capacity=5",
    "festival.rate-limit.artist-hyped.refill-per-second=0.001"
})
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class ArtistHypedBatchIntegrationTest {
    private static final UUID FESTIVAL = ApiMetaTestFixtures.FESTIVAL_ID;
    private static final UUID REVISION = ApiMetaTestFixtures.REVISION_ID;
    private static final Instant NOW = Instant.parse("2030-10-01T03:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ArtistHypedBatchService service;
    @Autowired ArtistHypedBatchStore batches;
    @Autowired ArtistHypedStore counts;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WebApplicationContext context;
    @MockitoBean CatalogSnapshotProvider snapshots;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(snapshots.required()).thenReturn(new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext(FESTIVAL.toString(), REVISION, 1),
            List.of(), List.of(), List.of(), Map.of(), null, null, null,
            new CatalogSnapshot.FestivalHome("Test festival", List.of(LocalDate.now(ZoneId.of("Asia/Seoul"))), List.of())));
        when(snapshots.publishedLocales()).thenReturn(List.of("ko"));
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean("rateLimitFilter", FilterRegistrationBean.class).getFilter())
            .apply(springSecurity()).build();
    }

    @Test
    @Timeout(60)
    void concurrentRetriesApplyOneBatchExactlyOnceAndDifferentBatchesRemainAdditive() throws Exception {
        String artist = artist();
        UUID batch = UUID.randomUUID();
        var command = command(artist, batch, 20, "", true);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<ArtistHypedBatchService.Applied>> requests = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                requests.add(executor.submit(() -> { start.await(); return service.apply(command, request()); }));
            }
            start.countDown();
            for (var result : requests) assertThat(result.get(30, TimeUnit.SECONDS).count()).isEqualTo(20);
            List<Future<?>> additions = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                additions.add(executor.submit(() -> service.apply(command(artist, UUID.randomUUID(), 3, "", true), request())));
            }
            for (var addition : additions) addition.get(30, TimeUnit.SECONDS);
        }
        assertThat(count(artist)).isEqualTo(44);
        assertThat(receipts(batch)).isEqualTo(1);
    }

    @Test
    void lostResponseReplaysAfterServiceRecreationAndClosingWithoutMovingRehearsalCounts() {
        String artist = artist();
        UUID batch = UUID.randomUUID();
        String rehearsal = "rehearsal-2026-09-28:";
        assertThat(service.apply(command(artist, batch, 7, rehearsal, true), request()).count()).isEqualTo(7);
        counts.increment(FESTIVAL, rehearsal + artist, 2, NOW);
        // A fresh service/store pair has no process-local receipt cache, as after a restart.
        var recreated = new ArtistHypedBatchService(new ArtistHypedBatchStore(jdbc), new ArtistHypedStore(jdbc));
        var replay = new TransactionTemplate(transactionManager).execute(status ->
            recreated.apply(command(artist, batch, 7, "", false), request()));
        assertThat(replay.count()).isEqualTo(7);
        assertThat(replay.countPrefix()).isEqualTo(rehearsal);
        assertThat(count(artist)).isZero();
        assertThat(count(rehearsal + artist)).isEqualTo(9);
        assertThatThrownBy(() -> service.apply(command(artist, UUID.randomUUID(), 1, "", false), request()))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("HYPED_CLOSED"));
    }

    @Test
    void rejectsChangedPayloadAndRollsBackClaimsForClosedOrUnknownArtists() {
        String artist = artist();
        UUID batch = UUID.randomUUID();
        service.apply(command(artist, batch, 2, "", true), request());
        for (var conflict : List.of(command(artist, batch, 3, "", true), command("other-artist", batch, 2, "", false))) {
            assertThatThrownBy(() -> service.apply(conflict, request()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        }
        UUID closed = UUID.randomUUID();
        assertThatThrownBy(() -> service.apply(command(artist, closed, 3, "", false), request()))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("HYPED_CLOSED"));
        assertThat(receipts(closed)).isZero();
        assertThat(service.apply(command(artist, closed, 3, "", true), request()).count()).isEqualTo(5);
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.apply(command("unknown-artist", unknown, 1, "", true), request()))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("NOT_FOUND"));
        assertThat(receipts(unknown)).isZero();
    }

    @Test
    void databaseFailureAfterIncrementRollsBackBothCountAndReceiptSoTheSameBatchCanRetry() {
        String artist = artist();
        UUID batch = UUID.randomUUID();
        jdbc.getJdbcTemplate().execute("""
            CREATE FUNCTION fail_hyped_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'injected receipt failure'; END $$
            """);
        jdbc.getJdbcTemplate().execute("""
            CREATE TRIGGER fail_hyped_receipt BEFORE UPDATE ON artist_hyped_batches
            FOR EACH ROW EXECUTE FUNCTION fail_hyped_receipt()
            """);
        try {
            assertThatThrownBy(() -> service.apply(command(artist, batch, 6, "", true), request()))
                .isInstanceOf(DataAccessException.class);
            assertThat(count(artist)).isZero();
            assertThat(receipts(batch)).isZero();
        } finally {
            jdbc.getJdbcTemplate().execute("DROP TRIGGER fail_hyped_receipt ON artist_hyped_batches");
            jdbc.getJdbcTemplate().execute("DROP FUNCTION fail_hyped_receipt()");
        }
        assertThat(service.apply(command(artist, batch, 6, "", true), request()).count()).isEqualTo(6);
        assertThat(service.apply(command(artist, batch, 6, "", true), request()).count()).isEqualTo(6);
    }

    @Test
    void weightedLimitRollsBackUnappliedBatchAndReplayCostsOnlyTheRequestToken() throws Exception {
        String artist = artist();
        UUID batch = UUID.randomUUID();
        mvc.perform(batchRequest(artist, batch, 3, "198.51.100.71"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.hypedCount").value(3));
        mvc.perform(batchRequest(artist, batch, 3, "198.51.100.71"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.hypedCount").value(3));
        mvc.perform(post(path(artist)).contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(request -> { request.setRemoteAddr("198.51.100.71"); return request; }))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.hypedCount").value(4));

        mvc.perform(batchRequest(artist, UUID.randomUUID(), 4, "198.51.100.72")).andExpect(status().isOk());
        UUID limited = UUID.randomUUID();
        mvc.perform(batchRequest(artist, limited, 2, "198.51.100.72"))
            .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
            .andExpect(jsonPath("$.error.retryable").value(true));
        assertThat(receipts(limited)).isZero();
        assertThat(count(artist)).isEqualTo(8);
        mvc.perform(batchRequest(artist, limited, 2, "198.51.100.73"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.hypedCount").value(10));
        UUID oversized = UUID.randomUUID();
        mvc.perform(batchRequest(artist, oversized, 6, "198.51.100.74"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.retryable").value(false));
        assertThat(receipts(oversized)).isZero();
        assertThat(count(artist)).isEqualTo(10);
    }

    @Test
    void requestAcceptsWholeValuedDecimalAndExponentJsonNumbers() throws Exception {
        String artist = artist();
        int expectedCount = 0;
        for (String number : List.of("1.0", "1e0")) {
            UUID batch = UUID.randomUUID();
            String rawJson = body(FESTIVAL, batch, 1).replace("\"delta\":1", "\"delta\":" + number);
            mvc.perform(post(path(artist)).contentType(MediaType.APPLICATION_JSON).content(rawJson)
                    .with(request -> { request.setRemoteAddr("198.51.100.75"); return request; }))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hypedCount").value(++expectedCount));
            assertThat(receipts(batch)).isEqualTo(1);
        }
        assertThat(count(artist)).isEqualTo(2);
    }

    @Test
    void requestValidationRejectsUnknownOrPartialFieldsAndOtherFestivalsWithoutCounting() throws Exception {
        String artist = artist();
        String valid = body(FESTIVAL, UUID.randomUUID(), 1);
        for (String invalid : List.of("{\"delta\":1}", valid.replace("\"delta\":1", "\"delta\":1.5"),
            valid.replace("\"delta\":1", "\"delta\":0"), valid.replace("\"delta\":1", "\"delta\":21"),
            valid.replace("\"delta\":1", "\"delta\":\"1\""), valid.replace("\"delta\":1", "\"delta\":true"),
            valid.replace("\"delta\":1", "\"delta\":1e100"),
            valid.replace("\"delta\":1", "\"delta\":9999999999999999999999999999999999999999"),
            valid.replace("}", ",\"extra\":true}"),
            valid.replace(FESTIVAL.toString(), "1-1-1-1-1"))) {
            mvc.perform(post(path(artist)).contentType(MediaType.APPLICATION_JSON).content(invalid)
                    .with(request -> { request.setRemoteAddr(UUID.randomUUID().toString()); return request; }))
                .andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(post(path(artist)).contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), UUID.randomUUID(), 1)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("FESTIVAL_MISMATCH"));
        assertThat(count(artist)).isZero();
    }

    private ArtistHypedBatchService.Command command(String artist, UUID batch, int delta, String prefix, boolean enabled) {
        return new ArtistHypedBatchService.Command(FESTIVAL, REVISION, batch, artist, delta, prefix, NOW, enabled);
    }

    private MockHttpServletRequest request() { return new MockHttpServletRequest(); }

    private String artist() {
        String id = "batch-" + UUID.randomUUID();
        jdbc.update("INSERT INTO artists (festival_revision_id, id, category) VALUES (:revision, :id, 'ARTIST')",
            Map.of("revision", REVISION, "id", id));
        return id;
    }

    private long count(String artist) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(hyped_count), 0) FROM artist_hyped_counts WHERE festival_id = :festival AND artist_id = :artist",
            Map.of("festival", FESTIVAL, "artist", artist), Long.class);
    }

    private long receipts(UUID batch) {
        return jdbc.queryForObject("SELECT count(*) FROM artist_hyped_batches WHERE festival_id = :festival AND batch_id = :batch",
            Map.of("festival", FESTIVAL, "batch", batch), Long.class);
    }

    private static String path(String artist) { return "/api/v2/artists/" + artist + "/hyped"; }
    private static String body(UUID festival, UUID batch, int delta) {
        return "{\"festivalId\":\"" + festival + "\",\"batchId\":\"" + batch + "\",\"delta\":" + delta + "}";
    }
    private static MockHttpServletRequestBuilder batchRequest(String artist, UUID batch, int delta, String address) {
        return post(path(artist)).contentType(MediaType.APPLICATION_JSON).content(body(FESTIVAL, batch, delta))
            .with(request -> { request.setRemoteAddr(address); return request; });
    }
}
