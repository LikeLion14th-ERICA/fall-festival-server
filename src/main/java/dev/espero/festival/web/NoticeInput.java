package dev.espero.festival.web;

import java.util.List;
import java.util.Map;

public record NoticeInput(
    String type,
    Map<String, TranslationInput> translations,
    List<LinkInput> links,
    String templateId
) {

    public record TranslationInput(String title, String body) {}

    public record LinkInput(String url, Map<String, String> labels) {}
}
