package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.entity.IngestionJob;
import io.emcip.knowledge.engine.entity.IngestionJob.IngestionStatus;
import io.emcip.knowledge.engine.entity.IngestionJob.SourceType;
import io.emcip.knowledge.engine.repository.IngestionJobRepository;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentIngestionService {

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;
    private static final ExecutorService INGESTION_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final IngestionJobRepository jobRepository;
    private final KnowledgeExtractionService extractionService;
    private final Tika tika;

    /** Submit a URL for async ingestion. Returns the job ID immediately. */
    public String submitUrlIngestion(String url, UUID tenantId) {
        IngestionJob job = createAndSaveJob(SourceType.URL, url, tenantId);
        UUID jobId = job.getId();
        INGESTION_EXECUTOR.submit(() -> processUrlAsync(jobId, url, tenantId));
        return jobId.toString();
    }

    /**
     * Submit a file for async ingestion. Reads all bytes immediately (before HTTP request ends),
     * then processes asynchronously. Returns the job ID immediately.
     */
    public String submitFileIngestion(InputStream inputStream, String filename, UUID tenantId)
            throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        IngestionJob job = createAndSaveJob(SourceType.FILE_UPLOAD, filename, tenantId);
        UUID jobId = job.getId();
        INGESTION_EXECUTOR.submit(() -> processFileAsync(jobId, bytes, filename, tenantId));
        return jobId.toString();
    }

    public IngestionJob getJob(UUID jobId) {
        return jobRepository
                .findById(jobId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Ingestion job not found: " + jobId));
    }

    public Page<IngestionJob> listJobs(UUID tenantId, Pageable pageable) {
        if (tenantId != null) {
            return jobRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
        }
        return jobRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @PreDestroy
    void shutdown() {
        INGESTION_EXECUTOR.shutdown();
        try {
            if (!INGESTION_EXECUTOR.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Ingestion executor did not terminate gracefully; forcing shutdown");
                INGESTION_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            INGESTION_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── private async workers ────────────────────────────────────────────────

    private void processUrlAsync(UUID jobId, String url, UUID tenantId) {
        updateJobStatus(jobId, IngestionStatus.RUNNING, null, null);
        try {
            String text = tika.parseToString(new URL(url));
            int chunkCount = processChunks(text, url, tenantId);
            updateJobStatus(jobId, IngestionStatus.COMPLETED, chunkCount, null);
            log.info(
                    "URL ingestion COMPLETED: jobId={}, url={}, chunks={}", jobId, url, chunkCount);
        } catch (Exception e) {
            log.error("URL ingestion FAILED: jobId={}, url={}: {}", jobId, url, e.getMessage(), e);
            updateJobStatus(jobId, IngestionStatus.FAILED, null, e.getMessage());
        }
    }

    private void processFileAsync(UUID jobId, byte[] fileBytes, String filename, UUID tenantId) {
        updateJobStatus(jobId, IngestionStatus.RUNNING, null, null);
        try {
            String text = tika.parseToString(new ByteArrayInputStream(fileBytes));
            int chunkCount = processChunks(text, filename, tenantId);
            updateJobStatus(jobId, IngestionStatus.COMPLETED, chunkCount, null);
            log.info(
                    "File ingestion COMPLETED: jobId={}, file={}, chunks={}",
                    jobId,
                    filename,
                    chunkCount);
        } catch (Exception e) {
            log.error(
                    "File ingestion FAILED: jobId={}, file={}: {}",
                    jobId,
                    filename,
                    e.getMessage(),
                    e);
            updateJobStatus(jobId, IngestionStatus.FAILED, null, e.getMessage());
        }
    }

    private int processChunks(String text, String sourceRef, UUID tenantId) {
        List<String> chunks = chunkText(text, CHUNK_SIZE, CHUNK_OVERLAP);
        for (String chunk : chunks) {
            extractionService.processDocument(chunk, sourceRef, tenantId);
        }
        return chunks.size();
    }

    private IngestionJob createAndSaveJob(SourceType sourceType, String sourceRef, UUID tenantId) {
        IngestionJob job = new IngestionJob();
        job.setSourceType(sourceType);
        job.setSourceRef(sourceRef);
        job.setTenantId(tenantId);
        job.setStatus(IngestionStatus.QUEUED);
        job.setCreatedAt(OffsetDateTime.now());
        return jobRepository.save(job);
    }

    @Transactional
    private void updateJobStatus(
            UUID jobId, IngestionStatus status, Integer chunkCount, String errorMessage) {
        Optional<IngestionJob> opt = jobRepository.findById(jobId);
        if (opt.isEmpty()) {
            log.warn("updateJobStatus: job not found: {}", jobId);
            return;
        }
        IngestionJob job = opt.get();
        job.setStatus(status);
        if (chunkCount != null) job.setChunkCount(chunkCount);
        if (errorMessage != null) job.setErrorMessage(errorMessage);
        jobRepository.save(job);
    }

    private List<String> chunkText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;
        String[] words = text.split("\\s+");
        int start = 0;
        while (start < words.length) {
            int end = Math.min(start + chunkSize, words.length);
            chunks.add(String.join(" ", Arrays.copyOfRange(words, start, end)));
            start += chunkSize - overlap;
        }
        return chunks;
    }
}
