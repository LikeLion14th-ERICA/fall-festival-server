package dev.espero.festival.media;

public record GoodsImageInspection(
    GoodsImageFormat format,
    String sourceSha256,
    long sourceSizeBytes,
    int sourceWidth,
    int sourceHeight,
    int orientation,
    int orientedWidth,
    int orientedHeight
) {
}
