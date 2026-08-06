package com.medicalocr.repository;

import com.medicalocr.model.OcrResult;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface OcrResultRepository extends MongoRepository<OcrResult, String> {

    /**
     * Ownership is enforced in the query, not after it.
     *
     * Loading by id and then comparing the owner in Java is one forgotten check
     * away from an IDOR. Every read path in OcrService goes through this method,
     * so a record that isn't yours is simply absent.
     */
    Optional<OcrResult> findByIdAndUserId(String id, String userId);

    List<OcrResult> findByUserIdOrderByProcessedAtDesc(String userId);
}
