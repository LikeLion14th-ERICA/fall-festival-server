package dev.espero.festival.web;

import java.util.Map;

public record AdminGoodsSizeResponse(String id, Map<String, Translation> translations) {

    public record Translation(String label) {}
}
