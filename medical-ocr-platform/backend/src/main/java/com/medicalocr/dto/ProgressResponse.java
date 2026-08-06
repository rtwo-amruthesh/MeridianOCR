package com.medicalocr.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pipeline state for one read.
 *
 * status is one of UPLOADING, PROCESSING, EXTRACTING, SUMMARIZING, COMPLETED,
 * FAILED. The web client maps these onto its step indicator, so don't rename
 * them without changing config.js.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProgressResponse {
    private String status;
    private int progress;
    private String message;
    private String resultId;
}
