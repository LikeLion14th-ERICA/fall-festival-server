package dev.espero.festival.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Static ticket guidance belonging to one published catalog revision.
 *
 * <p>Bank account and transfer link values are deliberately absent: they are
 * revision-independent operational settings managed by the account CLI, so a
 * catalog revision neither carries nor restores them.</p>
 */
public record TicketGuideConfig(
    Integer unitPriceAmount,
    List<String> instructions,
    LocalDate festivalStartDate,
    LocalDate festivalEndDate,
    LocalTime dailyTransferOpenTime,
    LocalTime dailyTransferCloseTime,
    LocalTime dailyPickupOpenTime,
    LocalTime dailyPickupCloseTime,
    Instant updatedAt
) {

    public TicketGuideConfig {
        instructions = List.copyOf(instructions);
    }

    public boolean hasSchedule() {
        return festivalStartDate != null && festivalEndDate != null
            && dailyTransferOpenTime != null && dailyTransferCloseTime != null
            && dailyPickupOpenTime != null && dailyPickupCloseTime != null;
    }
}
