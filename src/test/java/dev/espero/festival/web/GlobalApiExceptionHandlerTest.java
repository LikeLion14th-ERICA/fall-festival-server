package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.espero.festival.support.ApiMetaTestFixtures;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalApiExceptionHandlerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2030-10-01T09:00:00Z"), ZoneOffset.UTC);
    private final ApiMetaSupport metaSupport = ApiMetaTestFixtures.systemMetaSupport(clock);
    private final GlobalApiExceptionHandler handler = new GlobalApiExceptionHandler(metaSupport);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void mapsApiExceptionToItsDeclaredStatusAndCode() {
        ApiException exception = new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE, "STAMP_GUIDE_NOT_CONFIGURED", "안내가 없습니다.", true
        );

        ResponseEntity<ApiErrorResponse> response = handler.handleApiException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error().code()).isEqualTo("STAMP_GUIDE_NOT_CONFIGURED");
        assertThat(response.getBody().error().retryable()).isTrue();
        assertThat(response.getBody().meta().revision()).isZero();
    }

    @Test
    void mapsMissingFestivalContextToRetryable503WithSystemMeta() {
        ApiException exception = new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "FESTIVAL_CONTEXT_UNAVAILABLE",
            "현재 축제 정보를 제공할 수 없습니다.",
            true
        );

        ResponseEntity<ApiErrorResponse> response = handler.handleApiException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error().code()).isEqualTo("FESTIVAL_CONTEXT_UNAVAILABLE");
        assertThat(response.getBody().error().retryable()).isTrue();
        assertThat(response.getBody().meta().revision()).isZero();
    }

    @Test
    void mapsFestivalDatabaseFailureToRetryable503WithSystemMeta() {
        ApiException exception = new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "SERVICE_UNAVAILABLE",
            "일시적으로 정보를 불러올 수 없습니다.",
            true
        );

        ResponseEntity<ApiErrorResponse> response = handler.handleApiException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error().code()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.getBody().error().retryable()).isTrue();
        assertThat(response.getBody().meta().revision()).isZero();
    }

    @Test
    void mapsUnmappedPathTo404() {
        NoResourceFoundException exception = new NoResourceFoundException(
            org.springframework.http.HttpMethod.GET, "/api/v1/festivals", "not found"
        );

        ResponseEntity<ApiErrorResponse> response = handler.handleNoResourceFound(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void mapsUnsupportedMethodTo405() {
        HttpRequestMethodNotSupportedException exception = new HttpRequestMethodNotSupportedException("POST");

        ResponseEntity<ApiErrorResponse> response = handler.handleMethodNotSupported(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().error().code()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void mapsUnexpectedExceptionTo500WithoutLeakingDetails() {
        ResponseEntity<ApiErrorResponse> response = handler.handleUnexpected(new IllegalStateException("boom"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().error().message()).doesNotContain("boom");
    }
}
