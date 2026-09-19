package dev.espero.festival.web;

import dev.espero.festival.media.MediaVariant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Conditional request support for immutable goods image variants. */
@Component
public class GoodsMediaConditionalSupport {

    public String strongEtag(UUID mediaId, MediaVariant variant) {
        String identity = "goods-media-v1\n" + mediaId + "\n" + variant.name();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public boolean matches(String ifNoneMatch, String currentEtag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String normalized = candidate.trim();
            if (normalized.equals("*") || stripWeakPrefix(normalized).equals(currentEtag)) {
                return true;
            }
        }
        return false;
    }

    private String stripWeakPrefix(String etag) {
        return etag.startsWith("W/") || etag.startsWith("w/") ? etag.substring(2).trim() : etag;
    }
}
