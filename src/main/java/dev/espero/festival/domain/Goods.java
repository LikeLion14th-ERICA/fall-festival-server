package dev.espero.festival.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code translations} always has ko and en for goods created under the
 * current rules; colors/sizes are empty for a SINGLE-mode product. */
public record Goods(
    UUID id,
    UUID festivalId,
    GoodsOptionMode optionMode,
    Map<String, GoodsTranslation> translations,
    long priceAmount,
    List<GoodsColor> colors,
    List<GoodsSize> sizes,
    List<GoodsCombination> combinations,
    Instant createdAt,
    Instant updatedAt
) {}
