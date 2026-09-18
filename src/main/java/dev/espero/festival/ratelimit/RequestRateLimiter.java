package dev.espero.festival.ratelimit;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token buckets keyed by policy and client. The deployment is a
 * single instance, so no shared store is needed; buckets idle for ten minutes
 * are dropped once the table grows past its bound.
 */
public class RequestRateLimiter {

    private static final long IDLE_NANOS = 10L * 60 * 1_000_000_000;
    private static final int MAX_BUCKETS = 100_000;

    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RequestRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Takes one token for the key. Returns 0 when the request may proceed,
     * otherwise the whole seconds until a token is available.
     */
    public long acquire(String policyName, RateLimitProperties.Policy policy, String client) {
        long now = nanos();
        if (buckets.size() > MAX_BUCKETS) {
            evict(now);
        }
        Bucket bucket = buckets.computeIfAbsent(policyName + '|' + client, key -> new Bucket(policy.capacity(), now));
        return bucket.take(policy, now);
    }

    int size() {
        return buckets.size();
    }

    private void evict(long now) {
        buckets.values().removeIf(bucket -> bucket.idleSince(now) > IDLE_NANOS);
        Iterator<String> keys = buckets.keySet().iterator();
        while (buckets.size() > MAX_BUCKETS / 2 && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }

    private long nanos() {
        java.time.Instant instant = clock.instant();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    private static final class Bucket {

        private double tokens;
        private long updatedAt;

        private Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.updatedAt = now;
        }

        synchronized long take(RateLimitProperties.Policy policy, long now) {
            double elapsedSeconds = Math.max(0, now - updatedAt) / 1_000_000_000.0;
            tokens = Math.min(policy.capacity(), tokens + elapsedSeconds * policy.refillPerSecond());
            updatedAt = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - tokens) / policy.refillPerSecond()));
        }

        synchronized long idleSince(long now) {
            return now - updatedAt;
        }
    }
}
