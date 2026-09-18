package dev.espero.festival.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record TicketGuideResponse(
    LocalDate date,
    Status status,
    Money unitPrice,
    OffsetDateTime transferOpensAt,
    OffsetDateTime transferClosesAt,
    OffsetDateTime pickupOpensAt,
    OffsetDateTime pickupClosesAt,
    BankAccount account,
    Link transferLink,
    Long paymentSettingsVersion,
    MapTarget mapTarget,
    List<String> instructions
) {

    public enum Status {
        BEFORE_FESTIVAL,
        TRANSFER_OPEN,
        DAILY_CLOSED,
        FESTIVAL_ENDED,
        UNCONFIGURED
    }

    public record Money(int amount, String currency) {}

    public record BankAccount(String bankName, String accountNumber, String holder) {}

    public record Link(String label, String url, String target) {}

    public record MapTarget(String mapId, String placeId, String pinId, String mapVersion) {}
}
