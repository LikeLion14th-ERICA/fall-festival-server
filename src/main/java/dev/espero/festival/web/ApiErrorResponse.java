package dev.espero.festival.web;

import java.util.List;

public record ApiErrorResponse(ErrorBody error, ApiMeta meta) {

    public record ErrorBody(String code, String message, List<ErrorDetail> details, boolean retryable) {}

    public record ErrorDetail(String field, String reason) {}
}
