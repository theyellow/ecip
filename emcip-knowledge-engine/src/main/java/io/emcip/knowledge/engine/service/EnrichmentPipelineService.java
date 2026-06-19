package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.ConnectorException;
import io.emcip.knowledge.engine.connector.EnrichmentConnectorRegistry;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.KnowledgeConnector;
import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.entity.RunStatus;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes the 6-stage enrichment pipeline for a single source + run:
 *
 * <ol>
 *   <li>Resolve the connector from the registry.
 *   <li>Resolve the API key if the connector requires one.
 *   <li>Fetch results from the connector.
 *   <li>Deduplicate — skip results already present in the document store.
 *   <li>Store new results as {@link KnowledgeDocument} and, best-effort, embed them.
 *   <li>Update {@link EnrichmentRun} counters and final status.
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EnrichmentPipelineService {

    private final EnrichmentConnectorRegistry connectorRegistry;
    private final ApiKeyResolver apiKeyResolver;
    private final KnowledgeDocumentRepository documentRepository;
    private final EnrichmentRunRepository runRepository;
    private final VectorSearchRepository vectorSearchRepository;
    private final LlmOrchestratorClient llmClient;

    /**
     * Runs the full enrichment pipeline and updates the run record on completion.
     *
     * @param source the enrichment source configuration
     * @param run the already-persisted run record (status = RUNNING)
     * @param mode how the run was triggered
     * @param query optional free-text query for TOPIC_DRIVEN / MANUAL runs
     * @param externalId optional external ID for single-item lookup
     * @param tenantId the tenant context for new documents
     */
    @Transactional
    public void execute(
            EnrichmentSource source,
            EnrichmentRun run,
            TriggerMode mode,
            @Nullable String query,
            @Nullable String externalId,
            UUID tenantId) {

        log.info(
                "Starting enrichment pipeline: source={} vendor={} mode={}",
                source.getId(),
                source.getVendorId(),
                mode);

        // Stage 1: resolve connector
        KnowledgeConnector connector =
                connectorRegistry
                        .find(source.getVendorId())
                        .orElseThrow(
                                () ->
                                        new ConnectorException(
                                                "No connector registered for vendor: "
                                                        + source.getVendorId()));

        // Stage 2: resolve API key if required
        String apiKey = null;
        if (connector.requiresApiKey()) {
            apiKey =
                    apiKeyResolver
                            .resolve(source.getVendorId(), tenantId)
                            .orElseThrow(
                                    () ->
                                            new ConnectorException(
                                                    "No API key available for vendor: "
                                                            + source.getVendorId()));
        }

        ConnectorContext ctx = new ConnectorContext(apiKey, tenantId, source.getLastRunAt());
        EnrichmentRequest request =
                new EnrichmentRequest(mode, query, externalId, buildParams(source));

        // Stage 3: fetch from connector
        List<EnrichmentResult> results;
        try {
            results = connector.fetch(request, ctx);
        } catch (ConnectorException e) {
            log.error(
                    "Connector fetch failed for vendor={}: {}",
                    source.getVendorId(),
                    e.getMessage(),
                    e);
            finalizeRun(run, RunStatus.FAILURE, 0, 0, e.getMessage());
            return;
        }

        log.debug("Fetched {} results from vendor={}", results.size(), source.getVendorId());

        // Stages 4–5: deduplicate and store
        int fetched = results.size();
        int ingested = 0;
        List<String> errors = new ArrayList<>();

        for (EnrichmentResult result : results) {
            try {
                // Stage 4: deduplicate
                if (documentRepository.existsBySourceRefAndSourceType(
                        result.externalId(), result.sourceVendorId())) {
                    log.debug(
                            "Skipping duplicate: sourceRef={} sourceType={}",
                            result.externalId(),
                            result.sourceVendorId());
                    continue;
                }

                // Stage 5: store document
                KnowledgeDocument doc = buildDocument(result, tenantId);
                doc = documentRepository.save(doc);
                ingested++;

                // Stage 5b: best-effort embedding — do not let failures block ingestion
                tryEmbed(doc);

            } catch (Exception e) {
                log.error(
                        "Failed to ingest result externalId={}: {}",
                        result.externalId(),
                        e.getMessage(),
                        e);
                errors.add(result.externalId() + ": " + e.getMessage());
            }
        }

        // Stage 6: finalize run
        RunStatus finalStatus = errors.isEmpty() ? RunStatus.SUCCESS : RunStatus.PARTIAL;
        String errorMessage = errors.isEmpty() ? null : String.join("; ", errors);
        finalizeRun(run, finalStatus, fetched, ingested, errorMessage);

        log.info(
                "Enrichment pipeline complete: source={} fetched={} ingested={} status={}",
                source.getId(),
                fetched,
                ingested,
                finalStatus);
    }

    private KnowledgeDocument buildDocument(EnrichmentResult result, UUID tenantId) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTenantId(tenantId);
        doc.setSourceType(result.sourceVendorId());
        doc.setSourceRef(result.externalId());

        String content =
                result.content() != null && !result.content().isBlank()
                        ? result.content()
                        : result.title();
        doc.setContent(content);
        doc.setChunkIndex(0);

        Map<String, Object> meta = result.metadata() != null ? result.metadata() : Map.of();
        doc.setMetadata(meta);

        return doc;
    }

    private void tryEmbed(KnowledgeDocument doc) {
        try {
            float[] embedding = llmClient.embed(doc.getContent());
            if (embedding.length > 0) {
                vectorSearchRepository.storeEmbedding(doc.getId(), embedding);
                log.debug("Stored embedding for document {}", doc.getId());
            }
        } catch (Exception e) {
            log.warn(
                    "Embedding skipped for document {} (best-effort): {}",
                    doc.getId(),
                    e.getMessage());
        }
    }

    private void finalizeRun(
            EnrichmentRun run,
            RunStatus status,
            int fetched,
            int ingested,
            @Nullable String errorMessage) {
        run.setStatus(status);
        run.setItemsFetched(fetched);
        run.setItemsIngested(ingested);
        run.setCompletedAt(Instant.now());
        run.setErrorMessage(errorMessage);
        runRepository.save(run);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> buildParams(EnrichmentSource source) {
        if (source.getConfig() == null) {
            return Map.of();
        }
        try {
            Map<String, Object> config = source.getConfig();
            Map<String, String> params = new java.util.HashMap<>();
            for (Map.Entry<String, Object> entry : config.entrySet()) {
                if (entry.getValue() instanceof String s) {
                    params.put(entry.getKey(), s);
                }
            }
            return params;
        } catch (Exception e) {
            log.warn("Could not parse source config as params: {}", e.getMessage());
            return Map.of();
        }
    }
}
