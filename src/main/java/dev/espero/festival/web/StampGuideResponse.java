package dev.espero.festival.web;

import java.time.LocalDate;
import java.util.List;

public record StampGuideResponse(
    String title,
    List<LocalDate> dates,
    List<String> instructions,
    Reward reward,
    int dailyLimit,
    String timezone,
    String qrValue
) {

    public record Reward(String name, String locationText, String hoursText, String notice) {}
}
