package dev.espero.festival.web;

public record ApiResponse<T>(T data, ApiMeta meta) {}
