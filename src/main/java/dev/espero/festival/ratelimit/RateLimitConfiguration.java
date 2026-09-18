package dev.espero.festival.ratelimit;

import dev.espero.festival.auth.ApiSecurityErrorWriter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the API rate limit filter ahead of Spring Security. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(prefix = "festival.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
class RateLimitConfiguration {

    /** Spring Security's filter chain runs at -100; limiting comes first. */
    static final int ORDER = -200;

    @Bean
    RequestRateLimiter requestRateLimiter(Clock clock) {
        return new RequestRateLimiter(clock);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
        RateLimitProperties properties,
        RequestRateLimiter limiter,
        ApiSecurityErrorWriter errors
    ) {
        FilterRegistrationBean<RateLimitFilter> registration =
            new FilterRegistrationBean<>(new RateLimitFilter(properties, limiter, errors));
        registration.setOrder(ORDER);
        registration.addUrlPatterns("/api/v2/*");
        return registration;
    }
}
