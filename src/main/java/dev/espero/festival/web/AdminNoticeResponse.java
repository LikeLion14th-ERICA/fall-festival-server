package dev.espero.festival.web;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record AdminNoticeResponse(
    String id,
    String type,
    Map<String, AdminNoticeTranslationResponse> translations,
    List<AdminNoticeLinkResponse> links,
    String templateId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
