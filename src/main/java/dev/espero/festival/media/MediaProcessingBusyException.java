package dev.espero.festival.media;

import java.time.Duration;

/** Immediate back-pressure signal; HTTP mapping belongs to the upload orchestration slice. */
public final class MediaProcessingBusyException extends Exception {

    private final Duration retryAfter;

    public MediaProcessingBusyException(Duration retryAfter) {
        super("Image processing capacity is currently exhausted");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
