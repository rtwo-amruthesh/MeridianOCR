package com.medicalocr.service;

import com.medicalocr.dto.HistoryItem;
import com.medicalocr.dto.LineDto;
import com.medicalocr.dto.OcrResponse;
import com.medicalocr.dto.ProgressResponse;
import com.medicalocr.exception.NotFoundException;
import com.medicalocr.model.OcrLine;
import com.medicalocr.model.OcrResult;
import com.medicalocr.repository.OcrResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrService {

    private final OcrResultRepository repository;
    private final StorageService storage;
    private final OcrClient ocrClient;
    private final ProgressTracker progress;
    private final SummaryService summaryService;

    @Qualifier("ocrExecutor")
    private final TaskExecutor executor;

    /**
     * Accepts the upload, records it, and hands the slow part to a worker.
     *
     * The previous version created one OcrResult object and mutated it from both
     * the request thread and the async callback, so two threads wrote the same
     * document and updates were lost. Here the request thread saves once, and
     * the worker re-reads the document by id before writing.
     */
    public OcrResponse processImage(String userId, MultipartFile file) {
        storage.validate(file);

        String fileId = UUID.randomUUID().toString();
        String originalName = storage.safeName(file.getOriginalFilename());

        progress.update(fileId, "UPLOADING", 10, "Uploading scan…");

        String storedName;
        try {
            storedName = storage.store(file, fileId);
        } catch (IOException ex) {
            log.error("Could not save upload {}", fileId, ex);
            progress.update(fileId, "FAILED", 0, "Couldn't save the file.");
            throw new com.medicalocr.exception.ApiException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "Couldn't save that file. Try again.");
        }

        OcrResult record = new OcrResult();
        record.setId(fileId);
        record.setUserId(userId);
        record.setFileName(originalName);
        record.setStoredFileName(storedName);
        record.setFileType(file.getContentType());
        record.setFileSize(file.getSize());
        record.setStatus("PROCESSING");
        record.setProcessedAt(LocalDateTime.now());
        repository.save(record);

        progress.update(fileId, "PROCESSING", 30, "Queued for reading…");
        executor.execute(() -> runOcr(fileId, storedName));

        OcrResponse response = new OcrResponse();
        response.setId(fileId);
        response.setFileName(originalName);
        response.setStatus("PROCESSING");
        response.setMessage("Upload received. Reading has started.");
        return response;
    }

    /** Runs on a worker thread. Every exit path leaves the record in a terminal state. */
    void runOcr(String fileId, String storedName) {
        try {
            progress.update(fileId, "EXTRACTING", 55, "Detecting text regions…");
            Path path = storage.pathOf(storedName);

            OcrClient.OcrServiceResponse ocr = ocrClient.recognise(path);
            progress.update(fileId, "EXTRACTING", 75, "Recognising text…");

            List<OcrLine> lines = new ArrayList<>();
            if (ocr.getLines() != null) {
                for (OcrClient.ServiceLine line : ocr.getLines()) {
                    lines.add(new OcrLine(line.getText(), line.getConfidence(),
                            line.getBbox(), line.getPage()));
                }
            }

            progress.update(fileId, "SUMMARIZING", 88, "Structuring fields…");
            String summary = summaryService.summarise(
                    lines.stream().map(OcrLine::getText).toList());

            OcrResult record = repository.findById(fileId)
                    .orElseThrow(() -> new NotFoundException("Result record disappeared."));
            record.setLines(lines);
            record.setLineCount(lines.size());
            record.setAccuracy(ocr.getAccuracy());
            record.setPageCount(ocr.getPageCount());
            record.setImageWidth(ocr.getWidth());
            record.setImageHeight(ocr.getHeight());
            record.setSummary(summary);
            record.setStatus("COMPLETED");
            record.setCompletedAt(LocalDateTime.now());
            repository.save(record);

            progress.update(fileId, "COMPLETED", 100, "Ready to verify");
            log.info("Read {} lines from {}", lines.size(), fileId);

        } catch (Exception ex) {
            log.error("OCR failed for {}", fileId, ex);
            progress.update(fileId, "FAILED", 100,
                    "Couldn't read that document. Check the scan quality and try again.");
            repository.findById(fileId).ifPresent(record -> {
                record.setStatus("FAILED");
                record.setFailureReason(ex.getMessage());
                record.setCompletedAt(LocalDateTime.now());
                repository.save(record);
            });
        }
    }

    /**
     * Progress is scoped to the owner.
     *
     * The old endpoint took no principal at all, so any signed-in user could
     * poll any other user's job by guessing an id.
     */
    public ProgressResponse getProgress(String resultId, String userId) {
        OcrResult record = repository.findByIdAndUserId(resultId, userId)
                .orElseThrow(() -> new NotFoundException("No such result."));

        ProgressResponse live = progress.get(resultId);
        if (live != null) {
            return live;
        }
        // Restarted, or swept: fall back to the persisted terminal state.
        return switch (String.valueOf(record.getStatus())) {
            case "COMPLETED" -> new ProgressResponse("COMPLETED", 100, "Ready to verify", resultId);
            case "FAILED" -> new ProgressResponse("FAILED", 100,
                    "Couldn't read that document.", resultId);
            default -> new ProgressResponse("PROCESSING", 50, "Still working…", resultId);
        };
    }

    public OcrResponse getResult(String resultId, String userId) {
        OcrResult record = repository.findByIdAndUserId(resultId, userId)
                .orElseThrow(() -> new NotFoundException("No such result."));
        return toResponse(record);
    }

    public List<HistoryItem> getUserResults(String userId) {
        return repository.findByUserIdOrderByProcessedAtDesc(userId).stream()
                .map(r -> new HistoryItem(r.getId(), r.getFileName(), r.getAccuracy(),
                        r.getLineCount(), r.getStatus(), r.getProcessedAt()))
                .collect(Collectors.toList());
    }

    /** Backs GET /ocr/file/{id} so a saved result can be reopened beside its scan. */
    public OcrResult getOwnedRecord(String resultId, String userId) {
        return repository.findByIdAndUserId(resultId, userId)
                .orElseThrow(() -> new NotFoundException("No such result."));
    }

    public void delete(String resultId, String userId) {
        OcrResult record = getOwnedRecord(resultId, userId);
        storage.deleteQuietly(record.getStoredFileName());
        repository.delete(record);
        progress.forget(resultId);
    }

    private OcrResponse toResponse(OcrResult record) {
        OcrResponse response = new OcrResponse();
        response.setId(record.getId());
        response.setFileName(record.getFileName());
        response.setStatus(record.getStatus());
        response.setAccuracy(record.getAccuracy());
        response.setLineCount(record.getLineCount());
        response.setPageCount(record.getPageCount());
        response.setImageWidth(record.getImageWidth());
        response.setImageHeight(record.getImageHeight());
        response.setSummary(record.getSummary());
        response.setProcessedAt(record.getProcessedAt());

        List<OcrLine> lines = record.getLines() != null ? record.getLines() : List.of();
        response.setLines(lines.stream()
                .map(l -> new LineDto(l.getText(), l.getConfidence(), l.getBbox(), l.getPage()))
                .toList());
        // Kept for clients written against the original string[] contract.
        response.setExtractedText(lines.stream().map(OcrLine::getText).toList());
        return response;
    }
}
