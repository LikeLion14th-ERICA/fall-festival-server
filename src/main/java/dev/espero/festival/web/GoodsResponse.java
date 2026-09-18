package dev.espero.festival.web;

import java.util.List;

public record GoodsResponse(
    String id,
    String contentLocale,
    String name,
    String description,
    Money price,
    String optionMode,
    List<GoodsColorResponse> colors,
    List<GoodsSizeResponse> sizes
) {

    public record Money(long amount, String currency) {}
}
