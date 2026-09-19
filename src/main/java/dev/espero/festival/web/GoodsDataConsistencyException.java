package dev.espero.festival.web;

/** Signals persisted goods content that violates a read-side invariant. */
final class GoodsDataConsistencyException extends RuntimeException {

    GoodsDataConsistencyException(String message) {
        super(message);
    }
}
