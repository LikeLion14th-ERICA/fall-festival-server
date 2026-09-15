package dev.espero.festival.web;

import java.time.OffsetDateTime;

public record ApiMeta(
    String requestId,
    OffsetDateTime serverTime,
    String timezone,
    String festivalId,
    long revision,
    String locale,
    boolean mock
) {}
