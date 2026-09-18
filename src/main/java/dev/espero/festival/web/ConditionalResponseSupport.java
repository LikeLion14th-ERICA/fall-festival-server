package dev.espero.festival.web;

import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Creates deterministic conditional API responses without changing the
 * existing {@link ApiResponse} envelope used by non-conditional endpoints.
 *
 * <p>The ETag is a quoted SHA-256 digest of the canonical JSON representation
 * of {@link ConditionalApiResponse}. Volatile request metadata is emitted as
 * headers and is therefore excluded from the digest.</p>
 */
@Component
public class ConditionalResponseSupport {

    public static final String ETAG_HEADER = "ETag";
    public static final String IF_NONE_MATCH_HEADER = "If-None-Match";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String SERVER_TIME_HEADER = "X-Server-Time";

    private final ObjectMapper canonicalMapper;

    public ConditionalResponseSupport(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.rebuild()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();
    }

    /**
     * Returns a 304 response when the request's If-None-Match matches the
     * current representation, otherwise returns a 200 response with its body.
     */
    public <T> ResponseEntity<ConditionalApiResponse<T>> respond(
        HttpServletRequest request,
        T data,
        ApiMeta meta
    ) {
        return respond(request, data, meta, null);
    }

    /**
     * Same as {@link #respond(HttpServletRequest, Object, ApiMeta)} with an
     * explicit Cache-Control value. The value is also sent on 304 responses so
     * a revalidating cache keeps the same directives.
     */
    public <T> ResponseEntity<ConditionalApiResponse<T>> respond(
        HttpServletRequest request,
        T data,
        ApiMeta meta,
        String cacheControl
    ) {
        if (request == null) {
            throw new IllegalArgumentException("HTTP request is required");
        }
        if (meta == null) {
            throw new IllegalArgumentException("API metadata is required");
        }

        ConditionalApiResponse<T> representation = new ConditionalApiResponse<>(
            data,
            ConditionalApiMeta.from(meta)
        );
        String etag = strongEtag(representation);
        HttpHeaders headers = headers(request, meta);
        headers.set(ETAG_HEADER, etag);
        if (cacheControl != null && !cacheControl.isBlank()) {
            headers.set(HttpHeaders.CACHE_CONTROL, cacheControl);
        }
        if (matches(request.getHeader(IF_NONE_MATCH_HEADER), etag)) {
            return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
        }
        return ResponseEntity.ok().headers(headers).body(representation);
    }

    /**
     * Computes the quoted strong ETag for a stable conditional representation.
     * The serialization sorts bean properties and map keys before hashing.
     */
    public String strongEtag(Object representation) {
        if (representation == null) {
            throw new IllegalArgumentException("Representation is required");
        }
        try {
            byte[] canonicalJson = canonicalMapper.writeValueAsBytes(representation);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson);
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Representation cannot be serialized", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private HttpHeaders headers(HttpServletRequest request, ApiMeta meta) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(REQUEST_ID_HEADER, ApiMetaSupport.resolveRequestId(request));
        headers.set(SERVER_TIME_HEADER, meta.serverTime().toString());
        return headers;
    }

    private boolean matches(String ifNoneMatch, String currentEtag) {
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
