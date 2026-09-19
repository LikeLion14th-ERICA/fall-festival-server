package dev.espero.festival.web;

import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.media.MediaServiceUnavailableException;
import dev.espero.festival.media.MediaStorage;
import dev.espero.festival.media.MediaVariant;
import dev.espero.festival.persistence.MediaAssetStore;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Anonymous streaming delivery for live goods image associations. */
@RestController
@RequestMapping("/api/v2/media/goods-images")
@Profile("db")
@ConditionalOnProperty(prefix = "festival.media", name = "storage-root")
public class GoodsMediaController {

    static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final MediaAssetStore mediaAssets;
    private final MediaStorage mediaStorage;
    private final FestivalProperties festivalProperties;
    private final GoodsMediaConditionalSupport conditional;

    public GoodsMediaController(
        MediaAssetStore mediaAssets,
        MediaStorage mediaStorage,
        FestivalProperties festivalProperties,
        GoodsMediaConditionalSupport conditional
    ) {
        this.mediaAssets = mediaAssets;
        this.mediaStorage = mediaStorage;
        this.festivalProperties = festivalProperties;
        this.conditional = conditional;
    }

    @GetMapping("/{mediaId}/{variant}")
    public ResponseEntity<StreamingResponseBody> get(
        HttpServletRequest request,
        @PathVariable UUID mediaId,
        @PathVariable String variant
    ) {
        validateQuery(request);
        MediaVariant mediaVariant = parseVariant(variant);
        UUID festivalId = festivalProperties.configuredFestivalId();
        if (mediaAssets.findAttachedForServing(festivalId, mediaId).isEmpty()) {
            throw notFound();
        }

        String etag = conditional.strongEtag(mediaId, mediaVariant);
        HttpHeaders headers = responseHeaders(etag);
        InputStream input;
        try {
            input = mediaStorage.open(festivalId, mediaId, mediaVariant);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
        if (conditional.matches(request.getHeader(HttpHeaders.IF_NONE_MATCH), etag)) {
            try {
                input.close();
            } catch (IOException exception) {
                throw unavailable(exception);
            }
            return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
        }
        StreamingResponseBody body = output -> {
            try (input) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static HttpHeaders responseHeaders(String etag) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("image/webp"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setCacheControl(CACHE_CONTROL);
        headers.setETag(etag);
        return headers;
    }

    private static MediaVariant parseVariant(String value) {
        return switch (value) {
            case "master" -> MediaVariant.MASTER;
            case "320" -> MediaVariant.THUMB_320;
            case "640" -> MediaVariant.THUMB_640;
            default -> throw notFound();
        };
    }

    private static void validateQuery(HttpServletRequest request) {
        if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "요청 파라미터를 확인해 주세요.", false);
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 정보를 찾을 수 없습니다.", false);
    }

    private static MediaServiceUnavailableException unavailable(IOException cause) {
        return new MediaServiceUnavailableException("Stored goods image variant is unavailable", cause);
    }
}
