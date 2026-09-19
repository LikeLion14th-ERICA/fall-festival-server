package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
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
