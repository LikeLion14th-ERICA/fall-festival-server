package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

@ExtendWith(OutputCaptureExtension.class)
class HttpRequestLogFilterTest {

    @Test
    void logsHandledStreamFailureEvenWhenSuccessLoggingIsDisabled(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v2/media/test");
        var response = new MockHttpServletResponse();
        new HttpRequestLogFilter(60_000, false).doFilter(request, response, (req, res) -> {
            RequestDiagnostics.failure(request, new java.io.IOException("private-message"));
        });
        assertThat(output).contains("http_request", "failed").doesNotContain("private-message");
    }

    @Test
    void waitsForAsyncCompletionAndRecordsStreamFailureOnce(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v2/media/test");
        var response = new MockHttpServletResponse();
        request.setAsyncSupported(true);
        new HttpRequestLogFilter(500, true).doFilter(request, response, (req, res) -> request.startAsync(req, res));
        assertThat(output).doesNotContain("http_request");
        var context = (org.springframework.mock.web.MockAsyncContext) request.getAsyncContext();
        var event = new jakarta.servlet.AsyncEvent(context, request, response, new java.io.IOException("private-stream-error"));
        for (var listener : context.getListeners()) {
            listener.onError(event);
            listener.onComplete(event);
            listener.onComplete(event);
        }
        assertThat(output).contains("status=200", "failed").doesNotContain("private-stream-error");
        assertThat(output.getOut().lines().filter(line -> line.contains("http_request method=")).count()).isEqualTo(1);
    }

    @Test
    void restoresMdcAndDoesNotLogExceptionMessages(CapturedOutput output) throws Exception {
        org.slf4j.MDC.put("request_id", "outer-context");
        try {
            var request = new MockHttpServletRequest("GET", "/api/v2/maps");
            var response = new MockHttpServletResponse();
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new HttpRequestLogFilter(500, true).doFilter(request, response, (req, res) -> {
                    assertThat(org.slf4j.MDC.get("request_id")).isEqualTo(response.getHeader("X-Request-Id"));
                    throw new IllegalStateException("secret-message", new java.io.IOException("secret-cause"));
                })).isInstanceOf(IllegalStateException.class);
            assertThat(org.slf4j.MDC.get("request_id")).isEqualTo("outer-context");
            assertThat(RequestDiagnostics.describe(new IllegalStateException("secret-message")))
                .contains("IllegalStateException").doesNotContain("secret-message");
            assertThat(output).doesNotContain("secret-message", "secret-cause");
        } finally { org.slf4j.MDC.clear(); }
    }

    @Test
    void logsFailedRequestWithSafeRouteAndServerRequestId(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/places/private-value");
        request.setQueryString("token=private-query");
        request.addHeader("X-Request-Id", "untrusted-client-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new HttpRequestLogFilter(500, false).doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v2/places/{placeId}");
            response.setStatus(503);
        });

        assertThat(output).contains("http_request method=GET route=/api/v2/places/{placeId} status=503")
            .contains("request_id=" + ApiMetaSupport.resolveRequestId(request))
            .doesNotContain("private-value", "private-query", "untrusted-client-id");
    }

    @Test
    void keepsNormalSuccessQuietAndLogsItWhenEnabled(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/maps");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new HttpRequestLogFilter(60_000, false).doFilter(request, response, (ignoredRequest, ignoredResponse) -> {});
        assertThat(output).doesNotContain("http_request");

        new HttpRequestLogFilter(60_000, true).doFilter(
            new MockHttpServletRequest("GET", "/api/v2/maps"), new MockHttpServletResponse(),
            (ignoredRequest, ignoredResponse) -> {}
        );
        assertThat(output).contains("http_request method=GET route=unmatched status=200");
    }

    @Test
    void logsUnmatchedFailureWithoutLiteralPath(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v2/private-path");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new HttpRequestLogFilter(500, false).doFilter(request, response,
            (ignoredRequest, ignoredResponse) -> response.setStatus(404));

        assertThat(output).contains("route=unmatched status=404")
            .doesNotContain("private-path");
    }
}
