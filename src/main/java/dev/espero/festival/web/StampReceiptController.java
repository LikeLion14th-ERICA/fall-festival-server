package dev.espero.festival.web;

import dev.espero.festival.domain.CatalogSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v2/stamp-receipt-verifications (STAMP-001). The LIKELION booth
 * staff type the reward code on the visitor's phone; a match only answers
 * {@code verified: true}. No participation or reward history is recorded, and
 * a wrong code is refused without saying why. Requests are rate limited per
 * client ahead of this controller.
 */
@RestController
@RequestMapping("/api/v2")
@Profile("db")
public class StampReceiptController {

    private final CatalogSnapshotProvider snapshots;
    private final ApiMetaSupport metaSupport;
    private final StampReceiptVerifier verifier;

    public StampReceiptController(
        CatalogSnapshotProvider snapshots,
        ApiMetaSupport metaSupport,
        StampReceiptVerifier verifier
    ) {
        this.snapshots = snapshots;
        this.metaSupport = metaSupport;
        this.verifier = verifier;
    }

    @PostMapping(path = "/stamp-receipt-verifications", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Verification> verify(HttpServletRequest request, @RequestBody Input input) {
        CatalogSnapshot snapshot = snapshots.required();
        metaSupport.setContext(request, snapshot.context(), PublicContentLocale.KOREAN);
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!entry.getKey().equals("locale") || entry.getValue().length != 1) {
                throw PublicContentLocale.invalidQuery();
            }
        }
        String locale = PublicContentLocale.requirePublishedLocale(request, snapshots.publishedLocales());
        if (!verifier.configured()) {
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "STAMP_RECEIPT_UNCONFIGURED",
                "수령 인증을 아직 사용할 수 없습니다.",
                false
            );
        }
        if (input == null || !verifier.matches(input.code())) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_RECEIPT_CODE",
                "수령 인증 코드를 확인해 주세요.",
                false
            );
        }
        return new ApiResponse<>(new Verification(true), metaSupport.meta(request, snapshot.context(), locale));
    }

    /** The code is write-only: it never appears in toString, logs or responses. */
    public record Input(String code) {

        @Override
        public String toString() {
            return "Input[code=REDACTED]";
        }
    }

    public record Verification(boolean verified) {}
}
