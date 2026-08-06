package com.medicalocr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;

/**
 * The single error shape.
 *
 * Every message on this object is written for a person to read. Driver text,
 * stack traces and framework internals stay in the log — see
 * GlobalExceptionHandler, which is the only place this is constructed.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private Instant timestamp;
    private int status;
    private String error;
    private String message;
    private String path;

    /** Populated only for validation failures: field name → what to fix. */
    private Map<String, String> fieldErrors;

    public static ErrorResponse of(HttpStatus status, String message, String path) {
        return new ErrorResponse(Instant.now(), status.value(),
                status.getReasonPhrase(), message, path, null);
    }
}
