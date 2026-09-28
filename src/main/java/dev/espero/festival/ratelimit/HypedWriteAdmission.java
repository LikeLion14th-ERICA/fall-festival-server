package dev.espero.festival.ratelimit;

import dev.espero.festival.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

/** Shares the filter's exact client key and bucket with the newly applied batch. */
public final class HypedWriteAdmission {
    private static final String ATTRIBUTE = HypedWriteAdmission.class.getName();

    private HypedWriteAdmission() {}

    static void record(HttpServletRequest request, RequestRateLimiter limiter,
        RateLimitProperties.Policy policy, String client) {
        request.setAttribute(ATTRIBUTE, new Reservation(limiter, policy, client));
    }

    public static void reserveAdditionalClicks(HttpServletRequest request, int delta) {
        Object value = request.getAttribute(ATTRIBUTE);
        // No admission exists when request limiting is explicitly disabled.
        if (!(value instanceof Reservation reservation)) return;
        if (delta > reservation.policy().capacity()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "요청한 기대 수가 한 번에 처리할 수 있는 범위를 초과했습니다.", false);
        }
        if (delta == 1) return;
        long wait = reservation.limiter().acquire("artist-hyped", reservation.policy(), reservation.client(), delta - 1);
        if (wait > 0) {
            // The next HTTP retry must also reserve the filter's one request token.
            long requestTokenWait = Math.max(1, (long) Math.ceil(1 / reservation.policy().refillPerSecond()));
            long retryAfter = wait > Long.MAX_VALUE - requestTokenWait ? Long.MAX_VALUE : wait + requestTokenWait;
            throw new HypedRateLimitException(retryAfter);
        }
    }

    private record Reservation(RequestRateLimiter limiter, RateLimitProperties.Policy policy, String client) {}
}
