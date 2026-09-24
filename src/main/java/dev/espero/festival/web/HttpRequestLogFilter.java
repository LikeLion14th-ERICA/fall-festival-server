package dev.espero.festival.web;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** One completion event per request, including security rejections and streamed responses. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class HttpRequestLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(HttpRequestLogFilter.class);
    private final long slowRequestMs;
    private final boolean logSuccess;

    HttpRequestLogFilter(
        @Value("${festival.http-observation.slow-request-ms:500}") long slowRequestMs,
        @Value("${festival.http-observation.log-success:true}") boolean logSuccess
    ) {
        if (slowRequestMs < 1) throw new IllegalArgumentException("slow-request-ms must be positive");
        this.slowRequestMs = slowRequestMs;
        this.logSuccess = logSuccess;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {
        long start = System.nanoTime();
        String requestId = ApiMetaSupport.resolveRequestId(request);
        String previousId = MDC.get("request_id");
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        Runnable finish = () -> {
            if (completed.compareAndSet(false, true)) write(request, response, requestId, start, failed.get());
        };
        MDC.put("request_id", requestId);
        response.setHeader("X-Request-Id", requestId);
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException failure) {
            failed.set(true);
            RequestDiagnostics.failure(request, failure);
            throw failure;
        } finally {
            try {
                if (request.isAsyncStarted()) {
                    AsyncListener listener = new AsyncListener() {
                        @Override public void onComplete(AsyncEvent event) { finish.run(); }
                        @Override public void onError(AsyncEvent event) {
                            failed.set(true);
                            if (event.getThrowable() != null) RequestDiagnostics.failure(request, event.getThrowable());
                        }
                        @Override public void onTimeout(AsyncEvent event) {
                            failed.set(true);
                            RequestDiagnostics.error(request, "ASYNC_TIMEOUT");
                        }
                        @Override public void onStartAsync(AsyncEvent event) {
                            event.getAsyncContext().addListener(this);
                        }
                    };
                    try { request.getAsyncContext().addListener(listener); }
                    catch (IllegalStateException alreadyCompleted) { finish.run(); }
                } else {
                    finish.run();
                }
            } finally {
                if (previousId == null) MDC.remove("request_id");
                else MDC.put("request_id", previousId);
            }
        }
    }

    private void write(HttpServletRequest request, HttpServletResponse response, String requestId,
        long start, boolean failed) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        int status = response.getStatus();
        Object diagnostic = request.getAttribute(RequestDiagnostics.FAILURE);
        failed = failed || diagnostic != null;
        boolean probe = request.getRequestURI().equals("/healthz") || request.getRequestURI().equals("/readyz");
        if (!failed && status < 400 && durationMs < slowRequestMs && (!logSuccess || probe)) return;
        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = matched instanceof String pattern ? pattern : "unmatched";
        String method = switch (request.getMethod()) {
            case "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS" -> request.getMethod();
            default -> "OTHER";
        };
        Object error = request.getAttribute(RequestDiagnostics.ERROR);
        Object metaValue = request.getAttribute(RequestDiagnostics.META);
        ApiMeta meta = metaValue instanceof ApiMeta value ? value : null;
        String locale = meta != null && meta.locale() != null && java.util.Set.of("ko", "en", "zh-Hans").contains(meta.locale())
            ? meta.locale() : "-";
        // A stream can fail after a 200 was committed. Preserve its real status and record the failure separately.
        var event = failed || status >= 500 ? log.atError()
            : status >= 400 || durationMs >= slowRequestMs ? log.atWarn() : log.atInfo();
        String previousId = MDC.get("request_id");
        MDC.put("request_id", requestId);
        try {
            event.addKeyValue("event", "http_request").addKeyValue("method", method).addKeyValue("route", route)
                .addKeyValue("status", status).addKeyValue("duration_ms", durationMs)
                .addKeyValue("error_code", error == null ? "-" : error)
                .addKeyValue("diagnostic", diagnostic == null ? "-" : diagnostic)
                .addKeyValue("completion", failed ? "failed" : "completed")
                .addKeyValue("response_committed", response.isCommitted())
                .addKeyValue("revision", meta == null ? 0 : meta.revision()).addKeyValue("locale", locale)
                .log("http_request method={} route={} status={} duration_ms={} request_id={}",
                    method, route, status, durationMs, requestId);
        } finally {
            if (previousId == null) MDC.remove("request_id");
            else MDC.put("request_id", previousId);
        }
    }
}
