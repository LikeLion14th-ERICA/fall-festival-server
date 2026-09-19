package dev.espero.festival.web;

import dev.espero.festival.domain.NoticeCategory;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * ko/en presence and non-blank title/body are structural requirements the
 * mock's JSON schema enforces for free; here they need an explicit check.
 * Link labels are a cross-object invariant that could never be schema-only:
 * a label must exist exactly for the locales the notice has a translation
 * for.
 */
final class NoticeInputValidator {

    private static final Set<String> OPTIONAL_LOCALES = Set.of("zh-Hans", "ja");
    private static final Set<String> KNOWN_LOCALES = Set.of("ko", "en", "zh-Hans", "ja");
    private static final Pattern TEMPLATE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private NoticeInputValidator() {}

    static NoticeCategory validate(NoticeInput input) {
        if (input == null) {
            throw validationFailed();
        }
        NoticeCategory category = category(input.type());
        Map<String, NoticeInput.TranslationInput> translations = input.translations();
        if (translations == null || !KNOWN_LOCALES.containsAll(translations.keySet())) {
            throw validationFailed();
        }
        if (!filled(translations.get("ko"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "KOREAN_REQUIRED", "한국어 제목·본문은 필수입니다.", false);
        }
        if (!filled(translations.get("en"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ENGLISH_REQUIRED", "영어 제목·본문은 필수입니다.", false);
        }
        for (String locale : OPTIONAL_LOCALES) {
            if (translations.containsKey(locale) && !filled(translations.get(locale))) {
                throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "TRANSLATION_CONTENT_REQUIRED",
                    "입력한 번역은 제목과 본문이 필요합니다.",
                    false
                );
            }
        }
        if (input.links() == null) {
            throw validationFailed();
        }
        for (NoticeInput.LinkInput link : input.links()) {
            validateLink(link, translations.keySet());
        }
        // Whether the template exists is checked against the store; an id that
        // cannot be a template id is reported the same way.
        if (input.templateId() != null && !TEMPLATE_ID.matcher(input.templateId()).matches()) {
            throw templateNotFound();
        }
        return category;
    }

    private static void validateLink(NoticeInput.LinkInput link, Set<String> presentLocales) {
        if (link == null
            || link.url() == null
            || !link.url().matches("^https://\\S+$")
            || link.labels() == null
            || !link.labels().keySet().equals(KNOWN_LOCALES)) {
            throw validationFailed();
        }
        for (String locale : KNOWN_LOCALES) {
            boolean hasBody = presentLocales.contains(locale);
            String label = link.labels().get(locale);
            if (hasBody && (label == null || label.isBlank())) {
                throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "LINK_LABEL_REQUIRED",
                    "본문이 있는 언어의 링크 label이 필요합니다.",
                    false
                );
            }
            if (!hasBody && label != null) {
                throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "LINK_LABEL_UNEXPECTED",
                    "본문이 없는 언어의 링크 label은 null이어야 합니다.",
                    false
                );
            }
        }
    }

    private static boolean filled(NoticeInput.TranslationInput translation) {
        return translation != null
            && translation.title() != null && !translation.title().isBlank()
            && translation.body() != null && !translation.body().isBlank();
    }

    private static NoticeCategory category(String type) {
        try {
            return NoticeCategory.valueOf(type);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw validationFailed();
        }
    }

    static ApiException templateNotFound() {
        return new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "TEMPLATE_NOT_FOUND",
            "등록된 공지 템플릿이 없습니다.",
            false
        );
    }

    private static ApiException validationFailed() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "요청 필드를 확인해 주세요.", false);
    }
}
