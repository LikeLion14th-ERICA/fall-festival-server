package dev.espero.festival.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.auth.AdminTokenService;
import dev.espero.festival.domain.AdminAccount;
import dev.espero.festival.domain.AdminRefreshSession;
import java.time.Instant;
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
class AdminAuthStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private AdminAuthStore store;

    @Autowired
    private AdminTokenService tokenService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @Transactional
    void v7CreatesEmptyAuthTablesAndStorePersistsOnlyRefreshHash() {
        assertThat(store.countAccounts()).isZero();
        assertThat(refreshSessionCount()).isZero();

        Instant now = Instant.parse("2030-10-01T03:00:00Z");
        UUID adminId = UUID.randomUUID();
        AdminAccount account = new AdminAccount(
            adminId, "integration-admin", "$2a$10$not-a-real-secret-value-for-storage-only", "ADMIN", true,
            now, now, null
        );
        store.insertAccount(account);

        String rawRefreshToken = tokenService.newRefreshToken();
        String tokenHash = tokenService.hashRefreshToken(rawRefreshToken);
        AdminRefreshSession session = new AdminRefreshSession(
            UUID.randomUUID(), adminId, tokenHash, now.plusSeconds(60), null, now
        );
        store.insertRefreshSession(session);

        assertThat(store.findAccountByUsername("integration-admin")).contains(account);
        assertThat(store.findRefreshSessionForUpdate(tokenHash)).contains(session);
        assertThat(rawStoredToken()).isEqualTo(tokenHash).isNotEqualTo(rawRefreshToken);
        assertThat(store.revokeRefreshSession(session.id(), now.plusSeconds(1))).isTrue();
        assertThat(store.findRefreshSessionForUpdate(tokenHash).orElseThrow().revokedAt())
            .isEqualTo(now.plusSeconds(1));
    }

    private long refreshSessionCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM admin_refresh_sessions", Map.of(), Long.class);
        return count == null ? 0 : count;
    }

    private String rawStoredToken() {
        return jdbc.queryForObject("SELECT token_hash FROM admin_refresh_sessions", Map.of(), String.class);
    }
}
