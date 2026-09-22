package dev.espero.festival.auth;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(AdminAuthProperties.class)
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AdminNoStoreFilter adminNoStoreFilter() {
        return new AdminNoStoreFilter();
    }

    @Bean
    UserDetailsService noFormLoginUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Form login is not supported");
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        ObjectProvider<AdminJwtAuthenticationFilter> jwtFilterProvider,
        AdminNoStoreFilter adminNoStoreFilter,
        AdminCookieCsrfFilter cookieCsrfFilter,
        ApiSecurityErrorWriter errorWriter,
        CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> errorWriter.write(
                    request, response, HttpStatus.UNAUTHORIZED.value(),
                    "UNAUTHORIZED", "유효한 관리자 인증이 필요합니다."
                ))
                .accessDeniedHandler((request, response, exception) -> errorWriter.write(
                    request, response, HttpStatus.FORBIDDEN.value(),
                    "FORBIDDEN", "관리자 권한이 필요합니다."
                )))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.OPTIONS, "/api/v2/admin/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v2/admin/sessions").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v2/admin/sessions/refresh").permitAll()
                .requestMatchers("/api/v2/admin/**").hasAuthority("ADMIN")
                .anyRequest().permitAll())
            .addFilterBefore(adminNoStoreFilter, HeaderWriterFilter.class)
            .addFilterBefore(cookieCsrfFilter, UsernamePasswordAuthenticationFilter.class);

        AdminJwtAuthenticationFilter jwtFilter = jwtFilterProvider.getIfAvailable();
        if (jwtFilter != null) {
            http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AdminAuthProperties properties) {
        return corsConfigurationSource(properties.allowedOrigin());
    }

    private CorsConfigurationSource corsConfigurationSource(String allowedOrigin) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (allowedOrigin == null || allowedOrigin.isBlank()) {
            return source;
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
            "Authorization", "Content-Type", "X-Request-Id", "If-Match", "If-None-Match", "Idempotency-Key"
        ));
        configuration.setExposedHeaders(List.of("X-Request-Id", "Retry-After", "Location", "ETag", "X-Server-Time"));
        configuration.setAllowCredentials(true);
        source.registerCorsConfiguration("/api/v2/admin/**", configuration);
        return source;
    }
}
