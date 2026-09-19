package dev.espero.festival.web;

import dev.espero.festival.media.MediaStorageUnconfiguredCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stands in for the goods image endpoints when no media storage is
 * configured. Without it the routes would not exist, so an upload answered
 * 404 and product image URLs looked like missing images. Here both answer
 * 503 with the reason, and startup logs what to set.
 */
@RestController
@Profile("db")
@Conditional(MediaStorageUnconfiguredCondition.class)
public class UnconfiguredGoodsMediaController {

    private static final Logger log = LoggerFactory.getLogger(UnconfiguredGoodsMediaController.class);

    public UnconfiguredGoodsMediaController() {
        log.warn("FESTIVAL_MEDIA_STORAGE_ROOT is not set: goods image upload and delivery answer 503 "
            + "MEDIA_STORAGE_UNCONFIGURED, so products cannot be created or show their images.");
    }

    @PostMapping("/api/v2/admin/media/goods-images")
    public void upload() {
        throw unconfigured();
    }

    @GetMapping("/api/v2/media/goods-images/{mediaId}/{variant}")
    public void image() {
        throw unconfigured();
    }

    private static ApiException unconfigured() {
        return new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MEDIA_STORAGE_UNCONFIGURED",
            "이미지 저장소가 설정되지 않았습니다.",
            false
        );
    }
}
