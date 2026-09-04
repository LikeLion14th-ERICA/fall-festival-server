package dev.espero.stamptest.web;

import dev.espero.stamptest.config.ClockTestProperties;
import dev.espero.stamptest.service.RateLimitedException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;
    private final ClockTestProperties properties;

    public GlobalExceptionHandler(Clock clock, ClockTestProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> apiException(ApiException exception, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        if (exception instanceof RateLimitedException rateLimited) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(rateLimited.retryAfterSeconds()));
        }
        return new ResponseEntity<>(body(exception.code(), exception.getMessage(), List.of(), request), headers, exception.status());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiErrorResponse.FieldError> details = exception.getBindingResult().getFieldErrors().stream()
            .map(this::fieldError)
            .toList();
        return ResponseEntity.badRequest()
            .cacheControl(org.springframework.http.CacheControl.noStore())
            .body(body("VALIDATION_ERROR", "The request body is invalid.", details, request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> malformed(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
            .cacheControl(org.springframework.http.CacheControl.noStore())
            .body(body("MALFORMED_JSON", "The JSON request body could not be read.", List.of(), request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
        log.error(
            "Unhandled API exception: method={} path={}",
            request.getMethod(),
            request.getRequestURI(),
            exception
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .cacheControl(org.springframework.http.CacheControl.noStore())
            .body(body("INTERNAL_ERROR", "An unexpected server error occurred.", List.of(), request));
    }

    private ApiErrorResponse body(
        String code,
        String message,
        List<ApiErrorResponse.FieldError> details,
        HttpServletRequest request
    ) {
        return new ApiErrorResponse(new ApiErrorResponse.ErrorBody(
            code,
            message,
            details,
            OffsetDateTime.ofInstant(clock.instant(), properties.zone()),
            request.getRequestURI()
        ));
    }

    private ApiErrorResponse.FieldError fieldError(FieldError error) {
        return new ApiErrorResponse.FieldError(error.getField(), error.getDefaultMessage());
    }
}
