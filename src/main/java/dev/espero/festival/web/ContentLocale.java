package dev.espero.festival.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Shared locale policy for public notice and goods representations. */
final class ContentLocale {

    private ContentLocale() {}

    static String requestedLocale(HttpServletRequest request, CatalogSnapshotProvider snapshots) {
        return PublicContentLocale.requirePublishedLocale(request, snapshots.publishedLocales());
    }

    static boolean hasTranslation(Map<String, ?> translations, String locale) {
        return translations != null && translations.get(locale) != null;
    }
}
