package com.medicalocr.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base for failures that already know their HTTP status.
 *
 * GlobalExceptionHandler has one handler for this type, so a new failure mode
 * only needs a subclass — not another @ExceptionHandler method.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
