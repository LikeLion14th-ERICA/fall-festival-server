package dev.espero.festival.domain;

import java.util.Map;
import java.util.UUID;

/** {@code labels} only contains the locales that have a label row; a missing
 * key means no label was stored for that locale (matching the DB, which only
 * stores a label row for locales the notice actually has a translation for). */
public record NoticeLink(UUID id, String url, Map<String, String> labels) {}
