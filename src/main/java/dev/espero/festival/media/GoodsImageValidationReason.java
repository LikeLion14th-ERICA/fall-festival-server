package dev.espero.festival.media;

public enum GoodsImageValidationReason {
    EMPTY_FILE,
    FILE_TOO_LARGE,
    UNSUPPORTED_FORMAT,
    INVALID_DIMENSIONS,
    INVALID_EXIF_ORIENTATION,
    ANIMATED_WEBP_NOT_SUPPORTED,
    CORRUPT_IMAGE
}
