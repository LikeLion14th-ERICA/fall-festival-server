package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.PerformanceCatalogReadStore;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.Artist;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.Image;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.LineupItem;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.Link;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.Performance;
import dev.espero.festival.support.ApiMetaTestFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Validates real lineup and artist HTTP JSON against api-v2/openapi.json. */
class PerformanceControllerOpenApiTest {

    private static final Path OPENAPI_FILE = Path.of("api-v2", "openapi.json");
    private static final UUID REVISION_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final LocalDate FESTIVAL_DATE = LocalDate.parse("2030-10-01");
    private static final SchemaValidatorsConfig SCHEMA_CONFIG = SchemaValidatorsConfig.builder()
        .formatAssertionsEnabled(true)
        .build();

    private final ObjectMapper json = new ObjectMapper();
    private final CatalogSnapshotProvider snapshots = mock(CatalogSnapshotProvider.class);
    private final PerformanceCatalogReadStore store = mock(PerformanceCatalogReadStore.class);
    private JsonNode openApi;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        openApi = json.readTree(Files.readString(OPENAPI_FILE));
        Clock clock = Clock.fixed(Instant.parse("2030-10-01T09:00:00.123456789Z"), ZoneOffset.UTC);
        ApiMetaSupport metaSupport = ApiMetaTestFixtures.contentMetaSupport(clock);
        mvc = MockMvcBuilders.standaloneSetup(
            new PerformanceController(snapshots, store, metaSupport, clock)
        ).setControllerAdvice(new GlobalApiExceptionHandler(metaSupport)).build();
        when(snapshots.required()).thenReturn(snapshot());
        when(store.festivalDates(REVISION_ID)).thenReturn(List.of(FESTIVAL_DATE));
    }

    @Test
    void validatesLineupSuccessAndEmptyResponses() throws Exception {
        when(store.lineup(REVISION_ID, FESTIVAL_DATE, "ARTIST", "ko")).thenReturn(List.of(
            new LineupItem("artist-a", "performance-a", "아티스트 A", image())
        ));
        assertMatches("/api/v2/lineup", "200", get("/api/v2/lineup"), 200);

        when(store.lineup(REVISION_ID, FESTIVAL_DATE, "CONTEST", "ko")).thenReturn(List.of());
        assertMatches(
            "/api/v2/lineup",
            "200",
            get("/api/v2/lineup").param("category", "CONTEST"),
            200
        );
    }

    @Test
    void validatesArtistSuccessAndMissingOptionalResponses() throws Exception {
        when(store.findArtist(REVISION_ID, "artist-a", "ko")).thenReturn(Optional.of(new Artist(
            "artist-a",
            "ARTIST",
            "아티스트 A",
            image(),
            "소개",
            List.of(new Link("공식 채널", "https://example.com/artist")),
            List.of(new Link("대표곡", "https://example.com/song")),
            List.of(new Performance(
                "performance-a",
                FESTIVAL_DATE,
                OffsetDateTime.parse("2030-10-01T09:20:00.123456Z"),
                OffsetDateTime.parse("2030-10-01T09:50:00Z")
            ))
        )));
        assertMatches("/api/v2/artists/{artistId}", "200", get("/api/v2/artists/artist-a"), 200);

        when(store.findArtist(REVISION_ID, "artist-empty", "ko")).thenReturn(Optional.of(new Artist(
            "artist-empty", "CONTEST", "빈 팀", image(), null, List.of(), List.of(), List.of()
        )));
        assertMatches("/api/v2/artists/{artistId}", "200", get("/api/v2/artists/artist-empty"), 200);
    }

    @Test
    void validatesDocumented400And404ErrorEnvelopes() throws Exception {
        assertMatches(
            "/api/v2/lineup",
            "400",
            get("/api/v2/lineup").param("category", "INVALID"),
            400
        );
        when(store.findArtist(REVISION_ID, "missing-artist", "ko")).thenReturn(Optional.empty());
        assertMatches(
            "/api/v2/artists/{artistId}",
            "404",
            get("/api/v2/artists/missing-artist"),
            404
        );
    }

    @Test
    void validatesCatalogUnavailableAgainstBoth503Schemas() throws Exception {
        when(snapshots.required()).thenThrow(new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "CATALOG_NOT_READY",
            "카탈로그를 아직 사용할 수 없습니다.",
            true
        ));

        assertMatches("/api/v2/lineup", "503", get("/api/v2/lineup"), 503);
        assertMatches("/api/v2/artists/{artistId}", "503", get("/api/v2/artists/artist-a"), 503);
    }

    private void assertMatches(
        String openApiPath,
        String responseStatus,
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
        int expectedStatus
    ) throws Exception {
        MvcResult result = mvc.perform(request.header("X-Request-Id", "performance-openapi-test"))
            .andExpect(status().is(expectedStatus))
            .andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        Set<ValidationMessage> errors = validate(responseSchema(openApiPath, responseStatus), response);

        assertThat(errors)
            .as("Spring response for %s (%s) must satisfy api-v2/openapi.json", openApiPath, responseStatus)
            .isEmpty();
    }

    private JsonNode responseSchema(String path, String status) {
        JsonNode operation = openApi.get("paths").get(path).get("get");
        assertThat(operation).as("OpenAPI GET operation for %s", path).isNotNull();
        JsonNode schema = operation.get("responses").get(status)
            .get("content").get("application/json").get("schema");
        assertThat(schema).as("OpenAPI %s JSON schema for %s", status, path).isNotNull();
        return resolveReferences(schema);
    }

    private Set<ValidationMessage> validate(JsonNode schema, JsonNode response) {
        JsonSchema validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(schema, SCHEMA_CONFIG);
        return validator.validate(response);
    }

    private JsonNode resolveReferences(JsonNode schema) {
        if (schema.isObject() && schema.has("$ref")) {
            String reference = schema.get("$ref").asText();
            assertThat(reference).startsWith("#/components/schemas/");
            String componentName = reference.substring("#/components/schemas/".length());
            return resolveReferences(openApi.get("components").get("schemas").get(componentName));
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

    private Image image() {
        return new Image("/assets/artist.png", "아티스트 A", 800, 600);
    }

    private CatalogSnapshot snapshot() {
        return new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext("festival-catalog", REVISION_ID, 7),
            List.of(), List.of(), List.of(), Map.of(), null
        );
    }
}
