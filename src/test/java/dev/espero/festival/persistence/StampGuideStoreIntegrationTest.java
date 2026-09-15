package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.domain.StampGuide;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the real V1__create_stamp_guide migration against an ephemeral
 * Postgres and reads it back through StampGuideStore, closing the one gap
 * the mocked StampGuideControllerTest can't cover: that the DATE[]/TEXT[]
 * column-to-List<T> casting in StampGuideStore actually works.
 */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class StampGuideStoreIntegrationTest {

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

    @Test
    void migrationSeedsTheGuideRowAndArrayColumnsMapCorrectly() {
        Optional<StampGuide> guide = store.find();

        assertThat(guide).isPresent();
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
}
