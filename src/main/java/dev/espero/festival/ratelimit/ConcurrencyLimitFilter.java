package dev.espero.festival.ratelimit;

import dev.espero.festival.auth.ApiRequestPath;
import dev.espero.festival.auth.ApiSecurityErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds how many public API requests run at once, so a traffic spike is
 * answered with a quick retryable 503 instead of every request queueing for a
 * database connection. Hyped writes get their own smaller bulkhead below the
 * connection pool size, leaving connections for every other screen. Admin
 * routes are exempt so operators can still act during a public spike.
 */
class ConcurrencyLimitFilter extends OncePerRequestFilter {

    private static final Pattern ARTIST_HYPED_MUTATION = Pattern.compile("^/api/v2/artists/[^/]+/hyped$");

    private final Semaphore publicApi;
    private final Semaphore hypedWrites;
    private final long maxWaitMs;
    private final ApiSecurityErrorWriter errors;

    ConcurrencyLimitFilter(OverloadProperties properties, ApiSecurityErrorWriter errors) {
        this.publicApi = new Semaphore(properties.publicMaxConcurrent(), true);
        this.hypedWrites = new Semaphore(properties.hypedWriteMaxConcurrent(), true);
        this.maxWaitMs = properties.maxWaitMs();
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        String path;
        try {
            path = ApiRequestPath.of(request);
        } catch (IllegalArgumentException exception) {
            // The rate limit filter already rejects undecodable paths.
            chain.doFilter(request, response);
            return;
        }
        if (request.getMethod().equals("OPTIONS") || !path.startsWith("/api/v2/")
            || path.startsWith("/api/v2/admin/")) {
            chain.doFilter(request, response);
            return;
        }
        Semaphore bulkhead = request.getMethod().equals("POST") && ARTIST_HYPED_MUTATION.matcher(path).matches()
            ? hypedWrites : publicApi;
        if (!acquire(bulkhead)) {
            response.setHeader("Retry-After", "1");
            errors.write(request, response, HttpStatus.SERVICE_UNAVAILABLE.value(),
                "SERVICE_UNAVAILABLE", "요청이 많아 잠시 후 다시 시도해 주세요.", true);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            bulkhead.release();
        }
    }

    private boolean acquire(Semaphore bulkhead) {
        try {
            return bulkhead.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
