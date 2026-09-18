package dev.espero.festival.idempotency;

/** A successful response retained for a completed idempotency key. */
public record IdempotencyResponse(int status, String contentType, String body) {

    private static final int MAX_BODY_LENGTH = 262_144;

    public IdempotencyResponse {
        if (status < 200 || status >= 300) {
            throw new IllegalArgumentException("Only successful responses can complete an idempotency key");
        }
        if (body == null && contentType != null) {
            throw new IllegalArgumentException("A response content type requires a response body");
        }
        if (body != null && (contentType == null || contentType.isBlank() || contentType.length() > 128)) {
            throw new IllegalArgumentException("A response body requires a valid content type");
        }
        if (body != null && body.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("Idempotent response body exceeds 256 KiB");
        }
    }

    public static IdempotencyResponse noContent() {
        return new IdempotencyResponse(204, null, null);
    }
}
