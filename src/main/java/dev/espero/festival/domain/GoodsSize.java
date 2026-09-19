package dev.espero.festival.domain;

import java.util.Map;
import java.util.UUID;

public record GoodsSize(UUID id, Map<String, GoodsSizeTranslation> translations) {}
