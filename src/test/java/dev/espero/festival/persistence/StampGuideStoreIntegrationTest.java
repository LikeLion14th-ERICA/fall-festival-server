package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.domain.StampGuide;
import dev.espero.festival.web.ApiResponse;
import dev.espero.festival.web.StampGuideController;
import dev.espero.festival.web.StampGuideResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the real Flyway chain through V8 against ephemeral Postgres and verifies
 * the stamp guide's revision ownership, API meta, and array-column mapping.
 */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class StampGuideStoreIntegrationTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID REVISION_ID = UUID.fromString("f109dca2-8b28-4e09-8114-beebc2bd3ea2");
    private static final UUID OTHER_REVISION_ID = UUID.fromString("d2ec6f1c-0567-4422-b24f-c7b779e8919d");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private StampGuideStore store;

    @Autowired
    private StampGuideController controller;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void migrationBackfillsRevisionAndControllerReturnsMatchingMeta() {
        Optional<StampGuide> guide = store.find(REVISION_ID);
        UUID storedRevisionId = jdbc.queryForObject(
            "SELECT festival_revision_id FROM stamp_guide WHERE id = 1",
            Map.of(),
            UUID.class
        );
        String nullable = jdbc.queryForObject("""
            SELECT is_nullable
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'stamp_guide'
              AND column_name = 'festival_revision_id'
            """, Map.of(), String.class);
        ApiResponse<StampGuideResponse> response = controller.getStampGuide(new MockHttpServletRequest());

        assertThat(guide).isPresent();
        assertThat(storedRevisionId).isEqualTo(REVISION_ID);
        assertThat(nullable).isEqualTo("NO");
        assertThat(response.meta().festivalId()).isEqualTo(FESTIVAL_ID.toString());
        assertThat(response.meta().revision()).isEqualTo(1);
        assertThat(guide.get().title()).isEqualTo("스탬프투어");
        assertThat(guide.get().dates()).isEmpty();
        assertThat(guide.get().instructions()).containsExactly(
            "멋사 부스에서 QR을 스캔해 시작 스탬프 1개를 적립합니다.",
            "다른 부스를 체험한 뒤 운영자가 보여주는 QR을 스캔합니다.",
            "총 4개를 적립하면 멋사 부스에서 몬스터를 수령합니다."
        );
        assertThat(guide.get().rewardName()).isEqualTo("몬스터");
        assertThat(guide.get().rewardLocationText()).isNull();
        assertThat(guide.get().rewardHoursText()).isNull();
        assertThat(guide.get().qrValue()).isNull();
        assertThat(guide.get().updatedAt()).isNotNull();
    }

    @Test
    @Transactional
    void revisionScopedValuesTakePrecedenceOverTheLegacySingletonMirror() {
        jdbc.update("""
            UPDATE stamp_guide
            SET title = '오래된 미러 값', reward_location_text = '오래된 위치',
                reward_hours_text = '오래된 시간', qr_value = 'OLD-PUBLIC-QR'
            WHERE id = 1
            """, java.util.Map.of());
        java.util.UUID publishedRevisionId = jdbc.queryForObject(
            "SELECT id FROM festival_revisions WHERE state = 'published'",
            java.util.Map.of(), java.util.UUID.class
        );

        Optional<StampGuide> guide = store.find(publishedRevisionId);

        assertThat(guide).isPresent();
        assertThat(guide.get().title()).isEqualTo("스탬프투어");
        assertThat(guide.get().rewardLocationText()).isNull();
        assertThat(guide.get().rewardHoursText()).isNull();
        assertThat(guide.get().qrValue()).isNull();
    }

    @Test
    @Transactional
    void doesNotReturnGuideForAnotherRevision() {
        insertOtherRevision();

        assertThat(store.find(OTHER_REVISION_ID)).isEmpty();
    }

    private void insertOtherRevision() {
        jdbc.update("""
            INSERT INTO festival_revisions (
                id, festival_id, revision_number, state,
                approved_at, scheduled_at, published_at, created_at, updated_at
            ) VALUES (
                :id, :festivalId, 2, 'archived',
                NULL, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """, Map.of("id", OTHER_REVISION_ID, "festivalId", FESTIVAL_ID));
    }
}
