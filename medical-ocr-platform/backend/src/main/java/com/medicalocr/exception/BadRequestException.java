package com.medicalocr.exception;

import org.springframework.http.HttpStatus;

/** The request itself is wrong — wrong file type, missing field, unsafe name. */
public class BadRequestException extends ApiException {
    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
