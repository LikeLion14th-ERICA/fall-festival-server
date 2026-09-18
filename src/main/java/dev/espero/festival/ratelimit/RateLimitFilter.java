package dev.espero.festival.ratelimit;

import dev.espero.festival.auth.ApiSecurityErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the per-client limits to API v2 requests before authentication, so
 * a flood of login or receipt guesses never reaches the application. Health,
 * readiness and documentation routes are not limited.
 */
class RateLimitFilter extends OncePerRequestFilter {

    static final String STAMP_RECEIPT_PATH = "/api/v2/stamp-receipt-verifications";

    private final RateLimitProperties properties;
    private final RequestRateLimiter limiter;
    private final ApiSecurityErrorWriter errors;

    RateLimitFilter(RateLimitProperties properties, RequestRateLimiter limiter, ApiSecurityErrorWriter errors) {
        this.properties = properties;
        this.limiter = limiter;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String policyName = policyName(request.getMethod(), path);
        if (policyName != null) {
            long retryAfter = limiter.acquire(policyName, policy(policyName), client(request));
            if (retryAfter > 0) {
                response.setHeader("Retry-After", Long.toString(retryAfter));
                errors.write(request, response, HttpStatus.TOO_MANY_REQUESTS.value(),
                    "RATE_LIMITED", "잠시 후 다시 요청해 주세요.", true);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    static String policyName(String method, String path) {
        if (method.equals("OPTIONS") || !path.startsWith("/api/v2/")) {
            return null;
        }
        if (method.equals("POST") && path.equals(STAMP_RECEIPT_PATH)) {
            return "stamp-receipt";
        }
        if (method.equals("POST")
            && (path.equals("/api/v2/admin/sessions") || path.equals("/api/v2/admin/sessions/refresh"))) {
            return "admin-login";
        }
        if (path.startsWith("/api/v2/admin/")) {
            return "admin";
        }
        return "public-read";
    }

    private RateLimitProperties.Policy policy(String name) {
        return switch (name) {
            case "stamp-receipt" -> properties.stampReceipt();
            case "admin-login" -> properties.adminLogin();
            case "admin" -> properties.admin();
            default -> properties.publicRead();
        };
    }

    /**
     * Each trusted proxy appends the address it received the request from, so
     * with {@code n} trusted hops the client is the {@code n}-th entry from the
     * right. A shorter chain did not pass through every trusted proxy; the
     * socket address is used then.
     */
    String client(HttpServletRequest request) {
        int hops = properties.trustedProxyHops();
        if (hops == 0) {
            return request.getRemoteAddr();
        }
        List<String> chain = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders("X-Forwarded-For"))) {
            for (String entry : header.split(",")) {
                if (!entry.isBlank()) {
                    chain.add(entry.strip());
                }
            }
        }
        return chain.size() >= hops ? chain.get(chain.size() - hops) : request.getRemoteAddr();
    }
}
