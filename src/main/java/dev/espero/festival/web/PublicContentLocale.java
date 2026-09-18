package dev.espero.festival.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * Validates the public-content locale policy while Korean is the only
 * published locale. A known but incomplete locale is distinguishable from an
 * unknown query value so clients can keep their current Korean content.
 */
final class PublicContentLocale {

    static final String KOREAN = "ko";
    private static final Set<String> KNOWN_LOCALES = Set.of("ko", "en", "zh-Hans", "ja");

    /**
     * Published locales in display order with their native names. A locale is
     * added here only once all of its public content is complete.
     */
    static final List<Map.Entry<String, String>> PUBLISHED_LANGUAGES = List.of(Map.entry(KOREAN, "한국어"));

    private PublicContentLocale() {}

    static String requirePublishedLocale(HttpServletRequest request) {
        String locale = request.getParameter("locale");
        if (locale == null || locale.equals(KOREAN)) {
            return KOREAN;
        }
        if (KNOWN_LOCALES.contains(locale)) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "LOCALE_NOT_READY",
                "아직 준비되지 않은 언어입니다.",
                false
            );
        }
        throw invalidQuery();
    }

    static ApiException invalidQuery() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "요청 파라미터를 확인해 주세요.", false);
    }
}
