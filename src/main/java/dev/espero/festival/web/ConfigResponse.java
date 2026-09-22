package dev.espero.festival.web;

import java.time.LocalDate;
import java.util.List;

/** The API v2 {@code Config} payload. */
public record ConfigResponse(Festival festival, List<Language> languages, Links links) {

    public record Festival(String id, String title, List<LocalDate> dates, LocalDate defaultDate) {}

    public record Language(String code, String label) {}

    public record Links(Link universityNotices, Link faq, List<Channel> officialChannels) {}

    public record Link(String label, String url, String target) {}

    public record Channel(String id, String label, String url, String target, String iconKey) {}
}
