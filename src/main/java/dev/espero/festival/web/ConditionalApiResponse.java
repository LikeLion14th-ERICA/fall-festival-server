package dev.espero.festival.web;

/** Stable response envelope for endpoints that support conditional requests. */
public record ConditionalApiResponse<T>(T data, ConditionalApiMeta meta) {}
