package dev.espero.festival.web;

import java.util.Map;

public record AdminNoticeLinkResponse(String url, Map<String, String> labels) {}
