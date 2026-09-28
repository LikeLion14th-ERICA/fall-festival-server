package dev.espero.festival.web;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;

record ArtistHypedBatchRequest(UUID festivalId, UUID batchId, int delta) {
    static ArtistHypedBatchRequest parse(Map<String, Object> body) {
        if (body == null) throw invalid();
        if (body.isEmpty()) return null; // Legacy single-click request.
        if (!body.keySet().equals(Set.of("festivalId", "batchId", "delta"))
            || !(body.get("delta") instanceof Number number)) throw invalid();
        double delta = number.doubleValue();
        // JSON Schema integers also include whole-valued decimal/exponent forms (1.0, 1e0).
        if (!Double.isFinite(delta) || delta < 1 || delta > 20 || delta != Math.rint(delta)) throw invalid();
        return new ArtistHypedBatchRequest(uuid(body.get("festivalId")), uuid(body.get("batchId")), (int) delta);
    }

    private static UUID uuid(Object value) {
        if (!(value instanceof String text)) throw invalid();
        try {
            UUID uuid = UUID.fromString(text);
            if (!uuid.toString().equalsIgnoreCase(text)) throw invalid();
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "요청 필드를 확인해 주세요.", false);
    }
}
