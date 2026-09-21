package dev.espero.festival.web;

import dev.espero.festival.auth.ApiSecurityErrorWriter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Places the bounded JSON reader after rate limiting and before Spring Security. */
@Configuration(proxyBeanMethods = false)
class JsonRequestBodyLimitConfiguration {

    private static final int ORDER = -150;

    @Bean
    FilterRegistrationBean<JsonRequestBodyLimitFilter> jsonRequestBodyLimitFilter(ApiSecurityErrorWriter errors) {
        FilterRegistrationBean<JsonRequestBodyLimitFilter> registration =
            new FilterRegistrationBean<>(new JsonRequestBodyLimitFilter(errors));
        registration.setOrder(ORDER);
        registration.addUrlPatterns("/api/v2/*");
        return registration;
    }
}
