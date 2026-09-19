package dev.espero.festival.web;

public record GoodsImageResponse(
    String alt,
    String masterUrl,
    String thumbnail320Url,
    String thumbnail640Url
) {}
