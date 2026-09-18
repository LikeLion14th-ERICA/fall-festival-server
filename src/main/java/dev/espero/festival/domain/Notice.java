package dev.espero.festival.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code translations} only contains the locales the notice actually has;
 * ko and en are always present for notices created under the current rules,
 * but legacy rows may lack en. */
public record Notice(
    UUID id,
    UUID festivalId,
    NoticeCategory category,
    Map<String, NoticeTranslation> translations,
    List<NoticeLink> links,
    Instant createdAt,
    Instant updatedAt
) {}
