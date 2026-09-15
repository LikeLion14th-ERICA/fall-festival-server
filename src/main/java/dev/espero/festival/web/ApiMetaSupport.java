package dev.espero.festival.web;

import dev.espero.festival.domain.CatalogSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code meta} envelope shared by every api-v2 response. Catalog
 * routes set their published revision context before producing a response;
 * legacy routes retain the safe default until they move to that snapshot.
 */
@Component
public class ApiMetaSupport {

    static final String REQUEST_ID_ATTRIBUTE = ApiMetaSupport.class.getName() + ".requestId";
    private static final String RESPONSE_CONTEXT_ATTRIBUTE = ApiMetaSupport.class.getName() + ".responseContext";
    private static final ZoneId FESTIVAL_ZONE = ZoneId.of("Asia/Seoul");
    private static final String FESTIVAL_ID = "default";
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Clock clock;

    public ApiMetaSupport(Clock clock) {
        this.clock = clock;
    }

    public ApiMeta meta(HttpServletRequest request, long revision, String locale) {
        return meta(request, FESTIVAL_ID, revision, locale);
    }

    public ApiMeta meta(HttpServletRequest request, CatalogSnapshot.FestivalContext context, String locale) {
        return meta(request, context.festivalId(), context.revision(), locale);
    }

    public void setContext(HttpServletRequest request, CatalogSnapshot.FestivalContext context, String locale) {
        request.setAttribute(RESPONSE_CONTEXT_ATTRIBUTE, new ResponseContext(context.festivalId(), context.revision(), locale));
    }

    public ApiMeta metaForError(HttpServletRequest request) {
        Object attribute = request.getAttribute(RESPONSE_CONTEXT_ATTRIBUTE);
        if (attribute instanceof ResponseContext context) {
            return buildMeta(request, context.festivalId(), context.revision(), context.locale());
        }
        return buildMeta(request, FESTIVAL_ID, 1, "ko");
    }

    static String resolveRequestId(HttpServletRequest request) {
        Object stored = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (stored instanceof String requestId) {
            return requestId;
        }
        String provided = request.getHeader("X-Request-Id");
        String requestId = provided != null && REQUEST_ID_PATTERN.matcher(provided).matches()
            ? provided
            : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        return requestId;
    }

    private ApiMeta meta(HttpServletRequest request, String festivalId, long revision, String locale) {
        request.setAttribute(RESPONSE_CONTEXT_ATTRIBUTE, new ResponseContext(festivalId, revision, locale));
        return buildMeta(request, festivalId, revision, locale);
    }

    private ApiMeta buildMeta(HttpServletRequest request, String festivalId, long revision, String locale) {
        return new ApiMeta(
            resolveRequestId(request),
            OffsetDateTime.now(clock.withZone(FESTIVAL_ZONE)),
            FESTIVAL_ZONE.getId(),
            festivalId,
            revision,
            locale,
            false
        );
    }

    private record ResponseContext(String festivalId, long revision, String locale) {}
}
