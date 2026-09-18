package dev.espero.festival.web;

public record AdminGoodsCombinationResponse(
    String combinationId,
    String colorId,
    String sizeId,
    String status
) {}
