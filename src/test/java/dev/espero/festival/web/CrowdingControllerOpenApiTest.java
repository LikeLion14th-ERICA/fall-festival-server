package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import dev.espero.festival.auth.AdminAuditService;
import dev.espero.festival.auth.AdminContext;
import dev.espero.festival.auth.AdminPrincipal;
import dev.espero.festival.domain.CrowdingRecord;
import dev.espero.festival.domain.CrowdingSchedule;
import dev.espero.festival.domain.PublishedFestivalContext;
import dev.espero.festival.idempotency.AdminIdempotencyService;
import dev.espero.festival.persistence.CrowdingStore;
import dev.espero.festival.support.ApiMetaTestFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Checks the crowding provider JSON and concurrency error against OpenAPI. */
class CrowdingControllerOpenApiTest {

    private static final Path OPENAPI_FILE = Path.of("api-v2", "openapi.json");
    private static final SchemaValidatorsConfig SCHEMA_CONFIG = SchemaValidatorsConfig.builder()
        .formatAssertionsEnabled(true)
        .build();

    private final ObjectMapper json = new ObjectMapper();
    private final CrowdingViewService views = mock(CrowdingViewService.class);
    private final CrowdingStore store = mock(CrowdingStore.class);
    private final AdminMutationPreconditions preconditions = mock(AdminMutationPreconditions.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AdminContext adminContext = mock(AdminContext.class);
    private JsonNode openApi;
    private MockMvc mvc;
    private String etag;

    @BeforeEach
    void setUp() throws Exception {
        openApi = json.readTree(Files.readString(OPENAPI_FILE));
        Clock clock = Clock.fixed(Instant.parse("2030-10-01T05:00:00Z"), ZoneOffset.UTC);
        ApiMeta meta = new ApiMeta(
            "provider-request",
            OffsetDateTime.parse("2030-10-01T14:00:00+09:00"),
            "Asia/Seoul",
            ApiMetaTestFixtures.FESTIVAL_ID.toString(),
            0,
            "ko",
            false
        );
        CrowdingSchedule schedule = new CrowdingSchedule(
            LocalDate.parse("2030-10-01"),
            OffsetDateTime.parse("2030-10-01T13:00:00+09:00"),
            OffsetDateTime.parse("2030-10-01T22:00:00+09:00")
        );
        CrowdingResponse response = new CrowdingResponse(
            schedule.operatingDate(), schedule.opensAt(), schedule.closesAt(),
            CrowdingResponse.OperatingStatus.OPEN,
            CrowdingResponse.Status.RELAXED,
            null, "green", "재학생존의 공간이 많이 남았어요.", null,
            CrowdingResponse.TimeBasis.OPENING
        );
        PublishedFestivalContext context = new PublishedFestivalContext(
            ApiMetaTestFixtures.FESTIVAL_ID,
            ApiMetaTestFixtures.REVISION_ID,
            7,
            ZoneId.of("Asia/Seoul")
        );
        CrowdingViewService.CrowdingSnapshot snapshot = new CrowdingViewService.CrowdingSnapshot(
            context, List.of(schedule), schedule.operatingDate(), schedule, Optional.empty(), response, meta, ""
        );
        ConditionalResponseSupport conditional = new ConditionalResponseSupport(new tools.jackson.databind.ObjectMapper());
        etag = conditional.strongEtag(new ConditionalApiResponse<>(response, ConditionalApiMeta.from(meta)));
        when(views.current(org.mockito.ArgumentMatchers.any())).thenReturn(snapshot);
        ApiMetaSupport metaSupport = ApiMetaTestFixtures.contentMetaSupport(clock);
        mvc = MockMvcBuilders.standaloneSetup(new CrowdingController(
            views, store, conditional, preconditions, idempotency, audit, adminContext, clock
        )).setControllerAdvice(new GlobalApiExceptionHandler(metaSupport)).build();
    }

    @Test
    void validatesPublicSuccessAndConditionalNotModifiedResponses() throws Exception {
        MvcResult result = mvc.perform(get("/api/v2/crowding"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getHeader("ETag")).isEqualTo(etag);
        assertThat(validate(responseSchema("/api/v2/crowding", "200"), response)).isEmpty();
        assertThat(response.path("data").path("operatingStatus").asText()).isEqualTo("OPEN");

        mvc.perform(get("/api/v2/crowding").header("If-None-Match", etag))
            .andExpect(status().isNotModified());
    }

    @Test
    void validatesMissingConcurrencyPreconditionAgainstThe428Contract() throws Exception {
        MvcResult result = mvc.perform(put("/api/v2/admin/crowding")
                .contentType("application/json")
                .content("{\"level\":\"CROWDED\"}"))
            .andExpect(status().isPreconditionRequired())
            .andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        assertThat(validate(responseSchema("/api/v2/admin/crowding", "428"), response)).isEmpty();
        assertThat(response.path("error").path("code").asText()).isEqualTo("PRECONDITION_REQUIRED");
    }

    private JsonNode responseSchema(String path, String status) {
        JsonNode operation = openApi.get("paths").get(path).get(path.endsWith("crowding") && "428".equals(status) ? "put" : "get");
        JsonNode schema = operation.get("responses").get(status).get("content").get("application/json").get("schema");
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
            return resolveReferences(openApi.get("components").get("schemas")
                .get(reference.substring("#/components/schemas/".length())));
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
}
