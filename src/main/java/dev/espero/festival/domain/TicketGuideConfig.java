package dev.espero.festival.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record TicketGuideConfig(
    Integer unitPriceAmount,
    String accountBankName,
    String accountNumber,
    String accountHolder,
    String transferLinkLabel,
    String transferLinkUrl,
    String mapId,
    String placeId,
    String pinId,
    String mapVersion,
    List<String> instructions,
    LocalDate festivalStartDate,
    LocalDate festivalEndDate,
    LocalTime dailyTransferOpenTime,
    LocalTime dailyTransferCloseTime,
    LocalTime dailyPickupOpenTime,
    LocalTime dailyPickupCloseTime,
    Instant updatedAt
) {

    public boolean hasSchedule() {
        return festivalStartDate != null && festivalEndDate != null
            && dailyTransferOpenTime != null && dailyTransferCloseTime != null
            && dailyPickupOpenTime != null && dailyPickupCloseTime != null;
    }

    public boolean hasAccount() {
        return accountBankName != null && accountNumber != null && accountHolder != null;
    }

    public boolean hasMapTarget() {
        return mapId != null && placeId != null && pinId != null && mapVersion != null;
    }

    public boolean hasTransferLink() {
        return transferLinkLabel != null && transferLinkUrl != null;
    }
}
