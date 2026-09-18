package dev.espero.festival.web;

import java.time.LocalDate;
import java.util.List;

public record NoticesResponse(List<NoticeResponse> items, List<String> visibleIds, LocalDate asOfDate) {}
