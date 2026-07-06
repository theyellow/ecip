package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmbeddingBackfillService {

    private static final ExecutorService BACKFILL_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "embedding-backfill");
                        t.setDaemon(true);
                        return t;
                    });
    private static final long DELAY_BETWEEN_EMBEDS_MS = 200;

    private final KnowledgeDocumentRepository documentRepository;
    private final VectorSearchRepository vectorSearchRepository;
    private final LlmOrchestratorClient llmClient;

    private final Map<String, BackfillStatus> activeBackfills = new ConcurrentHashMap<>();

    public BackfillStatus preview() {
        long count = documentRepository.countWithNullEmbedding();
        return new BackfillStatus("preview", "PREVIEW", 0, (int) count, 0, null);
    }

    public String triggerBackfill() {
        // Prevent concurrent backfills
        boolean alreadyRunning =
                activeBackfills.values().stream().anyMatch(s -> "RUNNING".equals(s.status()));
        if (alreadyRunning) {
            return activeBackfills.entrySet().stream()
                    .filter(e -> "RUNNING".equals(e.getValue().status()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse("unknown");
        }

        String backfillId = UUID.randomUUID().toString();
        List<KnowledgeDocument> docs = documentRepository.findAllWithNullEmbedding();

        activeBackfills.put(
                backfillId, new BackfillStatus(backfillId, "RUNNING", 0, docs.size(), 0, null));

        log.info("Embedding backfill triggered: id={}, documents={}", backfillId, docs.size());

        BACKFILL_EXECUTOR.submit(() -> runBackfill(backfillId, docs));

        return backfillId;
    }

    private void runBackfill(String backfillId, List<KnowledgeDocument> docs) {
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        try {
            for (KnowledgeDocument doc : docs) {
                try {
                    String content = doc.getContent();
                    if (content == null || content.isBlank()) {
                        log.debug("Skipping document {} with empty content", doc.getId());
                        failed.incrementAndGet();
                        continue;
                    }

                    float[] embedding = llmClient.embed(content);
                    if (embedding.length > 0) {
                        vectorSearchRepository.storeEmbedding(doc.getId(), embedding);
                        log.debug(
                                "Backfilled embedding for document {}: dimensions={}",
                                doc.getId(),
                                embedding.length);
                    } else {
                        log.warn("Empty embedding returned for document {}, skipping", doc.getId());
                        failed.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error(
                            "Failed to backfill embedding for document {}: {}",
                            doc.getId(),
                            e.getMessage());
                    failed.incrementAndGet();
                }

                processed.incrementAndGet();
                activeBackfills.put(
                        backfillId,
                        new BackfillStatus(
                                backfillId,
                                "RUNNING",
                                processed.get(),
                                docs.size(),
                                failed.get(),
                                null));

                try {
                    Thread.sleep(DELAY_BETWEEN_EMBEDS_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            String finalStatus = failed.get() == docs.size() ? "FAILED" : "COMPLETED";
            activeBackfills.put(
                    backfillId,
                    new BackfillStatus(
                            backfillId,
                            finalStatus,
                            processed.get(),
                            docs.size(),
                            failed.get(),
                            null));

            log.info(
                    "Embedding backfill {} {}: processed={}, failed={}, total={}",
                    backfillId,
                    finalStatus,
                    processed.get(),
                    failed.get(),
                    docs.size());

        } catch (Exception e) {
            log.error("Embedding backfill {} failed: {}", backfillId, e.getMessage(), e);
            activeBackfills.put(
                    backfillId,
                    new BackfillStatus(
                            backfillId,
                            "FAILED",
                            processed.get(),
                            docs.size(),
                            failed.get(),
                            e.getMessage()));
        }
    }

    public BackfillStatus getStatus(String backfillId) {
        return activeBackfills.getOrDefault(
                backfillId, new BackfillStatus(backfillId, "NOT_FOUND", 0, 0, 0, null));
    }

    public record BackfillStatus(
            String backfillId,
            String status,
            int processed,
            int total,
            int failed,
            String errorMessage) {}
}
