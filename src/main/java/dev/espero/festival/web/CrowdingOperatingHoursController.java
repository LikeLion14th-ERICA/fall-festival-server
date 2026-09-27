package dev.espero.festival.web;

import dev.espero.festival.auth.AdminAuditService;
import dev.espero.festival.auth.AdminContext;
import dev.espero.festival.context.FestivalContextService;
import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.AdminAuditAction;
import dev.espero.festival.domain.AdminAuditResourceType;
import dev.espero.festival.domain.CrowdingOperatingHours;
import dev.espero.festival.idempotency.AdminIdempotencyService;
import dev.espero.festival.idempotency.CanonicalPayload;
import dev.espero.festival.idempotency.IdempotencyKeyPolicy;
import dev.espero.festival.idempotency.IdempotencyRequest;
import dev.espero.festival.idempotency.IdempotencyResponse;
import dev.espero.festival.persistence.CrowdingStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-date administrator settings, independent of the published catalog revision. */
@RestController
@Profile("db")
@RequestMapping("/api/v2/admin/crowding/operating-hours")
public class CrowdingOperatingHoursController {
    private static final String ITEM_ROUTE = "/api/v2/admin/crowding/operating-hours/{operatingDay}";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final CrowdingStore store;
    private final FestivalContextService contexts;
    private final FestivalProperties properties;
    private final ConditionalResponseSupport conditional;
    private final AdminMutationPreconditions preconditions;
    private final AdminIdempotencyService idempotency;
    private final AdminAuditService audit;
    private final AdminContext admins;
    private final ApiMetaSupport metadata;
    private final Clock clock;

    public CrowdingOperatingHoursController(CrowdingStore store, FestivalContextService contexts,
        FestivalProperties properties, ConditionalResponseSupport conditional,
        AdminMutationPreconditions preconditions, AdminIdempotencyService idempotency,
        AdminAuditService audit, AdminContext admins, ApiMetaSupport metadata, Clock clock) {
        this.store = store;
        this.contexts = contexts;
        this.properties = properties;
        this.conditional = conditional;
        this.preconditions = preconditions;
        this.idempotency = idempotency;
        this.audit = audit;
        this.admins = admins;
        this.metadata = metadata;
        this.clock = clock;
    }

    @GetMapping
    public ResponseEntity<ConditionalApiResponse<HoursList>> list(HttpServletRequest request) {
        validateQuery(request);
        List<Hours> items = publishedHours().stream().map(this::response).toList();
        return conditional.respond(request, new HoursList(items), meta(request), "no-store");
    }

    @GetMapping("/{operatingDay}")
    public ResponseEntity<ConditionalApiResponse<Hours>> detail(
        @PathVariable String operatingDay, HttpServletRequest request) {
        validateQuery(request);
        LocalDate date = parseDate(operatingDay);
        Hours hours = response(publishedHours().stream().filter(row -> row.operatingDate().equals(date))
            .findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "해당 축제 운영일을 찾을 수 없습니다.", false)));
        return conditional.respond(request, hours, meta(request), "no-store");
    }

    @PutMapping("/{operatingDay}")
    @AdminMutationPolicy(AdminMutationConcurrency.IF_MATCH_REQUIRED)
    public ResponseEntity<Void> save(@PathVariable String operatingDay, HttpServletRequest request,
        @RequestBody Map<String, Object> input) {
        validateQuery(request);
        LocalDate date = parseDate(operatingDay);
        String ifMatch = preconditions.requireValidIfMatch(request);
        String key = requireKey(request);
        Times times = validateInput(date, input);
        String resourceId = properties.configuredFestivalId() + "/" + date;
        IdempotencyRequest operation = new IdempotencyRequest(admins.requireCurrent().adminId(), "PUT",
            ITEM_ROUTE, resourceId, key, CanonicalPayload.from(Map.of(
                "opensAt", times.opensAt().toInstant().toString(),
                "closesAt", times.closesAt().toInstant().toString())));
        // Replay is checked before membership or current ETag. Removed dates
        // therefore do not invalidate an already completed save.
        idempotency.execute(operation, () -> {
            store.lockFestival(properties.configuredFestivalId());
            CrowdingOperatingHours current = publishedHours().stream()
                .filter(row -> row.operatingDate().equals(date)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NOT_FESTIVAL_DAY",
                    "게시된 축제 운영일에만 시간을 저장할 수 있습니다.", false));
            String etag = conditional.strongEtag(new ConditionalApiResponse<>(response(current),
                ConditionalApiMeta.from(meta(request))));
            preconditions.requireCurrentRepresentation(ifMatch, AdminMutationConcurrency.IF_MATCH_REQUIRED, etag);
            if (store.saveOperatingHours(properties.configuredFestivalId(), date,
                times.opensAt(), times.closesAt(), clock.instant())) {
                audit.record(AdminAuditAction.CROWDING_OPERATING_HOURS_UPDATED,
                    AdminAuditResourceType.CROWDING_OPERATING_HOURS, resourceId,
                    ApiMetaSupport.resolveRequestId(request));
            }
            return IdempotencyResponse.noContent();
        });
        return ResponseEntity.noContent().build();
    }

    private List<CrowdingOperatingHours> publishedHours() {
        return store.findOperatingHours(contexts.currentPublished().festivalRevisionId());
    }

    private ApiMeta meta(HttpServletRequest request) {
        return metadata.unscopedMeta(request, PublicContentLocale.KOREAN);
    }

    private Hours response(CrowdingOperatingHours row) {
        return new Hours(row.operatingDate(), atKst(row.opensAt()), atKst(row.closesAt()),
            row.updatedAt() == null ? null : row.updatedAt().atZone(KST).toOffsetDateTime());
    }

    private OffsetDateTime atKst(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(KST).toOffsetDateTime();
    }

    private LocalDate parseDate(String value) {
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeException("Invalid date");
            return LocalDate.parse(value);
        } catch (DateTimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE", "날짜 형식을 확인해 주세요.", false);
        }
    }

    private Times validateInput(LocalDate date, Map<String, Object> input) {
        if (input == null || !input.keySet().equals(Set.of("opensAt", "closesAt"))) throw validation();
        OffsetDateTime opensAt = parseTime(input.get("opensAt"));
        OffsetDateTime closesAt = parseTime(input.get("closesAt"));
        if (!opensAt.toLocalDate().equals(date) || !opensAt.isBefore(closesAt)
            || closesAt.isAfter(date.plusDays(1).atStartOfDay(KST).toOffsetDateTime())) throw validation();
        return new Times(opensAt, closesAt);
    }

    private OffsetDateTime parseTime(Object value) {
        if (!(value instanceof String text)
            || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(Z|[+-][0-9]{2}:[0-9]{2})")) {
            throw validation();
        }
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(text);
            if (parsed.getSecond() != 0 || parsed.getNano() != 0) throw validation();
            return atKst(parsed);
        } catch (DateTimeException exception) {
            throw validation();
        }
    }

    private String requireKey(HttpServletRequest request) {
        List<String> keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.isEmpty()) throw new ApiException(HttpStatus.PRECONDITION_REQUIRED,
            "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key 헤더가 필요합니다.", false);
        if (keys.size() != 1 || !IdempotencyKeyPolicy.isValid(keys.getFirst())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key 헤더 형식이 올바르지 않습니다.", false);
        }
        return keys.getFirst();
    }

    private void validateQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,
            "INVALID_QUERY", "요청 파라미터를 확인해 주세요.", false);
    }

    private ApiException validation() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
            "해당 운영일의 분 단위 시작·종료 시간을 확인해 주세요. 종료는 익일 00:00까지 가능합니다.", false);
    }

    public record Hours(LocalDate operatingDay, OffsetDateTime opensAt, OffsetDateTime closesAt,
        OffsetDateTime updatedAt) {}
    public record HoursList(List<Hours> items) {}
    private record Times(OffsetDateTime opensAt, OffsetDateTime closesAt) {}
}
