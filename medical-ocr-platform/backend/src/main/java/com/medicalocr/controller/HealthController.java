package com.medicalocr.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unauthenticated liveness check at /api/health — this is what the container
 * HEALTHCHECK and most platform probes call. It deliberately reports nothing
 * about Mongo or the OCR service; /actuator/health covers readiness.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Health")
public class HealthController {

    @GetMapping("/health")
    @Operation(summary = "Liveness check")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "medical-ocr-api");
        body.put("time", Instant.now().toString());
        return ResponseEntity.ok(body);
    }
}
