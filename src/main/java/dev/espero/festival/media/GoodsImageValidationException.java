package dev.espero.festival.media;

import java.io.IOException;

/** User-input validation failure, distinct from media-tool infrastructure failures. */
public final class GoodsImageValidationException extends IOException {

    private final GoodsImageValidationReason reason;

    public GoodsImageValidationException(GoodsImageValidationReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public GoodsImageValidationException(GoodsImageValidationReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public GoodsImageValidationReason reason() {
        return reason;
    }
}
