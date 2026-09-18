package dev.espero.festival.web;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record AdminGoodsResponse(
    String id,
    String optionMode,
    Map<String, Translation> translations,
    Money price,
    List<AdminGoodsColorResponse> colors,
    List<AdminGoodsSizeResponse> sizes,
    List<AdminGoodsCombinationResponse> combinations,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {

    public record Translation(String name, String description) {}

    public record Money(long amount, String currency) {}
}
