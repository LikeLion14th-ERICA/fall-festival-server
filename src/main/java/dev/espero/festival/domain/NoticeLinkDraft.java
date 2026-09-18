package dev.espero.festival.domain;

import java.util.Map;

/** A link as submitted by an admin write, before the store assigns it an id. */
public record NoticeLinkDraft(String url, Map<String, String> labels) {}
