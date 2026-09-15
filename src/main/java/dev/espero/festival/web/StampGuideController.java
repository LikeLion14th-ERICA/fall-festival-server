package dev.espero.festival.web;

import dev.espero.festival.domain.StampGuide;
import dev.espero.festival.persistence.StampGuideStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements GET /api/v2/stamp-guide per api-v2/contract-source.mjs (StampGuide
 * schema). Only this read-only guide is server-backed: participation start,
 * daily stamp count and redemption stay browser-local (api-v2/README.md,
 * "화면 계약의 주요 결정" and docs/wiki/engineering/data-model.md, both dated
 * 2026-09-14). Do not add session/participation endpoints here.
 *
 * locale is intentionally not yet honored: only Korean content exists, so the
 * query parameter is accepted but ignored until translated content is stored.
 */
@RestController
@RequestMapping("/api/v2")
@Profile("db")
public class StampGuideController {

    private static final String TIMEZONE = "Asia/Seoul";
    private static final int DAILY_LIMIT = 4;
    private static final String CONTENT_LOCALE = "ko";

    private final StampGuideStore store;
    private final ApiMetaSupport metaSupport;

    public StampGuideController(StampGuideStore store, ApiMetaSupport metaSupport) {
        this.store = store;
        this.metaSupport = metaSupport;
    }

    @GetMapping("/stamp-guide")
    public ApiResponse<StampGuideResponse> getStampGuide(HttpServletRequest request) {
        StampGuide guide = store.find().orElseThrow(() -> new ApiException(
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
        return new ApiResponse<>(data, metaSupport.meta(request, 1, CONTENT_LOCALE));
    }
}
