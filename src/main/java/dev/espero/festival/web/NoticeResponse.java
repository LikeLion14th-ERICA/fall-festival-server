package dev.espero.festival.web;

import java.time.OffsetDateTime;
import java.util.List;

public record NoticeResponse(
    String id,
    String type,
    String contentLocale,
    String title,
    String body,
    List<NoticeLinkResponse> links,
    OffsetDateTime createdAt
) {}
