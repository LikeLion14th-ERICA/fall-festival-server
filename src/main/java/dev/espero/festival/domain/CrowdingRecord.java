package dev.espero.festival.domain;

import java.time.Instant;

public record CrowdingRecord(String level, Instant updatedAt) {}
