package dev.espero.festival.auth;

import dev.espero.festival.domain.AdminAccount;
import dev.espero.festival.persistence.AdminAuthStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("db")
public class AdminJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String ADMIN_PREFIX = "/api/v2/admin/";
    private static final String LOGIN_PATH = "/api/v2/admin/sessions";
    private static final String REFRESH_PATH = "/api/v2/admin/sessions/refresh";

    private final AdminTokenService tokenService;
    private final AdminAuthStore store;
    private final ApiSecurityErrorWriter errorWriter;

    public AdminJwtAuthenticationFilter(
        AdminTokenService tokenService,
        AdminAuthStore store,
        ApiSecurityErrorWriter errorWriter
    ) {
        this.tokenService = tokenService;
        this.store = store;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(ADMIN_PREFIX)
            || ("POST".equals(request.getMethod()) && (LOGIN_PATH.equals(path) || REFRESH_PATH.equals(path)));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        String rawToken = authorization.substring("Bearer ".length()).strip();
        AdminTokenService.AccessClaims claims = tokenService.verifyAccessToken(rawToken).orElse(null);
        if (claims == null) {
            unauthorized(request, response);
            return;
        }

        AdminAccount account;
        try {
            account = store.findAccountById(claims.adminId()).orElse(null);
        } catch (DataAccessException exception) {
            serviceUnavailable(request, response);
            return;
        }
        if (account == null || !account.enabled() || !claims.authority().equals(account.authority())) {
            unauthorized(request, response);
            return;
        }

        AdminPrincipal principal = new AdminPrincipal(account.id(), account.username(), account.authority());
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority(account.authority()))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        errorWriter.write(
            request,
            response,
            HttpStatus.UNAUTHORIZED.value(),
            "UNAUTHORIZED",
            "유효한 관리자 인증이 필요합니다."
        );
    }

    private void serviceUnavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        errorWriter.write(
            request,
            response,
            HttpStatus.SERVICE_UNAVAILABLE.value(),
            "SERVICE_UNAVAILABLE",
            "일시적으로 정보를 불러올 수 없습니다."
        );
    }
}
