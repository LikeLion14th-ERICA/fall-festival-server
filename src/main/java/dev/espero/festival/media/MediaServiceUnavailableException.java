package dev.espero.festival.media;

/** Safe HTTP-boundary signal for media infrastructure failures. */
public final class MediaServiceUnavailableException extends RuntimeException {

    public MediaServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
