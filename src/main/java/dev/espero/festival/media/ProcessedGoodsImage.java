package dev.espero.festival.media;

public record ProcessedGoodsImage(
    String sourceSha256,
    long sourceSizeBytes,
    GoodsImageFormat sourceFormat,
    int sourceWidth,
    int sourceHeight,
    int masterWidth,
    int masterHeight
) {
    public static final GoodsImageFormat OUTPUT_FORMAT = GoodsImageFormat.WEBP;
    public static final String OUTPUT_MEDIA_TYPE = "image/webp";
}
