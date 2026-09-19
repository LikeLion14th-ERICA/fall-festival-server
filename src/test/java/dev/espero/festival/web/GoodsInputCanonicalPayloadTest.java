package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoodsInputCanonicalPayloadTest {

    private static final String ETAG_A = "\"" + "a".repeat(64) + "\"";
    private static final String ETAG_B = "\"" + "b".repeat(64) + "\"";

    @Test
    void ignoresLocaleMapInsertionOrder() {
        GoodsInput first = input();
        GoodsInput second = inputWithIds(first);
        reverse(second.translations());
        reverse(second.images().getFirst().alt());
        reverse(second.colors().getFirst().translations());

        assertThat(GoodsInputCanonicalPayload.fields(second))
            .isEqualTo(GoodsInputCanonicalPayload.fields(first));
    }

    @Test
    void preservesImageColorSizeAndOptionArrayOrder() {
        GoodsInput input = input();

        assertThat(GoodsInputCanonicalPayload.fields(swapImages(input)))
            .isNotEqualTo(GoodsInputCanonicalPayload.fields(input));
        assertThat(GoodsInputCanonicalPayload.fields(swapColors(input)))
            .isNotEqualTo(GoodsInputCanonicalPayload.fields(input));
        assertThat(GoodsInputCanonicalPayload.fields(swapSizes(input)))
            .isNotEqualTo(GoodsInputCanonicalPayload.fields(input));
        assertThat(GoodsInputCanonicalPayload.fields(swapOptions(input)))
            .isNotEqualTo(GoodsInputCanonicalPayload.fields(input));
    }

    @Test
    void includesPriceAndExcludesGeneratedIdentifiers() {
        GoodsInput input = input();
        GoodsInput changedPrice = new GoodsInput(
            input.optionMode(), input.translations(), new GoodsInput.PriceInput(2000, "KRW"),
            input.images(), input.colors(), input.sizes(), input.options()
        );

        assertThat(GoodsInputCanonicalPayload.fields(changedPrice))
            .isNotEqualTo(GoodsInputCanonicalPayload.fields(input));
        assertThat(GoodsInputCanonicalPayload.fields(input).toString())
            .doesNotContain("goodsId", "combinationId", "requestId", "serverTime");
    }

    @Test
    void updateFingerprintIncludesTheValidatedIfMatchValue() {
        GoodsInput input = input();

        assertThat(GoodsInputCanonicalPayload.fields(input, ETAG_A))
            .isEqualTo(GoodsInputCanonicalPayload.fields(input, ETAG_A));
        assertThat(GoodsInputCanonicalPayload.fields(input, ETAG_A))
            .isNotEqualTo(GoodsInputCanonicalPayload.fields(input, ETAG_B));
        assertThat(GoodsInputCanonicalPayload.fields(input, ETAG_A))
            .containsEntry("ifMatch", ETAG_A);
        assertThat(GoodsInputCanonicalPayload.fields(input)).doesNotContainKey("ifMatch");
    }

    private static GoodsInput input() {
        UUID colorA = UUID.fromString("00000000-0000-4000-8000-000000000101");
        UUID colorB = UUID.fromString("00000000-0000-4000-8000-000000000102");
        UUID sizeA = UUID.fromString("00000000-0000-4000-8000-000000000201");
        UUID sizeB = UUID.fromString("00000000-0000-4000-8000-000000000202");
        return new GoodsInput(
            "OPTIONS",
            translations(),
            new GoodsInput.PriceInput(1000, "KRW"),
            List.of(
                image("00000000-0000-4000-8000-000000000050", "앞면"),
                image("00000000-0000-4000-8000-000000000051", "뒷면")
            ),
            List.of(color(colorA, "검정"), color(colorB, "흰색")),
            List.of(size(sizeA, "M"), size(sizeB, "L")),
            List.of(
                new GoodsInput.OptionInput(colorA, sizeA),
                new GoodsInput.OptionInput(colorB, sizeB)
            )
        );
    }

    private static GoodsInput inputWithIds(GoodsInput source) {
        return new GoodsInput(
            source.optionMode(), new LinkedHashMap<>(source.translations()), source.price(),
            source.images().stream().map(image -> new GoodsInput.ImageInput(
                image.mediaId(), new LinkedHashMap<>(image.alt())
            )).toList(),
            source.colors().stream().map(color -> new GoodsInput.ColorInput(
                color.id(), new LinkedHashMap<>(color.translations())
            )).toList(),
            source.sizes().stream().map(size -> new GoodsInput.SizeInput(
                size.id(), new LinkedHashMap<>(size.translations())
            )).toList(),
            source.options()
        );
    }

    private static Map<String, GoodsInput.TranslationInput> translations() {
        Map<String, GoodsInput.TranslationInput> result = new LinkedHashMap<>();
        result.put("ko", new GoodsInput.TranslationInput("상품", null));
        result.put("en", new GoodsInput.TranslationInput("Goods", null));
        result.put("zh-Hans", null);
        result.put("ja", null);
        return result;
    }

    private static GoodsInput.ImageInput image(String id, String ko) {
        Map<String, String> alt = new LinkedHashMap<>();
        alt.put("ko", ko);
        alt.put("en", ko + " image");
        alt.put("zh-Hans", null);
        alt.put("ja", null);
        return new GoodsInput.ImageInput(UUID.fromString(id), alt);
    }

    private static GoodsInput.ColorInput color(UUID id, String name) {
        Map<String, GoodsInput.ColorTranslationInput> translations = new LinkedHashMap<>();
        translations.put("ko", new GoodsInput.ColorTranslationInput(name));
        translations.put("en", new GoodsInput.ColorTranslationInput(name));
        translations.put("zh-Hans", null);
        translations.put("ja", null);
        return new GoodsInput.ColorInput(id, translations);
    }

    private static GoodsInput.SizeInput size(UUID id, String label) {
        Map<String, GoodsInput.SizeTranslationInput> translations = new LinkedHashMap<>();
        translations.put("ko", new GoodsInput.SizeTranslationInput(label));
        translations.put("en", new GoodsInput.SizeTranslationInput(label));
        translations.put("zh-Hans", null);
        translations.put("ja", null);
        return new GoodsInput.SizeInput(id, translations);
    }

    private static GoodsInput swapImages(GoodsInput input) {
        return replace(input, reversed(input.images()), input.colors(), input.sizes(), input.price());
    }

    private static GoodsInput swapColors(GoodsInput input) {
        return replace(input, input.images(), reversed(input.colors()), input.sizes(), input.price());
    }

    private static GoodsInput swapSizes(GoodsInput input) {
        return replace(input, input.images(), input.colors(), reversed(input.sizes()), input.price());
    }

    private static GoodsInput swapOptions(GoodsInput input) {
        return new GoodsInput(
            input.optionMode(), input.translations(), input.price(), input.images(), input.colors(), input.sizes(),
            reversed(input.options())
        );
    }

    private static GoodsInput replace(
        GoodsInput input,
        List<GoodsInput.ImageInput> images,
        List<GoodsInput.ColorInput> colors,
        List<GoodsInput.SizeInput> sizes,
        GoodsInput.PriceInput price
    ) {
        return new GoodsInput(
            input.optionMode(), input.translations(), price, images, colors, sizes, input.options()
        );
    }

    private static <T> List<T> reversed(List<T> input) {
        List<T> result = new ArrayList<>(input);
        java.util.Collections.reverse(result);
        return result;
    }

    private static <K, V> void reverse(Map<K, V> map) {
        List<Map.Entry<K, V>> entries = new ArrayList<>(map.entrySet());
        java.util.Collections.reverse(entries);
        map.clear();
        entries.forEach(entry -> map.put(entry.getKey(), entry.getValue()));
    }
}
