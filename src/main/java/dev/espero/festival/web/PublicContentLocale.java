package dev.espero.festival.web;

import dev.espero.festival.domain.CatalogSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Validates the public-content locale policy. Korean is always published;
 * other locales are published only when {@link CatalogSnapshotProvider} found
 * their content complete. A known but unpublished locale is distinguishable
 * from an unknown query value so clients can keep their current content.
 */
final class PublicContentLocale {

    static final String KOREAN = "ko";

    /** Known locales with their native names, in the order they are offered. */
    private static final Map<String, String> NATIVE_NAMES = Map.of(
        "ko", "한국어",
        "en", "English",
        "zh-Hans", "中文",
        "ja", "日本語"
    );

    private PublicContentLocale() {}

    /**
     * Parses {@code PUBLIC_LOCALES}, a comma-separated list such as
     * {@code ko,en,zh-Hans}. Korean is always first whether listed or not. A
     * locale needs approved server messages (crowding) to be listed; Japanese
     * has none yet.
     */
    static List<String> parseConfigured(String configured) {
        List<String> locales = new ArrayList<>(List.of(KOREAN));
        if (configured == null) {
            return List.copyOf(locales);
        }
        for (String value : configured.split(",")) {
            String locale = value.strip();
            if (locale.isEmpty() || locale.equals(KOREAN)) {
                continue;
            }
            if (!NATIVE_NAMES.containsKey(locale)) {
                throw new IllegalStateException("PUBLIC_LOCALES contains an unsupported locale: " + locale);
            }
            if (!CrowdingMessages.supports(locale)) {
                throw new IllegalStateException("PUBLIC_LOCALES contains a locale without approved server messages: " + locale);
            }
            if (locales.contains(locale)) {
                throw new IllegalStateException("PUBLIC_LOCALES lists a locale twice: " + locale);
            }
            locales.add(locale);
        }
        return List.copyOf(locales);
    }

    static String requirePublishedLocale(HttpServletRequest request, Collection<String> published) {
        String locale = request.getParameter("locale");
        if (locale == null || locale.equals(KOREAN)) {
            return KOREAN;
        }
        if (published.contains(locale)) {
            return locale;
        }
        if (NATIVE_NAMES.containsKey(locale)) {
            throw localeNotReady();
        }
        throw invalidQuery();
    }

    /** The snapshot in an already validated locale. */
    static CatalogSnapshot snapshot(CatalogSnapshotProvider snapshots, String locale) {
        return locale.equals(KOREAN) ? snapshots.required() : snapshots.required(locale);
    }

    static String nativeName(String locale) {
        return NATIVE_NAMES.get(locale);
    }

    static ApiException localeNotReady() {
        return new ApiException(
            HttpStatus.BAD_REQUEST,
            "LOCALE_NOT_READY",
            "아직 준비되지 않은 언어입니다.",
            false
        );
    }

    static ApiException invalidQuery() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "요청 파라미터를 확인해 주세요.", false);
    }
}
