package com.medicalocr.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One read.
 *
 * The id is the upload's UUID, assigned before the worker starts, so progress
 * and the eventual record share a key. userId is indexed because every read of
 * this collection is scoped by owner — there is no query in the codebase that
 * fetches a result without it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ocr_results")
public class OcrResult {

    @Id
    private String id;

    @Indexed
    private String userId;

    /** As uploaded, already scrubbed by StorageService.safeName. */
    private String fileName;

    /** {uuid}_{safeName} on disk. Never comes from the client. */
    private String storedFileName;

    private String fileType;
    private Long fileSize;

    /** PROCESSING, COMPLETED or FAILED. */
    private String status;

    private List<OcrLine> lines;
    private Integer lineCount;

    /** Mean confidence as a percentage. See OcrResponse for why it's called this. */
    private Double accuracy;

    private Integer pageCount;
    private Integer imageWidth;
    private Integer imageHeight;

    private String summary;
    private String failureReason;

    private LocalDateTime processedAt;
    private LocalDateTime completedAt;
}
