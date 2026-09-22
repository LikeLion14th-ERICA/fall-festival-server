package dev.espero.festival.web;

import dev.espero.festival.domain.CatalogSnapshot;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Booth stamp endpoints (STAMP-001). START (once per festival day) hands the
 * browser an anonymous participant cookie and returns 201; a second START on
 * the same day returns 200. Until today's START the card answers
 * {@code STAMP_NOT_STARTED}, which the frontend shows as the start screen.
 * Scanning a booth QR posts the token from its link. The
 * cookie is HttpOnly, so the frontend never stores or reads the participant
 * id; every response is {@code no-store}.
 */
@RestController
@RequestMapping("/api/v2")
@Profile("db")
public class StampCardController {

    static final String PARTICIPANT_COOKIE = "__Host-festival-stamp";
    private static final Duration COOKIE_MAX_AGE = Duration.ofDays(30);

    private final CatalogSnapshotProvider snapshots;
    private final ApiMetaSupport metaSupport;
    private final StampCardService cards;

    public StampCardController(CatalogSnapshotProvider snapshots, ApiMetaSupport metaSupport, StampCardService cards) {
        this.snapshots = snapshots;
        this.metaSupport = metaSupport;
        this.cards = cards;
    }

    @PostMapping("/stamp-participants")
    public ResponseEntity<ApiResponse<StampCardResponse>> start(HttpServletRequest request) {
        String locale = validate(request);
        StampCardService.Started started = cards.start(participantToken(request));
        ResponseEntity.BodyBuilder response = ResponseEntity
            .status(started.startedNow() ? HttpStatus.CREATED : HttpStatus.OK)
            .cacheControl(CacheControl.noStore());
        if (started.cookieToken() != null) {
            response.header(HttpHeaders.SET_COOKIE, ResponseCookie.from(PARTICIPANT_COOKIE, started.cookieToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(COOKIE_MAX_AGE)
                .build()
                .toString());
        }
        return response.body(new ApiResponse<>(started.card(), meta(request, locale)));
    }

    @GetMapping("/stamp-card")
    public ResponseEntity<ApiResponse<StampCardResponse>> card(HttpServletRequest request) {
        String locale = validate(request);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(cards.current(participantToken(request)), meta(request, locale)));
    }

    @PostMapping(path = "/stamp-collections", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<StampCardResponse>> collect(
        HttpServletRequest request,
        @RequestBody CollectInput input
    ) {
        String locale = validate(request);
        StampCardResponse card = cards.collect(participantToken(request), input == null ? null : input.token());
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(card, meta(request, locale)));
    }

    /** Sets the error meta context first, then accepts only a single {@code locale} parameter. */
    private String validate(HttpServletRequest request) {
        CatalogSnapshot snapshot = snapshots.required();
        metaSupport.setContext(request, snapshot.context(), PublicContentLocale.KOREAN);
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!entry.getKey().equals("locale") || entry.getValue().length != 1) {
                throw PublicContentLocale.invalidQuery();
            }
        }
        return PublicContentLocale.requirePublishedLocale(request, snapshots.publishedLocales());
    }

    private ApiMeta meta(HttpServletRequest request, String locale) {
        return metaSupport.meta(request, snapshots.required().context(), locale);
    }

    static String participantToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (PARTICIPANT_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** The booth token is a credential: it never appears in toString or logs. */
    public record CollectInput(String token) {

        @Override
        public String toString() {
            return "CollectInput[token=REDACTED]";
        }
    }
}
