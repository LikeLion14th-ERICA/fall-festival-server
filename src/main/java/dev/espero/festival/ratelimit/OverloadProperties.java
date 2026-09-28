package dev.espero.festival.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * In-flight request bounds for the single backend instance. Keep
 * {@code hypedWriteMaxConcurrent} below the database pool size so Hyped
 * batches can never hold every connection.
 */
@ConfigurationProperties(prefix = "festival.overload")
public record OverloadProperties(
    Boolean enabled,
    Integer publicMaxConcurrent,
    Integer hypedWriteMaxConcurrent,
    Long maxWaitMs
) {

    public OverloadProperties {
        enabled = enabled == null || enabled;
        publicMaxConcurrent = publicMaxConcurrent == null ? 150 : publicMaxConcurrent;
        hypedWriteMaxConcurrent = hypedWriteMaxConcurrent == null ? 8 : hypedWriteMaxConcurrent;
        maxWaitMs = maxWaitMs == null ? 200L : maxWaitMs;
        if (publicMaxConcurrent < 1 || hypedWriteMaxConcurrent < 1) {
            throw new IllegalArgumentException("festival.overload concurrency limits must be positive");
        }
        if (maxWaitMs < 0 || maxWaitMs > 5_000) {
            throw new IllegalArgumentException("festival.overload.max-wait-ms must be between 0 and 5000");
        }
    }
}
