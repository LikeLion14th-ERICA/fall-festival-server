package dev.espero.festival.idempotency;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A deterministic representation of an already validated request body.
 *
 * <p>The representation is only used to calculate an idempotency fingerprint.
 * It deliberately does not retain the raw request body after the request has
 * been handled. Map keys are sorted, collection order is preserved, and values
 * are type-tagged so distinct values cannot collide through delimiters.</p>
 */
public final class CanonicalPayload {

    private static final CanonicalPayload EMPTY = new CanonicalPayload("object[];");

    private final String value;

    private CanonicalPayload(String value) {
        this.value = value;
    }

    public static CanonicalPayload empty() {
        return EMPTY;
    }

    public static CanonicalPayload from(Map<String, ?> fields) {
        Objects.requireNonNull(fields, "Payload fields are required");
        StringBuilder canonical = new StringBuilder();
        append(canonical, fields);
        return new CanonicalPayload(canonical.toString());
    }

    String value() {
        return value;
    }

    private static void append(StringBuilder target, Object value) {
        if (value == null) {
            target.append("null;");
            return;
        }
        if (value instanceof String text) {
            appendPart(target, "string", text);
            return;
        }
        if (value instanceof Character character) {
            appendPart(target, "string", character.toString());
            return;
        }
        if (value instanceof Boolean bool) {
            target.append(bool ? "boolean:true;" : "boolean:false;");
            return;
        }
        if (value instanceof UUID uuid) {
            appendPart(target, "uuid", uuid.toString());
            return;
        }
        if (value instanceof Enum<?> enumValue) {
            appendPart(target, "enum", enumValue.getDeclaringClass().getName() + ":" + enumValue.name());
            return;
        }
        if (value instanceof Number number) {
            appendPart(target, "number", normalizedNumber(number));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            appendMap(target, map);
            return;
        }
        if (value instanceof Collection<?> collection) {
            appendCollection(target, collection);
            return;
        }
        if (value instanceof Object[] array) {
            appendCollection(target, List.of(array));
            return;
        }
        throw new IllegalArgumentException("Unsupported canonical payload value: " + value.getClass().getName());
    }

    private static void appendMap(StringBuilder target, Map<?, ?> map) {
        List<Map.Entry<String, ?>> entries = new ArrayList<>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException("Canonical payload map keys must be non-blank strings");
            }
            entries.add(Map.entry(key, entry.getValue()));
        }
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        target.append("object[");
        for (Map.Entry<String, ?> entry : entries) {
            appendPart(target, "key", entry.getKey());
            append(target, entry.getValue());
        }
        target.append("];");
    }

    private static void appendCollection(StringBuilder target, Collection<?> collection) {
        target.append("array[");
        for (Object item : collection) {
            append(target, item);
        }
        target.append("];");
    }

    private static void appendPart(StringBuilder target, String type, String value) {
        target.append(type).append(':').append(value.length()).append(':').append(value).append(';');
    }

    private static String normalizedNumber(Number number) {
        if (number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long
            || number instanceof BigInteger) {
            return number.toString();
        }
        BigDecimal decimal;
        if (number instanceof BigDecimal bigDecimal) {
            decimal = bigDecimal;
        } else if (number instanceof Float floatValue) {
            if (!Float.isFinite(floatValue)) {
                throw new IllegalArgumentException("Canonical payload numbers must be finite");
            }
            decimal = new BigDecimal(floatValue.toString());
        } else if (number instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue)) {
                throw new IllegalArgumentException("Canonical payload numbers must be finite");
            }
            decimal = new BigDecimal(doubleValue.toString());
        } else {
            throw new IllegalArgumentException("Unsupported canonical payload number: " + number.getClass().getName());
        }
        if (decimal.signum() == 0) {
            return "0";
        }
        return decimal.stripTrailingZeros().toPlainString();
    }
}
