package dev.espero.festival.web;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.TicketGuideConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements GET /api/v2/ticket-guide per api-v2/contract-source.mjs
 * (TicketGuide schema). Only the published amount is displayed. The response
 * supports conditional revalidation when the published catalog changes.
 *
 * <p>Unknown or unpublished locales and unknown query parameters are rejected
 * instead of silently falling back to Korean.</p>
 */
@RestController
@RequestMapping("/api/v2")
@Profile("db")
public class TicketGuideController {

    private static final String CACHE_CONTROL = "private, no-cache";

    private final CatalogSnapshotProvider snapshots;
    private final ConditionalResponseSupport conditionalResponses;
    private final ApiMetaSupport metaSupport;

    public TicketGuideController(
        CatalogSnapshotProvider snapshots,
        ConditionalResponseSupport conditionalResponses,
        ApiMetaSupport metaSupport
    ) {
        this.snapshots = snapshots;
        this.conditionalResponses = conditionalResponses;
        this.metaSupport = metaSupport;
    }

    @GetMapping("/ticket-guide")
    public ResponseEntity<ConditionalApiResponse<TicketGuideResponse>> getTicketGuide(
        HttpServletRequest request
    ) {
        CatalogSnapshot korean = snapshots.required();
        metaSupport.setContext(request, korean.context(), PublicContentLocale.KOREAN);
        String locale = validateQuery(request);
        CatalogSnapshot snapshot = PublicContentLocale.snapshot(snapshots, locale);

        TicketGuideConfig guide = snapshot.ticketGuideConfig();
        TicketGuideResponse data = new TicketGuideResponse(guide == null ? null : money(guide));

        return conditionalResponses.respond(
            request,
            data,
            metaSupport.meta(request, snapshot.context(), locale),
            CACHE_CONTROL
        );
    }

    private TicketGuideResponse.Money money(TicketGuideConfig guide) {
        return guide.unitPriceAmount() == null
            ? null
            : new TicketGuideResponse.Money(guide.unitPriceAmount(), "KRW");
    }

    private String validateQuery(HttpServletRequest request) {
        for (java.util.Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!entry.getKey().equals("locale") || entry.getValue().length != 1) {
                throw PublicContentLocale.invalidQuery();
            }
        }
        return PublicContentLocale.requirePublishedLocale(request, snapshots.publishedLocales());
    }
}
