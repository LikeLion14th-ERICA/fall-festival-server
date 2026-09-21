package dev.espero.festival.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public goods endpoints. */
@RestController
@RequestMapping("/api/v2")
@Profile("db")
public class GoodsController {

    private static final String CACHE_CONTROL = "private, no-cache, must-revalidate";

    private final GoodsViewService views;
    private final ConditionalResponseSupport conditionalResponses;

    public GoodsController(GoodsViewService views, ConditionalResponseSupport conditionalResponses) {
        this.views = views;
        this.conditionalResponses = conditionalResponses;
    }

    @GetMapping("/goods")
    public ResponseEntity<ApiResponse<GoodsListResponse>> getGoods(HttpServletRequest request) {
        validateQuery(request);
        GoodsViewService.GoodsListSnapshot snapshot = views.list(request);
        return ResponseEntity.ok(new ApiResponse<>(snapshot.response(), snapshot.meta()));
    }

    @GetMapping("/goods/{goodsId}")
    public ResponseEntity<ApiResponse<GoodsResponse>> getGood(HttpServletRequest request, @PathVariable UUID goodsId) {
        validateQuery(request);
        GoodsViewService.GoodsSnapshot snapshot = views.find(request, goodsId);
        return ResponseEntity.ok(new ApiResponse<>(snapshot.response(), snapshot.meta()));
    }

    @GetMapping("/goods-availability")
    public ResponseEntity<ConditionalApiResponse<GoodsAvailabilityListResponse>> getGoodsAvailability(HttpServletRequest request) {
        validateQuery(request);
        GoodsViewService.GoodsAvailabilityListSnapshot snapshot = views.availabilityList(request);
        return conditionalResponses.respond(request, snapshot.response(), snapshot.meta(), CACHE_CONTROL);
    }

    @GetMapping("/goods/{goodsId}/availability")
    public ResponseEntity<ApiResponse<GoodsAvailabilityResponse>> getGoodAvailability(
        HttpServletRequest request,
        @PathVariable UUID goodsId
    ) {
        validateQuery(request);
        GoodsViewService.GoodsAvailabilitySnapshot snapshot = views.availability(request, goodsId);
        return ResponseEntity.ok(new ApiResponse<>(snapshot.response(), snapshot.meta()));
    }

    @GetMapping("/goods/{goodsId}/payment-guide")
    public ResponseEntity<ApiResponse<GoodsPaymentGuideResponse>> getPaymentGuide(
        HttpServletRequest request,
        @PathVariable UUID goodsId
    ) {
        validateQuery(request);
        GoodsViewService.GoodsPaymentGuideSnapshot snapshot = views.paymentGuide(request, goodsId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(snapshot.response(), snapshot.meta()));
    }

    private void validateQuery(HttpServletRequest request) {
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!entry.getKey().equals("locale") || entry.getValue().length != 1) {
                throw PublicContentLocale.invalidQuery();
            }
        }
    }
}
