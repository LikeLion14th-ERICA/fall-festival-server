package dev.espero.festival.idempotency;

/** Raised before business work if an expired lease was claimed by a retry. */
final class IdempotencyOwnershipLostException extends RuntimeException {

    IdempotencyOwnershipLostException() {
        super("The idempotency lease is no longer owned by this request");
    }
}
