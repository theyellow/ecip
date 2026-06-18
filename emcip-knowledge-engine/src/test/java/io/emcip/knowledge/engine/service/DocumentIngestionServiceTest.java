package io.emcip.knowledge.engine.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.ExtractionResult;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceTest {

    @Mock KnowledgeDocumentRepository documentRepository;
    @Mock VectorSearchRepository vectorSearchRepository;
    @Mock GraphRepository graphRepository;
    @Mock EntityResolutionService entityResolutionService;
    @Mock LlmOrchestratorClient llmClient;
    @Mock OntologyService ontologyService;

    KnowledgeExtractionService extractionService;

    @BeforeEach
    void setUp() {
        extractionService =
                new KnowledgeExtractionService(
                        documentRepository,
                        vectorSearchRepository,
                        graphRepository,
                        entityResolutionService,
                        llmClient,
                        ontologyService);
    }

    @Test
    void processDocument_callsLlmExtractForChunk() {
        String chunk = "Alice met Bob in Berlin to discuss the treaty.";
        UUID tenantId = UUID.randomUUID();

        when(ontologyService.getAllConceptTypes()).thenReturn(List.of());
        when(ontologyService.getAllRelationshipTypes()).thenReturn(List.of());
        when(llmClient.embed(any())).thenReturn(new float[] {0.1f, 0.2f});
        when(documentRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            KnowledgeDocument doc = inv.getArgument(0);
                            doc.setId(UUID.randomUUID());
                            return doc;
                        });
        when(llmClient.extract(eq(chunk), anyList(), anyList()))
                .thenReturn(new ExtractionResult(List.of(), List.of()));

        extractionService.processDocument(chunk, "https://example.com/doc", tenantId);

        verify(llmClient).extract(eq(chunk), anyList(), anyList());
    }

    @Test
    void processDocument_skipsBlankChunk() {
        extractionService.processDocument("   ", "https://example.com/doc", null);
        verifyNoInteractions(llmClient, documentRepository);
    }
}
