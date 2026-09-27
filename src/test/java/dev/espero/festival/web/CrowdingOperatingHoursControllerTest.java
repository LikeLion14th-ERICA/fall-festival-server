package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.espero.festival.auth.AdminAuditService;
import dev.espero.festival.auth.AdminContext;
import dev.espero.festival.auth.AdminPrincipal;
import dev.espero.festival.context.FestivalContextService;
import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.CrowdingOperatingHours;
import dev.espero.festival.idempotency.AdminIdempotencyService;
import dev.espero.festival.idempotency.IdempotencyExecution;
import dev.espero.festival.idempotency.IdempotencyRequest;
import dev.espero.festival.idempotency.IdempotencyResponse;
import dev.espero.festival.persistence.CrowdingStore;
import dev.espero.festival.support.ApiMetaTestFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Provider validation independent of Docker; real transactions are covered by the flow/E2E tests. */
class CrowdingOperatingHoursControllerTest {
    private static final String ROUTE = "/api/v2/admin/crowding/operating-hours";
    private static final String ITEM = ROUTE + "/2026-09-29";
    private static final LocalDate DATE = LocalDate.parse("2026-09-29");
    private final CrowdingStore store = mock(CrowdingStore.class);
    private final FestivalContextService contexts = mock(FestivalContextService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-29T03:00:00Z"), ZoneOffset.UTC);
        AdminContext admins = mock(AdminContext.class);
        when(admins.requireCurrent()).thenReturn(new AdminPrincipal(UUID.randomUUID(), "test-admin", "ADMIN"));
        when(contexts.currentPublished()).thenReturn(ApiMetaTestFixtures.PUBLISHED_CONTEXT);
        when(store.findOperatingHours(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of(row(null)));
        when(idempotency.execute(any(), any())).thenAnswer(invocation -> {
            Supplier<IdempotencyResponse> callback = invocation.getArgument(1);
            return new IdempotencyExecution(callback.get(), false);
        });
        var metadata = ApiMetaTestFixtures.contentMetaSupport(clock);
        mvc = MockMvcBuilders.standaloneSetup(new CrowdingOperatingHoursController(store, contexts,
            new FestivalProperties(ApiMetaTestFixtures.FESTIVAL_ID.toString()),
            new ConditionalResponseSupport(new tools.jackson.databind.ObjectMapper()),
            new AdminMutationPreconditions(), idempotency, audit, admins, metadata, clock))
            .setControllerAdvice(new GlobalApiExceptionHandler(metadata)).build();
    }

    @Test
    void validatesDateAndQueryAndServesConditionalRawFallback() throws Exception {
        mvc.perform(get(ROUTE + "/2026-02-30")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_DATE"));
        mvc.perform(get(ROUTE + "/2026-9-29")).andExpect(status().isBadRequest());
        mvc.perform(get(ROUTE + "/2026-09-30")).andExpect(status().isNotFound());
        mvc.perform(get(ROUTE).param("locale", "en")).andExpect(status().isBadRequest());
        when(store.findOperatingHours(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of(
            new CrowdingOperatingHours(DATE, OffsetDateTime.parse("2026-09-29T11:00:31+09:00"), null, null)));
        String tag = mvc.perform(get(ITEM)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.opensAt").value("2026-09-29T11:00:31+09:00"))
            .andExpect(jsonPath("$.data.closesAt").isEmpty()).andExpect(jsonPath("$.meta.revision").value(0))
            .andReturn().getResponse().getHeader("ETag");
        mvc.perform(get(ITEM).header("If-None-Match", tag)).andExpect(status().isNotModified())
            .andExpect(header().string("Cache-Control", "no-store"));
        when(store.findOperatingHours(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of());
        mvc.perform(get(ROUTE)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void rejectsInvalidTimesAndHeadersBeforeMutation() throws Exception {
        String tag = etag();
        mvc.perform(put(ITEM).contentType("application/json").content(body("11:00:00", "23:00:00")))
            .andExpect(status().isPreconditionRequired());
        mvc.perform(put(ITEM).header("If-Match", tag).contentType("application/json")
            .content(body("11:00:00", "23:00:00"))).andExpect(status().isPreconditionRequired());
        for (String invalid : List.of(body("11:00:01", "23:00:00"), body("11:00:00.000", "23:00:00"),
            body("11:00:00", "10:00:00"), "{}", "{\"opensAt\":null,\"closesAt\":null}",
            "{\"opensAt\":12,\"closesAt\":true}")) {
            mvc.perform(put(ITEM).header("If-Match", tag).header("Idempotency-Key", "unit-key")
                .contentType("application/json").content(invalid)).andExpect(status().isUnprocessableEntity());
        }
        verifyNoInteractions(idempotency);
    }

    @Test
    void normalizesEquivalentOffsetsAndChecksCurrentEtagAfterLock() throws Exception {
        String tag = etag();
        when(store.saveOperatingHours(any(), any(), any(), any(), any())).thenReturn(true);
        mvc.perform(put(ITEM).header("If-Match", tag).header("Idempotency-Key", "unit-key")
            .contentType("application/json")
            .content("{\"opensAt\":\"2026-09-29T02:00:00Z\",\"closesAt\":\"2026-09-29T15:00:00Z\"}"))
            .andExpect(status().isNoContent());
        var order = inOrder(store, contexts);
        order.verify(store).lockFestival(ApiMetaTestFixtures.FESTIVAL_ID);
        order.verify(contexts).currentPublished();
        order.verify(store).findOperatingHours(ApiMetaTestFixtures.REVISION_ID);
        order.verify(store).saveOperatingHours(eq(ApiMetaTestFixtures.FESTIVAL_ID), eq(DATE),
            eq(OffsetDateTime.parse("2026-09-29T11:00:00+09:00")),
            eq(OffsetDateTime.parse("2026-09-30T00:00:00+09:00")), any());
        verify(audit).record(any(), any(), eq(ApiMetaTestFixtures.FESTIVAL_ID + "/" + DATE), any());
        when(store.findOperatingHours(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of(row(Instant.now())));
        mvc.perform(put(ITEM).header("If-Match", tag).header("Idempotency-Key", "unit-key-2")
            .contentType("application/json").content(body("11:00:00", "23:00:00")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EDIT_CONFLICT"));
    }

    @Test
    void completedReplayDoesNotCheckRemovedDateAndIdenticalSaveDoesNotAudit() throws Exception {
        String tag = etag();
        mvc.perform(put(ITEM).header("If-Match", tag).header("Idempotency-Key", "no-op")
            .contentType("application/json").content(body("11:00:00", "23:00:00")))
            .andExpect(status().isNoContent());
        verifyNoInteractions(audit);
        clearInvocations(store, contexts);
        doReturn(new IdempotencyExecution(IdempotencyResponse.noContent(), true))
            .when(idempotency).execute(any(IdempotencyRequest.class), any());
        mvc.perform(put(ITEM).header("If-Match", tag).header("Idempotency-Key", "replay")
            .contentType("application/json").content(body("11:00:00", "23:00:00")))
            .andExpect(status().isNoContent());
        verifyNoInteractions(store, contexts);
    }


    @Test
    void validatesProviderResponsesAgainstOpenApi() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var spec = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("api-v2/openapi.json")));
        for (String path : List.of(ROUTE, ITEM)) {
            var result = mvc.perform(get(path)).andExpect(status().isOk()).andReturn();
            String contractPath = path.equals(ITEM) ? ROUTE + "/{operatingDay}" : ROUTE;
            var schema = spec.path("paths").path(contractPath).path("get").path("responses").path("200")
                .path("content").path("application/json").path("schema");
            assertThat(schema.isMissingNode()).isFalse();
            var validator = com.networknt.schema.JsonSchemaFactory
                .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                .getSchema(resolve(schema, spec, mapper), com.networknt.schema.SchemaValidatorsConfig.builder()
                    .formatAssertionsEnabled(true).build());
            assertThat(validator.validate(mapper.readTree(result.getResponse().getContentAsString()))).isEmpty();
        }
    }

    private com.fasterxml.jackson.databind.JsonNode resolve(com.fasterxml.jackson.databind.JsonNode node,
        com.fasterxml.jackson.databind.JsonNode spec, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (node.isObject() && node.has("$ref")) return resolve(spec.at(node.path("$ref").asText().substring(1)), spec, mapper);
        if (node.isObject()) {
            var copy = mapper.createObjectNode();
            node.fields().forEachRemaining(field -> copy.set(field.getKey(), resolve(field.getValue(), spec, mapper)));
            return copy;
        }
        if (node.isArray()) {
            var copy = mapper.createArrayNode();
            node.forEach(value -> copy.add(resolve(value, spec, mapper)));
            return copy;
        }
        return node.deepCopy();
    }

    private String etag() throws Exception {
        return mvc.perform(get(ITEM)).andReturn().getResponse().getHeader("ETag");
    }
    private CrowdingOperatingHours row(Instant updatedAt) {
        return new CrowdingOperatingHours(DATE, OffsetDateTime.parse("2026-09-29T11:00:00+09:00"),
            OffsetDateTime.parse("2026-09-29T23:00:00+09:00"), updatedAt);
    }
    private String body(String openTime, String closeTime) {
        return "{\"opensAt\":\"2026-09-29T" + openTime + "+09:00\",\"closesAt\":\"2026-09-29T"
            + closeTime + "+09:00\"}";
    }
}
