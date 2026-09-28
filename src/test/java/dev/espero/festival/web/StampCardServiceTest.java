package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.StampGuide;
import dev.espero.festival.persistence.StampStore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StampCardServiceTest {
    private static final UUID ID = UUID.randomUUID();
    private static final String TOKEN = "a".repeat(43);
    private static final String BOOTH = "b".repeat(32);
    private static final LocalDate DAY = LocalDate.parse("2026-09-29");
    private final StampStore store = mock(StampStore.class);
    private final CatalogSnapshotProvider snapshots = mock(CatalogSnapshotProvider.class);
    private final Clock clock = mock(Clock.class);
    private final StampCardService service = new StampCardService(store, snapshots,
        new FestivalProperties(ID.toString()), clock);

    @BeforeEach
    void setup() {
        when(snapshots.required()).thenReturn(snapshot(List.of(DAY, DAY.plusDays(1), DAY.plusDays(2))));
        when(store.findParticipant(any(), any())).thenReturn(Optional.of(ID));
        when(store.startedOn(any(), any())).thenReturn(true);
        when(store.startDay(any(), any(), any())).thenReturn(true);
        when(store.findBoothToken(any(), any())).thenReturn(Optional.of(new StampStore.BoothToken("booth", "부스", null)));
        when(clock.instant()).thenReturn(Instant.parse("2026-09-29T03:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.withZone(any())).thenReturn(clock);
    }

    @Test
    void rejectsAllWritesOutsidePublishedDatesWithoutWriting() {
        for (LocalDate date : List.of(DAY.minusDays(1), DAY.plusDays(3))) {
            when(clock.instant()).thenReturn(date.atTime(12, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant());
            for (Runnable operation : List.<Runnable>of(() -> service.start(TOKEN),
                () -> service.collect(TOKEN, BOOTH), () -> service.claimReward(TOKEN))) {
                assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo("STAMP_EVENT_CLOSED"));
            }
        }
        verify(store, never()).createParticipant(any(), any(), any());
        verify(store, never()).startDay(any(), any(), any());
        verify(store, never()).insertCollection(any(), any(), any(), any());
        verify(store, never()).insertReward(any(), any(), any());
    }

    @Test
    void rejectsMissingAndEmptyGuideAndGapsInsteadOfAssumingADateRange() {
        for (StampGuide guide : Arrays.asList(null, snapshot(List.of()).stampGuide(),
            snapshot(List.of(DAY.minusDays(1), DAY.plusDays(1))).stampGuide())) {
            CatalogSnapshot base = snapshot(List.of(DAY));
            when(snapshots.required()).thenReturn(new CatalogSnapshot(base.context(), List.of(), List.of(),
                List.of(), Map.of(), null, guide, null));
            assertThatThrownBy(() -> service.start(null)).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(store);
    }

    @Test
    void startReadAndCollectionUseOneInstantEvenWhenTheNextClockReadCrossesMidnight() {
        Instant before = Instant.parse("2026-09-29T14:59:59Z");
        Instant after = before.plusSeconds(2);
        for (String operation : List.of("start", "current", "collect")) {
            clearInvocations(clock, snapshots, store);
            when(clock.instant()).thenReturn(before, after);
            StampCardResponse result = switch (operation) {
                case "start" -> service.start(TOKEN).card();
                case "current" -> service.current(TOKEN);
                default -> service.collect(TOKEN, BOOTH);
            };
            assertThat(result.date()).isEqualTo(DAY);
            verify(clock, times(1)).instant();
            verify(snapshots, times(1)).required();
            verify(store, atLeastOnce()).collections(ID, DAY);
            if (operation.equals("start")) verify(store).startDay(ID, DAY, before);
            if (operation.equals("collect")) verify(store).insertCollection(ID, DAY, "booth", before);
        }
    }

    @Test
    void receiptUsesTheSameInstantForOpeningHoursDateAndStorage() {
        when(store.collections(any(), any())).thenReturn(Collections.nCopies(4, new StampStore.Collected("booth", Instant.EPOCH)));
        for (String time : List.of("11:00:00", "16:59:59")) {
            Instant instant = OffsetDateTime.parse("2026-09-29T" + time + "+09:00").toInstant();
            when(clock.instant()).thenReturn(instant, instant.plusSeconds(86400));
            service.claimReward(TOKEN);
            verify(store).insertReward(ID, DAY, instant);
        }
        for (String time : List.of("10:59:59", "17:00:00")) {
            when(clock.instant()).thenReturn(OffsetDateTime.parse("2026-09-29T" + time + "+09:00").toInstant());
            assertThatThrownBy(() -> service.claimReward(TOKEN)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("STAMP_REWARD_CLOSED"));
        }
    }

    private CatalogSnapshot snapshot(List<LocalDate> dates) {
        return new CatalogSnapshot(new CatalogSnapshot.FestivalContext(ID.toString(), ID, 1), List.of(),
            List.of(), List.of(), Map.of(), null,
            new StampGuide("test", dates, List.of(), "test", null, null, null, null, Instant.EPOCH), null);
    }
}
