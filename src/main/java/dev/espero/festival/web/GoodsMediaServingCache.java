package dev.espero.festival.web;

import dev.espero.festival.media.MediaVariant;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Keeps goods image bytes in memory for repeated requests. Stored variants
 * never change once finalized, so their bytes can be kept as is; whether an
 * image may still be served is checked on every request by the controller.
 */
@Component
@Profile("db")
@ConditionalOnProperty(prefix = "festival.media", name = "storage-root")
public class GoodsMediaServingCache {

    // A single variant larger than this is streamed from disk instead of being kept.
    private static final long MAX_ENTRY_BYTES = 4L * 1024 * 1024;

    private final long maxBytes;
    private final LinkedHashMap<Key, byte[]> bytes = new LinkedHashMap<>(64, 0.75f, true);
    private long cachedBytes;

    public GoodsMediaServingCache(@Value("${festival.media.serving-cache-max-bytes:33554432}") long maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("festival.media.serving-cache-max-bytes must not be negative");
        }
        this.maxBytes = maxBytes;
    }

    boolean fits(long length) {
        return length <= MAX_ENTRY_BYTES && length <= maxBytes;
    }

    synchronized Optional<byte[]> bytes(UUID mediaId, MediaVariant variant) {
        return Optional.ofNullable(bytes.get(new Key(mediaId, variant)));
    }

    /** Least recently served variants are dropped first to stay within the byte budget. */
    synchronized void rememberBytes(UUID mediaId, MediaVariant variant, byte[] content) {
        if (!fits(content.length)) {
            return;
        }
        byte[] previous = bytes.put(new Key(mediaId, variant), content);
        cachedBytes += content.length - (previous == null ? 0 : previous.length);
        var eldest = bytes.entrySet().iterator();
        while (cachedBytes > maxBytes && eldest.hasNext()) {
            cachedBytes -= eldest.next().getValue().length;
            eldest.remove();
        }
    }

    synchronized long cachedBytes() {
        return cachedBytes;
    }

    private record Key(UUID mediaId, MediaVariant variant) {}
}
