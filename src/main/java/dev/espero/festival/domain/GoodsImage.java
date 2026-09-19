package dev.espero.festival.domain;

import java.util.Map;
import java.util.UUID;

/** An attached goods image; list position represents its persisted sort order. */
public record GoodsImage(
    UUID mediaId,
    Map<String, GoodsImageTranslation> translations
) {}
