package dev.espero.festival.web;

import dev.espero.festival.idempotency.CanonicalPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the complete semantic ProductInput fingerprint without generated IDs or request metadata. */
final class GoodsInputCanonicalPayload {

    private GoodsInputCanonicalPayload() {}

    static CanonicalPayload from(GoodsInput input) {
        return CanonicalPayload.from(fields(input));
    }

    static Map<String, Object> fields(GoodsInput input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("optionMode", input.optionMode());
        payload.put("translations", productTranslations(input.translations()));
        payload.put("price", Map.of("amount", input.price().amount(), "currency", input.price().currency()));
        payload.put("images", input.images().stream().map(GoodsInputCanonicalPayload::image).toList());
        payload.put("colors", input.colors().stream().map(GoodsInputCanonicalPayload::color).toList());
        payload.put("sizes", input.sizes().stream().map(GoodsInputCanonicalPayload::size).toList());
        payload.put("options", input.options().stream().map(option -> Map.of(
            "colorId", option.colorId(),
            "sizeId", option.sizeId()
        )).toList());
        return payload;
    }

    private static Map<String, Object> productTranslations(
        Map<String, GoodsInput.TranslationInput> translations
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String locale : List.of("ko", "en", "zh-Hans", "ja")) {
            GoodsInput.TranslationInput translation = translations.get(locale);
            result.put(locale, translation == null ? List.of() : List.of(Map.of(
                "name", translation.name(),
                "description", nullable(translation.description())
            )));
        }
        return result;
    }

    private static Map<String, Object> image(GoodsInput.ImageInput image) {
        Map<String, Object> alt = new LinkedHashMap<>();
        for (String locale : List.of("ko", "en", "zh-Hans", "ja")) {
            alt.put(locale, nullable(image.alt().get(locale)));
        }
        return Map.of("mediaId", image.mediaId(), "alt", alt);
    }

    private static Map<String, Object> color(GoodsInput.ColorInput color) {
        Map<String, Object> translations = new LinkedHashMap<>();
        for (String locale : List.of("ko", "en", "zh-Hans", "ja")) {
            GoodsInput.ColorTranslationInput translation = color.translations().get(locale);
            translations.put(locale, translation == null ? List.of() : List.of(translation.name()));
        }
        return Map.of("id", color.id(), "translations", translations);
    }

    private static Map<String, Object> size(GoodsInput.SizeInput size) {
        Map<String, Object> translations = new LinkedHashMap<>();
        for (String locale : List.of("ko", "en", "zh-Hans", "ja")) {
            GoodsInput.SizeTranslationInput translation = size.translations().get(locale);
            translations.put(locale, translation == null ? List.of() : List.of(translation.label()));
        }
        return Map.of("id", size.id(), "translations", translations);
    }

    private static List<String> nullable(String value) {
        List<String> encoded = new ArrayList<>(1);
        if (value != null) {
            encoded.add(value);
        }
        return encoded;
    }
}
