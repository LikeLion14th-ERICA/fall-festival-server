package dev.espero.festival.web;

import dev.espero.festival.domain.GoodsOptionMode;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * SINGLE products carry no colors/sizes/options at all. OPTIONS products must
 * register at least one color, size and option, every option must reference a
 * registered color/size, and every registered color/size must be used by at
 * least one option (no unused registrations, no auto cross-product).
 */
final class GoodsInputValidator {

    private static final Set<String> OPTIONAL_LOCALES = Set.of("zh-Hans", "ja");
    private static final Set<String> KNOWN_LOCALES = Set.of("ko", "en", "zh-Hans", "ja");

    private GoodsInputValidator() {}

    static GoodsOptionMode validate(GoodsInput input) {
        if (input == null || input.translations() == null || !KNOWN_LOCALES.containsAll(input.translations().keySet())) {
            throw validationFailed();
        }
        GoodsOptionMode optionMode = optionMode(input.optionMode());
        if (!filled(input.translations().get("ko"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "KOREAN_REQUIRED", "한국어 상품명은 필수입니다.", false);
        }
        if (!filled(input.translations().get("en"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ENGLISH_REQUIRED", "영어 상품명은 필수입니다.", false);
        }
        for (String locale : OPTIONAL_LOCALES) {
            GoodsInput.TranslationInput translation = input.translations().get(locale);
            if (translation != null && !filled(translation)) {
                throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "TRANSLATION_CONTENT_REQUIRED", "입력한 번역은 상품명이 필요합니다.", false
                );
            }
        }
        if (input.price() == null || input.price().amount() < 0 || !"KRW".equals(input.price().currency())) {
            throw validationFailed();
        }
        validateImages(input);
        if (input.colors() == null || input.sizes() == null || input.options() == null) {
            throw validationFailed();
        }
        if (optionMode == GoodsOptionMode.SINGLE) {
            if (!input.colors().isEmpty() || !input.sizes().isEmpty() || !input.options().isEmpty()) {
                throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "SINGLE 상품은 색상·사이즈·조합을 등록할 수 없습니다.", false
                );
            }
            return optionMode;
        }
        if (input.colors().isEmpty() || input.sizes().isEmpty() || input.options().isEmpty()) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_CONFIGURATION", "OPTIONS 상품은 색상·사이즈·조합이 모두 필요합니다.", false
            );
        }
        requireUniqueIds(input.colors().stream().map(GoodsInput.ColorInput::id).toList());
        requireUniqueIds(input.sizes().stream().map(GoodsInput.SizeInput::id).toList());
        for (GoodsInput.ColorInput color : input.colors()) {
            requireNameOrLabel(color.translations(), "color");
        }
        for (GoodsInput.SizeInput size : input.sizes()) {
            requireNameOrLabel(size.translations(), "size");
        }
        Set<String> optionKeys = new HashSet<>();
        Set<java.util.UUID> knownColors = input.colors().stream().map(GoodsInput.ColorInput::id).collect(java.util.stream.Collectors.toSet());
        Set<java.util.UUID> knownSizes = input.sizes().stream().map(GoodsInput.SizeInput::id).collect(java.util.stream.Collectors.toSet());
        Set<java.util.UUID> usedColors = new HashSet<>();
        Set<java.util.UUID> usedSizes = new HashSet<>();
        for (GoodsInput.OptionInput option : input.options()) {
            if (!optionKeys.add(option.colorId() + "/" + option.sizeId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DUPLICATE_OPTION", "판매 조합이 중복됩니다.", false);
            }
            if (!knownColors.contains(option.colorId()) || !knownSizes.contains(option.sizeId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OPTION", "등록된 색상·사이즈만 조합할 수 있습니다.", false);
            }
            usedColors.add(option.colorId());
            usedSizes.add(option.sizeId());
        }
        if (!usedColors.containsAll(knownColors) || !usedSizes.containsAll(knownSizes)) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "UNUSED_OPTION", "등록한 모든 색상·사이즈는 조합에서 사용돼야 합니다.", false
            );
        }
        return optionMode;
    }

    private static void validateImages(GoodsInput input) {
        if (input.images() == null || input.images().isEmpty() || input.images().size() > 2) {
            throw validationFailed();
        }
        Set<java.util.UUID> mediaIds = new HashSet<>();
        for (GoodsInput.ImageInput image : input.images()) {
            if (image == null || image.mediaId() == null || image.alt() == null
                || !KNOWN_LOCALES.containsAll(image.alt().keySet())) {
                throw validationFailed();
            }
            if (!mediaIds.add(image.mediaId())) {
                throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DUPLICATE_MEDIA",
                    "상품 이미지가 중복됩니다.",
                    false
                );
            }
            if (!hasText(image.alt().get("ko"))) {
                throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "KOREAN_REQUIRED",
                    "상품 이미지의 한국어 대체 텍스트는 필수입니다.",
                    false
                );
            }
            if (!hasText(image.alt().get("en"))) {
                throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "ENGLISH_REQUIRED",
                    "상품 이미지의 영어 대체 텍스트는 필수입니다.",
                    false
                );
            }
            for (String locale : OPTIONAL_LOCALES) {
                boolean productHasTranslation = input.translations().get(locale) != null;
                String alt = image.alt().get(locale);
                if (alt != null && !hasText(alt)) {
                    throw validationFailed();
                }
                if (productHasTranslation != hasText(alt)) {
                    throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "TRANSLATION_CONTENT_REQUIRED",
                        "상품 번역과 이미지 대체 텍스트의 언어를 일치시켜 주세요.",
                        false
                    );
                }
            }
        }
    }

    private static void requireUniqueIds(java.util.List<java.util.UUID> ids) {
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DUPLICATE_OPTION", "색상·사이즈 ID가 중복됩니다.", false);
        }
    }

    private static void requireNameOrLabel(Object translations, String kind) {
        if (translations == null) {
            throw validationFailed();
        }
        java.util.Map<?, ?> map = (java.util.Map<?, ?>) translations;
        Object ko = map.get("ko");
        Object en = map.get("en");
        if (!hasText(ko, kind)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "KOREAN_REQUIRED", "색상·사이즈의 한국어 이름은 필수입니다.", false);
        }
        if (!hasText(en, kind)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ENGLISH_REQUIRED", "색상·사이즈의 영어 이름은 필수입니다.", false);
        }
    }

    private static boolean hasText(Object translation, String kind) {
        if (translation instanceof GoodsInput.ColorTranslationInput color) {
            return color.name() != null && !color.name().isBlank();
        }
        if (translation instanceof GoodsInput.SizeTranslationInput size) {
            return size.label() != null && !size.label().isBlank();
        }
        return false;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean filled(GoodsInput.TranslationInput translation) {
        return translation != null
            && translation.name() != null && !translation.name().isBlank()
            && (translation.description() == null || !translation.description().isBlank());
    }

    private static GoodsOptionMode optionMode(String value) {
        try {
            return GoodsOptionMode.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw validationFailed();
        }
    }

    private static ApiException validationFailed() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "요청 필드를 확인해 주세요.", false);
    }
}
