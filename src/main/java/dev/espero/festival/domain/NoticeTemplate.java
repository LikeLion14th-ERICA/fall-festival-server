package dev.espero.festival.domain;

import java.util.Map;

/**
 * Prepared notice text an administrator can load into the notice form
 * (ADM-NOTICE-004). {@code translations} holds only the locales the template
 * has; Korean is always present.
 */
public record NoticeTemplate(
    String id,
    String name,
    Map<String, NoticeTranslation> translations
) {

    public NoticeTemplate {
        translations = Map.copyOf(translations);
    }
}
