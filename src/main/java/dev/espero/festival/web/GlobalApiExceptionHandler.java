package dev.espero.festival.web;

import dev.espero.festival.media.GoodsImageUploadTooLargeException;
import dev.espero.festival.media.GoodsImageValidationException;
import dev.espero.festival.media.MediaProcessingBusyException;
import dev.espero.festival.media.MediaServiceUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    private final ApiMetaSupport metaSupport;

    public GlobalApiExceptionHandler(ApiMetaSupport metaSupport) {
        this.metaSupport = metaSupport;
    }

    /**
     * Spring throws this for any request that matches no controller and no
     * static resource (e.g. still-unimplemented API paths). Without this
     * handler it would otherwise fall through to the generic Exception
     * handler below and report 500 instead of 404.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ApiErrorResponse> handleNoResourceFound(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorResponse(
            new ApiErrorResponse.ErrorBody("NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", List.of(), false),
            metaSupport.metaForError(request)
        ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
        HttpRequestMethodNotSupportedException exception,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(new ApiErrorResponse(
            new ApiErrorResponse.ErrorBody("METHOD_NOT_ALLOWED", "지원하지 않는 메서드입니다.", List.of(), false),
            metaSupport.metaForError(request)
        ));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiErrorResponse(
            new ApiErrorResponse.ErrorBody(exception.code(), exception.getMessage(), List.of(), exception.retryable()),
            metaSupport.metaForError(request)
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(
        MethodArgumentNotValidException exception,
        HttpServletRequest request
    ) {
        List<ApiErrorResponse.ErrorDetail> details = exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new ApiErrorResponse.ErrorDetail(error.getField(), "INVALID"))
            .toList();
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
            new ApiErrorResponse.ErrorBody("VALIDATION_ERROR", "요청 값을 확인해 주세요.", details, false),
            metaSupport.metaForError(request)
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadableBody(
        HttpMessageNotReadableException exception,
        HttpServletRequest request
    ) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
            new ApiErrorResponse.ErrorBody("INVALID_REQUEST", "요청 본문을 확인해 주세요.", List.of(), false),
            metaSupport.metaForError(request)
        ));
    }

    @ExceptionHandler(GoodsImageValidationException.class)
    ResponseEntity<ApiErrorResponse> handleGoodsImageValidation(
        GoodsImageValidationException exception,
        HttpServletRequest request
    ) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "요청 파일을 확인해 주세요.", false, request);
    }

    @ExceptionHandler({GoodsImageUploadTooLargeException.class, MaxUploadSizeExceededException.class})
    ResponseEntity<ApiErrorResponse> handleUploadTooLarge(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "업로드 파일은 10 MiB 이하여야 합니다.", false, request);
    }

    @ExceptionHandler(MediaProcessingBusyException.class)
    ResponseEntity<ApiErrorResponse> handleMediaProcessingBusy(
        MediaProcessingBusyException exception,
        HttpServletRequest request
    ) {
        long retryAfterSeconds = retryAfterSeconds(exception.retryAfter());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
            .body(errorBody("RATE_LIMITED", "잠시 후 다시 요청해 주세요.", true, request));
    }

    @ExceptionHandler(MediaServiceUnavailableException.class)
    ResponseEntity<ApiErrorResponse> handleMediaServiceUnavailable(
        MediaServiceUnavailableException exception,
        HttpServletRequest request
    ) {
        RequestDiagnostics.failure(request, exception);
        log.error("Goods media infrastructure failure: request_id={} diagnostic={}",
            ApiMetaSupport.resolveRequestId(request), RequestDiagnostics.describe(exception));
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "일시적으로 이미지를 처리할 수 없습니다.", true, request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(
        HttpMediaTypeNotSupportedException exception,
        HttpServletRequest request
    ) {
        String message = unsupportedMediaTypeMessage(exception.getSupportedMediaTypes());
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", message, false, request);
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ApiErrorResponse> handleMalformedMultipart(MultipartException exception, HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "요청 파일을 확인해 주세요.", false, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        RequestDiagnostics.failure(request, exception);
        log.error(
            "Unhandled API exception: request_id={} error_type={} diagnostic={}",
            ApiMetaSupport.resolveRequestId(request),
            exception.getClass().getSimpleName(),
            RequestDiagnostics.describe(exception)
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiErrorResponse(
            new ApiErrorResponse.ErrorBody("INTERNAL_ERROR", "처리 중 오류가 발생했습니다.", List.of(), true),
            metaSupport.metaForError(request)
        ));
    }

    private ResponseEntity<ApiErrorResponse> error(
        HttpStatus status,
        String code,
        String message,
        boolean retryable,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(status).body(errorBody(code, message, retryable, request));
    }

    private ApiErrorResponse errorBody(
        String code,
        String message,
        boolean retryable,
        HttpServletRequest request
    ) {
        return new ApiErrorResponse(
            new ApiErrorResponse.ErrorBody(code, message, List.of(), retryable),
            metaSupport.metaForError(request)
        );
    }

    private static long retryAfterSeconds(Duration retryAfter) {
        long millis = Math.max(0, retryAfter.toMillis());
        return Math.max(1, (millis + 999) / 1_000);
    }

    private static String unsupportedMediaTypeMessage(List<MediaType> supportedMediaTypes) {
        if (supportedMediaTypes.stream().anyMatch(MediaType.MULTIPART_FORM_DATA::isCompatibleWith)) {
            return "multipart/form-data 요청이 필요합니다.";
        }
        if (supportedMediaTypes.stream().anyMatch(MediaType.APPLICATION_JSON::isCompatibleWith)) {
            return "application/json 요청이 필요합니다.";
        }
        return "지원하지 않는 Content-Type입니다.";
    }
}
