package io.emcip.knowledge.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.ExtractionResult;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedEntity;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.repository.ConceptTypeRepository;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.service.KnowledgeExtractionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@IntegrationTest
class KnowledgeExtractionIntegrationTest {

    @Autowired private KnowledgeExtractionService extractionService;
    @Autowired private KnowledgeDocumentRepository documentRepository;
    @Autowired private ConceptTypeRepository conceptTypeRepository;

    @MockitoBean private LlmOrchestratorClient llmClient;

    // AgeGraphRepository requires Apache AGE extension not present in pgvector/pgvector:pg16
    @MockitoBean private GraphRepository graphRepository;

    @BeforeEach
    void clean() {
        documentRepository.deleteAll();
        conceptTypeRepository.deleteAll();
    }

    @Test
    void processMessage_persistsDocumentWithMetadata() {
        ConceptType person = new ConceptType();
        person.setName("PERSON");
        person.setDescription("A human individual");
        person.setShared(false);
        conceptTypeRepository.save(person);

        // graphRepository is mocked — stub createNode so EntityResolutionService.resolve()
        // doesn't NPE when it falls through to node creation (AGE not in test container image)
        when(graphRepository.createNode(any(), any(), any(), any()))
                .thenAnswer(
                        inv ->
                                new GraphNode(
                                        UUID.randomUUID(),
                                        inv.getArgument(0),
                                        inv.getArgument(3),
                                        inv.getArgument(1),
                                        Map.of(),
                                        Instant.now(),
                                        Instant.now()));

        // Return empty float[] so storeEmbedding is skipped (vector(1024) constraint in DB)
        when(llmClient.embed(any())).thenReturn(new float[0]);
        when(llmClient.extract(any(), any(), any()))
                .thenReturn(
                        new ExtractionResult(
                                List.of(new ExtractedEntity("PERSON", "Alice", Map.of())),
                                List.of()));

        UUID tenantId = UUID.randomUUID();
        extractionService.processMessage(
                "Alice met Bob at the summit",
                "tg:100:42",
                tenantId,
                100L,
                "999",
                "Alice Smith",
                "TestGroup",
                1718272800);

        List<KnowledgeDocument> docs = documentRepository.findAll();
        assertThat(docs).hasSize(1);

        KnowledgeDocument doc = docs.get(0);
        assertThat(doc.getSourceRef()).isEqualTo("tg:100:42");
        assertThat(doc.getTenantId()).isEqualTo(tenantId);
        assertThat(doc.getMetadata()).isNotNull();
        assertThat(((Number) doc.getMetadata().get("chatId")).longValue()).isEqualTo(100L);
        assertThat(doc.getMetadata()).containsEntry("chatTitle", "TestGroup");
        assertThat(doc.getMetadata()).containsEntry("senderDisplayName", "Alice Smith");
    }
}
