package dev.espero.festival.domain;

import java.util.Map;
import java.util.UUID;

public record GoodsColor(UUID id, Map<String, GoodsColorTranslation> translations) {}
