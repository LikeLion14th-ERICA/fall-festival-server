package dev.espero.festival.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record StampGuide(
    String title,
    List<LocalDate> dates,
    List<String> instructions,
    String rewardName,
    String rewardLocationText,
    String rewardHoursText,
    String rewardNotice,
    String qrValue,
    Instant updatedAt
) {}
