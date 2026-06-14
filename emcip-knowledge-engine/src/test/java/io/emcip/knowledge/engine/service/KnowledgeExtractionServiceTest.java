package io.emcip.knowledge.engine.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.ExtractionResult;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedEntity;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedRelationship;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeExtractionServiceTest {

    @Mock private KnowledgeDocumentRepository documentRepository;
    @Mock private VectorSearchRepository vectorSearchRepository;
    @Mock private GraphRepository graphRepository;
    @Mock private EntityResolutionService entityResolutionService;
    @Mock private LlmOrchestratorClient llmClient;
    @Mock private OntologyService ontologyService;

    private KnowledgeExtractionService service;

    @BeforeEach
    void setUp() {
        service =
                new KnowledgeExtractionService(
                        documentRepository,
                        vectorSearchRepository,
                        graphRepository,
                        entityResolutionService,
                        llmClient,
                        ontologyService);
    }

    @Test
    void shouldStoreDocumentAndExtractEntities() {
        UUID tenantId = UUID.randomUUID();
        String text = "Alice discussed AI with Bob";
        String sourceRef = "msg-42";

        when(llmClient.embed(text)).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(documentRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            KnowledgeDocument doc = inv.getArgument(0);
                            doc.setId(UUID.randomUUID());
                            return doc;
                        });

        var entities =
                List.of(
                        new ExtractedEntity("Person", "Alice", Map.of()),
                        new ExtractedEntity("Person", "Bob", Map.of()),
                        new ExtractedEntity("Topic", "AI", Map.of()));
        var relationships =
                List.of(
                        new ExtractedRelationship("DISCUSSES", "Alice", "AI", Map.of()),
                        new ExtractedRelationship("DISCUSSES", "Bob", "AI", Map.of()));
        when(llmClient.extract(eq(text), any(), any()))
                .thenReturn(new ExtractionResult(entities, relationships));

        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        UUID aiId = UUID.randomUUID();
        // Entity loop stubs
        when(entityResolutionService.resolve("Alice", "Person", tenantId)).thenReturn(aliceId);
        when(entityResolutionService.resolve("Bob", "Person", tenantId)).thenReturn(bobId);
        when(entityResolutionService.resolve("AI", "Topic", tenantId)).thenReturn(aiId);
        // Relationship loop stubs — inferType returns "Topic" for both sides (ontologyService not
        // mocked, getRelationshipType throws, caught → "Topic")
        when(entityResolutionService.resolve("Alice", "Topic", tenantId)).thenReturn(aliceId);
        when(entityResolutionService.resolve("Bob", "Topic", tenantId)).thenReturn(bobId);

        service.processMessage(text, sourceRef, tenantId);

        verify(documentRepository).save(any());
        verify(vectorSearchRepository).storeEmbedding(any(), any());
        verify(graphRepository)
                .createRelationship(eq("DISCUSSES"), eq(aliceId), eq(aiId), any(), any());
        verify(graphRepository)
                .createRelationship(eq("DISCUSSES"), eq(bobId), eq(aiId), any(), any());
    }
}
