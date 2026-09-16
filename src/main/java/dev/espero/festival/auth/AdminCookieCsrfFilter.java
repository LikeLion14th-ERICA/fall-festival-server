package dev.espero.festival.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminCookieCsrfFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/v2/admin/sessions";
    private static final String REFRESH_PATH = "/api/v2/admin/sessions/refresh";
    private static final String LOGOUT_PATH = "/api/v2/admin/sessions/current";

    private final AdminAuthProperties properties;
    private final ApiSecurityErrorWriter errorWriter;

    public AdminCookieCsrfFilter(AdminAuthProperties properties, ApiSecurityErrorWriter errorWriter) {
        this.properties = properties;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return !("POST".equals(method) && (LOGIN_PATH.equals(path) || REFRESH_PATH.equals(path)))
            && !("DELETE".equals(method) && LOGOUT_PATH.equals(path));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String configuredOrigin = properties.allowedOrigin();
        String requestOrigin = request.getHeader("Origin");
        if (configuredOrigin == null || configuredOrigin.isBlank() || !configuredOrigin.equals(requestOrigin)) {
            errorWriter.write(
                request,
                response,
                HttpStatus.FORBIDDEN.value(),
                "ADMIN_CSRF_INVALID",
                "허용되지 않은 관리자 요청 출처입니다."
            );
            return;
        }
        filterChain.doFilter(request, response);
    }
}
