package dev.espero.festival.idempotency;

/** Distinguishes a newly executed mutation from a stored successful replay. */
public record IdempotencyExecution(IdempotencyResponse response, boolean replayed) {}
