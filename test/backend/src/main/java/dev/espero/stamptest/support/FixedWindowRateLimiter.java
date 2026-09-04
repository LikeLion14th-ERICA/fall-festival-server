package dev.espero.stamptest.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class FixedWindowRateLimiter {

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public Decision acquire(String key, int limit, Duration duration) {
        Instant now = clock.instant();
        Window updated = windows.compute(key, (ignored, current) -> {
            if (current == null || !now.isBefore(current.expiresAt())) {
                return new Window(1, now.plus(duration));
            }
            return new Window(current.count() + 1, current.expiresAt());
        });
        boolean allowed = updated.count() <= limit;
        long retryAfterSeconds = Math.max(1, Duration.between(now, updated.expiresAt()).toSeconds());
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        }
        return new Decision(allowed, retryAfterSeconds);
    }

    public void clear(String key) {
        windows.remove(key);
    }

    private record Window(int count, Instant expiresAt) {}

    public record Decision(boolean allowed, long retryAfterSeconds) {}
}
