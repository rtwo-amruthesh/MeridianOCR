package com.medicalocr.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One recognised line, with the polygon it came from.
 *
 * bbox is a four-point polygon in source-image pixels, as PaddleOCR returns it.
 * The first version discarded this, which is why nothing could be traced back to
 * the page. Everything the review bench does depends on it surviving to here.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LineDto {
    private String text;
    private Double confidence;
    private List<List<Double>> bbox;
    private Integer page;
}
