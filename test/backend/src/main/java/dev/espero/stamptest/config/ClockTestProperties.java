package dev.espero.stamptest.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.clock-test")
public record ClockTestProperties(
    ZoneId zone,
    Duration duration,
    Duration interval,
    int notificationCount,
    Duration schedulerDelay
) {

    public ClockTestProperties {
        zone = zone == null ? ZoneId.of("Asia/Seoul") : zone;
        duration = duration == null ? Duration.ofMinutes(5) : duration;
        interval = interval == null ? Duration.ofMinutes(1) : interval;
        notificationCount = notificationCount <= 0 ? 5 : notificationCount;
        schedulerDelay = schedulerDelay == null ? Duration.ofSeconds(5) : schedulerDelay;
    }
}
