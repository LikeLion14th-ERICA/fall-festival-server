package dev.espero.stamptest.web;

import java.time.OffsetDateTime;
import java.util.List;

public record ApiErrorResponse(ErrorBody error) {

    public record ErrorBody(
        String code,
        String message,
        List<FieldError> details,
        OffsetDateTime timestamp,
        String path
    ) {}

    public record FieldError(String field, String message) {}
}
