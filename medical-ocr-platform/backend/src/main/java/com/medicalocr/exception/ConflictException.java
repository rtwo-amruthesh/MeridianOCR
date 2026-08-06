package com.medicalocr.exception;

import org.springframework.http.HttpStatus;

/** Username or email already taken. */
public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
