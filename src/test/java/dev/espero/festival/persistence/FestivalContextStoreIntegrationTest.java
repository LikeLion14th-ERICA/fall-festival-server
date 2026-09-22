package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.support.PostgresTestImages;
import dev.espero.festival.domain.PublishedFestivalContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class FestivalContextStoreIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID REVISION_ID = UUID.fromString("f109dca2-8b28-4e09-8114-beebc2bd3ea2");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private FestivalContextStore store;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void readsConfiguredFestivalPublishedRevisionFromV6() {
        PublishedFestivalContext context = store.findPublishedByFestivalId(FESTIVAL_ID).orElseThrow();

        assertThat(context.festivalId()).isEqualTo(FESTIVAL_ID);
        assertThat(context.festivalRevisionId()).isEqualTo(REVISION_ID);
        assertThat(context.revisionNumber()).isEqualTo(1);
        assertThat(context.timezone().getId()).isEqualTo("Asia/Seoul");
    }

    @Test
    @Transactional
    void doesNotUseDraftAsPublishedFallback() {
        assertNotPublished("draft");
    }

    @Test
    @Transactional
    void doesNotUseScheduledAsPublishedFallback() {
        assertNotPublished("scheduled");
    }

    @Test
    @Transactional
    void doesNotUseArchivedAsPublishedFallback() {
        assertNotPublished("archived");
    }

    private void assertNotPublished(String state) {
        jdbc.update(
            "UPDATE festival_revisions SET state = :state WHERE id = :revisionId",
            Map.of("state", state, "revisionId", REVISION_ID)
        );
        assertThat(store.findPublishedByFestivalId(FESTIVAL_ID)).isEmpty();
    }
}
