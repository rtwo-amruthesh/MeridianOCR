package com.medicalocr.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One line as stored.
 *
 * bbox is a four-point polygon in source-image pixels — the same shape the
 * Python service returns and the client draws. Kept as a nested document rather
 * than flattened, so a line and its region can never drift apart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrLine {
    private String text;
    private Double confidence;
    private List<List<Double>> bbox;
    private Integer page;
}
