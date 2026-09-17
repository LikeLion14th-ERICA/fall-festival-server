package dev.espero.festival.domain;

import java.time.Instant;

/** The operator's last saved crowding level for one festival operating day. */
public record CrowdingRecord(String level, Instant updatedAt) {}
