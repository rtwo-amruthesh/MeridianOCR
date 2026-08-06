package com.medicalocr.exception;

import org.springframework.http.HttpStatus;

/**
 * No such record — or none you own.
 *
 * Deliberately the same response either way. Distinguishing "doesn't exist" from
 * "not yours" would confirm that an id belongs to someone else.
 */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
