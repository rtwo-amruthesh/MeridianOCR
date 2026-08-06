package com.medicalocr.service;

import com.medicalocr.dto.ProgressResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory pipeline progress.
 *
 * The original used a plain HashMap written from CompletableFuture threads and
 * read from request threads — an unsynchronised map mutated concurrently, which
 * can corrupt its internal structure, not merely return stale values. It also
 * grew forever. This is a ConcurrentHashMap with a sweep.
 *
 * Still process-local: entries vanish on restart and are invisible to other
 * replicas. Move to Redis before running more than one instance.
 */
@Slf4j
@Component
public class ProgressTracker {

    private static final Duration RETENTION = Duration.ofMinutes(30);

    private record Entry(ProgressResponse response, Instant updatedAt) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public void update(String resultId, String status, int progress, String message) {
        entries.put(resultId, new Entry(
                new ProgressResponse(status, progress, message, resultId), Instant.now()));
    }

    public ProgressResponse get(String resultId) {
        Entry entry = entries.get(resultId);
        return entry != null ? entry.response() : null;
    }

    public void forget(String resultId) {
        entries.remove(resultId);
    }

    @Scheduled(fixedDelay = 300_000)
    void sweep() {
        Instant cutoff = Instant.now().minus(RETENTION);
        int before = entries.size();
        entries.entrySet().removeIf(e -> e.getValue().updatedAt().isBefore(cutoff));
        int removed = before - entries.size();
        if (removed > 0) {
            log.debug("Swept {} finished progress entries", removed);
        }
    }
}
