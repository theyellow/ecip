package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.entity.RelationshipType;
import io.emcip.knowledge.engine.model.ExtractionResult;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedEntity;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedRelationship;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeExtractionService {

    private final KnowledgeDocumentRepository documentRepository;
    private final VectorSearchRepository vectorSearchRepository;
    private final GraphRepository graphRepository;
    private final EntityResolutionService entityResolutionService;
    private final LlmOrchestratorClient llmClient;
    private final OntologyService ontologyService;
    private final KnowledgeEventPublisher eventPublisher;

    @Transactional
    public void processMessage(
            String text,
            String sourceRef,
            UUID tenantId,
            Long chatId,
            String senderId,
            String senderDisplayName,
            String chatTitle,
            Integer messageDate) {
        if (text == null || text.isBlank()) {
            log.debug("Skipping empty message: {}", sourceRef);
            return;
        }

        // Step 1: Store raw content as KnowledgeDocument
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTenantId(tenantId);
        doc.setSourceType("CHAT_MESSAGE");
        doc.setSourceRef(sourceRef);
        doc.setContent(text);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("chatId", chatId);
        metadata.put("senderId", senderId != null ? senderId : "");
        metadata.put("senderDisplayName", senderDisplayName != null ? senderDisplayName : "");
        metadata.put("chatTitle", chatTitle != null ? chatTitle : "");
        metadata.put("messageDate", messageDate);
        doc.setMetadata(metadata);
        doc.setChunkIndex(0);
        KnowledgeDocument saved = documentRepository.saveAndFlush(doc);

        // Step 2: Generate and store embedding
        float[] embedding = llmClient.embed(text);
        if (embedding.length > 0) {
            vectorSearchRepository.storeEmbedding(saved.getId(), embedding);
        }

        // Step 3: LLM-based entity/relationship extraction
        List<ConceptType> conceptTypes = ontologyService.getAllConceptTypes();
        List<RelationshipType> relTypes = ontologyService.getAllRelationshipTypes();

        ExtractionResult result = llmClient.extract(text, conceptTypes, relTypes);

        // Build known-type sets for validation
        Set<String> knownConceptNames =
                conceptTypes.stream().map(ConceptType::getName).collect(Collectors.toSet());
        Set<String> knownRelNames =
                relTypes.stream().map(RelationshipType::getName).collect(Collectors.toSet());

        // Validate and filter entities
        List<ExtractedEntity> validEntities =
                result.entities().stream()
                        .filter(
                                e -> {
                                    if (e.type() == null
                                            || e.type().isBlank()
                                            || e.label() == null
                                            || e.label().isBlank()) {
                                        log.warn(
                                                "Skipping invalid entity: type={}, label={}",
                                                e.type(),
                                                e.label());
                                        return false;
                                    }
                                    if (!knownConceptNames.contains(e.type())) {
                                        log.warn(
                                                "Skipping entity with unknown type: type={},"
                                                        + " label={}",
                                                e.type(),
                                                e.label());
                                        return false;
                                    }
                                    return true;
                                })
                        .toList();

        // Validate and filter relationships
        List<ExtractedRelationship> validRelationships =
                result.relationships().stream()
                        .filter(
                                r -> {
                                    if (r.type() == null
                                            || r.type().isBlank()
                                            || r.source() == null
                                            || r.source().isBlank()
                                            || r.target() == null
                                            || r.target().isBlank()) {
                                        log.warn(
                                                "Skipping invalid relationship: type={}, source={},"
                                                        + " target={}",
                                                r.type(),
                                                r.source(),
                                                r.target());
                                        return false;
                                    }
                                    if (!knownRelNames.contains(r.type())) {
                                        log.warn(
                                                "Skipping relationship with unknown type: type={},"
                                                        + " source={}, target={}",
                                                r.type(),
                                                r.source(),
                                                r.target());
                                        return false;
                                    }
                                    return true;
                                })
                        .toList();

        // Step 4: Entity resolution + graph storage
        for (ExtractedEntity entity : validEntities) {
            entityResolutionService.resolve(entity.label(), entity.type(), tenantId);
            eventPublisher.publishEntityCreated(entity.label(), tenantId);
        }

        for (ExtractedRelationship rel : validRelationships) {
            UUID sourceId =
                    entityResolutionService.resolve(rel.source(), inferType(rel, true), tenantId);
            UUID targetId =
                    entityResolutionService.resolve(rel.target(), inferType(rel, false), tenantId);

            graphRepository.createRelationship(
                    rel.type(), sourceId, targetId, rel.properties(), saved.getId());
        }

        log.info(
                "Processed message {}: {} entities, {} relationships",
                sourceRef,
                validEntities.size(),
                validRelationships.size());
    }

    @Transactional
    public void processDocument(
            String chunk,
            String sourceRef,
            UUID tenantId,
            int chunkIndex,
            Map<String, String> documentMetadata) {
        if (chunk == null || chunk.isBlank()) {
            log.debug("Skipping empty chunk for: {}", sourceRef);
            return;
        }

        // Step 1: Store chunk as KnowledgeDocument (no chat metadata)
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTenantId(tenantId);
        doc.setSourceType("DOCUMENT");
        doc.setSourceRef(sourceRef);
        doc.setContent(chunk);
        doc.setChunkIndex(chunkIndex);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourceRef", sourceRef != null ? sourceRef : "");
        if (documentMetadata != null) {
            metadata.putAll(documentMetadata);
        }
        doc.setMetadata(metadata);
        KnowledgeDocument saved = documentRepository.saveAndFlush(doc);

        // Step 2: Generate and store embedding
        float[] embedding = llmClient.embed(chunk);
        log.info("Embed result for doc {}: dimensions={}", saved.getId(), embedding.length);
        if (embedding.length > 0) {
            vectorSearchRepository.storeEmbedding(saved.getId(), embedding);
            log.info("Stored embedding for doc {}", saved.getId());
        } else {
            log.warn("Empty embedding for doc {}, skipping store", saved.getId());
        }

        // Step 3: LLM entity/relationship extraction
        List<ConceptType> conceptTypes = ontologyService.getAllConceptTypes();
        List<RelationshipType> relTypes = ontologyService.getAllRelationshipTypes();
        ExtractionResult result = llmClient.extract(chunk, conceptTypes, relTypes);

        // Step 4: Filter invalid entries (same logic as processMessage)
        Set<String> knownConceptNames =
                conceptTypes.stream().map(ConceptType::getName).collect(Collectors.toSet());
        Set<String> knownRelNames =
                relTypes.stream().map(RelationshipType::getName).collect(Collectors.toSet());

        List<ExtractedEntity> validEntities =
                result.entities().stream()
                        .filter(
                                e ->
                                        e.type() != null
                                                && !e.type().isBlank()
                                                && e.label() != null
                                                && !e.label().isBlank()
                                                && knownConceptNames.contains(e.type()))
                        .toList();

        List<ExtractedRelationship> validRelationships =
                result.relationships().stream()
                        .filter(
                                r ->
                                        r.type() != null
                                                && !r.type().isBlank()
                                                && r.source() != null
                                                && !r.source().isBlank()
                                                && r.target() != null
                                                && !r.target().isBlank()
                                                && knownRelNames.contains(r.type()))
                        .toList();

        // Step 5: Batch-embed novel entity labels, then resolve with precomputed embeddings
        List<String> allLabels = new ArrayList<>();
        allLabels.addAll(validEntities.stream().map(e -> e.label().toLowerCase().trim()).toList());
        for (ExtractedRelationship rel : validRelationships) {
            allLabels.add(rel.source().toLowerCase().trim());
            allLabels.add(rel.target().toLowerCase().trim());
        }

        // Deduplicate labels for a single batch embed call
        List<String> novelLabels = allLabels.stream().distinct().toList();

        // Single batch embed call for all novel labels
        Map<String, float[]> embeddingMap = new HashMap<>();
        if (!novelLabels.isEmpty()) {
            List<float[]> embeddings = llmClient.embedBatch(novelLabels);
            for (int idx = 0; idx < novelLabels.size() && idx < embeddings.size(); idx++) {
                embeddingMap.put(novelLabels.get(idx), embeddings.get(idx));
            }
        }

        for (ExtractedEntity entity : validEntities) {
            String normalized = entity.label().toLowerCase().trim();
            float[] emb = embeddingMap.getOrDefault(normalized, new float[0]);
            if (emb.length > 0) {
                entityResolutionService.resolve(entity.label(), entity.type(), tenantId, emb);
            } else {
                entityResolutionService.resolve(entity.label(), entity.type(), tenantId);
            }
            eventPublisher.publishEntityCreated(entity.label(), tenantId);
        }

        for (ExtractedRelationship rel : validRelationships) {
            String sourceNorm = rel.source().toLowerCase().trim();
            String targetNorm = rel.target().toLowerCase().trim();
            float[] sourceEmb = embeddingMap.getOrDefault(sourceNorm, new float[0]);
            float[] targetEmb = embeddingMap.getOrDefault(targetNorm, new float[0]);

            UUID sourceId;
            if (sourceEmb.length > 0) {
                sourceId =
                        entityResolutionService.resolve(
                                rel.source(), inferType(rel, true), tenantId, sourceEmb);
            } else {
                sourceId =
                        entityResolutionService.resolve(
                                rel.source(), inferType(rel, true), tenantId);
            }

            UUID targetId;
            if (targetEmb.length > 0) {
                targetId =
                        entityResolutionService.resolve(
                                rel.target(), inferType(rel, false), tenantId, targetEmb);
            } else {
                targetId =
                        entityResolutionService.resolve(
                                rel.target(), inferType(rel, false), tenantId);
            }

            graphRepository.createRelationship(
                    rel.type(), sourceId, targetId, rel.properties(), saved.getId());
        }

        log.info(
                "processDocument complete: sourceRef={}, entities={}, relationships={}",
                sourceRef,
                validEntities.size(),
                validRelationships.size());
    }

    private String inferType(ExtractedRelationship rel, boolean isSource) {
        try {
            var relType = ontologyService.getRelationshipType(rel.type());
            var types = isSource ? relType.getSourceTypes() : relType.getTargetTypes();
            return types.isEmpty() ? "Topic" : types.getFirst();
        } catch (Exception e) {
            log.debug("inferType fallback for rel type {}: {}", rel.type(), e.getMessage());
            return "Topic";
        }
    }
}
