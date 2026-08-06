package com.medicalocr.controller;

import com.medicalocr.dto.HistoryItem;
import com.medicalocr.dto.OcrResponse;
import com.medicalocr.dto.ProgressResponse;
import com.medicalocr.model.OcrResult;
import com.medicalocr.service.OcrService;
import com.medicalocr.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/ocr")
@RequiredArgsConstructor
@Tag(name = "OCR")
public class OcrController {

    private final OcrService ocrService;
    private final StorageService storage;

    @PostMapping("/upload")
    @Operation(summary = "Upload a scan and start reading it")
    public ResponseEntity<OcrResponse> upload(@RequestParam("file") MultipartFile file,
                                              Authentication authentication) {
        return ResponseEntity.accepted()
                .body(ocrService.processImage(authentication.getName(), file));
    }

    /** Takes the principal now — the original was unauthenticated and enumerable. */
    @GetMapping("/progress/{resultId}")
    @Operation(summary = "Poll pipeline progress for one of your own reads")
    public ResponseEntity<ProgressResponse> progress(@PathVariable String resultId,
                                                     Authentication authentication) {
        return ResponseEntity.ok(ocrService.getProgress(resultId, authentication.getName()));
    }

    @GetMapping("/result/{resultId}")
    @Operation(summary = "Fetch the finished extraction")
    public ResponseEntity<OcrResponse> result(@PathVariable String resultId,
                                              Authentication authentication) {
        return ResponseEntity.ok(ocrService.getResult(resultId, authentication.getName()));
    }

    @GetMapping("/history")
    @Operation(summary = "List your previous reads")
    public ResponseEntity<List<HistoryItem>> history(Authentication authentication) {
        return ResponseEntity.ok(ocrService.getUserResults(authentication.getName()));
    }

    /** Serves the original scan back so a saved result can be reviewed beside it. */
    @GetMapping("/file/{resultId}")
    @Operation(summary = "Download the original scan")
    public ResponseEntity<Resource> file(@PathVariable String resultId,
                                         Authentication authentication) {
        OcrResult record = ocrService.getOwnedRecord(resultId, authentication.getName());
        Resource resource = new FileSystemResource(storage.pathOf(record.getStoredFileName()));
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = record.getFileType() != null
                ? MediaType.parseMediaType(record.getFileType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + storage.safeName(record.getFileName()) + "\"")
                .body(resource);
    }

    @DeleteMapping("/result/{resultId}")
    @Operation(summary = "Delete a read and its stored scan")
    public ResponseEntity<Void> delete(@PathVariable String resultId,
                                       Authentication authentication) {
        ocrService.delete(resultId, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
