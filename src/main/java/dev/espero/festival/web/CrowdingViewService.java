package dev.espero.festival.web;

import dev.espero.festival.context.FestivalContextService;
import dev.espero.festival.domain.CrowdingRecord;
import dev.espero.festival.domain.CrowdingSchedule;
import dev.espero.festival.domain.PublishedFestivalContext;
import dev.espero.festival.persistence.CrowdingStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Builds the public and administrator crowding representation from one schedule source. */
@Service
@Profile("db")
public class CrowdingViewService {

    private static final String CONTENT_LOCALE = PublicContentLocale.KOREAN;

    private final CrowdingStore store;
    private final FestivalContextService contextService;
    private final ApiMetaSupport metaSupport;
    private final ConditionalResponseSupport conditionalResponses;
    private final Clock clock;

    public CrowdingViewService(
        CrowdingStore store,
        FestivalContextService contextService,
        ApiMetaSupport metaSupport,
        ConditionalResponseSupport conditionalResponses,
        Clock clock
    ) {
        this.store = store;
        this.contextService = contextService;
        this.metaSupport = metaSupport;
        this.conditionalResponses = conditionalResponses;
        this.clock = clock;
    }

    public CrowdingSnapshot current(HttpServletRequest request) {
        PublishedFestivalContext context = publishedContext();
        List<CrowdingSchedule> schedules = schedules(context);
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(context.timezone()));
        CrowdingSchedule selected = select(schedules, today);
        Optional<CrowdingRecord> saved;
        try {
            saved = today.equals(selected.operatingDate())
                ? store.findFor(context.festivalId(), selected.operatingDate())
                : Optional.empty();
        } catch (DataAccessException exception) {
            throw unavailable();
        }
        return snapshot(request, context, schedules, today, selected, saved, now);
    }

    public CrowdingSnapshot withSaved(
        HttpServletRequest request,
        PublishedFestivalContext context,
        List<CrowdingSchedule> schedules,
        LocalDate today,
        CrowdingSchedule selected,
        Optional<CrowdingRecord> saved,
        Instant now
    ) {
        return snapshot(request, context, schedules, today, selected, saved, now);
    }

    public boolean isActualFestivalDay(CrowdingSnapshot snapshot) {
        return snapshot.today().equals(snapshot.operatingDay());
    }

    private CrowdingSnapshot snapshot(
        HttpServletRequest request,
        PublishedFestivalContext context,
        List<CrowdingSchedule> schedules,
        LocalDate today,
        CrowdingSchedule selected,
        Optional<CrowdingRecord> saved,
        Instant now
    ) {
        ApiMeta meta = metaSupport.unscopedMeta(request, CONTENT_LOCALE);
        CrowdingResponse response = response(today, selected, saved, now, context.timezone());
        String etag = conditionalResponses.strongEtag(
            new ConditionalApiResponse<>(response, ConditionalApiMeta.from(meta))
        );
        return new CrowdingSnapshot(
            context,
            List.copyOf(schedules),
            today,
            selected,
            saved,
            response,
            meta,
            etag
        );
    }

    private PublishedFestivalContext publishedContext() {
        try {
            return contextService.currentPublished();
        } catch (ApiException exception) {
            throw scheduleUnconfigured();
        } catch (IllegalStateException exception) {
            throw scheduleUnconfigured();
        }
    }

    private List<CrowdingSchedule> schedules(PublishedFestivalContext context) {
        final List<CrowdingSchedule> schedules;
        try {
            schedules = store.findSchedules(context.festivalRevisionId());
        } catch (DataAccessException exception) {
            throw scheduleUnconfigured();
        }
        if (schedules.isEmpty() || !validSchedules(schedules, context.timezone())) {
            throw scheduleUnconfigured();
        }
        return schedules;
    }

    private boolean validSchedules(List<CrowdingSchedule> schedules, ZoneId timezone) {
        LocalDate previous = null;
        HashSet<LocalDate> dates = new HashSet<>();
        for (CrowdingSchedule schedule : schedules) {
            if (schedule == null || schedule.operatingDate() == null
                || schedule.opensAt() == null || schedule.closesAt() == null
                || !schedule.opensAt().isBefore(schedule.closesAt())
                || !dates.add(schedule.operatingDate())) {
                return false;
            }
            LocalDate openDate = schedule.opensAt().atZoneSameInstant(timezone).toLocalDate();
            LocalDate closeDate = schedule.closesAt().atZoneSameInstant(timezone).toLocalDate();
            if (!schedule.operatingDate().equals(openDate)
                || !schedule.operatingDate().equals(closeDate)) {
                return false;
            }
            if (previous != null && !previous.isBefore(schedule.operatingDate())) {
                return false;
            }
            previous = schedule.operatingDate();
        }
        return true;
    }

    private CrowdingSchedule select(List<CrowdingSchedule> schedules, LocalDate today) {
        for (CrowdingSchedule schedule : schedules) {
            if (!schedule.operatingDate().isBefore(today)) {
                return schedule;
            }
        }
        return schedules.getLast();
    }

    private CrowdingResponse response(
        LocalDate today,
        CrowdingSchedule selected,
        Optional<CrowdingRecord> saved,
        Instant now,
        ZoneId timezone
    ) {
        OffsetDateTime opensAt = selected.opensAt().atZoneSameInstant(timezone).toOffsetDateTime();
        OffsetDateTime closesAt = selected.closesAt().atZoneSameInstant(timezone).toOffsetDateTime();
        boolean beforeSelectedDay = today.isBefore(selected.operatingDate());
        boolean afterSelectedDay = today.isAfter(selected.operatingDate());
        CrowdingResponse.OperatingStatus operatingStatus;
        if (beforeSelectedDay || now.isBefore(opensAt.toInstant())) {
            operatingStatus = CrowdingResponse.OperatingStatus.BEFORE_OPEN;
        } else if (afterSelectedDay || !now.isBefore(closesAt.toInstant())) {
            operatingStatus = CrowdingResponse.OperatingStatus.CLOSED;
        } else {
            operatingStatus = CrowdingResponse.OperatingStatus.OPEN;
        }

        CrowdingResponse.Status status = switch (operatingStatus) {
            case BEFORE_OPEN -> CrowdingResponse.Status.BEFORE_OPEN;
            case CLOSED -> CrowdingResponse.Status.CLOSED;
            case OPEN -> saved.map(record -> CrowdingResponse.Status.valueOf(record.level()))
                .orElse(CrowdingResponse.Status.RELAXED);
        };
        boolean active = operatingStatus == CrowdingResponse.OperatingStatus.OPEN;
        return new CrowdingResponse(
            selected.operatingDate(),
            opensAt,
            closesAt,
            operatingStatus,
            status,
            saved.map(CrowdingRecord::level).orElse(null),
            active ? color(status) : null,
            message(status, opensAt),
            active ? saved.map(record -> OffsetDateTime.ofInstant(record.updatedAt(), timezone)).orElse(null) : null,
            active
                ? saved.map(record -> CrowdingResponse.TimeBasis.OPERATOR)
                    .orElse(CrowdingResponse.TimeBasis.OPENING)
                : CrowdingResponse.TimeBasis.NONE
        );
    }

    private String color(CrowdingResponse.Status status) {
        return switch (status) {
            case RELAXED -> "green";
            case MODERATE -> "orange";
            case CROWDED -> "red";
            case FULL -> "black";
            case BEFORE_OPEN, CLOSED -> null;
        };
    }

    private String message(CrowdingResponse.Status status, OffsetDateTime opensAt) {
        return switch (status) {
            case BEFORE_OPEN -> "오늘 재학생존 입장은 %s에 시작해요".formatted(opensAt.toLocalTime());
            case RELAXED -> "재학생존의 공간이 많이 남았어요.";
            case MODERATE -> "재학생존의 공간이 절반 정도 찼어요.";
            case CROWDED -> "재학생존이 많이 혼잡해요.";
            case FULL -> "재학생존이 꽉 차서 외부인존에서만 즐길 수 있어요.";
            case CLOSED -> "오늘 재학생존 운영이 종료됐어요";
        };
    }

    private ApiException unavailable() {
        return new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "SERVICE_UNAVAILABLE",
            "일시적으로 정보를 불러올 수 없습니다.",
            true
        );
    }

    private ApiException scheduleUnconfigured() {
        return new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "CROWDING_SCHEDULE_UNCONFIGURED",
            "재학생존 운영 일정이 아직 등록되지 않았습니다.",
            true
        );
    }

    public record CrowdingSnapshot(
        PublishedFestivalContext context,
        List<CrowdingSchedule> schedules,
        LocalDate today,
        CrowdingSchedule selected,
        Optional<CrowdingRecord> saved,
        CrowdingResponse response,
        ApiMeta meta,
        String etag
    ) {
        public LocalDate operatingDay() {
            return selected.operatingDate();
        }

        public CrowdingSchedule schedule() {
            return selected;
        }
    }
}
