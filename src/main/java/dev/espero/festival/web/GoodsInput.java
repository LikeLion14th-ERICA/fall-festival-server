package dev.espero.festival.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GoodsInput(
    String optionMode,
    Map<String, TranslationInput> translations,
    PriceInput price,
    List<ImageInput> images,
    List<ColorInput> colors,
    List<SizeInput> sizes,
    List<OptionInput> options
) {

    public record TranslationInput(String name, String description) {}

    public record PriceInput(long amount, String currency) {}

    public record ImageInput(UUID mediaId, Map<String, String> alt) {}

    public record ColorInput(UUID id, Map<String, ColorTranslationInput> translations) {}

    public record ColorTranslationInput(String name) {}

    public record SizeInput(UUID id, Map<String, SizeTranslationInput> translations) {}

    public record SizeTranslationInput(String label) {}

    public record OptionInput(UUID colorId, UUID sizeId) {}
}
