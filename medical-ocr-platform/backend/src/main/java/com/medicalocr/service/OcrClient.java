package com.medicalocr.service;

import com.medicalocr.exception.UnprocessableException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Calls the Python OCR service.
 *
 * Uses RestClient rather than a blocking WebClient. The original created a
 * WebClient as a field initialiser and then called .block() on it, which pulls
 * in all of WebFlux to do synchronous work and blocks a reactor thread.
 */
@Slf4j
@Component
public class OcrClient {

    private final RestClient restClient;
    private final String ocrUrl;
    private final String serviceToken;

    public OcrClient(@Value("${ocr.service.url}") String ocrUrl,
                     @Value("${ocr.service.token:}") String serviceToken,
                     @Value("${ocr.service.timeout-seconds:120}") long timeoutSeconds) {
        this.ocrUrl = ocrUrl;
        this.serviceToken = serviceToken;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public OcrServiceResponse recognise(Path file) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new FileSystemResource(file));

        try {
            OcrServiceResponse response = restClient.post()
                    .uri(ocrUrl)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .headers(h -> {
                        if (serviceToken != null && !serviceToken.isBlank()) {
                            h.set("X-Service-Token", serviceToken);
                        }
                    })
                    .body(parts)
                    .retrieve()
                    .body(OcrServiceResponse.class);

            if (response == null) {
                throw new UnprocessableException("The OCR service returned an empty response.");
            }
            return response;
        } catch (RestClientException ex) {
            log.error("OCR service call failed", ex);
            throw new UnprocessableException("Couldn't reach the OCR service. Try again shortly.");
        }
    }

    @Data
    public static class OcrServiceResponse {
        private List<ServiceLine> lines;
        private Integer lineCount;
        private Double meanConfidence;
        private Double accuracy;
        private Integer width;
        private Integer height;
        private Integer pageCount;
    }

    @Data
    public static class ServiceLine {
        private String text;
        private Double confidence;
        private List<List<Double>> bbox;
        private Integer page;
    }
}
