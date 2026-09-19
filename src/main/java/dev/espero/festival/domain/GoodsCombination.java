package dev.espero.festival.domain;

import java.time.Instant;
import java.util.UUID;

/** {@code colorId}/{@code sizeId} are both null for a SINGLE-mode product's
 * one combination, both set for an OPTIONS-mode combination. */
public record GoodsCombination(UUID id, UUID colorId, UUID sizeId, GoodsAvailability availability, Instant updatedAt) {}
