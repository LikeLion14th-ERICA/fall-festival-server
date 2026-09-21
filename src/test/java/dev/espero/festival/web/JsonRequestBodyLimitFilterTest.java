package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.auth.ApiSecurityErrorWriter;
import dev.espero.festival.support.ApiMetaTestFixtures;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class JsonRequestBodyLimitFilterTest {

    @Test
    void rejectsADeclaredJsonBodyOverTheLimit() throws Exception {
        MockHttpServletRequest request = jsonRequest(JsonRequestBodyLimitFilter.MAX_BODY_BYTES + 1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter().doFilter(request, response, (nextRequest, nextResponse) -> called.set(true));

        assertThat(called).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE", "요청 본문은 64KiB 이하입니다.");
    }

    @Test
    void rejectsAnOverLimitChunkedJsonBodyWithoutADeclaredLength() throws Exception {
        MockHttpServletRequest source = jsonRequest(JsonRequestBodyLimitFilter.MAX_BODY_BYTES + 1);
        HttpServletRequest request = new HttpServletRequestWrapper(source) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter().doFilter(request, response, (nextRequest, nextResponse) -> called.set(true));

        assertThat(called).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void preservesAnExactlyLimitSizedJsonBodyForMvc() throws Exception {
        byte[] body = "x".repeat(JsonRequestBodyLimitFilter.MAX_BODY_BYTES).getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = jsonRequest(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> forwarded = new AtomicReference<>();

        filter().doFilter(request, response, (nextRequest, nextResponse) -> forwarded.set((HttpServletRequest) nextRequest));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(forwarded.get().getInputStream().readAllBytes()).isEqualTo(body);
    }

    @Test
    void leavesMultipartUploadsForTheirTenMiBLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v2/admin/goods-images");
        request.setContentType(MediaType.MULTIPART_FORM_DATA_VALUE);
        request.setContent("x".repeat(JsonRequestBodyLimitFilter.MAX_BODY_BYTES + 1).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> forwarded = new AtomicReference<>();

        filter().doFilter(request, response, (nextRequest, nextResponse) -> forwarded.set((HttpServletRequest) nextRequest));

        assertThat(forwarded.get()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private JsonRequestBodyLimitFilter filter() {
        Clock clock = Clock.fixed(Instant.parse("2030-10-01T09:00:00Z"), ZoneOffset.UTC);
        return new JsonRequestBodyLimitFilter(new ApiSecurityErrorWriter(
            new ObjectMapper(), ApiMetaTestFixtures.systemMetaSupport(clock)
        ));
    }

    private MockHttpServletRequest jsonRequest(int bodySize) {
        return jsonRequest("x".repeat(bodySize).getBytes(StandardCharsets.UTF_8));
    }

    private MockHttpServletRequest jsonRequest(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v2/admin/security-probe");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body);
        return request;
    }
}
