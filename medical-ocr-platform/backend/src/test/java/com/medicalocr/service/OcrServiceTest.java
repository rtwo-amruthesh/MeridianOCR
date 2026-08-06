package com.medicalocr.service;

import com.medicalocr.dto.OcrResponse;
import com.medicalocr.dto.ProgressResponse;
import com.medicalocr.exception.NotFoundException;
import com.medicalocr.model.OcrLine;
import com.medicalocr.model.OcrResult;
import com.medicalocr.repository.OcrResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OcrServiceTest {

    @Mock OcrResultRepository repository;
    @Mock StorageService storage;
    @Mock OcrClient ocrClient;
    @Mock SummaryService summaryService;

    ProgressTracker progress = new ProgressTracker();

    OcrService service;

    @BeforeEach
    void setUp() {
        service = new OcrService(repository, storage, ocrClient, progress, summaryService,
                new SyncTaskExecutor());   // run the worker inline
    }

    private OcrResult record(String id, String owner, String status) {
        OcrResult r = new OcrResult();
        r.setId(id);
        r.setUserId(owner);
        r.setStatus(status);
        r.setFileName("scan.png");
        r.setStoredFileName(id + "_scan.png");
        r.setProcessedAt(LocalDateTime.now());
        return r;
    }

    // ─────────── ownership ───────────

    @Test
    void one_user_cannot_read_another_users_result() {
        when(repository.findByIdAndUserId("r1", "mallory")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getResult("r1", "mallory"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void one_user_cannot_poll_another_users_progress() {
        // The old endpoint took no principal at all, so ids were enumerable.
        when(repository.findByIdAndUserId("r1", "mallory")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProgress("r1", "mallory"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void owner_can_read_their_own_result() {
        OcrResult r = record("r1", "alice", "COMPLETED");
        r.setLines(List.of(new OcrLine("Haemoglobin 11.2", 0.95, List.of(), 1)));
        r.setAccuracy(95.0);
        when(repository.findByIdAndUserId("r1", "alice")).thenReturn(Optional.of(r));

        OcrResponse response = service.getResult("r1", "alice");

        assertThat(response.getLines()).hasSize(1);
        assertThat(response.getLines().get(0).getConfidence()).isEqualTo(0.95);
        // Legacy string[] shape still populated for older clients.
        assertThat(response.getExtractedText()).containsExactly("Haemoglobin 11.2");
    }

    // ─────────── pipeline ───────────

    @Test
    void successful_read_persists_lines_and_boxes() throws Exception {
        OcrResult stored = record("f1", "alice", "PROCESSING");
        when(repository.findById("f1")).thenReturn(Optional.of(stored));
        when(storage.pathOf(anyString())).thenReturn(Path.of("/tmp/f1_scan.png"));
        when(summaryService.summarise(any())).thenReturn("summary");

        OcrClient.ServiceLine line = new OcrClient.ServiceLine();
        line.setText("MRN: MD-4417-2290");
        line.setConfidence(0.64);
        line.setBbox(List.of(List.of(58.0, 210.0), List.of(230.0, 210.0),
                             List.of(230.0, 231.0), List.of(58.0, 231.0)));
        line.setPage(1);

        OcrClient.OcrServiceResponse ocr = new OcrClient.OcrServiceResponse();
        ocr.setLines(List.of(line));
        ocr.setAccuracy(64.0);
        ocr.setWidth(900);
        ocr.setHeight(1200);
        ocr.setPageCount(1);
        when(ocrClient.recognise(any())).thenReturn(ocr);

        service.runOcr("f1", "f1_scan.png");

        verify(repository, atLeastOnce()).save(argThat(r ->
                "COMPLETED".equals(r.getStatus())
                        && r.getLines() != null
                        && r.getLines().size() == 1
                        && r.getLines().get(0).getBbox().size() == 4   // polygon kept
                        && r.getImageWidth() == 900));

        assertThat(progress.get("f1").getStatus()).isEqualTo("COMPLETED");
        assertThat(progress.get("f1").getProgress()).isEqualTo(100);
    }

    @Test
    void failed_read_marks_the_record_and_does_not_escape() {
        OcrResult stored = record("f2", "alice", "PROCESSING");
        when(repository.findById("f2")).thenReturn(Optional.of(stored));
        when(storage.pathOf(anyString())).thenReturn(Path.of("/tmp/f2_scan.png"));
        when(ocrClient.recognise(any())).thenThrow(new RuntimeException("engine down"));

        service.runOcr("f2", "f2_scan.png");   // must not throw

        verify(repository).save(argThat(r -> "FAILED".equals(r.getStatus())));
        assertThat(progress.get("f2").getStatus()).isEqualTo("FAILED");
    }

    @Test
    void upload_validates_before_touching_disk() {
        var file = new MockMultipartFile("file", "x.exe", "application/x-msdownload", "MZ".getBytes());
        doThrow(new com.medicalocr.exception.BadRequestException("Unsupported file type."))
                .when(storage).validate(any());

        assertThatThrownBy(() -> service.processImage("alice", file))
                .isInstanceOf(com.medicalocr.exception.BadRequestException.class);

        verifyNoInteractions(ocrClient);
        verify(repository, never()).save(any());
    }

    // ─────────── progress fallback ───────────

    @Test
    void progress_falls_back_to_stored_status_after_a_restart() {
        // Nothing in the tracker: the in-memory map does not survive a restart.
        when(repository.findByIdAndUserId("r9", "alice"))
                .thenReturn(Optional.of(record("r9", "alice", "COMPLETED")));

        ProgressResponse p = service.getProgress("r9", "alice");

        assertThat(p.getStatus()).isEqualTo("COMPLETED");
        assertThat(p.getProgress()).isEqualTo(100);
    }
}
