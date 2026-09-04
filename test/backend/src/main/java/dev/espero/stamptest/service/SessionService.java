package dev.espero.stamptest.service;

import dev.espero.stamptest.config.AccessProperties;
import dev.espero.stamptest.config.SessionProperties;
import dev.espero.stamptest.domain.DomainModels.NewSession;
import dev.espero.stamptest.domain.DomainModels.SessionRecord;
import dev.espero.stamptest.persistence.SessionStore;
import dev.espero.stamptest.support.FixedWindowRateLimiter;
import dev.espero.stamptest.support.TokenSupport;
import dev.espero.stamptest.web.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SessionService {

    private final SessionStore store;
    private final SessionProperties sessionProperties;
    private final AccessProperties accessProperties;
    private final TokenSupport tokens;
    private final FixedWindowRateLimiter rateLimiter;
    private final Clock clock;

    public SessionService(
        SessionStore store,
        SessionProperties sessionProperties,
        AccessProperties accessProperties,
        TokenSupport tokens,
        FixedWindowRateLimiter rateLimiter,
        Clock clock
    ) {
        this.store = store;
        this.sessionProperties = sessionProperties;
        this.accessProperties = accessProperties;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    public SessionAccess open(HttpServletRequest request, String accessCode, String clientIp) {
        Optional<String> cookieToken = readCookie(request);
        if (cookieToken.isPresent()) {
            Optional<SessionRecord> existing = resolve(cookieToken.get());
            if (existing.isPresent()) {
                store.touch(existing.get().sessionId(), clock.instant());
                return new SessionAccess(existing.get(), cookieToken.get(), false);
            }
        }

        if (!tokens.configured() || accessProperties.code().isBlank()) {
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SESSION_CONFIGURATION_MISSING",
                "Anonymous session issuance is not configured."
            );
        }

        if (accessCode == null || accessCode.isBlank()) {
            throw new ApiException(
                HttpStatus.FORBIDDEN,
                "ACCESS_CODE_REQUIRED",
                "An access code is required to create a new anonymous session."
            );
        }

        String rateKey = "session-access:" + clientIp;
        if (!tokens.constantTimeEquals(accessCode, accessProperties.code())) {
            FixedWindowRateLimiter.Decision decision = rateLimiter.acquire(
                rateKey,
                accessProperties.maxFailures(),
                accessProperties.window()
            );
            if (!decision.allowed()) {
                throw new RateLimitedException(
                    "ACCESS_CODE_RATE_LIMITED",
                    "Too many invalid access-code attempts.",
                    decision.retryAfterSeconds()
                );
            }
            throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_ACCESS_CODE", "The access code is invalid.");
        }
        rateLimiter.clear(rateKey);

        Instant now = clock.instant();
        Instant expiresAt = now.plus(sessionProperties.ttl());
        String rawToken = tokens.randomToken();
        SessionRecord session = store.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            tokens.randomToken(),
            tokens.sessionTokenHash(rawToken),
            tokens.randomToken(),
            now,
            expiresAt
        );
        return new SessionAccess(session, rawToken, true);
    }

    public Optional<SessionRecord> resolveRequest(HttpServletRequest request) {
        return readCookie(request).flatMap(this::resolve);
    }

    public Optional<String> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
            .filter(cookie -> sessionProperties.cookieName().equals(cookie.getName()))
            .map(Cookie::getValue)
            .filter(value -> !value.isBlank())
            .findFirst();
    }

    private Optional<SessionRecord> resolve(String rawToken) {
        if (!tokens.configured()) {
            return Optional.empty();
        }
        return store.findActiveByTokenHash(tokens.sessionTokenHash(rawToken), clock.instant());
    }

    public record SessionAccess(SessionRecord session, String rawToken, boolean created) {}
}
