package com.medicalocr.exception;

import org.springframework.http.HttpStatus;

/**
 * The request was well formed but the work could not be completed — the OCR
 * service was unreachable, or returned nothing usable. Distinct from a 400,
 * because the client did nothing wrong and a retry may well succeed.
 */
public class UnprocessableException extends ApiException {
    public UnprocessableException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
