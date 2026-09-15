package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.TicketGuideConfig;
import dev.espero.festival.persistence.TicketGuideStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TicketGuideControllerTest {

    private final TicketGuideStore store = mock(TicketGuideStore.class);
    private final CatalogSnapshotProvider snapshots = mock(CatalogSnapshotProvider.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private TicketGuideController controllerAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        when(snapshots.required()).thenReturn(snapshot(null));
        return new TicketGuideController(store, snapshots, new ApiMetaSupport(clock), clock);
    }

    private CatalogSnapshot snapshot(CatalogSnapshot.MapTarget ticketMapTarget) {
        return new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext(
                "festival-test", UUID.fromString("00000000-0000-0000-0000-000000000001"), 7
            ),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            ticketMapTarget
        );
    }

    private TicketGuideConfig scheduledConfig() {
        return new TicketGuideConfig(
            15000,
            "개발용 은행",
            "MOCK-NOT-PAYABLE",
            "개발용 예금주",
            null,
            null,
            List.of("안내1", "안내2"),
            LocalDate.parse("2030-10-01"),
            LocalDate.parse("2030-10-03"),
            LocalTime.parse("00:00"),
            LocalTime.parse("21:00"),
            LocalTime.parse("13:00"),
            LocalTime.parse("21:00"),
            Instant.parse("2030-09-01T00:00:00Z")
        );
    }

    @Test
    void returnsUnconfiguredWhenNoRowExists() {
        when(store.find(any(UUID.class))).thenReturn(Optional.empty());

        TicketGuideResponse data = controllerAt("2030-10-01T09:00:00Z").getTicketGuide(request).data();

        assertThat(data.status()).isEqualTo(TicketGuideResponse.Status.UNCONFIGURED);
        assertThat(data.unitPrice()).isNull();
        assertThat(data.account()).isNull();
        assertThat(data.mapTarget()).isNull();
        assertThat(data.instructions()).isEmpty();
    }

    @Test
    void returnsUnconfiguredWithKnownPriceWhenDatesAreMissing() {
        TicketGuideConfig partial = new TicketGuideConfig(
            15000, null, null, null, null, null,
            List.of("안내1"), null, null,
            LocalTime.parse("00:00"), LocalTime.parse("21:00"),
            LocalTime.parse("13:00"), LocalTime.parse("21:00"),
            Instant.parse("2030-09-01T00:00:00Z")
        );
        when(store.find(any(UUID.class))).thenReturn(Optional.of(partial));

        TicketGuideResponse data = controllerAt("2030-10-01T09:00:00Z").getTicketGuide(request).data();

        assertThat(data.status()).isEqualTo(TicketGuideResponse.Status.UNCONFIGURED);
        assertThat(data.unitPrice().amount()).isEqualTo(15000);
        assertThat(data.instructions()).containsExactly("안내1");
    }

    @Test
    void returnsBeforeFestivalAheadOfTheFirstDay() {
        when(store.find(any(UUID.class))).thenReturn(Optional.of(scheduledConfig()));

        TicketGuideResponse data = controllerAt("2030-09-30T09:00:00Z").getTicketGuide(request).data();

        assertThat(data.date()).isEqualTo(LocalDate.parse("2030-09-30"));
        assertThat(data.status()).isEqualTo(TicketGuideResponse.Status.BEFORE_FESTIVAL);
        assertThat(data.account()).isNull();
        assertThat(data.transferOpensAt().toLocalDate()).isEqualTo(LocalDate.parse("2030-10-01"));
    }

    @Test
    void returnsTransferOpenDuringTheDayBeforeCloseTime() {
        when(store.find(any(UUID.class))).thenReturn(Optional.of(scheduledConfig()));

        TicketGuideResponse data = controllerAt("2030-10-01T09:00:00Z").getTicketGuide(request).data();

        assertThat(data.status()).isEqualTo(TicketGuideResponse.Status.TRANSFER_OPEN);
        assertThat(data.account()).isNotNull();
        assertThat(data.account().bankName()).isEqualTo("개발용 은행");
    }

    @Test
    void returnsDailyClosedAtOrAfterCloseTimeAndHidesAccount() {
        when(store.find(any(UUID.class))).thenReturn(Optional.of(scheduledConfig()));

        TicketGuideResponse data = controllerAt("2030-10-01T12:00:00Z").getTicketGuide(request).data();

        assertThat(data.status()).isEqualTo(TicketGuideResponse.Status.DAILY_CLOSED);
        assertThat(data.account()).isNull();
    }

    @Test
    void returnsFestivalEndedAfterTheLastDayAndClampsScheduleDate() {
        when(store.find(any(UUID.class))).thenReturn(Optional.of(scheduledConfig()));

        TicketGuideResponse data = controllerAt("2030-10-05T09:00:00Z").getTicketGuide(request).data();

        assertThat(data.status()).isEqualTo(TicketGuideResponse.Status.FESTIVAL_ENDED);
        assertThat(data.account()).isNull();
        assertThat(data.transferOpensAt().toLocalDate()).isEqualTo(LocalDate.parse("2030-10-03"));
    }

    @Test
    void returnsOnlyTheSnapshotVerifiedTicketMapTarget() {
        Clock clock = Clock.fixed(Instant.parse("2030-10-01T09:00:00Z"), ZoneOffset.UTC);
        CatalogSnapshot.MapTarget target = new CatalogSnapshot.MapTarget(
            "map-overview", "place-ticket-zone", "pin-ticket-zone", "asset-2026-01"
        );
        when(snapshots.required()).thenReturn(snapshot(target));
        when(store.find(any(UUID.class))).thenReturn(Optional.of(scheduledConfig()));
        TicketGuideController controller = new TicketGuideController(store, snapshots, new ApiMetaSupport(clock), clock);

        ApiResponse<TicketGuideResponse> response = controller.getTicketGuide(request);

        assertThat(response.data().mapTarget()).isEqualTo(new TicketGuideResponse.MapTarget(
            "map-overview", "place-ticket-zone", "pin-ticket-zone", "asset-2026-01"
        ));
        assertThat(response.meta().festivalId()).isEqualTo("festival-test");
        assertThat(response.meta().revision()).isEqualTo(7);
    }
}
