package dev.espero.festival.web;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds same-origin public URLs without exposing media storage details. */
@Component
public class GoodsMediaUrlSupport {

    private static final String BASE_PATH = "/api/v2/media/goods-images/";

    public GoodsMediaUrls urls(UUID mediaId) {
        String base = BASE_PATH + mediaId + "/";
        return new GoodsMediaUrls(base + "master", base + "320", base + "640");
    }

    public record GoodsMediaUrls(
        String masterUrl,
        String thumbnail320Url,
        String thumbnail640Url
    ) {}
}
