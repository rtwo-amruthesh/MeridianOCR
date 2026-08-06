package com.medicalocr.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A short, honest description of what was read.
 *
 * The previous generateSummary was labelled "medical document summarization
 * logic" but counted lines and grepped for twelve keywords, then pasted the
 * first 500 characters of raw text. That reads as a claim the code cannot
 * support. This states only what can actually be determined — length, apparent
 * document type, and whether numeric results are present — and says plainly
 * that nothing has been interpreted.
 *
 * Field-level extraction happens in the client, where every value stays
 * attached to its confidence and its region on the page.
 */
@Service
public class SummaryService {

    private static final Map<String, List<String>> DOCUMENT_HINTS = Map.of(
            "laboratory result", List.of("reference range", "ref. range", "analyte", "specimen",
                    "haemoglobin", "hemoglobin", "wbc", "platelet"),
            "radiology report", List.of("impression", "findings", "contrast", "radiologist"),
            "discharge summary", List.of("discharge", "admission", "course in hospital"),
            "prescription", List.of("rx", "sig", "dispense", "refill", "tablet", "mg daily"),
            "consent form", List.of("consent", "signature", "witness", "i authorise", "i authorize")
    );

    public String summarise(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "No text was recognised in this document. It may be blank, "
                    + "too low-resolution, or rotated beyond what the reader handles.";
        }

        String haystack = String.join(" ", lines).toLowerCase(Locale.ROOT);

        String type = DOCUMENT_HINTS.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(haystack::contains))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);

        long numericLines = lines.stream().filter(l -> l.matches(".*\\d.*")).count();

        StringBuilder summary = new StringBuilder();
        summary.append("Recognised ").append(lines.size()).append(" lines of text");
        if (type != null) {
            summary.append(". The wording suggests a ").append(type);
        }
        summary.append(".\n\n");
        summary.append(numericLines).append(" lines contain numbers, so there are likely ")
               .append("measurements or identifiers to check.\n\n");
        summary.append("Nothing here has been interpreted clinically. Values, identifiers and ")
               .append("dates are machine-read and need review against the original scan.");

        return summary.toString();
    }
}
