package com.medicalocr.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A row in the history list — deliberately without lines or summary, so listing
 * fifty past reads doesn't drag fifty full extractions across the wire.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoryItem {
    private String id;
    private String fileName;
    private Double accuracy;
    private Integer lineCount;
    private String status;
    private LocalDateTime processedAt;
}
