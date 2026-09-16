package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises the V13 performance catalog schema against PostgreSQL. */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerformanceCatalogSchemaIntegrationTest {

    private static final LocalDate DAY_ONE = LocalDate.parse("2026-09-29");
    private static final LocalDate DAY_TWO = LocalDate.parse("2026-09-30");
    private static final LocalDate DAY_THREE = LocalDate.parse("2026-10-01");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @Order(1)
    void migrationCreatesV13WithoutOperationalSeedData() {
        assertThat(successfulPerformanceCatalogMigrationCount()).isEqualTo(1);
        assertThat(count("artists")).isZero();
        assertThat(count("performances")).isZero();
        assertThat(count("performance_artists")).isZero();
        assertThat(count("timetable_configs")).isZero();
        assertThat(count("prohibited_items")).isZero();
    }

    @Test
    void acceptsACompleteRevisionScopedPerformanceCatalogAndAnEmptyFestivalDay() {
        Fixture fixture = insertFixture();
        String artistId = id("artist");
        String performanceId = id("performance");
        String itemId = id("item");

        insertArtist(fixture.revisionA(), artistId);
        jdbc.update("""
            INSERT INTO artist_translations (
                festival_revision_id, artist_id, locale, name, image_alt, introduction
            ) VALUES (:revisionId, :artistId, 'ko', '테스트 출연진', '테스트 출연진 사진', '소개')
            """, Map.of("revisionId", fixture.revisionA(), "artistId", artistId));
        jdbc.update("""
            INSERT INTO artist_links (festival_revision_id, artist_id, sort_order, url)
            VALUES (:revisionId, :artistId, 1, 'https://example.com/artist')
            """, Map.of("revisionId", fixture.revisionA(), "artistId", artistId));
        jdbc.update("""
            INSERT INTO artist_link_translations (
                festival_revision_id, artist_id, sort_order, locale, label
            ) VALUES (:revisionId, :artistId, 1, 'ko', '공식 링크')
            """, Map.of("revisionId", fixture.revisionA(), "artistId", artistId));
        for (int order = 1; order <= 3; order++) {
            jdbc.update("""
                INSERT INTO artist_songs (festival_revision_id, artist_id, sort_order, url)
                VALUES (:revisionId, :artistId, :sortOrder, :url)
                """, Map.of(
                "revisionId", fixture.revisionA(),
                "artistId", artistId,
                "sortOrder", order,
                "url", "https://example.com/song-" + order
            ));
            jdbc.update("""
                INSERT INTO artist_song_translations (
                    festival_revision_id, artist_id, sort_order, locale, title
                ) VALUES (:revisionId, :artistId, :sortOrder, 'ko', :title)
                """, Map.of(
                "revisionId", fixture.revisionA(),
                "artistId", artistId,
                "sortOrder", order,
                "title", "대표곡 " + order
            ));
        }

        insertPerformance(fixture.revisionA(), performanceId, DAY_ONE,
            "2026-09-29T17:00:00+09:00", "2026-09-29T18:00:00+09:00");
        insertPerformance(fixture.revisionA(), id("performance-without-artist"), DAY_ONE,
            "2026-09-29T18:00:00+09:00", "2026-09-29T19:00:00+09:00");
        jdbc.update("""
            INSERT INTO performance_translations (
                festival_revision_id, performance_id, locale, title, description
            ) VALUES (:revisionId, :performanceId, 'ko', '테스트 공연', '공연 안내')
            """, Map.of("revisionId", fixture.revisionA(), "performanceId", performanceId));
        jdbc.update("""
            INSERT INTO performance_artists (
                festival_revision_id, performance_id, artist_id, display_order
            ) VALUES (:revisionId, :performanceId, :artistId, 1)
            """, Map.of(
            "revisionId", fixture.revisionA(),
            "performanceId", performanceId,
            "artistId", artistId
        ));
        jdbc.update("""
            INSERT INTO timetable_configs (festival_revision_id, axis_start_time, axis_end_time)
            VALUES (:revisionId, '17:00', '22:00')
            """, Map.of("revisionId", fixture.revisionA()));
        jdbc.update("""
            INSERT INTO prohibited_items (festival_revision_id, id, sort_order)
            VALUES (:revisionId, :itemId, 1)
            """, Map.of("revisionId", fixture.revisionA(), "itemId", itemId));
        jdbc.update("""
            INSERT INTO prohibited_item_translations (
                festival_revision_id, item_id, locale, label
            ) VALUES (:revisionId, :itemId, 'ko', '반입 금지 물품')
            """, Map.of("revisionId", fixture.revisionA(), "itemId", itemId));
        jdbc.update("""
            INSERT INTO prohibited_messages (festival_revision_id, locale, message)
            VALUES (:revisionId, 'ko', '반입 금지 안내')
            """, Map.of("revisionId", fixture.revisionA()));

        assertThat(scopedCount("artist_songs", fixture.revisionA())).isEqualTo(3);
        assertThat(scopedCount("performance_artists", fixture.revisionA())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
            FROM festival_days d
            LEFT JOIN performances p
              ON p.festival_revision_id = d.festival_revision_id
             AND p.festival_date = d.festival_date
            WHERE d.festival_revision_id = :revisionId
              AND d.festival_date = :festivalDate
              AND p.id IS NULL
            """, Map.of("revisionId", fixture.revisionA(), "festivalDate", DAY_TWO), Long.class))
            .isEqualTo(1);
    }

    @Test
    void compositeForeignKeysEnforceRevisionIsolation() {
        Fixture fixture = insertFixture();
        String artistId = id("artist");
        String performanceId = id("performance");
        insertArtist(fixture.revisionB(), artistId);
        insertPerformance(fixture.revisionA(), performanceId, DAY_ONE,
            "2026-09-29T17:00:00+09:00", "2026-09-29T18:00:00+09:00");

        assertViolation("""
            INSERT INTO performance_artists (
                festival_revision_id, performance_id, artist_id, display_order
            ) VALUES (:revisionId, :performanceId, :artistId, 1)
            """, Map.of(
            "revisionId", fixture.revisionA(),
            "performanceId", performanceId,
            "artistId", artistId
        ));
        assertViolation("""
            INSERT INTO artist_translations (
                festival_revision_id, artist_id, locale, name, image_alt
            ) VALUES (:revisionId, :artistId, 'ko', '교차 revision', '교차 revision')
            """, Map.of("revisionId", fixture.revisionA(), "artistId", artistId));
        assertViolation(performanceInsertSql(), performanceParameters(
            fixture.revisionA(), id("missing-day"), DAY_THREE,
            "2026-10-01T17:00:00+09:00", "2026-10-01T18:00:00+09:00"
        ));
    }

    @Test
    void rejectsInvalidArtistValues() {
        Fixture fixture = insertFixture();

        assertViolation(artistInsertSql(), artistParameters(
            fixture.revisionA(), id("artist"), "HEADLINER", 100, 100
        ));
        assertViolation(artistInsertSql(), artistParameters(
            fixture.revisionA(), " ", "ARTIST", 100, 100
        ));
        assertViolation(artistInsertSql(), artistParameters(
            fixture.revisionA(), id("artist"), "ARTIST", 0, 100
        ));
        assertViolation(artistInsertSql(), artistParameters(
            fixture.revisionA(), id("artist"), "ARTIST", 100, -1
        ));
    }

    @Test
    void rejectsDuplicateOrBlankTranslations() {
        Fixture fixture = insertFixture();
        String artistId = id("artist");
        insertArtist(fixture.revisionA(), artistId);
        jdbc.update("""
            INSERT INTO artist_translations (
                festival_revision_id, artist_id, locale, name, image_alt
            ) VALUES (:revisionId, :artistId, 'ko', '이름', '대체 텍스트')
            """, Map.of("revisionId", fixture.revisionA(), "artistId", artistId));

        assertViolation("""
            INSERT INTO artist_translations (
                festival_revision_id, artist_id, locale, name, image_alt
            ) VALUES (:revisionId, :artistId, 'ko', '중복', '중복')
            """, Map.of("revisionId", fixture.revisionA(), "artistId", artistId));
        assertViolation("""
            INSERT INTO artist_translations (
                festival_revision_id, artist_id, locale, name, image_alt
            ) VALUES (:revisionId, :artistId, 'en', ' ', 'Image alt')
            """, Map.of("revisionId", fixture.revisionA(), "artistId", artistId));
    }

    @Test
    void rejectsInvalidOrDuplicateArtistLinks() {
        Fixture fixture = insertFixture();
        String artistId = id("artist");
        insertArtist(fixture.revisionA(), artistId);
        jdbc.update("""
            INSERT INTO artist_links (festival_revision_id, artist_id, sort_order, url)
            VALUES (:revisionId, :artistId, 1, 'https://example.com/one')
            """, Map.of("revisionId", fixture.revisionA(), "artistId", artistId));

        assertViolation("""
            INSERT INTO artist_links (festival_revision_id, artist_id, sort_order, url)
            VALUES (:revisionId, :artistId, 2, 'http://example.com/two')
            """, Map.of("revisionId", fixture.revisionA(), "artistId", artistId));
        assertViolation("""
            INSERT INTO artist_links (festival_revision_id, artist_id, sort_order, url)
            VALUES (:revisionId, :artistId, 1, 'https://example.com/duplicate')
            """, Map.of("revisionId", fixture.revisionA(), "artistId", artistId));
    }

    @Test
    void limitsArtistSongsToThreeHttpsEntries() {
        Fixture fixture = insertFixture();
        String artistId = id("artist");
        insertArtist(fixture.revisionA(), artistId);

        assertViolation(songInsertSql(), songParameters(fixture.revisionA(), artistId, 0,
            "https://example.com/zero"));
        assertViolation(songInsertSql(), songParameters(fixture.revisionA(), artistId, 4,
            "https://example.com/four"));
        assertViolation(songInsertSql(), songParameters(fixture.revisionA(), artistId, 1,
            "http://example.com/insecure"));
    }

    @Test
    void rejectsInvalidPerformanceTimesDatesAndMissingFestivalDays() {
        Fixture fixture = insertFixture();

        assertViolation(performanceInsertSql(), performanceParameters(
            fixture.revisionA(), id("time-order"), DAY_ONE,
            "2026-09-29T18:00:00+09:00", "2026-09-29T18:00:00+09:00"
        ));
        assertViolation(performanceInsertSql(), performanceParameters(
            fixture.revisionA(), id("kst-date"), DAY_ONE,
            "2026-09-29T23:30:00Z", "2026-09-30T00:30:00Z"
        ));
        assertViolation(performanceInsertSql(), performanceParameters(
            fixture.revisionA(), id("missing-day"), DAY_THREE,
            "2026-10-01T17:00:00+09:00", "2026-10-01T18:00:00+09:00"
        ));
    }

    @Test
    void rejectsDuplicateOrCrossRevisionPerformanceArtistRelations() {
        Fixture fixture = insertFixture();
        String artistOne = id("artist");
        String artistTwo = id("artist");
        String artistOtherRevision = id("artist");
        String performanceId = id("performance");
        insertArtist(fixture.revisionA(), artistOne);
        insertArtist(fixture.revisionA(), artistTwo);
        insertArtist(fixture.revisionB(), artistOtherRevision);
        insertPerformance(fixture.revisionA(), performanceId, DAY_ONE,
            "2026-09-29T17:00:00+09:00", "2026-09-29T18:00:00+09:00");
        insertPerformanceArtist(fixture.revisionA(), performanceId, artistOne, 1);

        assertViolation(performanceArtistInsertSql(), performanceArtistParameters(
            fixture.revisionA(), performanceId, artistOne, 2
        ));
        assertViolation(performanceArtistInsertSql(), performanceArtistParameters(
            fixture.revisionA(), performanceId, artistTwo, 1
        ));
        assertViolation(performanceArtistInsertSql(), performanceArtistParameters(
            fixture.revisionA(), performanceId, artistOtherRevision, 2
        ));
    }

    @Test
    void allowsOneSameDayTimetableConfigurationPerRevision() {
        Fixture fixture = insertFixture();
        jdbc.update("""
            INSERT INTO timetable_configs (festival_revision_id, axis_start_time, axis_end_time)
            VALUES (:revisionId, '17:00', '22:00')
            """, Map.of("revisionId", fixture.revisionA()));

        assertViolation("""
            INSERT INTO timetable_configs (festival_revision_id, axis_start_time, axis_end_time)
            VALUES (:revisionId, '18:00', '23:00')
            """, Map.of("revisionId", fixture.revisionA()));
        assertViolation("""
            INSERT INTO timetable_configs (festival_revision_id, axis_start_time, axis_end_time)
            VALUES (:revisionId, '22:00', '17:00')
            """, Map.of("revisionId", fixture.revisionB()));
    }

    @Test
    void rejectsDuplicateProhibitedOrderAndBlankLocalizedContent() {
        Fixture fixture = insertFixture();
        String itemId = id("item");
        jdbc.update("""
            INSERT INTO prohibited_items (festival_revision_id, id, sort_order)
            VALUES (:revisionId, :itemId, 1)
            """, Map.of("revisionId", fixture.revisionA(), "itemId", itemId));

        assertViolation("""
            INSERT INTO prohibited_items (festival_revision_id, id, sort_order)
            VALUES (:revisionId, :itemId, 1)
            """, Map.of("revisionId", fixture.revisionA(), "itemId", id("other-item")));
        assertViolation("""
            INSERT INTO prohibited_item_translations (
                festival_revision_id, item_id, locale, label
            ) VALUES (:revisionId, :itemId, 'ko', ' ')
            """, Map.of("revisionId", fixture.revisionA(), "itemId", itemId));
        assertViolation("""
            INSERT INTO prohibited_messages (festival_revision_id, locale, message)
            VALUES (:revisionId, 'ko', ' ')
            """, Map.of("revisionId", fixture.revisionA()));
    }

    @Test
    void evaluatesPerformanceDatesInAsiaSeoulAtUtcBoundaries() {
        Fixture fixture = insertFixture();

        insertPerformance(fixture.revisionA(), id("kst-allowed"), DAY_TWO,
            "2026-09-29T15:30:00Z", "2026-09-29T16:30:00Z");
        insertPerformance(fixture.revisionA(), id("overnight-allowed"), DAY_ONE,
            "2026-09-29T23:30:00+09:00", "2026-09-30T00:30:00+09:00");
        assertViolation(performanceInsertSql(), performanceParameters(
            fixture.revisionA(), id("utc-only"), DAY_ONE,
            "2026-09-29T23:30:00Z", "2026-09-30T00:30:00Z"
        ));
    }

    private Fixture insertFixture() {
        UUID festivalId = UUID.randomUUID();
        UUID revisionA = UUID.randomUUID();
        UUID revisionB = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO festivals (id, title, timezone, created_at, updated_at)
            VALUES (:festivalId, 'Schema test festival', 'Asia/Seoul', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("festivalId", festivalId));
        insertRevision(revisionA, festivalId, 1);
        insertRevision(revisionB, festivalId, 2);
        insertFestivalDay(revisionA, DAY_ONE);
        insertFestivalDay(revisionA, DAY_TWO);
        insertFestivalDay(revisionB, DAY_ONE);
        return new Fixture(revisionA, revisionB);
    }

    private void insertRevision(UUID revisionId, UUID festivalId, int revisionNumber) {
        jdbc.update("""
            INSERT INTO festival_revisions (
                id, festival_id, revision_number, state,
                approved_at, scheduled_at, published_at, created_at, updated_at
            ) VALUES (
                :revisionId, :festivalId, :revisionNumber, 'draft',
                NULL, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """, Map.of(
            "revisionId", revisionId,
            "festivalId", festivalId,
            "revisionNumber", revisionNumber
        ));
    }

    private void insertFestivalDay(UUID revisionId, LocalDate festivalDate) {
        jdbc.update("""
            INSERT INTO festival_days (
                id, festival_revision_id, festival_date,
                opens_at, closes_at, created_at, updated_at
            ) VALUES (
                :id, :revisionId, :festivalDate,
                :opensAt, :closesAt, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """, Map.of(
            "id", UUID.randomUUID(),
            "revisionId", revisionId,
            "festivalDate", festivalDate,
            "opensAt", OffsetDateTime.parse(festivalDate + "T09:00:00+09:00"),
            "closesAt", OffsetDateTime.parse(festivalDate + "T23:00:00+09:00")
        ));
    }

    private void insertArtist(UUID revisionId, String artistId) {
        jdbc.update(artistInsertSql(), artistParameters(revisionId, artistId, "ARTIST", 1200, 800));
    }

    private String artistInsertSql() {
        return """
            INSERT INTO artists (
                festival_revision_id, id, category, image_url, image_width, image_height
            ) VALUES (:revisionId, :artistId, :category, 'https://example.com/artist.jpg', :width, :height)
            """;
    }

    private Map<String, ?> artistParameters(
        UUID revisionId,
        String artistId,
        String category,
        int width,
        int height
    ) {
        return Map.of(
            "revisionId", revisionId,
            "artistId", artistId,
            "category", category,
            "width", width,
            "height", height
        );
    }

    private void insertPerformance(
        UUID revisionId,
        String performanceId,
        LocalDate festivalDate,
        String startsAt,
        String endsAt
    ) {
        jdbc.update(performanceInsertSql(), performanceParameters(
            revisionId, performanceId, festivalDate, startsAt, endsAt
        ));
    }

    private String performanceInsertSql() {
        return """
            INSERT INTO performances (
                festival_revision_id, id, festival_date, starts_at, ends_at
            ) VALUES (:revisionId, :performanceId, :festivalDate, :startsAt, :endsAt)
            """;
    }

    private Map<String, ?> performanceParameters(
        UUID revisionId,
        String performanceId,
        LocalDate festivalDate,
        String startsAt,
        String endsAt
    ) {
        return Map.of(
            "revisionId", revisionId,
            "performanceId", performanceId,
            "festivalDate", festivalDate,
            "startsAt", OffsetDateTime.parse(startsAt),
            "endsAt", OffsetDateTime.parse(endsAt)
        );
    }

    private void insertPerformanceArtist(
        UUID revisionId,
        String performanceId,
        String artistId,
        int displayOrder
    ) {
        jdbc.update(performanceArtistInsertSql(), performanceArtistParameters(
            revisionId, performanceId, artistId, displayOrder
        ));
    }

    private String performanceArtistInsertSql() {
        return """
            INSERT INTO performance_artists (
                festival_revision_id, performance_id, artist_id, display_order
            ) VALUES (:revisionId, :performanceId, :artistId, :displayOrder)
            """;
    }

    private Map<String, ?> performanceArtistParameters(
        UUID revisionId,
        String performanceId,
        String artistId,
        int displayOrder
    ) {
        return Map.of(
            "revisionId", revisionId,
            "performanceId", performanceId,
            "artistId", artistId,
            "displayOrder", displayOrder
        );
    }

    private String songInsertSql() {
        return """
            INSERT INTO artist_songs (festival_revision_id, artist_id, sort_order, url)
            VALUES (:revisionId, :artistId, :sortOrder, :url)
            """;
    }

    private Map<String, ?> songParameters(UUID revisionId, String artistId, int sortOrder, String url) {
        return Map.of(
            "revisionId", revisionId,
            "artistId", artistId,
            "sortOrder", sortOrder,
            "url", url
        );
    }

    private void assertViolation(String sql, Map<String, ?> parameters) {
        assertThatThrownBy(() -> jdbc.update(sql, parameters))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long count(String table) {
        Long result = jdbc.queryForObject("SELECT count(*) FROM " + table, Map.of(), Long.class);
        return result == null ? 0 : result;
    }

    private long scopedCount(String table, UUID revisionId) {
        Long result = jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE festival_revision_id = :revisionId",
            Map.of("revisionId", revisionId),
            Long.class
        );
        return result == null ? 0 : result;
    }

    private long successfulPerformanceCatalogMigrationCount() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '13' AND success",
            Map.of(),
            Long.class
        );
    }

    private String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private record Fixture(UUID revisionA, UUID revisionB) {}
}
