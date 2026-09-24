package dev.espero.festival.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Local abuse guard; ingress must also rate-limit across replicas. */
@Component
@Profile("db")
public class LoveLetterRateLimiter {
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private record Window(long minute, int count) {}

    public LoveLetterRateLimiter(Clock clock) { this.clock = clock; }

    public void check(HttpServletRequest request, String action, String participantToken) {
        String identity = "start".equals(action) || participantToken == null ? request.getRemoteAddr() : participantToken;
        String key = action + ":" + identity;
        long minute = clock.instant().getEpochSecond() / 60;
        if (windows.size() > 100_000) windows.entrySet().removeIf(entry -> entry.getValue().minute() < minute - 1);
        Window current = windows.compute(key, (ignored, previous) ->
            previous == null || previous.minute() != minute ? new Window(minute, 1) : new Window(minute, previous.count() + 1));
        int limit = "start".equals(action) ? 60 : 12;
        if (current.count() > limit)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOVE_RATE_LIMITED", "잠시 후 다시 시도해 주세요.", true);
    }
}
