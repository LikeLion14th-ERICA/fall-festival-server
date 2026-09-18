package dev.espero.festival.web;

import java.util.List;

public record GoodsPaymentGuideResponse(
    String goodsId,
    String name,
    Money price,
    BankAccount account,
    Link transferLink,
    List<String> instructions,
    String locationText,
    String hoursText
) {

    public record Money(long amount, String currency) {}

    public record BankAccount(String bankName, String accountNumber, String holder) {}

    public record Link(String label, String url, String target) {}
}
