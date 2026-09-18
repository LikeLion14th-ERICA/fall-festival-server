package dev.espero.festival.workbench;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards every workbench request.
 *
 * <ul>
 *   <li>The Host header must be exactly {@code 127.0.0.1:<port>}, which also
 *   defeats DNS rebinding.</li>
 *   <li>An Origin header, when present, must be the same origin; requests
 *   that change data must send it.</li>
 *   <li>API calls must carry this process's session token.</li>
 * </ul>
 */
class WorkbenchRequestGuard extends OncePerRequestFilter {

    static final String TOKEN_HEADER = "X-Workbench-Token";
    private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; "
        + "style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'; "
        + "frame-ancestors 'none'";

    private final WorkbenchSession session;

    WorkbenchRequestGuard(WorkbenchSession session) {
        this.session = session;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");

        String origin = "http://127.0.0.1:" + request.getLocalPort();
        List<String> hosts = Collections.list(request.getHeaders("Host"));
        if (hosts.size() != 1 || !hosts.getFirst().equals("127.0.0.1:" + request.getLocalPort())) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "HOST_NOT_ALLOWED");
            return;
        }
        List<String> origins = Collections.list(request.getHeaders("Origin"));
        boolean changesData = !request.getMethod().equals("GET") && !request.getMethod().equals("HEAD");
        if (origins.size() > 1 || (origins.size() == 1 && !origins.getFirst().equals(origin))
            || (changesData && origins.isEmpty())) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "ORIGIN_NOT_ALLOWED");
            return;
        }
        if (request.getRequestURI().startsWith("/api/")) {
            List<String> tokens = Collections.list(request.getHeaders(TOKEN_HEADER));
            if (tokens.size() != 1 || !session.matches(tokens.getFirst())) {
                reject(response, HttpServletResponse.SC_UNAUTHORIZED, "SESSION_TOKEN_REQUIRED");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
