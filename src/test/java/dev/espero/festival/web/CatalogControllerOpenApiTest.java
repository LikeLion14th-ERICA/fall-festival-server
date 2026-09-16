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
import dev.espero.festival.domain.CatalogSnapshot.CatalogMap;
import dev.espero.festival.domain.CatalogSnapshot.Image;
import dev.espero.festival.domain.CatalogSnapshot.Link;
import dev.espero.festival.domain.CatalogSnapshot.Money;
import dev.espero.festival.domain.CatalogSnapshot.Pin;
import dev.espero.festival.domain.CatalogSnapshot.PinKey;
import dev.espero.festival.domain.CatalogSnapshot.PinTarget;
import dev.espero.festival.domain.CatalogSnapshot.Place;
import dev.espero.festival.domain.CatalogSnapshot.Space;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Validates actual Spring HTTP JSON against the repository's OpenAPI response schemas. */
class CatalogControllerOpenApiTest {

    private static final Path OPENAPI_FILE = Path.of("api-v2", "openapi.json");
    private static final SchemaValidatorsConfig SCHEMA_CONFIG = SchemaValidatorsConfig.builder()
        .formatAssertionsEnabled(true)
        .build();

    private final ObjectMapper json = new ObjectMapper();
    private final CatalogSnapshotProvider snapshots = mock(CatalogSnapshotProvider.class);
    private JsonNode openApi;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        openApi = json.readTree(Files.readString(OPENAPI_FILE));
        Clock clock = Clock.fixed(Instant.parse("2030-10-01T09:00:00.123456789Z"), ZoneOffset.UTC);
        ApiMetaSupport metaSupport = new ApiMetaSupport(clock);
        mvc = MockMvcBuilders.standaloneSetup(
            new CatalogController(snapshots, metaSupport),
            new TicketGuideController(snapshots, metaSupport, clock)
        ).setControllerAdvice(new GlobalApiExceptionHandler(metaSupport)).build();
    }

    @Test
    void validatesEveryCatalogSuccessEnvelopeAndPayloadAgainstOpenApi() throws Exception {
        when(snapshots.required()).thenReturn(snapshot());

        assertMatchesSchema("/api/v2/spaces", get("/api/v2/spaces"));
        assertMatchesSchema("/api/v2/spaces/{spaceId}", get("/api/v2/spaces/space-booth"));
        assertMatchesSchema("/api/v2/maps", get("/api/v2/maps"));
        assertMatchesSchema("/api/v2/maps/{mapId}", get("/api/v2/maps/map-overview"));
        assertMatchesSchema(
            "/api/v2/maps/{mapId}/pins",
            get("/api/v2/maps/map-overview/pins").param("mapVersion", "overview-v1")
        );
        assertMatchesSchema("/api/v2/places/{placeId}", get("/api/v2/places/place-booth"));
        assertMatchesSchema("/api/v2/ticket-guide", get("/api/v2/ticket-guide"));
    }

    @Test
    void enablesTimestampAndUriFormatAssertionsFromTheOpenApiContract() throws Exception {
        when(snapshots.required()).thenReturn(snapshot());
        MvcResult result = mvc.perform(get("/api/v2/spaces").header("X-Request-Id", "format-test"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode schema = responseSchema("/api/v2/spaces");
        JsonNode response = json.readTree(result.getResponse().getContentAsString());

        ObjectNode invalidTimestamp = response.deepCopy();
        ((ObjectNode) invalidTimestamp.path("meta")).put("serverTime", "2030-10-01T18:00:00.123456+09:00");
        assertThat(validate(schema, invalidTimestamp)).isNotEmpty();

        ObjectNode invalidImage = response.deepCopy();
        ((ObjectNode) invalidImage.path("data").path("items").get(0).path("image"))
            .put("url", "/assets/지도.png");
        assertThat(validate(schema, invalidImage)).isNotEmpty();

        ObjectNode invalidLink = response.deepCopy();
        ((ObjectNode) invalidLink.path("data").path("items").get(0).path("contact"))
            .put("url", "https://example.org/문의");
        assertThat(validate(schema, invalidLink)).isNotEmpty();
    }

    @Test
    void validatesDocumentedCatalogErrorEnvelopesAgainstOpenApi() throws Exception {
        when(snapshots.required()).thenReturn(snapshot());

        assertMatchesErrorSchema(
            "/api/v2/spaces",
            "400",
            "INVALID_QUERY",
            get("/api/v2/spaces").param("category", "BOOTH", "PUB")
        );
        assertMatchesErrorSchema(
            "/api/v2/spaces/{spaceId}",
            "404",
            "NOT_FOUND",
            get("/api/v2/spaces/unknown-space")
        );
        assertMatchesErrorSchema(
            "/api/v2/maps/{mapId}/pins",
            "409",
            "MAP_VERSION_MISMATCH",
            get("/api/v2/maps/map-area/pins").param("mapVersion", "old-version")
        );

        when(snapshots.required()).thenThrow(new ApiException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "CATALOG_NOT_READY",
            "카탈로그를 아직 사용할 수 없습니다.",
            true
        ));
        assertMatchesErrorSchema(
            "/api/v2/maps",
            "503",
            "CATALOG_NOT_READY",
            get("/api/v2/maps")
        );
    }

    private void assertMatchesSchema(String openApiPath, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
        throws Exception {
        MvcResult result = mvc.perform(request.header("X-Request-Id", "openapi-provider-test"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        JsonNode schema = responseSchema(openApiPath);
        Set<ValidationMessage> errors = validate(schema, response);

        assertThat(errors)
            .as("Spring response for %s must satisfy api-v2/openapi.json", openApiPath)
            .isEmpty();
    }

    private void assertMatchesErrorSchema(
        String openApiPath,
        String status,
        String expectedCode,
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
    ) throws Exception {
        MvcResult result = mvc.perform(request.header("X-Request-Id", "openapi-provider-error-test"))
            .andExpect(status().is(Integer.parseInt(status)))
            .andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        Set<ValidationMessage> errors = validate(errorSchema(openApiPath, status), response);

        assertThat(errors)
            .as("Spring error response for %s (%s) must satisfy api-v2/openapi.json", openApiPath, status)
            .isEmpty();
        assertThat(response.path("error").path("code").asText()).isEqualTo(expectedCode);
        assertThat(response.path("error").path("details").isArray()).isTrue();
        assertThat(response.path("meta").path("revision").asInt()).isGreaterThanOrEqualTo(1);
    }

    private JsonNode responseSchema(String path) {
        JsonNode operation = openApi.get("paths").get(path).get("get");
        assertThat(operation).as("OpenAPI GET operation for %s", path).isNotNull();
        JsonNode schema = operation.get("responses").get("200").get("content").get("application/json").get("schema");
        assertThat(schema).as("OpenAPI 200 JSON schema for %s", path).isNotNull();
        return resolveReferences(schema);
    }

    private JsonNode errorSchema(String path, String status) {
        JsonNode operation = openApi.get("paths").get(path).get("get");
        assertThat(operation).as("OpenAPI GET operation for %s", path).isNotNull();
        JsonNode schema = operation.get("responses").get(status).get("content").get("application/json").get("schema");
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

    private CatalogSnapshot snapshot() {
        Space space = new Space(
            "space-booth",
            "BOOTH",
            "테스트 부스",
            new Image("/assets/booth.png", "부스", 100, 80),
            "학생회관 앞",
            "운영자",
            "12:00~18:00",
            "설명",
            "체험 안내",
            new Link("문의", "https://example.org/contact"),
            List.of("행사 안내"),
            List.of(new Money("메뉴", 5_000)),
            new CatalogSnapshot.MapTarget("map-area", "place-booth", "pin-booth", "map-v1")
        );
        CatalogMap overview = new CatalogMap(
            "map-overview", "전체 지도", "OVERVIEW", "overview-v1", new Image("/assets/map.png", "지도", 1000, 600)
        );
        CatalogMap area = new CatalogMap(
            "map-area", "구역 지도", "AREA", "map-v1", new Image("/assets/area.png", "구역 지도", 1000, 600)
        );
        Place place = new Place(
            "place-booth", "SPACE", "테스트 부스", "학생회관 앞", "12:00~18:00", "설명", "체험", "space-booth"
        );
        Pin placePin = new Pin(
            "pin-booth", "booth", "EXPERIENCE", "체험", "테스트 부스", new java.math.BigDecimal("0.125"), new java.math.BigDecimal("0.875"),
            new PinTarget("PLACE", "place-booth")
        );
        Pin areaPin = new Pin(
            "pin-area", "area", null, null, "구역 이동", new java.math.BigDecimal("0.75"), new java.math.BigDecimal("0.25"),
            new PinTarget("AREA", "map-area")
        );
        return new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext(
                "festival-catalog", UUID.fromString("00000000-0000-0000-0000-000000000003"), 3
            ),
            List.of(space),
            List.of(overview, area),
            List.of(place),
            Map.of(
                new PinKey("map-overview", "overview-v1"), List.of(placePin, areaPin),
                new PinKey("map-area", "map-v1"), List.of(placePin)
            ),
            new CatalogSnapshot.MapTarget("map-area", "place-booth", "pin-booth", "map-v1")
        );
    }
}
