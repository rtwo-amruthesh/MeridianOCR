package com.medicalocr.service;

import com.medicalocr.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageServiceTest {

    @TempDir
    Path tempDir;

    private StorageService storage;

    @BeforeEach
    void setUp() throws IOException {
        storage = new StorageService(tempDir.toString(), 10 * 1024 * 1024);
        storage.init();
    }

    // ─────────── path traversal ───────────

    @ParameterizedTest
    @ValueSource(strings = {
            "../../../etc/passwd",
            "..\\..\\windows\\system32\\config",
            "/etc/cron.d/backdoor",
            "....//....//evil.png",
            "subdir/nested.png",
    })
    void strips_traversal_from_uploaded_names(String hostile) {
        String safe = storage.safeName(hostile);
        assertThat(safe).doesNotContain("/").doesNotContain("\\").doesNotContain("..");
    }

    @Test
    void writes_stay_inside_the_upload_root() throws IOException {
        var file = new MockMultipartFile("file", "../../escape.png", "image/png", "data".getBytes());
        String stored = storage.store(file, "abc-123");

        Path written = tempDir.resolve(stored).normalize();
        assertThat(written.startsWith(tempDir)).isTrue();
        assertThat(Files.exists(written)).isTrue();
        // Nothing landed outside the root.
        assertThat(Files.exists(tempDir.getParent().resolve("escape.png"))).isFalse();
    }

    @Test
    void rejects_lookups_that_resolve_outside_the_root() {
        assertThatThrownBy(() -> storage.pathOf("../../../etc/passwd"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void leading_dots_are_removed_so_dotfiles_cannot_be_written() {
        assertThat(storage.safeName("...bashrc")).isEqualTo("bashrc");
    }

    @Test
    void blank_names_get_a_fallback() {
        assertThat(storage.safeName(null)).isEqualTo("upload");
        assertThat(storage.safeName("   ")).isNotBlank();
    }

    // ─────────── validation ───────────

    @Test
    void rejects_files_over_the_limit() throws IOException {
        StorageService small = new StorageService(tempDir.toString(), 10);
        small.init();
        var big = new MockMultipartFile("file", "scan.png", "image/png", new byte[64]);

        assertThatThrownBy(() -> small.validate(big))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("larger than");
    }

    @Test
    void rejects_executables_disguised_by_content_type() {
        var evil = new MockMultipartFile("file", "payload.exe",
                "application/x-msdownload", "MZ".getBytes());
        assertThatThrownBy(() -> storage.validate(evil))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejects_mismatched_extension_even_with_an_allowed_content_type() {
        var evil = new MockMultipartFile("file", "payload.sh", "image/png", "x".getBytes());
        assertThatThrownBy(() -> storage.validate(evil))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejects_empty_uploads() {
        var empty = new MockMultipartFile("file", "scan.png", "image/png", new byte[0]);
        assertThatThrownBy(() -> storage.validate(empty))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void accepts_a_normal_scan() {
        var ok = new MockMultipartFile("file", "report.png", "image/png", "data".getBytes());
        storage.validate(ok);   // no exception
    }
}
