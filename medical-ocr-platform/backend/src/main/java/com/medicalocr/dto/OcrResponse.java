package com.medicalocr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The full extraction.
 *
 * `accuracy` is mean confidence, not accuracy — the name is kept because the
 * original API used it and clients read it. `meanConfidence` is the honest name;
 * both carry the same number.
 *
 * `extractedText` is the original string[] contract, retained so anything
 * written against v1 keeps working. New clients should read `lines`, where each
 * value still carries its own confidence and its polygon.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OcrResponse {

    private String id;
    private String fileName;
    private String status;
    private String message;

    private Double accuracy;
    private Integer lineCount;
    private Integer pageCount;
    private Integer imageWidth;
    private Integer imageHeight;

    private List<LineDto> lines;
    private List<String> extractedText;

    private String summary;
    private LocalDateTime processedAt;

    /** Same value as accuracy, under the name that describes it. */
    public Double getMeanConfidence() {
        return accuracy;
    }
}
