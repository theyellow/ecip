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
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        doc.setMetadata(
                Map.of(
                        "chatId", chatId,
                        "senderId", senderId,
                        "senderDisplayName", senderDisplayName,
                        "chatTitle", chatTitle,
                        "messageDate", messageDate));
        doc.setChunkIndex(0);
        KnowledgeDocument saved = documentRepository.save(doc);

        // Step 2: Generate and store embedding
        float[] embedding = llmClient.embed(text);
        if (embedding.length > 0) {
            vectorSearchRepository.storeEmbedding(saved.getId(), embedding);
        }

        // Step 3: LLM-based entity/relationship extraction
        List<ConceptType> conceptTypes = ontologyService.getAllConceptTypes();
        List<RelationshipType> relTypes = ontologyService.getAllRelationshipTypes();

        ExtractionResult result = llmClient.extract(text, conceptTypes, relTypes);

        // Step 4: Entity resolution + graph storage
        for (ExtractedEntity entity : result.entities()) {
            entityResolutionService.resolve(entity.label(), entity.type(), tenantId);
        }

        for (ExtractedRelationship rel : result.relationships()) {
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
                result.entities().size(),
                result.relationships().size());
    }

    private String inferType(ExtractedRelationship rel, boolean isSource) {
        try {
            var relType = ontologyService.getRelationshipType(rel.type());
            var types = isSource ? relType.getSourceTypes() : relType.getTargetTypes();
            return types.isEmpty() ? "Topic" : types.getFirst();
        } catch (Exception e) {
            return "Topic";
        }
    }
}
