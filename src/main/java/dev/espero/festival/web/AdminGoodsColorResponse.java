package dev.espero.festival.web;

import java.util.Map;

public record AdminGoodsColorResponse(String id, Map<String, Translation> translations) {

    public record Translation(String name) {}
}
