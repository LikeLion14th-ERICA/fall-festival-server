package dev.espero.festival.media;

/** Server-owned filenames for normalized goods image variants. */
public enum MediaVariant {
    MASTER("master.webp"),
    THUMB_320("320.webp"),
    THUMB_640("640.webp");

    private final String filename;

    MediaVariant(String filename) {
        this.filename = filename;
    }

    String filename() {
        return filename;
    }
}
