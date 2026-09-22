package dev.espero.festival.ratelimit;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token buckets keyed by policy and client. The deployment is a
 * single instance, so no shared store is needed; buckets idle for ten minutes
 * are dropped at the table bound. When every client bucket is active, new
 * clients share one overflow bucket per policy instead of resetting an active
 * client's quota.
 */
public class RequestRateLimiter {

    private static final long IDLE_NANOS = 10L * 60 * 1_000_000_000;
    private static final int MAX_BUCKETS = 100_000;
    private static final long IDLE_PRUNE_INTERVAL_NANOS = 60L * 1_000_000_000;

    private final Clock clock;
    private final long idleNanos;
    private final int maxBuckets;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> overflowBuckets = new HashMap<>();
    // Covers token updates and eviction so an active bucket cannot be removed
    // after its most recent request refreshes it.
    private final Object lock = new Object();
    private long lastIdlePruneAt = Long.MIN_VALUE;

    public RequestRateLimiter(Clock clock) {
        this(clock, MAX_BUCKETS, IDLE_NANOS);
    }

    RequestRateLimiter(Clock clock, int maxBuckets, long idleNanos) {
        if (maxBuckets < 1 || idleNanos <= 0) {
            throw new IllegalArgumentException("Rate limiter bucket bounds must be positive");
        }
        this.clock = clock;
        this.maxBuckets = maxBuckets;
        this.idleNanos = idleNanos;
    }

    /**
     * Takes one token for the key. Returns 0 when the request may proceed,
     * otherwise the whole seconds until a token is available.
     */
    public long acquire(String policyName, RateLimitProperties.Policy policy, String client) {
        synchronized (lock) {
            long now = nanos();
            String key = policyName + '|' + client;
            Bucket bucket = buckets.get(key);
            if (bucket == null) {
                bucket = createBucket(policyName, policy, key, now);
            }
            return bucket.take(policy, now);
        }
    }

    int size() {
        return buckets.size();
    }

    private Bucket createBucket(String policyName, RateLimitProperties.Policy policy, String key, long now) {
        if (buckets.size() >= maxBuckets && shouldPruneIdleBuckets(now)) {
            evictIdleBuckets(now);
            lastIdlePruneAt = now;
        }
        if (buckets.size() < maxBuckets) {
            Bucket created = new Bucket(policy.capacity(), now);
            buckets.put(key, created);
            return created;
        }
        return overflowBuckets.computeIfAbsent(policyName, ignored -> new Bucket(policy.capacity(), now));
    }

    private boolean shouldPruneIdleBuckets(long now) {
        return lastIdlePruneAt == Long.MIN_VALUE || now - lastIdlePruneAt >= IDLE_PRUNE_INTERVAL_NANOS;
    }

    private void evictIdleBuckets(long now) {
        buckets.entrySet().removeIf(entry -> entry.getValue().idleSince(now) > idleNanos);
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

        long take(RateLimitProperties.Policy policy, long now) {
            double elapsedSeconds = Math.max(0, now - updatedAt) / 1_000_000_000.0;
            tokens = Math.min(policy.capacity(), tokens + elapsedSeconds * policy.refillPerSecond());
            updatedAt = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - tokens) / policy.refillPerSecond()));
        }

        long idleSince(long now) {
            return now - updatedAt;
        }
    }
}
