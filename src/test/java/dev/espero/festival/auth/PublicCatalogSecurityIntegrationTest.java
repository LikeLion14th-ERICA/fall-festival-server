package dev.espero.festival.auth;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies the real security chain around the db-backed public API surface. */
@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class PublicCatalogSecurityIntegrationTest {

    private static final String REQUEST_ID = "security-regression";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UUID revisionId = jdbc.queryForObject(
            "SELECT id FROM festival_revisions WHERE state = 'published'",
            Map.of(),
            UUID.class
        );
        LocalDate festivalDate = LocalDate.parse("2030-10-01");
        jdbc.update("""
            INSERT INTO festival_days (
                id, festival_revision_id, festival_date, opens_at, closes_at, created_at, updated_at
            ) VALUES (
                :id, :revisionId, :festivalDate, :opensAt, :closesAt, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            ) ON CONFLICT (festival_revision_id, festival_date) DO NOTHING
            """, Map.of(
            "id", UUID.randomUUID(),
            "revisionId", revisionId,
            "festivalDate", festivalDate,
            "opensAt", OffsetDateTime.parse("2030-10-01T09:00:00+09:00"),
            "closesAt", OffsetDateTime.parse("2030-10-01T23:00:00+09:00")
        ));
        jdbc.update("""
            INSERT INTO artists (
                festival_revision_id, id, category, image_url, image_width, image_height
            ) VALUES (:revisionId, 'security-artist', 'ARTIST', '/assets/security.png', 100, 100)
            ON CONFLICT (festival_revision_id, id) DO NOTHING
            """, Map.of("revisionId", revisionId));
        jdbc.update("""
            INSERT INTO artist_translations (
                festival_revision_id, artist_id, locale, name, image_alt
            ) VALUES (:revisionId, 'security-artist', 'ko', '보안 테스트', '보안 테스트 이미지')
            ON CONFLICT (festival_revision_id, artist_id, locale) DO NOTHING
            """, Map.of("revisionId", revisionId));
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void leavesPublicCatalogTicketAndReadinessRoutesAnonymous() throws Exception {
        mockMvc.perform(get("/api/v2/spaces"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray());

        mockMvc.perform(get("/api/v2/maps"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray());

        mockMvc.perform(get("/api/v2/ticket-guide"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", notNullValue()));

        mockMvc.perform(get("/api/v2/lineup").param("date", "2030-10-01"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray());

        mockMvc.perform(get("/api/v2/artists/security-artist"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id", is("security-artist")));

        mockMvc.perform(get("/readyz"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"status\":\"ready\"}"));
    }

    @Test
    void ignoresAnInvalidBearerHeaderOnPublicCatalogRoutes() throws Exception {
        mockMvc.perform(get("/api/v2/spaces").header(HttpHeaders.AUTHORIZATION, "Bearer definitely-invalid"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void returnsAnApiEnvelopeForUnauthenticatedAdminRequests() throws Exception {
        mockMvc.perform(get("/api/v2/admin/security-regression")
                .header("X-Request-Id", REQUEST_ID))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.error.code", is("UNAUTHORIZED")))
            .andExpect(jsonPath("$.meta.requestId", is(REQUEST_ID)))
            .andExpect(jsonPath("$.meta.revision", is(0)))
            .andExpect(jsonPath("$.meta", notNullValue()))
            .andExpect(header().string("X-Request-Id", REQUEST_ID));
    }
}
