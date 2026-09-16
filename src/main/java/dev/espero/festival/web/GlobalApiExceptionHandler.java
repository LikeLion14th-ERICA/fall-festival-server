package dev.espero.festival.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorResponse(
            new ApiErrorResponse.ErrorBody("NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", List.of(), false),
            metaSupport.meta(request, 0, "ko")
        ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
        HttpRequestMethodNotSupportedException exception,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(new ApiErrorResponse(
            new ApiErrorResponse.ErrorBody("METHOD_NOT_ALLOWED", "지원하지 않는 메서드입니다.", List.of(), false),
            metaSupport.meta(request, 0, "ko")
        ));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiErrorResponse(
            new ApiErrorResponse.ErrorBody(exception.code(), exception.getMessage(), List.of(), exception.retryable()),
            metaSupport.meta(request, 0, "ko")
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
            metaSupport.meta(request, 0, "ko")
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadableBody(
        HttpMessageNotReadableException exception,
        HttpServletRequest request
    ) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
            new ApiErrorResponse.ErrorBody("INVALID_REQUEST", "요청 본문을 확인해 주세요.", List.of(), false),
            metaSupport.meta(request, 0, "ko")
        ));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled API exception: method={} path={}", request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiErrorResponse(
            new ApiErrorResponse.ErrorBody("INTERNAL_ERROR", "처리 중 오류가 발생했습니다.", List.of(), true),
            metaSupport.meta(request, 0, "ko")
        ));
    }
}
