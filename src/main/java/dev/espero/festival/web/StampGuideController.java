package dev.espero.festival.web;

import dev.espero.festival.domain.StampGuide;
import dev.espero.festival.domain.CatalogSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements GET /api/v2/stamp-guide per api-v2/contract-source.mjs (StampGuide
 * schema): the read-only guide text. Participation, booth stamps and the
 * daily reward are server-side since the 2026-09-22 booth QR change and live
 * in {@link StampCardController}.
 *
 * It reads the same immutable published snapshot as catalog requests. Korean
 * is the only published locale until every guide translation is approved; no
 * fallback is returned for an unready language.
 */
@RestController
@RequestMapping("/api/v2")
@Profile("db")
public class StampGuideController {

    private static final String TIMEZONE = "Asia/Seoul";
    private static final int DAILY_LIMIT = 4;

    private final CatalogSnapshotProvider snapshots;
    private final ApiMetaSupport metaSupport;

    public StampGuideController(CatalogSnapshotProvider snapshots, ApiMetaSupport metaSupport) {
        this.snapshots = snapshots;
        this.metaSupport = metaSupport;
    }

    @GetMapping("/stamp-guide")
    public ApiResponse<StampGuideResponse> getStampGuide(HttpServletRequest request) {
        CatalogSnapshot korean = snapshots.required();
        metaSupport.setContext(request, korean.context(), PublicContentLocale.KOREAN);
        String locale = validateQuery(request);
        CatalogSnapshot snapshot = PublicContentLocale.snapshot(snapshots, locale);
        StampGuide guide = java.util.Optional.ofNullable(snapshot.stampGuide()).orElseThrow(() -> new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "STAMP_GUIDE_NOT_CONFIGURED",
            "스탬프 안내가 아직 설정되지 않았습니다.",
            true
        ));

        StampGuideResponse data = new StampGuideResponse(
            guide.title(),
            guide.dates(),
            guide.instructions(),
            new StampGuideResponse.Reward(
                guide.rewardName(),
                guide.rewardLocationText(),
                guide.rewardHoursText(),
                guide.rewardNotice()
            ),
            DAILY_LIMIT,
            TIMEZONE,
            guide.qrValue()
        );
        return new ApiResponse<>(data, metaSupport.meta(request, snapshot.context(), locale));
    }

    private String validateQuery(HttpServletRequest request) {
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!entry.getKey().equals("locale") || entry.getValue().length != 1) {
                throw PublicContentLocale.invalidQuery();
            }
        }
        return PublicContentLocale.requirePublishedLocale(request, snapshots.publishedLocales());
    }
}
