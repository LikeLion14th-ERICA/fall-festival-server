package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoodsInputValidatorTest {

    @Test
    void acceptsOneOrTwoImages() {
        assertThatCode(() -> GoodsInputValidator.validate(input(List.of(image()), null, null)))
            .doesNotThrowAnyException();
        assertThatCode(() -> GoodsInputValidator.validate(input(List.of(image(), image()), null, null)))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingEmptyOrMoreThanTwoImages() {
        assertValidationFailed(input(null, null, null));
        assertValidationFailed(input(List.of(), null, null));
        assertValidationFailed(input(List.of(image(), image(), image()), null, null));
    }

    @Test
    void rejectsDuplicateMediaIdsBeforePersistence() {
        UUID mediaId = UUID.randomUUID();
        GoodsInput duplicate = input(List.of(image(mediaId, alt()), image(mediaId, alt())), null, null);

        assertThatThrownBy(() -> GoodsInputValidator.validate(duplicate))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.code()).isEqualTo("DUPLICATE_MEDIA");
                assertThat(exception.status().value()).isEqualTo(422);
            });
    }

    @Test
    void rejectsMissingOrBlankRequiredAltText() {
        for (String locale : List.of("ko", "en")) {
            Map<String, String> missing = alt();
            missing.remove(locale);
            assertValidationFailed(input(List.of(image(UUID.randomUUID(), missing)), null, null));

            Map<String, String> blank = alt();
            blank.put(locale, "   ");
            assertValidationFailed(input(List.of(image(UUID.randomUUID(), blank)), null, null));
        }
    }

    @Test
    void rejectsUnknownImageAltLocale() {
        Map<String, String> alt = alt();
        alt.put("fr", "Image du produit");

        assertValidationFailed(input(List.of(image(UUID.randomUUID(), alt)), null, null));
    }

    @Test
    void optionalImageAltMustMatchEveryProductLocale() {
        for (String locale : List.of("zh-Hans", "ja")) {
            Map<String, String> matchingAlt = alt();
            matchingAlt.put(locale, "Localized alt");
            assertThatCode(() -> GoodsInputValidator.validate(input(
                List.of(image(UUID.randomUUID(), matchingAlt)), locale, translation("Localized product")
            ))).doesNotThrowAnyException();

            assertValidationFailed(input(List.of(image()), locale, translation("Localized product")));

            Map<String, String> unexpectedAlt = alt();
            unexpectedAlt.put(locale, "Unexpected alt");
            assertValidationFailed(input(List.of(image(UUID.randomUUID(), unexpectedAlt)), locale, null));

            Map<String, String> blankAlt = alt();
            blankAlt.put(locale, "   ");
            assertValidationFailed(input(List.of(image(UUID.randomUUID(), blankAlt)), locale, null));
        }
    }

    @Test
    void nullOptionalProductAndImageLocalesRemainValid() {
        assertThatCode(() -> GoodsInputValidator.validate(input(List.of(image()), null, null)))
            .doesNotThrowAnyException();
    }

    @Test
    void requiresDescriptionsAcrossEveryActiveProductLocaleOrNowhere() {
        assertThatCode(() -> GoodsInputValidator.validate(withDescriptions(null, null, null, null)))
            .doesNotThrowAnyException();
        assertValidationFailed(withDescriptions(null, "English", null, null));
        assertThatCode(() -> GoodsInputValidator.validate(withDescriptions("한국어", "English", "中文", "日本語")))
            .doesNotThrowAnyException();
        assertValidationFailed(withDescriptions("한국어", null, "中文", "日本語"));
        assertValidationFailed(withDescriptions("한국어", "English", null, "日本語"));
        assertValidationFailed(withDescriptions("한국어", "English", "中文", null));
        assertValidationFailed(withDescriptions(null, null, "中文", null));
    }

    @Test
    void requiresColorAndSizeTranslationsForExactlyTheActiveOptionalLocales() {
        assertThatCode(() -> GoodsInputValidator.validate(optionsInput("zh-Hans", true, true)))
            .doesNotThrowAnyException();
        assertValidationFailed(optionsInput("zh-Hans", false, true));
        assertValidationFailed(optionsInput("zh-Hans", true, false));
        assertValidationFailed(optionsInput(null, true, false));
        assertValidationFailed(optionsInput(null, false, true));
        assertThatCode(() -> GoodsInputValidator.validate(optionsInput("ja", true, true)))
            .doesNotThrowAnyException();
        assertValidationFailed(optionsInput("ja", false, true));
        assertValidationFailed(optionsInput("ja", true, false));
    }

    @Test
    void rejectsUnknownColorOrSizeLocales() {
        GoodsInput input = optionsInput(null, false, false);
        input.colors().getFirst().translations().put("fr", new GoodsInput.ColorTranslationInput("Noir"));
        assertValidationFailed(input);

        input = optionsInput(null, false, false);
        input.sizes().getFirst().translations().put("fr", new GoodsInput.SizeTranslationInput("M"));
        assertValidationFailed(input);
    }

    @Test
    void rejectsNullOptionItemsAndIdentifiersWithoutThrowingNullPointerException() {
        GoodsInput valid = optionsInput(null, false, false);
        assertValidationFailed(replaceOptions(valid, Arrays.asList((GoodsInput.OptionInput) null)));
        assertValidationFailed(replaceColors(valid, Arrays.asList((GoodsInput.ColorInput) null)));
        assertValidationFailed(replaceSizes(valid, Arrays.asList((GoodsInput.SizeInput) null)));
        assertValidationFailed(replaceColors(valid, List.of(new GoodsInput.ColorInput(null, colorTranslations(null)))));
        assertValidationFailed(replaceSizes(valid, List.of(new GoodsInput.SizeInput(null, sizeTranslations(null)))));
        assertValidationFailed(replaceOptions(valid, List.of(new GoodsInput.OptionInput(null, valid.sizes().getFirst().id()))));
        assertValidationFailed(replaceOptions(valid, List.of(new GoodsInput.OptionInput(valid.colors().getFirst().id(), null))));
    }

    private static void assertValidationFailed(GoodsInput input) {
        assertThatThrownBy(() -> GoodsInputValidator.validate(input))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.status().value()).isEqualTo(422));
    }

    private static GoodsInput input(
        List<GoodsInput.ImageInput> images,
        String optionalLocale,
        GoodsInput.TranslationInput optionalTranslation
    ) {
        Map<String, GoodsInput.TranslationInput> translations = new LinkedHashMap<>();
        translations.put("ko", translation("상품"));
        translations.put("en", translation("Goods"));
        translations.put("zh-Hans", null);
        translations.put("ja", null);
        if (optionalLocale != null) {
            translations.put(optionalLocale, optionalTranslation);
        }
        return new GoodsInput(
            "SINGLE",
            translations,
            new GoodsInput.PriceInput(1000, "KRW"),
            images,
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static GoodsInput.TranslationInput translation(String name) {
        return new GoodsInput.TranslationInput(name, null);
    }

    private static GoodsInput withDescriptions(String ko, String en, String zhHans, String ja) {
        Map<String, GoodsInput.TranslationInput> translations = new LinkedHashMap<>();
        translations.put("ko", new GoodsInput.TranslationInput("상품", ko));
        translations.put("en", new GoodsInput.TranslationInput("Goods", en));
        translations.put("zh-Hans", new GoodsInput.TranslationInput("商品", zhHans));
        translations.put("ja", new GoodsInput.TranslationInput("商品", ja));
        Map<String, String> alt = alt();
        alt.put("zh-Hans", "商品图片");
        alt.put("ja", "商品画像");
        return new GoodsInput(
            "SINGLE", translations, new GoodsInput.PriceInput(1000, "KRW"),
            List.of(image(UUID.randomUUID(), alt)), List.of(), List.of(), List.of()
        );
    }

    private static GoodsInput optionsInput(String optionalLocale, boolean colorOptional, boolean sizeOptional) {
        UUID colorId = UUID.randomUUID();
        UUID sizeId = UUID.randomUUID();
        Map<String, GoodsInput.TranslationInput> translations = new LinkedHashMap<>();
        translations.put("ko", translation("상품"));
        translations.put("en", translation("Goods"));
        translations.put("zh-Hans", null);
        translations.put("ja", null);
        Map<String, String> imageAlt = alt();
        if (optionalLocale != null) {
            translations.put(optionalLocale, translation("Localized product"));
            imageAlt.put(optionalLocale, "Localized image");
        }
        return new GoodsInput(
            "OPTIONS",
            translations,
            new GoodsInput.PriceInput(1000, "KRW"),
            List.of(image(UUID.randomUUID(), imageAlt)),
            List.of(new GoodsInput.ColorInput(
                colorId,
                colorTranslations(colorOptional ? (optionalLocale == null ? "zh-Hans" : optionalLocale) : null)
            )),
            List.of(new GoodsInput.SizeInput(
                sizeId,
                sizeTranslations(sizeOptional ? (optionalLocale == null ? "zh-Hans" : optionalLocale) : null)
            )),
            List.of(new GoodsInput.OptionInput(colorId, sizeId))
        );
    }

    private static Map<String, GoodsInput.ColorTranslationInput> colorTranslations(String optionalLocale) {
        Map<String, GoodsInput.ColorTranslationInput> translations = new LinkedHashMap<>();
        translations.put("ko", new GoodsInput.ColorTranslationInput("검정"));
        translations.put("en", new GoodsInput.ColorTranslationInput("Black"));
        translations.put("zh-Hans", null);
        translations.put("ja", null);
        if (optionalLocale != null) {
            translations.put(optionalLocale, new GoodsInput.ColorTranslationInput("Localized color"));
        }
        return translations;
    }

    private static Map<String, GoodsInput.SizeTranslationInput> sizeTranslations(String optionalLocale) {
        Map<String, GoodsInput.SizeTranslationInput> translations = new LinkedHashMap<>();
        translations.put("ko", new GoodsInput.SizeTranslationInput("중간"));
        translations.put("en", new GoodsInput.SizeTranslationInput("Medium"));
        translations.put("zh-Hans", null);
        translations.put("ja", null);
        if (optionalLocale != null) {
            translations.put(optionalLocale, new GoodsInput.SizeTranslationInput("Localized size"));
        }
        return translations;
    }

    private static GoodsInput replaceColors(GoodsInput input, List<GoodsInput.ColorInput> colors) {
        return new GoodsInput(input.optionMode(), input.translations(), input.price(), input.images(), colors, input.sizes(), input.options());
    }

    private static GoodsInput replaceSizes(GoodsInput input, List<GoodsInput.SizeInput> sizes) {
        return new GoodsInput(input.optionMode(), input.translations(), input.price(), input.images(), input.colors(), sizes, input.options());
    }

    private static GoodsInput replaceOptions(GoodsInput input, List<GoodsInput.OptionInput> options) {
        return new GoodsInput(input.optionMode(), input.translations(), input.price(), input.images(), input.colors(), input.sizes(), options);
    }

    private static GoodsInput.ImageInput image() {
        return image(UUID.randomUUID(), alt());
    }

    private static GoodsInput.ImageInput image(UUID mediaId, Map<String, String> alt) {
        return new GoodsInput.ImageInput(mediaId, alt);
    }

    private static Map<String, String> alt() {
        Map<String, String> alt = new LinkedHashMap<>();
        alt.put("ko", "상품 이미지");
        alt.put("en", "Product image");
        alt.put("zh-Hans", null);
        alt.put("ja", null);
        return alt;
    }
}
