package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.Artist;
import dev.espero.festival.support.ApiMetaTestFixtures;
import dev.espero.festival.web.ApiMetaSupport;
import dev.espero.festival.web.CatalogSnapshotProvider;
import dev.espero.festival.web.GlobalApiExceptionHandler;
import dev.espero.festival.web.PerformanceController;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class PerformanceCatalogReadStoreIntegrationTest {

    private static final LocalDate DAY_ONE = LocalDate.parse("2030-10-01");
    private static final LocalDate DAY_TWO = LocalDate.parse("2030-10-02");
    private static final LocalDate DAY_THREE = LocalDate.parse("2030-10-03");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private PerformanceCatalogReadStore store;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void returnsFestivalDaysInCanonicalOrder() {
        UUID revisionId = publishedRevisionId();
        insertDay(revisionId, DAY_THREE);
        insertDay(revisionId, DAY_ONE);
        insertDay(revisionId, DAY_TWO);

        assertThat(store.festivalDates(revisionId)).containsExactly(DAY_ONE, DAY_TWO, DAY_THREE);
    }

    @Test
    void choosesFirstFestivalDayBeforeTheFestival() throws Exception {
        assertDefaultDate(Instant.parse("2030-09-01T00:00:00Z"), DAY_ONE);
    }

    @Test
    void choosesTodayDuringTheFestival() throws Exception {
        assertDefaultDate(Instant.parse("2030-10-02T00:00:00Z"), DAY_TWO);
    }

    @Test
    void choosesLastFestivalDayAfterTheFestival() throws Exception {
        assertDefaultDate(Instant.parse("2030-11-01T00:00:00Z"), DAY_THREE);
    }

    @Test
    void filtersAndOrdersLineupAndRepeatsAnArtistForEachPerformance() {
        UUID revisionId = publishedRevisionId();
        insertDay(revisionId, DAY_ONE);
        insertDay(revisionId, DAY_TWO);
        insertArtist(revisionId, "artist-a", "ARTIST", "아티스트 A", "소개 A");
        insertArtist(revisionId, "artist-b", "ARTIST", "아티스트 B", null);
        insertArtist(revisionId, "contest-a", "CONTEST", "콘테스트 A", null);

        insertPerformance(revisionId, "performance-b", DAY_ONE, "2030-10-01T18:00:00+09:00");
        insertPerformance(revisionId, "performance-a", DAY_ONE, "2030-10-01T18:00:00+09:00");
        insertPerformance(revisionId, "performance-early", DAY_ONE, "2030-10-01T17:00:00+09:00");
        insertPerformance(revisionId, "performance-without-artist", DAY_ONE, "2030-10-01T19:00:00+09:00");
        relate(revisionId, "performance-early", "artist-b", 2);
        relate(revisionId, "performance-early", "artist-a", 1);
        relate(revisionId, "performance-a", "artist-a", 1);
        relate(revisionId, "performance-b", "artist-a", 1);
        relate(revisionId, "performance-b", "contest-a", 2);

        assertThat(store.lineup(revisionId, DAY_ONE, "ARTIST", "ko"))
            .extracting(
                PerformanceCatalogReadStore.LineupItem::performanceId,
                PerformanceCatalogReadStore.LineupItem::artistId
            )
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("performance-early", "artist-a"),
                org.assertj.core.groups.Tuple.tuple("performance-early", "artist-b"),
                org.assertj.core.groups.Tuple.tuple("performance-a", "artist-a"),
                org.assertj.core.groups.Tuple.tuple("performance-b", "artist-a")
            );
        assertThat(store.lineup(revisionId, DAY_ONE, "CONTEST", "ko"))
            .extracting(PerformanceCatalogReadStore.LineupItem::artistId)
            .containsExactly("contest-a");
        assertThat(store.lineup(revisionId, DAY_TWO, "ARTIST", "ko")).isEmpty();
    }

    @Test
    void keepsSameStableIdsFromDraftAndArchivedRevisionsIsolated() {
        UUID published = publishedRevisionId();
        UUID archived = insertRevision("archived", 2);
        UUID draft = insertRevision("draft", 3);
        for (UUID revisionId : new UUID[] {published, archived, draft}) {
            insertDay(revisionId, DAY_ONE);
            insertArtist(revisionId, "same-artist", "ARTIST", "이름-" + revisionId, null);
            insertPerformance(revisionId, "same-performance", DAY_ONE, "2030-10-01T18:00:00+09:00");
            relate(revisionId, "same-performance", "same-artist", 1);
        }

        assertThat(store.lineup(published, DAY_ONE, "ARTIST", "ko"))
            .singleElement()
            .satisfies(item -> {
                assertThat(item.artistId()).isEqualTo("same-artist");
                assertThat(item.performanceId()).isEqualTo("same-performance");
                assertThat(item.name()).isEqualTo("이름-" + published);
            });
    }

    @Test
    void rejectsALineupRelationWhoseReadyTranslationIsMissing() {
        UUID revisionId = publishedRevisionId();
        insertDay(revisionId, DAY_ONE);
        insertArtistWithoutTranslation(revisionId, "artist-a", "ARTIST");
        insertPerformance(revisionId, "performance-a", DAY_ONE, "2030-10-01T18:00:00+09:00");
        relate(revisionId, "performance-a", "artist-a", 1);

        assertThatThrownBy(() -> store.lineup(revisionId, DAY_ONE, "ARTIST", "ko"))
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("lineup artist");
    }

    @Test
    void returnsArtistDetailsWithDeterministicChildOrdering() {
        UUID revisionId = publishedRevisionId();
        insertDay(revisionId, DAY_ONE);
        insertDay(revisionId, DAY_TWO);
        insertArtist(revisionId, "artist-a", "ARTIST", "아티스트 A", "아티스트 소개");
        insertLink(revisionId, "artist-a", 2, "https://example.com/second", "두 번째");
        insertLink(revisionId, "artist-a", 1, "https://example.com/first", "첫 번째");
        insertSong(revisionId, "artist-a", 3, "https://example.com/song-3", "대표곡 3");
        insertSong(revisionId, "artist-a", 1, "https://example.com/song-1", "대표곡 1");
        insertSong(revisionId, "artist-a", 2, "https://example.com/song-2", "대표곡 2");
        insertPerformance(revisionId, "performance-c", DAY_TWO, "2030-10-02T17:00:00+09:00");
        insertPerformance(revisionId, "performance-b", DAY_ONE, "2030-10-01T18:00:00+09:00");
        insertPerformance(revisionId, "performance-a", DAY_ONE, "2030-10-01T18:00:00+09:00");
        relate(revisionId, "performance-c", "artist-a", 1);
        relate(revisionId, "performance-b", "artist-a", 1);
        relate(revisionId, "performance-a", "artist-a", 1);

        Artist artist = store.findArtist(revisionId, "artist-a", "ko").orElseThrow();

        assertThat(artist.name()).isEqualTo("아티스트 A");
        assertThat(artist.introduction()).isEqualTo("아티스트 소개");
        assertThat(artist.socialLinks()).extracting(PerformanceCatalogReadStore.Link::label)
            .containsExactly("첫 번째", "두 번째");
        assertThat(artist.songs()).extracting(PerformanceCatalogReadStore.Link::label)
            .containsExactly("대표곡 1", "대표곡 2", "대표곡 3");
        assertThat(artist.performances()).extracting(PerformanceCatalogReadStore.Performance::id)
            .containsExactly("performance-a", "performance-b", "performance-c");
    }

    @Test
    void returnsNullableIntroductionAndEmptyCollectionsForAnUnscheduledArtist() {
        UUID revisionId = publishedRevisionId();
        insertArtist(revisionId, "artist-empty", "CONTEST", "빈 팀", null);

        assertThat(store.findArtist(revisionId, "artist-empty", "ko"))
            .hasValueSatisfying(artist -> {
                assertThat(artist.introduction()).isNull();
                assertThat(artist.socialLinks()).isEmpty();
                assertThat(artist.songs()).isEmpty();
                assertThat(artist.performances()).isEmpty();
            });
    }

    @Test
    void archivedOrDraftCopiesDoNotMakeAnArtistExistInTheCurrentRevision() {
        UUID archived = insertRevision("archived", 2);
        UUID draft = insertRevision("draft", 3);
        insertArtist(archived, "other-revision-artist", "ARTIST", "보관 아티스트", null);
        insertArtist(draft, "other-revision-artist", "ARTIST", "초안 아티스트", null);

        assertThat(store.findArtist(publishedRevisionId(), "other-revision-artist", "ko")).isEmpty();
    }

    @Test
    void rejectsMissingArtistLinkAndSongTranslations() {
        UUID revisionId = publishedRevisionId();
        insertArtist(revisionId, "artist-a", "ARTIST", "아티스트 A", null);
        insertLinkWithoutTranslation(revisionId, "artist-a", 1, "https://example.com/link");

        assertThatThrownBy(() -> store.findArtist(revisionId, "artist-a", "ko"))
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("artist link");

        jdbc.update("DELETE FROM artist_links WHERE festival_revision_id = :revisionId", params(revisionId));
        insertSongWithoutTranslation(revisionId, "artist-a", 1, "https://example.com/song");

        assertThatThrownBy(() -> store.findArtist(revisionId, "artist-a", "ko"))
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("artist song");
    }

    @Test
    void rejectsAMissingArtistTranslation() {
        UUID revisionId = publishedRevisionId();
        insertArtistWithoutTranslation(revisionId, "artist-a", "ARTIST");

        assertThatThrownBy(() -> store.findArtist(revisionId, "artist-a", "ko"))
            .isInstanceOf(CatalogIntegrityException.class)
            .hasMessageContaining("Published artist");
    }

    private UUID publishedRevisionId() {
        return jdbc.queryForObject(
            "SELECT id FROM festival_revisions WHERE state = 'published'",
            Map.of(),
            UUID.class
        );
    }

    private void assertDefaultDate(Instant now, LocalDate expected) throws Exception {
        UUID revisionId = publishedRevisionId();
        insertDay(revisionId, DAY_ONE);
        insertDay(revisionId, DAY_TWO);
        insertDay(revisionId, DAY_THREE);
        CatalogSnapshotProvider snapshots = mock(CatalogSnapshotProvider.class);
        CatalogSnapshot snapshot = new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext(
                "festival-catalog",
                revisionId,
                1
            ),
            List.of(), List.of(), List.of(), Map.of(), null
        );
        when(snapshots.required()).thenReturn(snapshot);
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        ApiMetaSupport metaSupport = ApiMetaTestFixtures.contentMetaSupport(clock);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new PerformanceController(snapshots, store, metaSupport, clock)
        ).setControllerAdvice(new GlobalApiExceptionHandler(metaSupport)).build();

        mvc.perform(get("/api/v2/lineup"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.date").value(expected.toString()))
            .andExpect(jsonPath("$.data.items").isEmpty());
    }

    private UUID insertRevision(String state, int revisionNumber) {
        UUID revisionId = UUID.randomUUID();
        UUID festivalId = jdbc.queryForObject(
            "SELECT festival_id FROM festival_revisions WHERE state = 'published'",
            Map.of(),
            UUID.class
        );
        jdbc.update("""
            INSERT INTO festival_revisions (
                id, festival_id, revision_number, state,
                approved_at, scheduled_at, published_at, created_at, updated_at
            ) VALUES (
                :revisionId, :festivalId, :revisionNumber, :state,
                CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """, new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("festivalId", festivalId)
            .addValue("revisionNumber", revisionNumber)
            .addValue("state", state));
        return revisionId;
    }

    private void insertDay(UUID revisionId, LocalDate date) {
        jdbc.update("""
            INSERT INTO festival_days (
                id, festival_revision_id, festival_date, opens_at, closes_at, created_at, updated_at
            ) VALUES (
                :id, :revisionId, :date, :opensAt, :closesAt, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """, params(revisionId)
            .addValue("id", UUID.randomUUID())
            .addValue("date", date)
            .addValue("opensAt", OffsetDateTime.parse(date + "T09:00:00+09:00"))
            .addValue("closesAt", OffsetDateTime.parse(date + "T23:00:00+09:00")));
    }

    private void insertArtist(
        UUID revisionId,
        String artistId,
        String category,
        String name,
        String introduction
    ) {
        insertArtistWithoutTranslation(revisionId, artistId, category);
        jdbc.update("""
            INSERT INTO artist_translations (
                festival_revision_id, artist_id, locale, name, image_alt, introduction
            ) VALUES (:revisionId, :artistId, 'ko', :name, :imageAlt, :introduction)
            """, params(revisionId)
            .addValue("artistId", artistId)
            .addValue("name", name)
            .addValue("imageAlt", name + " 이미지")
            .addValue("introduction", introduction));
    }

    private void insertArtistWithoutTranslation(UUID revisionId, String artistId, String category) {
        jdbc.update("""
            INSERT INTO artists (
                festival_revision_id, id, category, image_url, image_width, image_height
            ) VALUES (:revisionId, :artistId, :category, '/assets/artist.png', 800, 600)
            """, params(revisionId).addValue("artistId", artistId).addValue("category", category));
    }

    private void insertPerformance(UUID revisionId, String performanceId, LocalDate date, String startsAt) {
        OffsetDateTime start = OffsetDateTime.parse(startsAt);
        jdbc.update("""
            INSERT INTO performances (
                festival_revision_id, id, festival_date, starts_at, ends_at
            ) VALUES (:revisionId, :performanceId, :date, :startsAt, :endsAt)
            """, params(revisionId)
            .addValue("performanceId", performanceId)
            .addValue("date", date)
            .addValue("startsAt", start)
            .addValue("endsAt", start.plusMinutes(30)));
    }

    private void relate(UUID revisionId, String performanceId, String artistId, int displayOrder) {
        jdbc.update("""
            INSERT INTO performance_artists (
                festival_revision_id, performance_id, artist_id, display_order
            ) VALUES (:revisionId, :performanceId, :artistId, :displayOrder)
            """, params(revisionId)
            .addValue("performanceId", performanceId)
            .addValue("artistId", artistId)
            .addValue("displayOrder", displayOrder));
    }

    private void insertLink(UUID revisionId, String artistId, int order, String url, String label) {
        insertLinkWithoutTranslation(revisionId, artistId, order, url);
        jdbc.update("""
            INSERT INTO artist_link_translations (
                festival_revision_id, artist_id, sort_order, locale, label
            ) VALUES (:revisionId, :artistId, :sortOrder, 'ko', :label)
            """, params(revisionId)
            .addValue("artistId", artistId)
            .addValue("sortOrder", order)
            .addValue("label", label));
    }

    private void insertLinkWithoutTranslation(UUID revisionId, String artistId, int order, String url) {
        jdbc.update("""
            INSERT INTO artist_links (festival_revision_id, artist_id, sort_order, url)
            VALUES (:revisionId, :artistId, :sortOrder, :url)
            """, params(revisionId)
            .addValue("artistId", artistId)
            .addValue("sortOrder", order)
            .addValue("url", url));
    }

    private void insertSong(UUID revisionId, String artistId, int order, String url, String title) {
        insertSongWithoutTranslation(revisionId, artistId, order, url);
        jdbc.update("""
            INSERT INTO artist_song_translations (
                festival_revision_id, artist_id, sort_order, locale, title
            ) VALUES (:revisionId, :artistId, :sortOrder, 'ko', :title)
            """, params(revisionId)
            .addValue("artistId", artistId)
            .addValue("sortOrder", order)
            .addValue("title", title));
    }

    private void insertSongWithoutTranslation(UUID revisionId, String artistId, int order, String url) {
        jdbc.update("""
            INSERT INTO artist_songs (festival_revision_id, artist_id, sort_order, url)
            VALUES (:revisionId, :artistId, :sortOrder, :url)
            """, params(revisionId)
            .addValue("artistId", artistId)
            .addValue("sortOrder", order)
            .addValue("url", url));
    }

    private MapSqlParameterSource params(UUID revisionId) {
        return new MapSqlParameterSource("revisionId", revisionId);
    }
}
