package dev.espero.festival.ratelimit;

import dev.espero.festival.auth.ApiSecurityErrorWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the in-flight bounds after rate limiting and before Spring Security. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OverloadProperties.class)
@ConditionalOnProperty(prefix = "festival.overload", name = "enabled", havingValue = "true", matchIfMissing = true)
class OverloadConfiguration {

    /** Rate limiting runs at -200, the JSON body limit at -150 and Spring Security at -100. */
    static final int ORDER = -175;

    @Bean
    FilterRegistrationBean<ConcurrencyLimitFilter> concurrencyLimitFilter(
        OverloadProperties properties,
        ApiSecurityErrorWriter errors
    ) {
        FilterRegistrationBean<ConcurrencyLimitFilter> registration =
            new FilterRegistrationBean<>(new ConcurrencyLimitFilter(properties, errors));
        registration.setOrder(ORDER);
        registration.addUrlPatterns("/api/v2/*");
        return registration;
    }
}
