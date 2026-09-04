package dev.espero.stamptest.web;

import dev.espero.stamptest.config.ClockTestProperties;
import dev.espero.stamptest.config.SecurityProperties;
import dev.espero.stamptest.domain.DomainModels.SessionRecord;
import dev.espero.stamptest.service.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiSecurityFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String ACK_PATH = "/api/v1/me/notifications/ack";

    private final SessionService sessions;
    private final SecurityProperties security;
    private final ClockTestProperties clockTest;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApiSecurityFilter(
        SessionService sessions,
        SecurityProperties security,
        ClockTestProperties clockTest,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.sessions = sessions;
        this.security = security;
        this.clockTest = clockTest;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String path = request.getRequestURI();
        boolean unsafe = !SAFE_METHODS.contains(request.getMethod());
        boolean capabilityAck = ACK_PATH.equals(path) && HttpMethod.POST.matches(request.getMethod());

        if (unsafe && !capabilityAck && !originAllowed(request.getHeader(HttpHeaders.ORIGIN))) {
            writeError(response, request, HttpStatus.FORBIDDEN, "ORIGIN_NOT_ALLOWED", "The request origin is not allowed.");
            return;
        }

        if (path.startsWith("/api/v1/me/") && !capabilityAck) {
            Optional<SessionRecord> session = sessions.resolveRequest(request);
            if (session.isEmpty()) {
                writeError(response, request, HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "A valid anonymous session is required.");
                return;
            }
            request.setAttribute(AuthenticatedSession.REQUEST_ATTRIBUTE, new AuthenticatedSession(session.get()));
            if (unsafe && !csrfMatches(request.getHeader("X-CSRF-Token"), session.get().csrfToken())) {
                writeError(response, request, HttpStatus.FORBIDDEN, "CSRF_TOKEN_INVALID", "The CSRF token is missing or invalid.");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean originAllowed(String origin) {
        return origin != null && security.allowedOrigins().contains(origin);
    }

    private boolean csrfMatches(String supplied, String expected) {
        return supplied != null && MessageDigest.isEqual(
            supplied.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void writeError(
        HttpServletResponse response,
        HttpServletRequest request,
        HttpStatus status,
        String code,
        String message
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiErrorResponse body = new ApiErrorResponse(new ApiErrorResponse.ErrorBody(
            code,
            message,
            List.of(),
            OffsetDateTime.ofInstant(clock.instant(), clockTest.zone()),
            request.getRequestURI()
        ));
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
