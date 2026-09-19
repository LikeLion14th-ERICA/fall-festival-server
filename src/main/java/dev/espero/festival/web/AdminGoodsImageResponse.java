package dev.espero.festival.web;

import java.util.Map;

public record AdminGoodsImageResponse(
    String mediaId,
    Map<String, String> alt,
    String masterUrl,
    String thumbnail320Url,
    String thumbnail640Url
) {}
