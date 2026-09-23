package dev.espero.festival.web;

import dev.espero.festival.web.LoveLetterService.Input;
import dev.espero.festival.web.LoveLetterService.SeedInput;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous love-letter routes and admin controls. All personal responses are no-store. */
@RestController
@RequestMapping("/api/v2")
@Profile("db")
public class LoveLetterController {
    private static final String COOKIE = "__Host-festival-love";
    private final LoveLetterService service;
    private final ApiMetaSupport meta;
    private final CatalogSnapshotProvider snapshots;
    private final LoveLetterRateLimiter limiter;
    private final String allowedOrigin;

    public LoveLetterController(LoveLetterService service, ApiMetaSupport meta, CatalogSnapshotProvider snapshots,
                                LoveLetterRateLimiter limiter,
                                @Value("${festival.love-letter.allowed-origin:}") String allowedOrigin) {
        this.service = service;
        this.meta = meta;
        this.snapshots = snapshots;
        this.limiter = limiter;
        this.allowedOrigin = allowedOrigin;
    }

    @GetMapping("/love-letter-guide")
    public ResponseEntity<ApiResponse<LoveLetterService.Guide>> guide(HttpServletRequest request) {
        return response(request, service.guide());
    }

    @PostMapping("/love-letter-participants")
    public ResponseEntity<ApiResponse<LoveLetterService.Status>> start(HttpServletRequest request) {
        locale(request);
        origin(request);
        limiter.check(request, "start", cookie(request));
        var session = service.start(cookie(request));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(session.cookie() == null ? HttpStatus.OK : HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore());
        if (session.cookie() != null) response.header(HttpHeaders.SET_COOKIE, cookieHeader(session.cookie()));
        return response.body(new ApiResponse<>(session.status(), meta.unscopedMeta(request, locale(request))));
    }

    @GetMapping("/love-letter-status")
    public ResponseEntity<ApiResponse<LoveLetterService.Status>> status(HttpServletRequest request) {
        return response(request, service.status(cookie(request)));
    }

    @PostMapping(path="/love-letters", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<LoveLetterService.Registered>> register(HttpServletRequest request,
        @RequestHeader(value="Idempotency-Key", required=false) String key,
        @RequestHeader(value="X-Love-Letter-CSRF", required=false) String csrf,
        @RequestBody Input input) {
        protect(request, csrf);
        limiter.check(request, "register", cookie(request));
        return response(request, service.register(cookie(request), input, key));
    }

    @PostMapping("/love-letter-draws")
    public ResponseEntity<ApiResponse<LoveLetterService.Draw>> draw(HttpServletRequest request,
        @RequestHeader(value="Idempotency-Key", required=false) String key,
        @RequestHeader(value="X-Love-Letter-CSRF", required=false) String csrf) {
        protect(request, csrf);
        limiter.check(request, "draw", cookie(request));
        return response(request, service.draw(cookie(request), key));
    }

    @PostMapping("/love-letter-seeded-draws")
    public ResponseEntity<ApiResponse<LoveLetterService.Draw>> seededDraw(HttpServletRequest request,
        @RequestHeader(value="Idempotency-Key", required=false) String key,
        @RequestHeader(value="X-Love-Letter-CSRF", required=false) String csrf) {
        protect(request, csrf);
        limiter.check(request, "draw", cookie(request));
        return response(request, service.drawSeeded(cookie(request), key));
    }

    @PostMapping("/love-letter-results/{id}/open")
    public ResponseEntity<ApiResponse<LoveLetterService.Opened>> open(HttpServletRequest request,
        @PathVariable UUID id, @RequestHeader(value="X-Love-Letter-CSRF", required=false) String csrf) {
        protect(request, csrf);
        limiter.check(request, "open", cookie(request));
        return response(request, service.open(cookie(request), id));
    }

    @PostMapping("/love-letter-results/{id}/reports")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> report(HttpServletRequest request,
        @PathVariable UUID id, @RequestHeader(value="X-Love-Letter-CSRF", required=false) String csrf) {
        protect(request, csrf);
        limiter.check(request, "report", cookie(request));
        service.report(cookie(request), id);
        return response(request, Map.of("reported", true));
    }

    public record ClaimInput(String invitationToken) {
        @Override public String toString() { return "ClaimInput[REDACTED]"; }
    }

    @PostMapping(path="/love-letter-invitations/claim", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<LoveLetterService.Status>> claim(HttpServletRequest request,
        @RequestHeader(value="X-Love-Letter-CSRF", required=false) String csrf, @RequestBody ClaimInput input) {
        protect(request, csrf);
        limiter.check(request, "claim", cookie(request));
        var session = service.claim(cookie(request), input == null ? null : input.invitationToken());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.SET_COOKIE, cookieHeader(session.cookie()))
            .body(new ApiResponse<>(session.status(), meta.unscopedMeta(request, locale(request))));
    }

    @PostMapping(path="/admin/love-letters/seeds", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<LoveLetterService.Seeded>> seed(HttpServletRequest request, @RequestBody SeedInput input) {
        return response(request, service.seed(input, ApiMetaSupport.resolveRequestId(request)));
    }

    @PostMapping("/admin/love-letters/participants/{id}/invitation")
    public ResponseEntity<ApiResponse<LoveLetterService.Seeded>> reissue(HttpServletRequest request, @PathVariable UUID id) {
        return response(request, service.reissue(id, ApiMetaSupport.resolveRequestId(request)));
    }

    @GetMapping("/admin/love-letters/reports")
    public ResponseEntity<ApiResponse<java.util.List<dev.espero.festival.persistence.LoveLetterStore.Report>>> reports(HttpServletRequest request) {
        return response(request, service.reports(ApiMetaSupport.resolveRequestId(request)));
    }

    @GetMapping("/admin/love-letters/reports/{id}")
    public ResponseEntity<ApiResponse<LoveLetterService.AdminReport>> reportDetail(HttpServletRequest request, @PathVariable UUID id) {
        return response(request, service.reportDetail(id, ApiMetaSupport.resolveRequestId(request)));
    }

    @PostMapping("/admin/love-letters/{id}/block")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> block(HttpServletRequest request, @PathVariable UUID id) {
        service.block(id, ApiMetaSupport.resolveRequestId(request));
        return response(request, Map.of("blocked", true));
    }

    public record RestrictionInput(boolean restricted) {}

    @PutMapping(path="/admin/love-letters/participants/{id}/restriction", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> restrict(HttpServletRequest request,
        @PathVariable UUID id, @RequestBody RestrictionInput input) {
        service.restrict(id, input.restricted(), ApiMetaSupport.resolveRequestId(request));
        return response(request, Map.of("restricted", input.restricted()));
    }

    public record EnabledInput(boolean enabled) {}

    public record SettingsInput(java.time.Instant opensAt, java.time.Instant closesAt, String consentVersion) {}

    @PutMapping(path="/admin/love-letters/configuration", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> configure(HttpServletRequest request, @RequestBody SettingsInput input) {
        service.configure(input.opensAt(), input.closesAt(), input.consentVersion(), ApiMetaSupport.resolveRequestId(request));
        return response(request, Map.of("enabled", false));
    }

    @PutMapping(path="/admin/love-letters/settings", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> enabled(HttpServletRequest request, @RequestBody EnabledInput input) {
        service.enabled(input.enabled(), ApiMetaSupport.resolveRequestId(request));
        return response(request, Map.of("enabled", input.enabled()));
    }

    private void protect(HttpServletRequest request, String csrf) {
        locale(request);
        origin(request);
        String token = cookie(request);
        if (token == null || csrf == null || !constantEquals(service.csrf(token), csrf))
            throw new ApiException(HttpStatus.FORBIDDEN, "LOVE_CSRF_INVALID", "요청 보호 토큰이 유효하지 않습니다.", false);
    }

    private void origin(HttpServletRequest request) {
        if (allowedOrigin.isBlank() || !allowedOrigin.equals(request.getHeader("Origin")))
            throw new ApiException(HttpStatus.FORBIDDEN, "LOVE_ORIGIN_INVALID", "허용되지 않은 요청 출처입니다.", false);
    }

    private static boolean constantEquals(String a, String b) {
        return a != null && java.security.MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String cookieHeader(String token) {
        return ResponseCookie.from(COOKIE, token).httpOnly(true).secure(true).sameSite("Lax")
            .path("/").maxAge(Duration.ofDays(30)).build().toString();
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) if (COOKIE.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private <T> ResponseEntity<ApiResponse<T>> response(HttpServletRequest request, T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(data, meta.unscopedMeta(request,
                request.getRequestURI().startsWith("/api/v2/admin/") ? "ko" : locale(request))));
    }

    private String locale(HttpServletRequest request) {
        var values = request.getParameterMap();
        if (values.size() > 1 || (values.size() == 1 &&
            (!values.containsKey("locale") || values.get("locale").length != 1))) throw PublicContentLocale.invalidQuery();
        return PublicContentLocale.requirePublishedLocale(request, snapshots.publishedLocales());
    }
}
