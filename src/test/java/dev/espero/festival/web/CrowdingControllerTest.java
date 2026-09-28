package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.espero.festival.context.FestivalContextService;
import dev.espero.festival.domain.CrowdingRecord;
import dev.espero.festival.domain.CrowdingSchedule;
import dev.espero.festival.persistence.CrowdingStore;
import dev.espero.festival.support.ApiMetaTestFixtures;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CrowdingControllerTest {

    private final CrowdingStore store = mock(CrowdingStore.class);
    private final FestivalContextService contextService = mock(FestivalContextService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private CrowdingViewService serviceAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        when(contextService.currentPublished()).thenReturn(ApiMetaTestFixtures.PUBLISHED_CONTEXT);
        return new CrowdingViewService(
            store,
            contextService,
            ApiMetaTestFixtures.contentMetaSupport(clock),
            new ConditionalResponseSupport(new ObjectMapper()),
            clock
        );
    }

    private void schedule() {
        when(store.findSchedules(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of(
            new CrowdingSchedule(
                java.time.LocalDate.parse("2030-10-01"),
                OffsetDateTime.parse("2030-10-01T13:00:00+09:00"),
                OffsetDateTime.parse("2030-10-01T22:00:00+09:00")
            ),
            new CrowdingSchedule(
                java.time.LocalDate.parse("2030-10-03"),
                OffsetDateTime.parse("2030-10-03T12:00:00+09:00"),
                OffsetDateTime.parse("2030-10-03T21:00:00+09:00")
            )
        ));
    }

    @Test
    void returnsBeforeOpenAheadOfOpeningTimeAndUsesPublishedSchedule() {
        schedule();
        when(store.findFor(ApiMetaTestFixtures.FESTIVAL_ID, java.time.LocalDate.parse("2030-10-01")))
            .thenReturn(Optional.empty());

        CrowdingResponse data = serviceAt("2030-10-01T02:00:00Z").current(request).response();

        assertThat(data.operatingStatus()).isEqualTo(CrowdingResponse.OperatingStatus.BEFORE_OPEN);
        assertThat(data.status()).isEqualTo(CrowdingResponse.Status.BEFORE_OPEN);
        assertThat(data.opensAt().toString()).isEqualTo("2030-10-01T13:00+09:00");
        assertThat(data.message()).contains("13:00");
    }

    @Test
    void returnsRelaxedDefaultDuringOperatingHoursWhenNothingSaved() {
        schedule();
        when(store.findFor(ApiMetaTestFixtures.FESTIVAL_ID, java.time.LocalDate.parse("2030-10-01")))
            .thenReturn(Optional.empty());

        CrowdingViewService.CrowdingSnapshot snapshot = serviceAt("2030-10-01T05:00:00Z").current(request);
        CrowdingResponse data = snapshot.response();

        assertThat(data.operatingStatus()).isEqualTo(CrowdingResponse.OperatingStatus.OPEN);
        assertThat(data.status()).isEqualTo(CrowdingResponse.Status.RELAXED);
        assertThat(data.colorToken()).isEqualTo("green");
        assertThat(data.timeBasis()).isEqualTo(CrowdingResponse.TimeBasis.OPENING);
        assertThat(snapshot.meta().revision()).isZero();
    }

    @Test
    void returnsSavedLevelDuringOperatingHours() {
        schedule();
        when(store.findFor(ApiMetaTestFixtures.FESTIVAL_ID, java.time.LocalDate.parse("2030-10-01")))
            .thenReturn(Optional.of(new CrowdingRecord("FULL", Instant.parse("2030-10-01T06:30:00Z"))));

        CrowdingResponse data = serviceAt("2030-10-01T07:00:00Z").current(request).response();

        assertThat(data.status()).isEqualTo(CrowdingResponse.Status.FULL);
        assertThat(data.colorToken()).isEqualTo("black");
        assertThat(data.savedLevel()).isEqualTo("FULL");
        assertThat(data.timeBasis()).isEqualTo(CrowdingResponse.TimeBasis.OPERATOR);
        assertThat(data.updatedAt()).isNotNull();
    }

    @Test
    void returnsClosedOnTheFinalDateAndBeforeOpenInAnInterDayGap() {
        schedule();
        when(store.findFor(ApiMetaTestFixtures.FESTIVAL_ID, java.time.LocalDate.parse("2030-10-03")))
            .thenReturn(Optional.empty());

        CrowdingResponse gap = serviceAt("2030-10-02T05:00:00Z").current(request).response();
        assertThat(gap.operatingDay()).isEqualTo(java.time.LocalDate.parse("2030-10-03"));
        assertThat(gap.operatingStatus()).isEqualTo(CrowdingResponse.OperatingStatus.BEFORE_OPEN);

        CrowdingResponse ended = serviceAt("2030-10-04T05:00:00Z").current(request).response();
        assertThat(ended.operatingDay()).isEqualTo(java.time.LocalDate.parse("2030-10-03"));
        assertThat(ended.operatingStatus()).isEqualTo(CrowdingResponse.OperatingStatus.CLOSED);
        assertThat(ended.status()).isEqualTo(CrowdingResponse.Status.CLOSED);
    }

    @Test
    void selectsEach2026OperatingDayWithSameLocalDateScheduleBoundaries() {
        when(store.findSchedules(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of(
            sameDaySchedule("2026-09-29"),
            sameDaySchedule("2026-09-30"),
            sameDaySchedule("2026-10-01")
        ));

        assertCurrent("2026-09-28T23:00:00Z", "2026-09-29", CrowdingResponse.OperatingStatus.BEFORE_OPEN);
        assertCurrent("2026-09-30T03:00:00Z", "2026-09-30", CrowdingResponse.OperatingStatus.OPEN);
        assertCurrent("2026-10-01T14:00:00Z", "2026-10-01", CrowdingResponse.OperatingStatus.CLOSED);
    }

    @Test
    void usesRehearsalScheduleAcrossSeptemberTwentyEighthAndOnlyAllowsWritesDuringWindow() {
        when(store.findSchedules(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of(
            sameDaySchedule("2026-09-29"), sameDaySchedule("2026-09-30"), sameDaySchedule("2026-10-01")
        ));
        var before = serviceAt("2026-09-28T01:59:59Z").current(request);
        assertThat(before.operatingDay()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(before.response().operatingStatus()).isEqualTo(CrowdingResponse.OperatingStatus.BEFORE_OPEN);
        assertThat(before.canUpdateLevel()).isFalse();

        var start = serviceAt("2026-09-28T02:00:00Z").current(request);
        assertThat(start.operatingDay()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(start.response().operatingStatus()).isEqualTo(CrowdingResponse.OperatingStatus.OPEN);
        assertThat(start.canUpdateLevel()).isTrue();

        var finalSecond = serviceAt("2026-09-28T05:59:59Z").current(request);
        assertThat(finalSecond.operatingDay()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(finalSecond.canUpdateLevel()).isTrue();

        var end = serviceAt("2026-09-28T06:00:00Z").current(request);
        assertThat(end.operatingDay()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(end.response().operatingStatus()).isEqualTo(CrowdingResponse.OperatingStatus.CLOSED);
        assertThat(end.canUpdateLevel()).isFalse();
    }

    @Test
    void rehearsalScheduleDoesNotDependOnConfiguredFestivalHours() {
        when(store.findSchedules(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of(
            new CrowdingSchedule(LocalDate.parse("2026-09-29"), null, null)
        ));
        var rehearsal = serviceAt("2026-09-28T02:00:00Z").current(request);
        assertThat(rehearsal.operatingDay()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(rehearsal.canUpdateLevel()).isTrue();
    }

    @Test
    void adminReadsTheSelectedOperatingDaysSavedLevelOutsideTheFestivalDay() {
        schedule();
        when(store.findFor(ApiMetaTestFixtures.FESTIVAL_ID, java.time.LocalDate.parse("2030-10-01")))
            .thenReturn(Optional.of(new CrowdingRecord("FULL", Instant.parse("2030-09-30T06:30:00Z"))));
        CrowdingViewService service = serviceAt("2030-09-30T05:00:00Z");

        CrowdingResponse publicData = service.current(request).response();
        CrowdingResponse adminData = service.currentForAdmin(request).response();

        assertThat(publicData.savedLevel()).isNull();
        assertThat(adminData.operatingDay()).isEqualTo(java.time.LocalDate.parse("2030-10-01"));
        assertThat(adminData.operatingStatus()).isEqualTo(CrowdingResponse.OperatingStatus.BEFORE_OPEN);
        assertThat(adminData.savedLevel()).isEqualTo("FULL");
    }

    @Test
    void rejectsAnUnconfiguredSchedule() {
        when(contextService.currentPublished()).thenReturn(ApiMetaTestFixtures.PUBLISHED_CONTEXT);
        when(store.findSchedules(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> serviceAt("2030-10-01T05:00:00Z").current(request))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> {
                ApiException api = (ApiException) exception;
                assertThat(api.status().value()).isEqualTo(503);
                assertThat(api.code()).isEqualTo("CROWDING_SCHEDULE_UNCONFIGURED");
            });
    }

    @Test
    void preservesLegacySecondsAndAllowsClosingExactlyAtNextMidnight() {
        LocalDate day = LocalDate.parse("2030-10-01");
        when(store.findSchedules(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of(
            new CrowdingSchedule(day, OffsetDateTime.parse("2030-10-01T13:00:31.125+09:00"),
                OffsetDateTime.parse("2030-10-02T00:00:00+09:00"))
        ));
        when(store.findFor(ApiMetaTestFixtures.FESTIVAL_ID, day)).thenReturn(Optional.empty());

        CrowdingResponse before = serviceAt("2030-10-01T04:00:31.124Z").current(request).response();
        assertThat(before.operatingStatus()).isEqualTo(CrowdingResponse.OperatingStatus.BEFORE_OPEN);
        CrowdingResponse opened = serviceAt("2030-10-01T04:00:31.125Z").current(request).response();
        assertThat(opened.operatingStatus()).isEqualTo(CrowdingResponse.OperatingStatus.OPEN);
        assertThat(opened.opensAt().getNano()).isEqualTo(125_000_000);
        assertThat(serviceAt("2030-10-01T14:59:59.999Z").current(request).response().operatingStatus())
            .isEqualTo(CrowdingResponse.OperatingStatus.OPEN);
        assertThat(serviceAt("2030-10-01T15:00:00Z").current(request).response().operatingStatus())
            .isEqualTo(CrowdingResponse.OperatingStatus.CLOSED);
    }

    @Test
    void rejectsAScheduleWhoseCloseFallsAfterNextDayOne() {
        when(contextService.currentPublished()).thenReturn(ApiMetaTestFixtures.PUBLISHED_CONTEXT);
        when(store.findSchedules(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of(
            new CrowdingSchedule(
                java.time.LocalDate.parse("2030-10-01"),
                OffsetDateTime.parse("2030-10-01T13:00:00+09:00"),
                OffsetDateTime.parse("2030-10-02T01:01:00+09:00")
            )
        ));

        assertThatThrownBy(() -> serviceAt("2030-10-01T05:00:00Z").current(request))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> {
                ApiException api = (ApiException) exception;
                assertThat(api.status().value()).isEqualTo(503);
                assertThat(api.code()).isEqualTo("CROWDING_SCHEDULE_UNCONFIGURED");
            });
    }

    @Test
    void continuesYesterdayUntilOneAndDoesNotCarryStateToNextOperatingDay() {
        LocalDate yesterday = LocalDate.parse("2030-10-01");
        when(store.findSchedules(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of(
            new CrowdingSchedule(yesterday, OffsetDateTime.parse("2030-10-01T13:00:00+09:00"),
                OffsetDateTime.parse("2030-10-02T01:00:00+09:00")), sameDaySchedule("2030-10-02")
        ));
        Instant savedAt = Instant.parse("2030-10-01T06:00:00Z");
        when(store.findFor(ApiMetaTestFixtures.FESTIVAL_ID, yesterday))
            .thenReturn(Optional.of(new CrowdingRecord("CROWDED", savedAt)));
        for (String instant : List.of("2030-10-01T15:00:00Z", "2030-10-01T15:30:00Z", "2030-10-01T15:59:59Z")) {
            var snapshot = serviceAt(instant).current(request);
            assertThat(snapshot.operatingDay()).isEqualTo(yesterday);
            assertThat(snapshot.response().status()).isEqualTo(CrowdingResponse.Status.CROWDED);
            assertThat(snapshot.response().updatedAt().toInstant()).isEqualTo(savedAt);
            assertThat(snapshot.canUpdateLevel()).isTrue();
        }
        var nextDay = serviceAt("2030-10-01T16:00:00Z").current(request);
        assertThat(nextDay.operatingDay()).isEqualTo(yesterday.plusDays(1));
        assertThat(nextDay.response().status()).isEqualTo(CrowdingResponse.Status.BEFORE_OPEN);
        assertThat(nextDay.response().savedLevel()).isNull();
    }

    @Test
    void prefersTodayFromOpeningAndDoesNotReturnToOverlappingYesterday() {
        LocalDate yesterday = LocalDate.parse("2030-10-01");
        LocalDate today = yesterday.plusDays(1);
        when(store.findSchedules(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of(
            new CrowdingSchedule(yesterday, OffsetDateTime.parse("2030-10-01T13:00:00+09:00"),
                OffsetDateTime.parse("2030-10-02T01:00:00+09:00")),
            new CrowdingSchedule(today, OffsetDateTime.parse("2030-10-02T00:20:00+09:00"),
                OffsetDateTime.parse("2030-10-02T00:40:00+09:00"))
        ));
        assertThat(serviceAt("2030-10-01T15:19:59Z").current(request).operatingDay()).isEqualTo(yesterday);
        var opened = serviceAt("2030-10-01T15:20:00Z").current(request);
        assertThat(opened.operatingDay()).isEqualTo(today);
        assertThat(opened.response().status()).isEqualTo(CrowdingResponse.Status.RELAXED);
        var ended = serviceAt("2030-10-01T15:50:00Z").current(request);
        assertThat(ended.operatingDay()).isEqualTo(today);
        assertThat(ended.response().status()).isEqualTo(CrowdingResponse.Status.CLOSED);
    }

    @Test
    void permitsFinalDayExtensionOnUnpublishedNextDateButStopsAtClosing() {
        LocalDate lastDay = LocalDate.parse("2030-10-01");
        when(store.findSchedules(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of(
            new CrowdingSchedule(lastDay, OffsetDateTime.parse("2030-10-01T13:00:00+09:00"),
                OffsetDateTime.parse("2030-10-02T01:00:00+09:00"))
        ));
        var active = serviceAt("2030-10-01T15:30:00Z").current(request);
        assertThat(active.operatingDay()).isEqualTo(lastDay);
        assertThat(active.response().operatingStatus()).isEqualTo(CrowdingResponse.OperatingStatus.OPEN);
        assertThat(active.canUpdateLevel()).isTrue();
        var ended = serviceAt("2030-10-01T16:00:00Z").current(request);
        assertThat(ended.operatingDay()).isEqualTo(lastDay);
        assertThat(ended.response().operatingStatus()).isEqualTo(CrowdingResponse.OperatingStatus.CLOSED);
        assertThat(ended.canUpdateLevel()).isFalse();
    }

    @Test
    void rejectsAScheduleWhoseOpenFallsOnThePreviousLocalDate() {
        when(contextService.currentPublished()).thenReturn(ApiMetaTestFixtures.PUBLISHED_CONTEXT);
        when(store.findSchedules(ApiMetaTestFixtures.REVISION_ID)).thenReturn(List.of(
            new CrowdingSchedule(
                java.time.LocalDate.parse("2030-10-01"),
                OffsetDateTime.parse("2030-09-30T23:00:00+09:00"),
                OffsetDateTime.parse("2030-10-01T02:00:00+09:00")
            )
        ));

        assertThatThrownBy(() -> serviceAt("2030-10-01T05:00:00Z").current(request))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> {
                ApiException api = (ApiException) exception;
                assertThat(api.status().value()).isEqualTo(503);
                assertThat(api.code()).isEqualTo("CROWDING_SCHEDULE_UNCONFIGURED");
            });
    }

    private void assertCurrent(
        String instant,
        String operatingDay,
        CrowdingResponse.OperatingStatus operatingStatus
    ) {
        CrowdingResponse data = serviceAt(instant).current(request).response();

        assertThat(data.operatingDay()).isEqualTo(LocalDate.parse(operatingDay));
        assertThat(data.opensAt().toLocalDate()).isEqualTo(LocalDate.parse(operatingDay));
        assertThat(data.closesAt().toLocalDate()).isEqualTo(LocalDate.parse(operatingDay));
        assertThat(data.operatingStatus()).isEqualTo(operatingStatus);
    }

    private static CrowdingSchedule sameDaySchedule(String date) {
        LocalDate operatingDate = LocalDate.parse(date);
        return new CrowdingSchedule(
            operatingDate,
            operatingDate.atTime(9, 0).atOffset(ZoneOffset.ofHours(9)),
            operatingDate.atTime(23, 0).atOffset(ZoneOffset.ofHours(9))
        );
    }
}
