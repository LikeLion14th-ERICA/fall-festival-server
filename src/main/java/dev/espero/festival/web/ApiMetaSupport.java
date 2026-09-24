package dev.espero.festival.web;

import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.PublishedFestivalContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds the shared API v2 metadata envelope without querying the database. */
@Component
public class ApiMetaSupport {

    static final String REQUEST_ID_ATTRIBUTE = ApiMetaSupport.class.getName() + ".requestId";
    private static final String RESPONSE_CONTEXT_ATTRIBUTE = ApiMetaSupport.class.getName() + ".responseContext";
    private static final ZoneId SYSTEM_ZONE = ZoneId.of("Asia/Seoul");

    private final Clock clock;
    private final FestivalProperties properties;

    public ApiMetaSupport(Clock clock, FestivalProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    public ApiMeta contentMeta(
        HttpServletRequest request,
        String locale,
        PublishedFestivalContext context
    ) {
        return scopedMeta(
            request,
            context.festivalId().toString(),
            context.revisionNumber(),
            locale,
            context.timezone()
        );
    }

    public ApiMeta meta(HttpServletRequest request, CatalogSnapshot.FestivalContext context, String locale) {
        return scopedMeta(request, context.festivalId(), context.revision(), locale, context.timezone());
    }

    /** Dynamic state changes independently of the published catalog revision. */
    public ApiMeta dynamicMeta(HttpServletRequest request, CatalogSnapshot.FestivalContext context, String locale) {
        return scopedMeta(request, context.festivalId(), 0, locale, context.timezone());
    }

    public void setDynamicContext(HttpServletRequest request, CatalogSnapshot.FestivalContext context, String locale) {
        request.setAttribute(
            RESPONSE_CONTEXT_ATTRIBUTE,
            new ResponseContext(context.festivalId(), 0, locale, context.timezone())
        );
    }

    public void setContext(HttpServletRequest request, CatalogSnapshot.FestivalContext context, String locale) {
        request.setAttribute(
            RESPONSE_CONTEXT_ATTRIBUTE,
            new ResponseContext(context.festivalId(), context.revision(), locale, context.timezone())
        );
    }

    public ApiMeta systemMeta(HttpServletRequest request, String locale) {
        return unscopedMeta(request, locale);
    }

    public ApiMeta unscopedMeta(HttpServletRequest request, String locale) {
        return buildMeta(request, properties.configuredFestivalId().toString(), 0, locale, SYSTEM_ZONE);
    }

    public ApiMeta metaForError(HttpServletRequest request) {
        Object attribute = request.getAttribute(RESPONSE_CONTEXT_ATTRIBUTE);
        if (attribute instanceof ResponseContext context) {
            return buildMeta(
                request,
                context.festivalId(),
                context.revision(),
                context.locale(),
                context.timezone()
            );
        }
        return unscopedMeta(request, "ko");
    }

    static String resolveRequestId(HttpServletRequest request) {
        Object stored = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (stored instanceof String requestId) {
            return requestId;
        }
        // A client supplied request ID is untrusted input.  Generate the value
        // once per request so the response header, JSON meta, and audit event
        // all use the same server-owned identifier.
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        return requestId;
    }

    private ApiMeta scopedMeta(
        HttpServletRequest request,
        String festivalId,
        long revision,
        String locale,
        ZoneId timezone
    ) {
        request.setAttribute(
            RESPONSE_CONTEXT_ATTRIBUTE,
            new ResponseContext(festivalId, revision, locale, timezone)
        );
        return buildMeta(request, festivalId, revision, locale, timezone);
    }

    private ApiMeta buildMeta(
        HttpServletRequest request,
        String festivalId,
        long revision,
        String locale,
        ZoneId timezone
    ) {
        ApiMeta meta = new ApiMeta(
            resolveRequestId(request),
            OffsetDateTime.now(clock.withZone(timezone)).truncatedTo(ChronoUnit.MILLIS),
            timezone.getId(),
            festivalId,
            revision,
            locale,
            false
        );
        request.setAttribute(RequestDiagnostics.META, meta);
        return meta;
    }

    private record ResponseContext(String festivalId, long revision, String locale, ZoneId timezone) {}
}
