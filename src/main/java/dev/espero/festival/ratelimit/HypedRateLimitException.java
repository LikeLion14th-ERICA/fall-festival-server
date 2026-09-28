package dev.espero.festival.ratelimit;

import dev.espero.festival.web.ApiException;
import org.springframework.http.HttpStatus;

public final class HypedRateLimitException extends ApiException {
    private final long retryAfterSeconds;

    public HypedRateLimitException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "잠시 후 다시 요청해 주세요.", true);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() { return retryAfterSeconds; }
}
