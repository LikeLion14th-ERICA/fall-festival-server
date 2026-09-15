package dev.espero.festival.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code meta} envelope shared by every api-v2 response.
 * festivalId/revision are fixed placeholders until a real Festival/FestivalRevision
 * aggregate exists (see docs/wiki/engineering/data-model.md).
 */
@Component
public class ApiMetaSupport {

    private static final ZoneId FESTIVAL_ZONE = ZoneId.of("Asia/Seoul");
    private static final String FESTIVAL_ID = "default";

    private final Clock clock;

    public ApiMetaSupport(Clock clock) {
        this.clock = clock;
    }

    public ApiMeta meta(HttpServletRequest request, long revision, String locale) {
        return new ApiMeta(
            requestId(request),
            OffsetDateTime.now(clock.withZone(FESTIVAL_ZONE)),
            FESTIVAL_ZONE.getId(),
            FESTIVAL_ID,
            revision,
            locale,
            false
        );
    }

    private String requestId(HttpServletRequest request) {
        String provided = request.getHeader("X-Request-Id");
        return provided != null && !provided.isBlank() ? provided : UUID.randomUUID().toString();
    }
}
