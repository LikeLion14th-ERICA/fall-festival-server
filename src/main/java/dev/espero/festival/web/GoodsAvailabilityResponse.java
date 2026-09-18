package dev.espero.festival.web;

import java.time.OffsetDateTime;
import java.util.List;

public record GoodsAvailabilityResponse(
    String goodsId,
    String name,
    List<GoodsCombinationStatusResponse> combinations,
    boolean allSoldOut,
    OffsetDateTime updatedAt
) {}
