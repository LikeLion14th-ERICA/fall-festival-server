package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.ArtistHypedStore;
import dev.espero.festival.support.ApiMetaTestFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ArtistHypedControllerOpenApiTest {

    private static final UUID FESTIVAL_ID = ApiMetaTestFixtures.FESTIVAL_ID;
    private static final UUID REVISION_ID = ApiMetaTestFixtures.REVISION_ID;
    private static final LocalDate DAY = LocalDate.parse("2030-10-01");
    private static final String LIST_PATH = "/api/v2/artist-hyped";
    private static final String POST_PATH = "/api/v2/artists/artist-a/hyped";

    private final ObjectMapper json = new ObjectMapper();
    private final CatalogSnapshotProvider snapshots = mock(CatalogSnapshotProvider.class);
    private final ArtistHypedStore store = mock(ArtistHypedStore.class);
    private final Clock clock = mock(Clock.class);
    private JsonNode openApi;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        openApi = json.readTree(Files.readString(Path.of("api-v2", "openapi.json")));
        useTime("2030-10-01T00:00:00Z");
        when(clock.withZone(any())).thenAnswer(invocation -> Clock.fixed(clock.instant(), invocation.getArgument(0)));
        ApiMetaSupport metaSupport = ApiMetaTestFixtures.contentMetaSupport(clock);
        mvc = MockMvcBuilders.standaloneSetup(new ArtistHypedController(snapshots, store, metaSupport, clock))
            .setControllerAdvice(new GlobalApiExceptionHandler(metaSupport))
            .addFilters(new RequestIdFilter())
            .build();
        when(snapshots.required()).thenReturn(snapshot());
        when(snapshots.publishedLocales()).thenReturn(List.of("ko"));
    }

    @Test
    void validatesHypedReadAndIncrementAgainstOpenApi() throws Exception {
        when(store.currentArtists(FESTIVAL_ID, REVISION_ID, "")).thenReturn(List.of(
            new ArtistHypedStore.Count("artist-a", 0),
            new ArtistHypedStore.Count("artist-b", 12_000)
        ));
        when(store.isCurrentArtist(REVISION_ID, "artist-a")).thenReturn(true);
        when(store.increment(FESTIVAL_ID, "artist-a", "", Instant.parse("2030-10-01T00:00:00Z")))
            .thenReturn(1L, 2L);

        MvcResult read = assertMatches("/api/v2/artist-hyped", "get", 200, get(LIST_PATH));
        assertThat(json.readTree(read.getResponse().getContentAsString()).at("/data/items/1/hypedCount").asLong())
            .isEqualTo(12_000);
        assertThat(json.readTree(read.getResponse().getContentAsString()).at("/data/hypedEnabled").asBoolean())
            .isTrue();
        assertThat(json.readTree(read.getResponse().getContentAsString()).at("/meta/revision").asLong())
            .isZero();
        assertThat(read.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");

        for (int expected = 1; expected <= 2; expected++) {
            MvcResult result = assertMatches("/api/v2/artists/{artistId}/hyped", "post", 200,
                post(POST_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"));
            assertThat(json.readTree(result.getResponse().getContentAsString()).at("/data/hypedCount").asInt())
                .isEqualTo(expected);
            assertThat(json.readTree(result.getResponse().getContentAsString()).at("/meta/revision").asLong())
                .isZero();
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }

    @Test
    void rejectsContestAndClosedDayWithoutIncrement() throws Exception {
        MvcResult contest = assertMatches("/api/v2/artists/{artistId}/hyped", "post", 404,
            post("/api/v2/artists/contest-a/hyped").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(json.readTree(contest.getResponse().getContentAsString()).at("/error/code").asText())
            .isEqualTo("NOT_FOUND");

        when(store.isCurrentArtist(REVISION_ID, "artist-a")).thenReturn(true);
        useTime("2030-10-01T14:59:59Z"); // 23:59:59 KST remains a festival day.
        when(store.increment(FESTIVAL_ID, "artist-a", "", Instant.parse("2030-10-01T14:59:59Z")))
            .thenReturn(1L);
        assertMatches("/api/v2/artists/{artistId}/hyped", "post", 200,
            post(POST_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"));
        useTime("2030-10-01T15:00:00Z"); // 00:00:00 KST, next day is not registered.
        MvcResult closed = assertMatches("/api/v2/artists/{artistId}/hyped", "post", 409,
            post(POST_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(json.readTree(closed.getResponse().getContentAsString()).at("/error/code").asText())
            .isEqualTo("HYPED_CLOSED");
        mvc.perform(get(LIST_PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hypedEnabled").value(false));
        verify(store, never()).increment(eq(FESTIVAL_ID), eq("contest-a"), any());
    }

    @Test
    void refusesInvalidBodiesAndUnavailableStorage() throws Exception {
        when(store.isCurrentArtist(REVISION_ID, "artist-a")).thenReturn(true);
        assertMatches("/api/v2/artists/{artistId}/hyped", "post", 422,
            post(POST_PATH).contentType(MediaType.APPLICATION_JSON).content("{\"extra\":true}"));
        assertMatches("/api/v2/artists/{artistId}/hyped", "post", 415,
            post(POST_PATH).contentType(MediaType.TEXT_PLAIN).content("{}"));
        when(store.currentArtists(FESTIVAL_ID, REVISION_ID, ""))
            .thenThrow(new TransientDataAccessResourceException("database unavailable"));
        MvcResult failure = assertMatches("/api/v2/artist-hyped", "get", 503, get(LIST_PATH));
        assertThat(json.readTree(failure.getResponse().getContentAsString()).at("/error/code").asText())
            .isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void keepsHypedOpenAfterRehearsalOnSeptemberTwentyEighth() throws Exception {
        useTime("2026-09-28T01:59:59Z");
        mvc.perform(get(LIST_PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hypedEnabled").value(false));
        useTime("2026-09-28T02:00:00Z");
        mvc.perform(get(LIST_PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hypedEnabled").value(true));
        useTime("2026-09-28T05:59:59Z");
        mvc.perform(get(LIST_PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hypedEnabled").value(true));
        useTime("2026-09-28T06:00:00Z");
        mvc.perform(get(LIST_PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hypedEnabled").value(true));
        useTime("2026-09-28T14:59:59Z");
        mvc.perform(get(LIST_PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hypedEnabled").value(true));
    }

    @Test
    void isolatesRehearsalCountsAndRestoresFestivalCountsAtThreePm() throws Exception {
        when(store.currentArtists(FESTIVAL_ID, REVISION_ID, "rehearsal-2026-09-28:"))
            .thenReturn(List.of(new ArtistHypedStore.Count("artist-a", 8)));
        when(store.isCurrentArtist(REVISION_ID, "artist-a")).thenReturn(true);
        when(store.increment(FESTIVAL_ID, "artist-a", "rehearsal-2026-09-28:",
            Instant.parse("2026-09-28T02:00:00Z"))).thenReturn(9L);
        useTime("2026-09-28T02:00:00Z");
        MvcResult rehearsalRead = mvc.perform(get(LIST_PATH)).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(rehearsalRead.getResponse().getContentAsString())
            .at("/data/items/0/artistId").asText()).isEqualTo("artist-a");
        assertThat(json.readTree(rehearsalRead.getResponse().getContentAsString())
            .at("/data/items/0/hypedCount").asLong()).isEqualTo(8);
        MvcResult rehearsalPost = mvc.perform(post(POST_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(rehearsalPost.getResponse().getContentAsString())
            .at("/data/artistId").asText()).isEqualTo("artist-a");
        assertThat(json.readTree(rehearsalPost.getResponse().getContentAsString())
            .at("/data/hypedCount").asLong()).isEqualTo(9);

        when(store.currentArtists(FESTIVAL_ID, REVISION_ID, ""))
            .thenReturn(List.of(new ArtistHypedStore.Count("artist-a", 3)));
        useTime("2026-09-28T06:00:00Z");
        MvcResult festivalRead = mvc.perform(get(LIST_PATH)).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(festivalRead.getResponse().getContentAsString())
            .at("/data/items/0/hypedCount").asLong()).isEqualTo(3);
        when(store.increment(FESTIVAL_ID, "artist-a", "", Instant.parse("2026-09-28T06:00:00Z")))
            .thenReturn(4L);
        MvcResult livePost = assertMatches("/api/v2/artists/{artistId}/hyped", "post", 200,
            post(POST_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(json.readTree(livePost.getResponse().getContentAsString())
            .at("/data/hypedCount").asLong()).isEqualTo(4);
        verify(store).increment(FESTIVAL_ID, "artist-a", "", Instant.parse("2026-09-28T06:00:00Z"));
        verify(store).increment(FESTIVAL_ID, "artist-a", "rehearsal-2026-09-28:",
            Instant.parse("2026-09-28T02:00:00Z"));
    }

    @Test
    void cachesCountsForOneSecondAndRetriesFailedRefreshWithoutServingExpiredCounts() throws Exception {
        when(store.currentArtists(FESTIVAL_ID, REVISION_ID, ""))
            .thenReturn(List.of(new ArtistHypedStore.Count("artist-a", 5)))
            .thenThrow(new TransientDataAccessResourceException("unavailable"))
            .thenReturn(List.of(new ArtistHypedStore.Count("artist-a", 9)));
        mvc.perform(get(LIST_PATH)).andExpect(jsonPath("$.data.items[0].hypedCount").value(5));
        useTime("2030-10-01T00:00:00.999Z");
        mvc.perform(get(LIST_PATH)).andExpect(jsonPath("$.data.items[0].hypedCount").value(5));
        verify(store).currentArtists(FESTIVAL_ID, REVISION_ID, "");
        useTime("2030-10-01T00:00:01Z");
        mvc.perform(get(LIST_PATH)).andExpect(status().isServiceUnavailable());
        mvc.perform(get(LIST_PATH)).andExpect(jsonPath("$.data.items[0].hypedCount").value(9));
        verify(store, times(3)).currentArtists(FESTIVAL_ID, REVISION_ID, "");
    }

    @Test
    void updatesCachedCountsWithoutExtendingTtlOrRegressingOnLateWriteCompletion() throws Exception {
        when(store.currentArtists(FESTIVAL_ID, REVISION_ID, ""))
            .thenReturn(List.of(new ArtistHypedStore.Count("artist-a", 5)))
            .thenReturn(List.of(new ArtistHypedStore.Count("artist-a", 8)));
        when(store.isCurrentArtist(REVISION_ID, "artist-a")).thenReturn(true);
        when(store.increment(eq(FESTIVAL_ID), eq("artist-a"), eq(""), any())).thenReturn(7L, 6L);
        mvc.perform(get(LIST_PATH)).andExpect(status().isOk());
        useTime("2030-10-01T00:00:00.500Z");
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(POST_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        }
        mvc.perform(get(LIST_PATH)).andExpect(jsonPath("$.data.items[0].hypedCount").value(7));
        verify(store).currentArtists(FESTIVAL_ID, REVISION_ID, "");
        useTime("2030-10-01T00:00:01Z");
        mvc.perform(get(LIST_PATH)).andExpect(jsonPath("$.data.items[0].hypedCount").value(8));
        verify(store, times(2)).currentArtists(FESTIVAL_ID, REVISION_ID, "");
    }

    @Test
    void recalculatesMidnightAvailabilityEvenWhileCountsAreCached() throws Exception {
        useTime("2030-10-01T14:59:59.500Z");
        mvc.perform(get(LIST_PATH)).andExpect(jsonPath("$.data.hypedEnabled").value(true));
        useTime("2030-10-01T15:00:00Z");
        mvc.perform(get(LIST_PATH)).andExpect(jsonPath("$.data.hypedEnabled").value(false));
        verify(store).currentArtists(FESTIVAL_ID, REVISION_ID, "");
    }

    @Test
    void switchesNamespaceAndRevisionBeforeCacheExpiry() throws Exception {
        useTime("2026-09-28T05:59:59.500Z");
        when(store.currentArtists(FESTIVAL_ID, REVISION_ID, "rehearsal-2026-09-28:"))
            .thenReturn(List.of(new ArtistHypedStore.Count("artist-a", 100)));
        when(store.currentArtists(FESTIVAL_ID, REVISION_ID, ""))
            .thenReturn(List.of(new ArtistHypedStore.Count("artist-a", 2)));
        mvc.perform(get(LIST_PATH)).andExpect(jsonPath("$.data.items[0].hypedCount").value(100));
        useTime("2026-09-28T06:00:00Z");
        mvc.perform(get(LIST_PATH)).andExpect(jsonPath("$.data.items[0].hypedCount").value(2));
        CatalogSnapshot original = snapshot();
        UUID revision = UUID.randomUUID();
        when(snapshots.required()).thenReturn(new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext(FESTIVAL_ID.toString(), revision, 8),
            List.of(), List.of(), List.of(), Map.of(), null, null, null, original.home()));
        when(store.currentArtists(FESTIVAL_ID, revision, ""))
            .thenReturn(List.of(new ArtistHypedStore.Count("artist-c", 3)));
        mvc.perform(get(LIST_PATH)).andExpect(jsonPath("$.data.items[0].artistId").value("artist-c"));
        verify(store).currentArtists(FESTIVAL_ID, revision, "");
    }

    @Test
    void concurrentReadsShareOneDatabaseQuery() throws Exception {
        when(store.currentArtists(FESTIVAL_ID, REVISION_ID, ""))
            .thenReturn(List.of(new ArtistHypedStore.Count("artist-a", 5)));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(12)) {
            var requests = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) {
                requests.add(executor.submit(() -> {
                    start.await();
                    mvc.perform(get(LIST_PATH)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.items[0].hypedCount").value(5));
                    return null;
                }));
            }
            start.countDown();
            for (var request : requests) {
                request.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        verify(store).currentArtists(FESTIVAL_ID, REVISION_ID, "");
    }

    private MvcResult assertMatches(String path, String method, int expectedStatus,
        MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request)
            .andExpect(status().is(expectedStatus))
            .andExpect(header().exists("X-Request-Id"))
            .andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        JsonNode schema = openApi.at("/paths/" + path.replace("/", "~1") + "/" + method
            + "/responses/" + expectedStatus + "/content/application~1json/schema");
        assertThat(schema.isMissingNode()).isFalse();
        var validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(resolveReferences(schema), SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        assertThat(validator.validate(response)).isEmpty();
        return result;
    }

    private JsonNode resolveReferences(JsonNode schema) {
        if (schema.isObject() && schema.has("$ref")) {
            String reference = schema.get("$ref").asText();
            return resolveReferences(openApi.at("/components/schemas/" + reference.substring("#/components/schemas/".length())));
        }
        if (schema.isObject()) {
            ObjectNode copy = json.createObjectNode();
            schema.fields().forEachRemaining(entry -> copy.set(entry.getKey(), resolveReferences(entry.getValue())));
            return copy;
        }
        if (schema.isArray()) {
            ArrayNode copy = json.createArrayNode();
            schema.forEach(item -> copy.add(resolveReferences(item)));
            return copy;
        }
        return schema.deepCopy();
    }

    private void useTime(String instant) {
        when(clock.instant()).thenReturn(Instant.parse(instant));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private CatalogSnapshot snapshot() {
        return new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext(FESTIVAL_ID.toString(), REVISION_ID, 7),
            List.of(), List.of(), List.of(), Map.of(), null, null, null,
            new CatalogSnapshot.FestivalHome("테스트 축제", List.of(DAY), List.of())
        );
    }
}
