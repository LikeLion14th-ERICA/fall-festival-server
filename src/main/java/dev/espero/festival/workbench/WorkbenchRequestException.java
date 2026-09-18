package dev.espero.festival.workbench;

import org.springframework.http.HttpStatus;

/** A request the workbench refuses before it reaches the catalog services. */
class WorkbenchRequestException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    WorkbenchRequestException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
