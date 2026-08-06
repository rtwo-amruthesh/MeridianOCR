package com.medicalocr.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The pool OCR work runs on.
 *
 * The original used CompletableFuture.supplyAsync with no executor, which puts
 * long blocking I/O on the common ForkJoinPool — sized to CPUs minus one and
 * shared with every parallel stream in the JVM. This is bounded, named and has
 * a queue, so a burst of uploads queues instead of starving unrelated work.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "ocrExecutor")
    public TaskExecutor ocrExecutor(
            @Value("${ocr.executor.core-size:2}") int coreSize,
            @Value("${ocr.executor.max-size:4}") int maxSize,
            @Value("${ocr.executor.queue-capacity:50}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ocr-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
