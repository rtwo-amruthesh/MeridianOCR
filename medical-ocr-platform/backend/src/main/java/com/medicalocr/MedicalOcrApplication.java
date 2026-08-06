package com.medicalocr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point.
 *
 * @EnableScheduling is required — ProgressTracker.sweep() is a @Scheduled method
 * and without this the in-memory progress map grows without bound.
 */
@SpringBootApplication
@EnableScheduling
public class MedicalOcrApplication {

    public static void main(String[] args) {
        SpringApplication.run(MedicalOcrApplication.class, args);
    }
}
