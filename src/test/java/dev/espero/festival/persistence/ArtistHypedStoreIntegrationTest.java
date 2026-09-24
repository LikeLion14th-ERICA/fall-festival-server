package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.support.ApiMetaTestFixtures;
import dev.espero.festival.support.PostgresTestImages;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class ArtistHypedStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ArtistHypedStore store;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @Timeout(60)
    void concurrentClicksStayAtomicAcrossCatalogRevisionsAndNewFestivalStartsAtZero() throws Exception {
        UUID festivalId = ApiMetaTestFixtures.FESTIVAL_ID;
        UUID firstRevision = ApiMetaTestFixtures.REVISION_ID;
        insertArtist(firstRevision, "hyped-test-artist", "ARTIST");
        insertArtist(firstRevision, "hyped-test-contest", "CONTEST");
        assertThat(store.isCurrentArtist(firstRevision, "hyped-test-artist")).isTrue();
        assertThat(store.isCurrentArtist(firstRevision, "hyped-test-contest")).isFalse();
        assertThat(count(festivalId, firstRevision, "hyped-test-artist")).isZero();

        var executor = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            for (int thread = 0; thread < 8; thread++) {
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int click = 0; click < 20; click++) {
                        store.increment(festivalId, "hyped-test-artist", Instant.now());
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> task : tasks) task.get(45, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(count(festivalId, firstRevision, "hyped-test-artist")).isEqualTo(160);

        UUID secondRevision = UUID.randomUUID();
        insertRevision(secondRevision, festivalId, 2);
        insertArtist(secondRevision, "hyped-test-artist", "ARTIST");
        assertThat(count(festivalId, secondRevision, "hyped-test-artist")).isEqualTo(160);
        assertThat(store.increment(festivalId, "hyped-test-artist", Instant.now())).isEqualTo(161);

        UUID newFestival = UUID.randomUUID();
        insertFestival(newFestival);
        UUID newRevision = UUID.randomUUID();
        insertRevision(newRevision, newFestival, 1);
        insertArtist(newRevision, "hyped-test-artist", "ARTIST");
        assertThat(count(newFestival, newRevision, "hyped-test-artist")).isZero();
        assertThat(store.increment(newFestival, "hyped-test-artist", Instant.now())).isEqualTo(1);
        assertThat(count(festivalId, secondRevision, "hyped-test-artist")).isEqualTo(161);
    }

    private long count(UUID festivalId, UUID revisionId, String artistId) {
        return store.currentArtists(festivalId, revisionId).stream()
            .filter(item -> item.artistId().equals(artistId))
            .findFirst().orElseThrow().hypedCount();
    }

    private void insertArtist(UUID revisionId, String artistId, String category) {
        jdbc.update("""
            INSERT INTO artists (festival_revision_id, id, category, image_url, image_width, image_height)
            VALUES (:revisionId, :artistId, :category, '/assets/artist.png', 800, 600)
            """, new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("artistId", artistId)
            .addValue("category", category));
    }

    private void insertFestival(UUID festivalId) {
        jdbc.update("""
            INSERT INTO festivals (id, title, timezone, created_at, updated_at)
            VALUES (:festivalId, 'Hyped test festival', 'Asia/Seoul', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, Map.of("festivalId", festivalId));
    }

    private void insertRevision(UUID revisionId, UUID festivalId, long number) {
        jdbc.update("""
            INSERT INTO festival_revisions (
                id, festival_id, revision_number, state, created_at, updated_at
            ) VALUES (:revisionId, :festivalId, :number, 'draft', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, new MapSqlParameterSource()
            .addValue("revisionId", revisionId)
            .addValue("festivalId", festivalId)
            .addValue("number", number));
    }
}
