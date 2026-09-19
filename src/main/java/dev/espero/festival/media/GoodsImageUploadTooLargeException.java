package dev.espero.festival.media;

import java.io.IOException;

/** Raised while streaming an upload as soon as it exceeds the exact application limit. */
public final class GoodsImageUploadTooLargeException extends IOException {

    public GoodsImageUploadTooLargeException() {
        super("Goods image upload exceeds the 10 MiB limit");
    }
}
