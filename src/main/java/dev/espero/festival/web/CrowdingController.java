package dev.espero.festival.web;

import dev.espero.festival.domain.CrowdingRecord;
import dev.espero.festival.persistence.CrowdingStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements GET /api/v2/crowding per api-v2/contract-source.mjs (Crowding
 * schema) and HOME-001 (docs/wiki/product/home.md). Read-only: the operator
 * write path (PUT /admin/crowding) needs A's authentication/authorization
 * foundation first, so it's deliberately not implemented here.
 *
 * Operating hours (13:00~22:00) are the "기본 기준" placeholder from
 * home.md, not per-festival-day data — festival_days has no seeded rows yet.
 * Swap this for a real per-day lookup once that data exists.
 *
 * locale is intentionally not yet honored: only Korean content exists, so the
 * query parameter is accepted but ignored until translated content is stored.
 */
@RestController
@RequestMapping("/api/v2")
@Profile("db")
public class CrowdingController {

    private static final ZoneId TIMEZONE = ZoneId.of("Asia/Seoul");
    private static final LocalTime OPENS_AT = LocalTime.of(13, 0);
    private static final LocalTime CLOSES_AT = LocalTime.of(22, 0);
    private static final String CONTENT_LOCALE = "ko";

    private static final Map<String, String> COLOR_TOKENS = Map.of(
        "RELAXED", "green",
        "MODERATE", "orange",
        "CROWDED", "red",
        "FULL", "black"
    );

    private final CrowdingStore store;
    private final ApiMetaSupport metaSupport;
    private final Clock clock;

    public CrowdingController(CrowdingStore store, ApiMetaSupport metaSupport, Clock clock) {
        this.store = store;
        this.metaSupport = metaSupport;
        this.clock = clock;
    }

    @GetMapping("/crowding")
    public ApiResponse<CrowdingResponse> getCrowding(HttpServletRequest request) {
        Instant now = clock.instant();
        LocalDate operatingDay = LocalDate.now(clock.withZone(TIMEZONE));
        OffsetDateTime opensAt = operatingDay.atTime(OPENS_AT).atZone(TIMEZONE).toOffsetDateTime();
        OffsetDateTime closesAt = operatingDay.atTime(CLOSES_AT).atZone(TIMEZONE).toOffsetDateTime();

        Optional<CrowdingRecord> saved = store.findFor(operatingDay);

        CrowdingResponse.Status status;
        if (now.isBefore(opensAt.toInstant())) {
            status = CrowdingResponse.Status.BEFORE_OPEN;
        } else if (!now.isBefore(closesAt.toInstant())) {
            status = CrowdingResponse.Status.CLOSED;
        } else {
            status = saved.map(record -> CrowdingResponse.Status.valueOf(record.level()))
                .orElse(CrowdingResponse.Status.RELAXED);
        }

        boolean active = status != CrowdingResponse.Status.BEFORE_OPEN && status != CrowdingResponse.Status.CLOSED;

        CrowdingResponse data = new CrowdingResponse(
            operatingDay,
            opensAt,
            closesAt,
            status,
            saved.map(CrowdingRecord::level).orElse(null),
            active ? COLOR_TOKENS.get(status.name()) : null,
            message(status, opensAt),
            saved.map(record -> OffsetDateTime.ofInstant(record.updatedAt(), TIMEZONE)).orElse(null),
            active
                ? (saved.isPresent() ? CrowdingResponse.TimeBasis.OPERATOR : CrowdingResponse.TimeBasis.OPENING)
                : CrowdingResponse.TimeBasis.NONE
        );

        return new ApiResponse<>(data, metaSupport.meta(request, 1, CONTENT_LOCALE));
    }

    private String message(CrowdingResponse.Status status, OffsetDateTime opensAt) {
        return switch (status) {
            case BEFORE_OPEN -> "오늘 재학생존 입장은 %s에 시작해요".formatted(opensAt.toLocalTime());
            case RELAXED -> "재학생존의 공간이 많이 남았어요.";
            case MODERATE -> "재학생존의 공간이 절반 이상 찼어요.";
            case CROWDED -> "재학생존이 많이 혼잡해요.";
            case FULL -> "재학생존이 꽉 차서 외부인존에서만 즐길 수 있어요.";
            case CLOSED -> "오늘 재학생존 운영이 종료됐어요";
        };
    }
}
