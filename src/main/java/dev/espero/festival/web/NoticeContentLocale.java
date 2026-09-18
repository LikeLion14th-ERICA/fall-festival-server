package dev.espero.festival.web;

import dev.espero.festival.domain.NoticeTranslation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;

/**
 * Notice resolves its own per-item content locale instead of using the
 * site-wide "language not launched yet" gate {@link PublicContentLocale}
 * enforces for the rest of the catalog (map/booth/performance).
 */
final class NoticeContentLocale {

    private static final Set<String> KNOWN_LOCALES = Set.of("ko", "en", "zh-Hans", "ja");

    private NoticeContentLocale() {}

    static String requestedLocale(HttpServletRequest request) {
        String locale = request.getParameter("locale");
        if (locale == null) {
            return "ko";
        }
        if (!KNOWN_LOCALES.contains(locale)) {
            throw PublicContentLocale.invalidQuery();
        }
        return locale;
    }

    /**
     * ko always resolves to ko. en falls back to ko for legacy notices saved
     * before English became required. zh-Hans/ja use their own translation
     * only when this notice has one, otherwise en, then ko. No per-field
     * mixed fallback: title, body and link labels always come from the same
     * resolved locale.
     */
    static String resolve(Map<String, NoticeTranslation> translations, String requested) {
        if ("ko".equals(requested)) {
            return "ko";
        }
        if ("en".equals(requested)) {
            return translations.containsKey("en") ? "en" : "ko";
        }
        if (translations.containsKey(requested)) {
            return requested;
        }
        return translations.containsKey("en") ? "en" : "ko";
    }
}
