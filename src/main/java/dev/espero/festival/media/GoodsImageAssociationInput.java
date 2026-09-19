package dev.espero.festival.media;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Internal command for attaching one uploaded image to a goods row. */
public record GoodsImageAssociationInput(
    UUID mediaId,
    int sortOrder,
    Map<String, String> alt
) {

    public GoodsImageAssociationInput {
        alt = Collections.unmodifiableMap(new LinkedHashMap<>(alt));
    }
}
