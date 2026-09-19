package dev.espero.festival.media;

/** A requested media reference cannot be attached without revealing why. */
public final class UnavailableGoodsImageException extends RuntimeException {

    public UnavailableGoodsImageException() {
        super("사용할 수 없는 상품 이미지가 포함되어 있습니다.");
    }
}
