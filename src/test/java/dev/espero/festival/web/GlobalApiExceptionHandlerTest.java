package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.espero.festival.media.GoodsImageUploadTooLargeException;
import dev.espero.festival.media.GoodsImageValidationException;
import dev.espero.festival.media.GoodsImageValidationReason;
import dev.espero.festival.media.MediaProcessingBusyException;
import dev.espero.festival.media.MediaServiceUnavailableException;
import dev.espero.festival.support.ApiMetaTestFixtures;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@ExtendWith(OutputCaptureExtension.class)
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
            org.springframework.http.HttpMethod.GET, "/api/v2/unknown-resource", "not found"
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
    void mapsImageValidationToSafe422() {
        GoodsImageValidationException exception = new GoodsImageValidationException(
            GoodsImageValidationReason.CORRUPT_IMAGE,
            "sensitive decoder detail"
        );

        ResponseEntity<ApiErrorResponse> response = handler.handleGoodsImageValidation(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().error().message()).doesNotContain("sensitive");
    }

    @Test
    void mapsBothApplicationAndMultipartParserLimitsToTheSame413Envelope() {
        ResponseEntity<ApiErrorResponse> applicationResponse = handler.handleUploadTooLarge(
            new GoodsImageUploadTooLargeException(),
            request
        );
        ResponseEntity<ApiErrorResponse> parserResponse = handler.handleUploadTooLarge(
            new MaxUploadSizeExceededException(10L * 1024 * 1024),
            request
        );

        assertThat(applicationResponse.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(parserResponse.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(applicationResponse.getBody().error().code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(parserResponse.getBody().error().code()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void mapsProcessingBackPressureToRetryable429WithRoundedUpRetryAfter() {
        ResponseEntity<ApiErrorResponse> response = handler.handleMediaProcessingBusy(
            new MediaProcessingBusyException(Duration.ofMillis(1001)),
            request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(response.getBody().error().code()).isEqualTo("RATE_LIMITED");
        assertThat(response.getBody().error().retryable()).isTrue();
    }

    @Test
    void mapsMediaInfrastructureFailureToSafeRetryable503() {
        ResponseEntity<ApiErrorResponse> response = handler.handleMediaServiceUnavailable(
            new MediaServiceUnavailableException("C:\\sensitive\\media", new IOException("codec stderr")),
            request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error().code()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.getBody().error().message()).doesNotContain("sensitive", "stderr");
        assertThat(response.getBody().error().retryable()).isTrue();
    }

    @Test
    void mapsAnUnsupportedTypeWithoutJsonOrMultipartSupportToTheGenericMessage() {
        HttpMediaTypeNotSupportedException exception = new HttpMediaTypeNotSupportedException(
            MediaType.TEXT_PLAIN,
            java.util.List.of(MediaType.APPLICATION_XML),
            HttpMethod.POST
        );

        ResponseEntity<ApiErrorResponse> response = handler.handleUnsupportedMediaType(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody().error().code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(response.getBody().error().message()).isEqualTo("지원하지 않는 Content-Type입니다.");
        assertThat(response.getBody().error().retryable()).isFalse();
    }

    @Test
    void mapsUnexpectedExceptionTo500WithoutLeakingDetails(CapturedOutput output) {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/v2/config");

        ResponseEntity<ApiErrorResponse> response = handler.handleUnexpected(
            new IllegalStateException("jdbc:postgresql://user:secret@db.invalid/festival"),
            request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().error().message()).doesNotContain("jdbc:", "secret");
        assertThat(output)
            .contains("Unhandled API exception: method=GET path=/api/v2/config error_type=IllegalStateException")
            .doesNotContain("jdbc:postgresql://user:secret@db.invalid/festival");
    }
}
