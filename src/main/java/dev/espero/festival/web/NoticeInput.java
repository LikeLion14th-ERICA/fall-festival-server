package dev.espero.festival.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record NoticeInput(
    String type,
    Map<String, TranslationInput> translations,
    List<LinkInput> links,
    UUID templateId
) {

    public record TranslationInput(String title, String body) {}

    public record LinkInput(String url, Map<String, String> labels) {}
}
