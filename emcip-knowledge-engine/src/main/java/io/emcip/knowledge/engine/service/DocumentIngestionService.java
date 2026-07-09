package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.config.IngestionProperties;
import io.emcip.knowledge.engine.entity.IngestionJob;
import io.emcip.knowledge.engine.entity.IngestionJob.IngestionStatus;
import io.emcip.knowledge.engine.entity.IngestionJob.SourceType;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.ChunkSummaryDto;
import io.emcip.knowledge.engine.model.DuplicateSourceException;
import io.emcip.knowledge.engine.model.EntitySummaryDto;
import io.emcip.knowledge.engine.model.ExtractedContent;
import io.emcip.knowledge.engine.model.GraphEdge;
import io.emcip.knowledge.engine.model.IngestionJobDetailDto;
import io.emcip.knowledge.engine.model.IngestionJobDto;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.IngestionJobRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentIngestionService {

    private static final int MAX_CONTENT_BYTES = 10 * 1024 * 1024; // 10 MB
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);
    private static final ExecutorService INGESTION_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    // RT-009: Prompt injection pattern detection
    private static final List<Pattern> INJECTION_PATTERNS =
            List.of(
                    Pattern.compile(
                            "ignore\\s+(all\\s+)?previous\\s+instructions",
                            Pattern.CASE_INSENSITIVE),
                    Pattern.compile("you\\s+are\\s+now\\s+", Pattern.CASE_INSENSITIVE),
                    Pattern.compile(
                            "^\\s*system\\s*:", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
                    Pattern.compile("disregard\\s+(all\\s+)?prior", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("forget\\s+(all\\s+)?previous", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("new\\s+instructions?\\s*:", Pattern.CASE_INSENSITIVE));

    private final IngestionJobRepository jobRepository;
    private final KnowledgeExtractionService extractionService;
    private final TikaExtractionService tikaExtractionService;
    private final SentenceAwareChunker chunker;
    private final IngestionProperties ingestionProperties;
    private final GraphRepository graphRepository;
    private final KnowledgeDocumentRepository documentRepository;

    /** Submit a URL for async ingestion. Returns the job ID immediately. */
    public String submitUrlIngestion(String url, UUID tenantId) {
        return submitUrlIngestion(url, tenantId, null);
    }

    /** Submit a URL for async ingestion, bypassing dedup when replaceJobId is non-null. */
    public String submitUrlIngestion(String url, UUID tenantId, UUID replaceJobId) {
        if (replaceJobId == null) {
            checkSourceRefDuplicate(url, tenantId);
        }
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
        return submitFileIngestion(inputStream, filename, tenantId, null);
    }

    /** Submit a file for async ingestion, bypassing dedup when replaceJobId is non-null. */
    public String submitFileIngestion(
            InputStream inputStream, String filename, UUID tenantId, UUID replaceJobId)
            throws IOException {
        if (replaceJobId == null) {
            checkSourceRefDuplicate(filename, tenantId);
        }
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

    @Transactional
    public void deleteJob(UUID jobId) {
        IngestionJob job =
                jobRepository
                        .findById(jobId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Ingestion job not found: " + jobId));

        List<KnowledgeDocument> chunks = documentRepository.findAllByJobId(jobId);
        List<UUID> chunkIds = chunks.stream().map(KnowledgeDocument::getId).toList();

        if (!chunkIds.isEmpty()) {
            graphRepository.deleteEdgesBySourceMessageIds(chunkIds);
            documentRepository.deleteAllByJobId(jobId);
        }

        jobRepository.deleteById(jobId);
        log.info(
                "Deleted ingestion job {}: {} chunks and their edges removed",
                jobId,
                chunkIds.size());
    }

    public IngestionJobDetailDto getJobDetails(UUID jobId) {
        IngestionJob job = getJob(jobId);
        List<KnowledgeDocument> chunks = documentRepository.findAllByJobId(jobId);
        List<UUID> chunkIds = chunks.stream().map(KnowledgeDocument::getId).toList();

        List<GraphEdge> edges = graphRepository.findEdgesBySourceMessageIds(chunkIds);

        // Build chunk summaries with per-chunk entity/relationship counts
        Map<UUID, Set<UUID>> entityNodesByDoc = new HashMap<>();
        Map<UUID, Long> relCountByDoc = new HashMap<>();
        for (GraphEdge edge : edges) {
            UUID docId = edge.sourceMessageId();
            relCountByDoc.merge(docId, 1L, Long::sum);
            if (edge.targetNodeId() != null) {
                entityNodesByDoc
                        .computeIfAbsent(docId, k -> new HashSet<>())
                        .add(edge.targetNodeId());
            }
        }

        List<ChunkSummaryDto> chunkSummaries =
                chunks.stream()
                        .sorted(java.util.Comparator.comparingInt(KnowledgeDocument::getChunkIndex))
                        .map(
                                doc -> {
                                    String preview =
                                            doc.getContent().length() > 200
                                                    ? doc.getContent().substring(0, 200)
                                                    : doc.getContent();
                                    return new ChunkSummaryDto(
                                            doc.getId(),
                                            doc.getChunkIndex(),
                                            preview,
                                            entityNodesByDoc
                                                    .getOrDefault(doc.getId(), Set.of())
                                                    .size(),
                                            relCountByDoc.getOrDefault(doc.getId(), 0L).intValue());
                                })
                        .toList();

        // Deduplicated entities from edge target nodes
        Set<UUID> seenNodeIds = new HashSet<>();
        List<EntitySummaryDto> entities = new ArrayList<>();
        for (GraphEdge edge : edges) {
            if (edge.targetNodeId() != null && seenNodeIds.add(edge.targetNodeId())) {
                // We need node label + conceptType — query from graph
                graphRepository
                        .findNodeById(edge.targetNodeId())
                        .ifPresent(
                                node ->
                                        entities.add(
                                                new EntitySummaryDto(
                                                        node.label(),
                                                        node.conceptType(),
                                                        node.id())));
            }
        }

        return new IngestionJobDetailDto(
                IngestionJobDto.from(job),
                chunkSummaries,
                entities,
                chunks.size(),
                entities.size(),
                edges.size());
    }

    public String reingestJob(UUID jobId) {
        IngestionJob oldJob = getJob(jobId);

        if (oldJob.getSourceType() == SourceType.FILE_UPLOAD) {
            throw new IllegalArgumentException("REUPLOAD_REQUIRED");
        }

        // Delete old data
        List<KnowledgeDocument> oldChunks = documentRepository.findAllByJobId(jobId);
        List<UUID> oldChunkIds = oldChunks.stream().map(KnowledgeDocument::getId).toList();
        if (!oldChunkIds.isEmpty()) {
            graphRepository.deleteEdgesBySourceMessageIds(oldChunkIds);
            documentRepository.deleteAllByJobId(jobId);
        }

        // Create new job and process
        return submitUrlIngestion(oldJob.getSourceRef(), oldJob.getTenantId(), jobId);
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
            byte[] body = fetchWithTimeout(url);
            ExtractedContent extracted = tikaExtractionService.extract(body);
            if (extracted.text().isBlank()) {
                updateJobStatus(jobId, IngestionStatus.FAILED, null, "No text extracted");
                return;
            }
            if (containsInjectionPatterns(extracted.text())) {
                log.warn("Potential injection patterns detected in document from {}", url);
                updateJobStatus(jobId, IngestionStatus.FLAGGED_INJECTION_RISK, null, null);
                return;
            }
            String contentHash = computeContentHash(extracted.text());
            if (contentHash != null) {
                updateJobContentHash(jobId, contentHash);
                Optional<IngestionJob> hashDuplicate =
                        jobRepository.findCompletedByContentHashAndTenant(
                                contentHash, tenantId, IngestionStatus.COMPLETED, jobId);
                if (hashDuplicate.isPresent()) {
                    IngestionJob dup = hashDuplicate.get();
                    updateJobStatus(
                            jobId,
                            IngestionStatus.COMPLETED,
                            0,
                            "Duplicate content (matches job "
                                    + dup.getId()
                                    + ", source: "
                                    + dup.getSourceRef()
                                    + ")");
                    log.info(
                            "Content hash duplicate detected: jobId={}, matchesJob={}",
                            jobId,
                            dup.getId());
                    return;
                }
            }
            int chunkCount = processChunks(extracted, url, tenantId, jobId);
            if (chunkCount == 0) {
                updateJobStatus(
                        jobId, IngestionStatus.FAILED, null, "No chunks produced after splitting");
                return;
            }
            updateJobStatus(jobId, IngestionStatus.COMPLETED, chunkCount, null);
            log.info(
                    "URL ingestion COMPLETED: jobId={}, url={}, chunks={}", jobId, url, chunkCount);
        } catch (Exception e) {
            log.error("URL ingestion FAILED: jobId={}, url={}: {}", jobId, url, e.getMessage(), e);
            updateJobStatus(jobId, IngestionStatus.FAILED, null, e.getMessage());
        }
    }

    private byte[] fetchWithTimeout(String url) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(FETCH_TIMEOUT).build()) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(FETCH_TIMEOUT)
                            .GET()
                            .build();
            HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (body.length > MAX_CONTENT_BYTES) {
                throw new IOException(
                        "Content too large: "
                                + body.length
                                + " bytes (max "
                                + MAX_CONTENT_BYTES
                                + ")");
            }
            return body;
        }
    }

    private void processFileAsync(UUID jobId, byte[] fileBytes, String filename, UUID tenantId) {
        updateJobStatus(jobId, IngestionStatus.RUNNING, null, null);
        try {
            ExtractedContent extracted = tikaExtractionService.extract(fileBytes);
            if (extracted.text().isBlank()) {
                updateJobStatus(jobId, IngestionStatus.FAILED, null, "No text extracted");
                return;
            }
            if (containsInjectionPatterns(extracted.text())) {
                log.warn("Potential injection patterns detected in document from {}", filename);
                updateJobStatus(jobId, IngestionStatus.FLAGGED_INJECTION_RISK, null, null);
                return;
            }
            String contentHash = computeContentHash(extracted.text());
            if (contentHash != null) {
                updateJobContentHash(jobId, contentHash);
                Optional<IngestionJob> hashDuplicate =
                        jobRepository.findCompletedByContentHashAndTenant(
                                contentHash, tenantId, IngestionStatus.COMPLETED, jobId);
                if (hashDuplicate.isPresent()) {
                    IngestionJob dup = hashDuplicate.get();
                    updateJobStatus(
                            jobId,
                            IngestionStatus.COMPLETED,
                            0,
                            "Duplicate content (matches job "
                                    + dup.getId()
                                    + ", source: "
                                    + dup.getSourceRef()
                                    + ")");
                    log.info(
                            "Content hash duplicate detected: jobId={}, matchesJob={}",
                            jobId,
                            dup.getId());
                    return;
                }
            }
            int chunkCount = processChunks(extracted, filename, tenantId, jobId);
            if (chunkCount == 0) {
                updateJobStatus(
                        jobId, IngestionStatus.FAILED, null, "No chunks produced after splitting");
                return;
            }
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

    private int processChunks(
            ExtractedContent extracted, String sourceRef, UUID tenantId, UUID jobId) {
        List<String> chunks = chunker.chunk(extracted.text());
        Map<String, String> metadata = new HashMap<>(extracted.metadata());
        metadata.put("totalChunks", String.valueOf(chunks.size()));
        Map<String, String> immutableMetadata = Map.copyOf(metadata);

        Semaphore semaphore = new Semaphore(ingestionProperties.parallelism());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            final int chunkIndex = i;
            final String chunk = chunks.get(i);
            CompletableFuture<Void> future =
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    semaphore.acquire();
                                    try {
                                        extractionService.processDocument(
                                                chunk,
                                                sourceRef,
                                                tenantId,
                                                chunkIndex,
                                                immutableMetadata,
                                                jobId);
                                    } finally {
                                        semaphore.release();
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new RuntimeException("Chunk processing interrupted", e);
                                }
                            },
                            INGESTION_EXECUTOR);
            futures.add(future);
        }

        // Virtual threads park on join() without consuming carrier threads.
        // Each chunk runs in its own @Transactional scope — chunk failures are isolated.
        // allOf().join() propagates the first failure; other chunk exceptions are lost.
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
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
    void updateJobStatus(
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

    private void checkSourceRefDuplicate(String sourceRef, UUID tenantId) {
        jobRepository
                .findCompletedBySourceRefAndTenant(sourceRef, tenantId, IngestionStatus.COMPLETED)
                .ifPresent(
                        existing -> {
                            throw new DuplicateSourceException(sourceRef, existing.getId());
                        });
    }

    private String computeContentHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("Failed to compute content hash: {}", e.getMessage());
            return null;
        }
    }

    @Transactional
    void updateJobContentHash(UUID jobId, String contentHash) {
        jobRepository
                .findById(jobId)
                .ifPresent(
                        job -> {
                            job.setContentHash(contentHash);
                            jobRepository.save(job);
                        });
    }

    /** RT-009: Scan content for common prompt injection patterns. Case-insensitive. */
    private boolean containsInjectionPatterns(String content) {
        if (content == null || content.isBlank()) return false;
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }
}
