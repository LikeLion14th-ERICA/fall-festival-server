package dev.espero.festival.idempotency;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The non-secret request identity stored as hashes for an administrator mutation.
 * Resource identifiers and request bodies remain in process memory only.
 */
public record IdempotencyRequest(
    UUID adminId,
    String method,
    String route,
    String resourceId,
    String key,
    CanonicalPayload payload
) {

    public IdempotencyRequest {
        Objects.requireNonNull(adminId, "Administrator id is required");
        method = requireMethod(method);
        route = requireRoute(route);
        resourceId = normalizeResourceId(resourceId);
        if (!IdempotencyKeyPolicy.isValid(key)) {
            throw new IllegalArgumentException("Idempotency key must be 1 to 128 URL-safe characters");
        }
        Objects.requireNonNull(payload, "Canonical payload is required");
    }

    String scopeHash() {
        return Sha256.parts("admin-idempotency-scope-v1", adminId.toString(), method, route, resourceId);
    }

    String keyHash() {
        return Sha256.parts("admin-idempotency-key-v1", key);
    }

    String fingerprint() {
        return Sha256.parts("admin-idempotency-fingerprint-v1", resourceId, payload.value());
    }

    private static String requireMethod(String value) {
        if (value == null || !value.matches("[A-Za-z]+")) {
            throw new IllegalArgumentException("HTTP method is invalid");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private static String requireRoute(String value) {
        if (value == null || value.isBlank() || !value.startsWith("/") || value.length() > 512) {
            throw new IllegalArgumentException("Route must be an absolute path up to 512 characters");
        }
        return value;
    }

    private static String normalizeResourceId(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException("Resource id must be null or 1 to 512 characters");
        }
        return value;
    }
}
