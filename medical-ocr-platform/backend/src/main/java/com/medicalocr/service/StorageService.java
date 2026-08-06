package com.medicalocr.service;

import com.medicalocr.exception.BadRequestException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;

/**
 * Everything that touches the filesystem.
 *
 * The original saved uploads as {@code uuid + "_" + file.getOriginalFilename()}
 * and resolved that against the upload directory. getOriginalFilename() is
 * attacker-controlled, and a UUID prefix does not stop a value like
 * {@code ../../../etc/cron.d/x} from resolving outside the directory. Names are
 * stripped to their final path segment, scrubbed to a safe character set, and
 * the resolved path is then checked to be inside the upload root before a byte
 * is written.
 */
@Slf4j
@Service
public class StorageService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "pdf");
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "application/pdf");

    private final Path root;
    private final long maxBytes;

    public StorageService(@Value("${file.upload.dir}") String uploadDir,
                          @Value("${file.upload.max-bytes}") long maxBytes) {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(root);
        log.info("Upload directory: {}", root);
    }

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Choose a file to upload.");
        }
        if (file.getSize() > maxBytes) {
            throw new BadRequestException(
                    "That file is larger than the " + (maxBytes / 1048576) + " MB limit.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BadRequestException("Unsupported file type. Use PNG, JPEG, WebP or PDF.");
        }
        if (!ALLOWED_EXTENSIONS.contains(extensionOf(safeName(file.getOriginalFilename())))) {
            throw new BadRequestException("Unsupported file extension. Use PNG, JPEG, WebP or PDF.");
        }
    }

    /** Reduces any client-supplied name to a harmless one. */
    public String safeName(String original) {
        if (original == null || original.isBlank()) {
            return "upload";
        }
        // Take only the final segment, defeating both / and \ separators.
        String base = Paths.get(original.replace('\\', '/')).getFileName().toString();
        base = base.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("_{2,}", "_");
        while (base.startsWith(".")) {
            base = base.substring(1);
        }
        if (base.isBlank()) {
            base = "upload";
        }
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }

    public String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Writes the upload and returns the stored file name.
     *
     * Uses Files.copy rather than MultipartFile.transferTo: with a relative
     * configured directory, transferTo resolves against the servlet container's
     * temp directory on some setups rather than the working directory.
     */
    public String store(MultipartFile file, String fileId) throws IOException {
        String storedName = fileId + "_" + safeName(file.getOriginalFilename());
        Path target = resolveInsideRoot(storedName);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return storedName;
    }

    public Path pathOf(String storedName) {
        return resolveInsideRoot(storedName);
    }

    public void deleteQuietly(String storedName) {
        if (storedName == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolveInsideRoot(storedName));
        } catch (IOException | BadRequestException ex) {
            log.warn("Could not delete {}: {}", storedName, ex.getMessage());
        }
    }

    /** The containment check. Nothing writes or reads without passing through here. */
    private Path resolveInsideRoot(String name) {
        Path resolved = root.resolve(name).normalize();
        if (!resolved.startsWith(root)) {
            log.warn("Blocked path traversal attempt: {}", name);
            throw new BadRequestException("Invalid file name.");
        }
        return resolved;
    }
}
