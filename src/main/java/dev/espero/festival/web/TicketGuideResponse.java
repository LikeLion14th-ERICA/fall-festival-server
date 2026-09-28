package dev.espero.festival.web;

public record TicketGuideResponse(Money unitPrice) {

    public record Money(int amount, String currency) {}
}
