package dev.espero.festival.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.CatalogIntegrityException;
import dev.espero.festival.persistence.PerformanceCatalogReadStore;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.Artist;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.Image;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.LineupItem;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.Link;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.ArtistPerformance;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.PerformanceArtist;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.PerformanceItem;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.ProhibitedItems;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.Timetable;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.TimetableAxis;
import dev.espero.festival.support.ApiMetaTestFixtures;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PerformanceControllerTest {

    private static final UUID REVISION_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final LocalDate DAY_ONE = LocalDate.parse("2030-10-01");
    private static final LocalDate DAY_TWO = LocalDate.parse("2030-10-02");
    private static final LocalDate DAY_THREE = LocalDate.parse("2030-10-03");

    private final CatalogSnapshotProvider snapshots = mock(CatalogSnapshotProvider.class);
    private final PerformanceCatalogReadStore store = mock(PerformanceCatalogReadStore.class);
    private final Clock clock = Clock.fixed(Instant.parse("2030-10-02T09:00:00.123456789Z"), ZoneOffset.UTC);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(snapshots, store);
        ApiMetaSupport metaSupport = ApiMetaTestFixtures.contentMetaSupport(clock);
        mvc = MockMvcBuilders.standaloneSetup(
            new PerformanceController(snapshots, store, metaSupport, clock)
        ).setControllerAdvice(new GlobalApiExceptionHandler(metaSupport))
            .addFilters(new RequestIdFilter())
            .build();
        when(snapshots.required()).thenReturn(snapshot());
        when(store.festivalDates(REVISION_ID)).thenReturn(List.of(DAY_ONE, DAY_TWO, DAY_THREE));
    }

    @Test
    void returnsLineupEnvelopeWithStableOneBasedOrderAndRequestId() throws Exception {
        when(store.lineup(REVISION_ID, DAY_TWO, "ARTIST", "ko")).thenReturn(List.of(
            lineupItem("artist-a", "performance-a"),
            lineupItem("artist-a", "performance-b")
        ));

        mvc.perform(get("/api/v2/lineup").header("X-Request-Id", "lineup-request"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Request-Id", "lineup-request"))
            .andExpect(jsonPath("$.data.date", is("2030-10-02")))
            .andExpect(jsonPath("$.data.category", is("ARTIST")))
            .andExpect(jsonPath("$.data.items", hasSize(2)))
            .andExpect(jsonPath("$.data.items[0].performanceId", is("performance-a")))
            .andExpect(jsonPath("$.data.items[0].order", is(1)))
            .andExpect(jsonPath("$.data.items[1].performanceId", is("performance-b")))
            .andExpect(jsonPath("$.data.items[1].order", is(2)))
            .andExpect(jsonPath("$.meta.requestId", is("lineup-request")))
            .andExpect(jsonPath("$.meta.timezone", is("Asia/Seoul")))
            .andExpect(jsonPath("$.meta.revision", is(7)))
            .andExpect(jsonPath("$.meta.locale", is("ko")))
            .andExpect(jsonPath("$.meta.mock", is(false)));

        verify(snapshots, times(1)).required();
    }

    @Test
    void returnsAnEmptyLineupForAValidFestivalDayAndCategory() throws Exception {
        when(store.lineup(REVISION_ID, DAY_ONE, "CONTEST", "ko")).thenReturn(List.of());

        mvc.perform(get("/api/v2/lineup").param("date", DAY_ONE.toString()).param("category", "CONTEST"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.date", is(DAY_ONE.toString())))
            .andExpect(jsonPath("$.data.category", is("CONTEST")))
            .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void choosesTheFirstOrLastFestivalDayOutsideTheFestival() throws Exception {
        Clock before = Clock.fixed(Instant.parse("2030-09-01T00:00:00Z"), ZoneOffset.UTC);
        Clock after = Clock.fixed(Instant.parse("2030-11-01T00:00:00Z"), ZoneOffset.UTC);

        performDefaultDateRequest(before, List.of(DAY_ONE, DAY_TWO, DAY_THREE), DAY_ONE);
        performDefaultDateRequest(after, List.of(DAY_ONE, DAY_TWO, DAY_THREE), DAY_THREE);
    }

    @Test
    void choosesTheNextFestivalDayWhenTodayFallsInAGap() throws Exception {
        performDefaultDateRequest(clock, List.of(DAY_ONE, DAY_THREE), DAY_THREE);
    }

    @Test
    void rejectsMalformedDuplicateUnknownAndInvalidLineupQueries() throws Exception {
        assertError("INVALID_QUERY", get("/api/v2/lineup").param("date", "2030-99-99"));
        assertError("INVALID_QUERY", get("/api/v2/lineup").param("category", "ARTIST", "CONTEST"));
        assertError("INVALID_QUERY", get("/api/v2/lineup").param("unknown", "value"));
        assertError("INVALID_QUERY", get("/api/v2/lineup").param("category", "OTHER"));
        assertError("INVALID_QUERY", get("/api/v2/lineup").param("locale", "xx"));
        assertError("LOCALE_NOT_READY", get("/api/v2/lineup").param("locale", "en"));
        assertError("INVALID_DATE", get("/api/v2/lineup").param("date", "2030-10-04"));
    }

    @Test
    void returnsArtistDetailWithNullableAndEmptyOptionalContentInKst() throws Exception {
        Artist artist = new Artist(
            "artist-a",
            "ARTIST",
            "테스트 아티스트",
            image(),
            null,
            List.of(),
            List.of(),
            List.of(new ArtistPerformance(
                "performance-a",
                DAY_ONE,
                OffsetDateTime.parse("2030-10-01T09:20:00.123456Z"),
                OffsetDateTime.parse("2030-10-01T09:50:00.987654Z")
            ))
        );
        when(store.findArtist(REVISION_ID, "artist-a", "ko")).thenReturn(Optional.of(artist));

        mvc.perform(get("/api/v2/artists/artist-a").header("X-Request-Id", "artist-request"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Request-Id", "artist-request"))
            .andExpect(jsonPath("$.data.id", is("artist-a")))
            .andExpect(jsonPath("$.data.introduction").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.socialLinks").isEmpty())
            .andExpect(jsonPath("$.data.songs").isEmpty())
            .andExpect(jsonPath("$.data.performances[0].startsAt", is("2030-10-01T18:20:00.123+09:00")))
            .andExpect(jsonPath("$.data.performances[0].endsAt", is("2030-10-01T18:50:00.987+09:00")))
            .andExpect(jsonPath("$.meta.requestId", is("artist-request")))
            .andExpect(jsonPath("$.meta.revision", is(7)));

        verify(snapshots, times(1)).required();
    }

    @Test
    void returnsArtistLinksAndSongsAsNewTabTargets() throws Exception {
        Artist artist = new Artist(
            "artist-a", "ARTIST", "테스트 아티스트", image(), "소개",
            List.of(new Link("공식 채널", "https://example.com/artist")),
            List.of(new Link("대표곡", "https://example.com/song")),
            List.of()
        );
        when(store.findArtist(REVISION_ID, "artist-a", "ko")).thenReturn(Optional.of(artist));

        mvc.perform(get("/api/v2/artists/artist-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.socialLinks[0].target", is("_blank")))
            .andExpect(jsonPath("$.data.songs[0].target", is("_blank")))
            .andExpect(jsonPath("$.data.performances").isEmpty());
    }

    @Test
    void rejectsArtistQueryAndIdErrorsAndReturnsCurrentRevisionNotFound() throws Exception {
        assertError("INVALID_QUERY", get("/api/v2/artists/Artist-A"));
        assertError("INVALID_QUERY", get("/api/v2/artists/artist-a").param("locale", "ko", "ko"));
        assertError("INVALID_QUERY", get("/api/v2/artists/artist-a").param("unknown", "value"));
        assertError("LOCALE_NOT_READY", get("/api/v2/artists/artist-a").param("locale", "ja"));

        when(store.findArtist(REVISION_ID, "missing-artist", "ko")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v2/artists/missing-artist"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", is("NOT_FOUND")));
    }

    @Test
    void mapsTransientDatabaseFailuresToServiceUnavailable() throws Exception {
        when(store.festivalDates(REVISION_ID)).thenThrow(new TransientDataAccessResourceException("temporary"));

        mvc.perform(get("/api/v2/lineup"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code", is("SERVICE_UNAVAILABLE")))
            .andExpect(jsonPath("$.error.retryable", is(true)))
            .andExpect(jsonPath("$.meta.revision", is(7)));
    }

    @Test
    void mapsJdbcConnectionFailuresToServiceUnavailable() throws Exception {
        when(store.findArtist(REVISION_ID, "artist-a", "ko"))
            .thenThrow(new CannotGetJdbcConnectionException("connection unavailable", new SQLException()));

        mvc.perform(get("/api/v2/artists/artist-a"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code", is("SERVICE_UNAVAILABLE")))
            .andExpect(jsonPath("$.error.retryable", is(true)));
    }

    @Test
    void keepsSqlProgrammingFailuresAsSafeInternalErrors() throws Exception {
        when(store.festivalDates(REVISION_ID))
            .thenThrow(new BadSqlGrammarException("read festival dates", "SELECT", new SQLException()));

        mvc.perform(get("/api/v2/lineup"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code", is("INTERNAL_ERROR")))
            .andExpect(jsonPath("$.error.message", is("처리 중 오류가 발생했습니다.")));
    }

    @Test
    void mapsStoredTranslationIntegrityFailuresToSafeInternalErrors() throws Exception {
        when(store.findArtist(REVISION_ID, "artist-a", "ko"))
            .thenThrow(new CatalogIntegrityException("sensitive database detail"));

        mvc.perform(get("/api/v2/artists/artist-a"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code", is("INTERNAL_ERROR")))
            .andExpect(jsonPath("$.error.message", is("처리 중 오류가 발생했습니다.")))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.not("sensitive database detail")));
    }

    @Test
    void returnsTimetableWithDatabaseAxisOrderedContentRequestIdAndKstTimes() throws Exception {
        when(store.timetable(REVISION_ID, "ko")).thenReturn(new Timetable(
            List.of(DAY_ONE, DAY_TWO, DAY_THREE),
            new TimetableAxis(java.time.LocalTime.of(16, 30), java.time.LocalTime.of(23, 30)),
            List.of(new PerformanceItem(
                "performance-a", DAY_ONE, "테스트 공연",
                List.of(new PerformanceArtist("artist-a", "테스트 아티스트")),
                OffsetDateTime.parse("2030-10-01T09:20:00.123456Z"),
                OffsetDateTime.parse("2030-10-01T15:30:00.987654Z"),
                null
            ))
        ));

        mvc.perform(get("/api/v2/timetable").header("X-Request-Id", "timetable-request"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Request-Id", "timetable-request"))
            .andExpect(jsonPath("$.data.dates", hasSize(3)))
            .andExpect(jsonPath("$.data.axis.startTime", is("16:30")))
            .andExpect(jsonPath("$.data.axis.endTime", is("23:30")))
            .andExpect(jsonPath("$.data.items[0].title", is("테스트 공연")))
            .andExpect(jsonPath("$.data.items[0].artists[0].id", is("artist-a")))
            .andExpect(jsonPath("$.data.items[0].startsAt", is("2030-10-01T18:20:00.123+09:00")))
            .andExpect(jsonPath("$.data.items[0].endsAt", is("2030-10-02T00:30:00.987+09:00")))
            .andExpect(jsonPath("$.data.items[0].description").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.meta.requestId", is("timetable-request")))
            .andExpect(jsonPath("$.meta.revision", is(7)));

        verify(snapshots, times(1)).required();
    }

    @Test
    void returnsTimetableWithEmptyPerformances() throws Exception {
        when(store.timetable(REVISION_ID, "ko")).thenReturn(new Timetable(
            List.of(DAY_ONE),
            new TimetableAxis(java.time.LocalTime.of(17, 0), java.time.LocalTime.of(22, 0)),
            List.of()
        ));

        mvc.perform(get("/api/v2/timetable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dates[0]", is(DAY_ONE.toString())))
            .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void rejectsUnsupportedTimetableQueriesAndLocales() throws Exception {
        assertError("INVALID_QUERY", get("/api/v2/timetable").param("date", DAY_ONE.toString()));
        assertError("INVALID_QUERY", get("/api/v2/timetable").param("category", "ARTIST"));
        assertError("INVALID_QUERY", get("/api/v2/timetable").param("unknown", "value"));
        assertError("INVALID_QUERY", get("/api/v2/timetable").param("locale", "ko", "ko"));
        assertError("INVALID_QUERY", get("/api/v2/timetable").param("locale", ""));
        assertError("INVALID_QUERY", get("/api/v2/timetable").param("locale", "xx"));
        assertError("LOCALE_NOT_READY", get("/api/v2/timetable").param("locale", "en"));
    }

    @Test
    void returnsPerformanceDetailWithNullableDescriptionAndEmptyArtists() throws Exception {
        when(store.findPerformance(REVISION_ID, "performance-a", "ko")).thenReturn(Optional.of(
            new PerformanceItem(
                "performance-a", DAY_ONE, "테스트 공연", List.of(),
                OffsetDateTime.parse("2030-10-01T14:30:00Z"),
                OffsetDateTime.parse("2030-10-01T15:30:00Z"),
                null
            )
        ));

        mvc.perform(get("/api/v2/performances/performance-a").header("X-Request-Id", "performance-request"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Request-Id", "performance-request"))
            .andExpect(jsonPath("$.data.id", is("performance-a")))
            .andExpect(jsonPath("$.data.artists").isEmpty())
            .andExpect(jsonPath("$.data.description").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.startsAt", is("2030-10-01T23:30:00+09:00")))
            .andExpect(jsonPath("$.data.endsAt", is("2030-10-02T00:30:00+09:00")))
            .andExpect(jsonPath("$.meta.revision", is(7)));

        verify(snapshots, times(1)).required();
    }

    @Test
    void validatesPerformanceIdQueryLocaleAndNotFound() throws Exception {
        assertError("INVALID_QUERY", get("/api/v2/performances/Performance-A"));
        assertError("INVALID_QUERY", get("/api/v2/performances/performance-a").param("unknown", "value"));
        assertError("INVALID_QUERY", get("/api/v2/performances/performance-a").param("locale", "ko", "ko"));
        assertError("LOCALE_NOT_READY", get("/api/v2/performances/performance-a").param("locale", "ja"));

        when(store.findPerformance(REVISION_ID, "missing-performance", "ko")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v2/performances/missing-performance"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code", is("NOT_FOUND")));
    }

    @Test
    void returnsProhibitedContentIncludingEmptyAndMessageOnlyStates() throws Exception {
        when(store.prohibitedItems(REVISION_ID, "ko"))
            .thenReturn(new ProhibitedItems(List.of("물품 A", "물품 B"), "안내 문구"))
            .thenReturn(new ProhibitedItems(List.of(), null))
            .thenReturn(new ProhibitedItems(List.of(), "메시지만 존재"));

        mvc.perform(get("/api/v2/prohibited-items").header("X-Request-Id", "prohibited-request"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Request-Id", "prohibited-request"))
            .andExpect(jsonPath("$.data.items", hasSize(2)))
            .andExpect(jsonPath("$.data.message", is("안내 문구")))
            .andExpect(jsonPath("$.meta.revision", is(7)));
        mvc.perform(get("/api/v2/prohibited-items"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v2/prohibited-items"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.message", is("메시지만 존재")));

        verify(snapshots, times(3)).required();
    }

    @Test
    void validatesProhibitedQueriesAndLocales() throws Exception {
        assertError("INVALID_QUERY", get("/api/v2/prohibited-items").param("unknown", "value"));
        assertError("INVALID_QUERY", get("/api/v2/prohibited-items").param("locale", "ko", "ko"));
        assertError("INVALID_QUERY", get("/api/v2/prohibited-items").param("locale", ""));
        assertError("INVALID_QUERY", get("/api/v2/prohibited-items").param("locale", "xx"));
        assertError("LOCALE_NOT_READY", get("/api/v2/prohibited-items").param("locale", "zh-Hans"));
    }

    @Test
    void mapsNewEndpointDatabaseAndIntegrityFailuresWithoutBroadeningServiceUnavailable() throws Exception {
        when(store.timetable(REVISION_ID, "ko"))
            .thenThrow(new TransientDataAccessResourceException("temporary"))
            .thenThrow(new CatalogIntegrityException("timetable integrity detail"));
        when(store.findPerformance(REVISION_ID, "performance-a", "ko"))
            .thenThrow(new CannotGetJdbcConnectionException("connection unavailable", new SQLException()))
            .thenThrow(new CatalogIntegrityException("performance integrity detail"));
        when(store.prohibitedItems(REVISION_ID, "ko"))
            .thenThrow(new TransientDataAccessResourceException("temporary"))
            .thenThrow(new CatalogIntegrityException("prohibited integrity detail"));

        mvc.perform(get("/api/v2/timetable"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code", is("SERVICE_UNAVAILABLE")));
        mvc.perform(get("/api/v2/timetable"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code", is("INTERNAL_ERROR")));
        mvc.perform(get("/api/v2/performances/performance-a"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code", is("SERVICE_UNAVAILABLE")));
        mvc.perform(get("/api/v2/prohibited-items"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code", is("SERVICE_UNAVAILABLE")));
        mvc.perform(get("/api/v2/performances/performance-a"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code", is("INTERNAL_ERROR")));
        mvc.perform(get("/api/v2/prohibited-items"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code", is("INTERNAL_ERROR")))
            .andExpect(jsonPath("$.error.message", is("처리 중 오류가 발생했습니다.")));
    }

    @Test
    void returnsCatalogNotReadyForAllNewEndpoints() throws Exception {
        when(snapshots.required()).thenThrow(new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "CATALOG_NOT_READY",
            "카탈로그를 아직 사용할 수 없습니다.",
            true
        ));

        for (String path : List.of(
            "/api/v2/timetable",
            "/api/v2/performances/performance-a",
            "/api/v2/prohibited-items"
        )) {
            mvc.perform(get(path))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code", is("CATALOG_NOT_READY")));
        }
    }

    private void performDefaultDateRequest(
        Clock requestClock,
        List<LocalDate> festivalDates,
        LocalDate expected
    ) throws Exception {
        CatalogSnapshotProvider localSnapshots = mock(CatalogSnapshotProvider.class);
        PerformanceCatalogReadStore localStore = mock(PerformanceCatalogReadStore.class);
        ApiMetaSupport metaSupport = ApiMetaTestFixtures.contentMetaSupport(requestClock);
        MockMvc localMvc = MockMvcBuilders.standaloneSetup(
            new PerformanceController(localSnapshots, localStore, metaSupport, requestClock)
        ).setControllerAdvice(new GlobalApiExceptionHandler(metaSupport)).build();
        when(localSnapshots.required()).thenReturn(snapshot());
        when(localStore.festivalDates(REVISION_ID)).thenReturn(festivalDates);
        when(localStore.lineup(eq(REVISION_ID), eq(expected), eq("ARTIST"), eq("ko"))).thenReturn(List.of());

        localMvc.perform(get("/api/v2/lineup"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.date", is(expected.toString())));
    }

    private void assertError(
        String code,
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
    ) throws Exception {
        mvc.perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code", is(code)));
    }

    private LineupItem lineupItem(String artistId, String performanceId) {
        return new LineupItem(artistId, performanceId, "테스트 아티스트", image());
    }

    private Image image() {
        return new Image("/assets/artist.png", "테스트 아티스트", 800, 600);
    }

    private CatalogSnapshot snapshot() {
        return new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext("festival-catalog", REVISION_ID, 7),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            null
        );
    }
}
