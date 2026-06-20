package io.emcip.knowledge.engine.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.IngestionJob;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.ExtractionResult;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.IngestionJobRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.tika.Tika;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    @Mock KnowledgeEventPublisher eventPublisher;
    @Mock IngestionJobRepository jobRepository;
    @Mock KnowledgeExtractionService extractionService;
    @Mock Tika tika;

    KnowledgeExtractionService realExtractionService;
    DocumentIngestionService service;

    @BeforeEach
    void setUp() {
        realExtractionService =
                new KnowledgeExtractionService(
                        documentRepository,
                        vectorSearchRepository,
                        graphRepository,
                        entityResolutionService,
                        llmClient,
                        ontologyService,
                        eventPublisher);
        service = new DocumentIngestionService(jobRepository, extractionService, tika);
    }

    // ── KnowledgeExtractionService.processDocument tests (carried over) ───────

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

        realExtractionService.processDocument(chunk, "https://example.com/doc", tenantId);

        verify(llmClient).extract(eq(chunk), anyList(), anyList());
    }

    @Test
    void processDocument_skipsBlankChunk() {
        realExtractionService.processDocument("   ", "https://example.com/doc", null);
        verifyNoInteractions(llmClient, documentRepository);
    }

    // ── DocumentIngestionService tests ───────────────────────────────────────

    @Test
    void submitUrlIngestion_createsQueuedJobAndReturnsId() {
        when(jobRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            IngestionJob j = inv.getArgument(0);
                            j.setId(UUID.randomUUID());
                            return j;
                        });

        String jobId = service.submitUrlIngestion("https://example.com", null);

        assertThat(jobId).isNotNull();
        ArgumentCaptor<IngestionJob> captor = ArgumentCaptor.forClass(IngestionJob.class);
        verify(jobRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(IngestionJob.IngestionStatus.QUEUED);
        assertThat(captor.getValue().getSourceType()).isEqualTo(IngestionJob.SourceType.URL);
        assertThat(captor.getValue().getSourceRef()).isEqualTo("https://example.com");
    }

    @Test
    void submitUrlIngestion_asyncProcessingCallsProcessDocumentPerChunk() throws Exception {
        UUID jobId = UUID.randomUUID();
        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setStatus(IngestionJob.IngestionStatus.QUEUED);

        when(jobRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            IngestionJob j = inv.getArgument(0);
                            if (j.getId() == null) j.setId(jobId);
                            return j;
                        });
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        // Tika returns 3 words → 1 chunk at default 500-word size
        when(tika.parseToString(any(java.net.URL.class))).thenReturn("word1 word2 word3");

        service.submitUrlIngestion("https://example.com", null);

        await().atMost(5, SECONDS)
                .untilAsserted(
                        () ->
                                verify(extractionService, atLeastOnce())
                                        .processDocument(
                                                eq("word1 word2 word3"),
                                                eq("https://example.com"),
                                                isNull()));
    }

    @Test
    void submitUrlIngestion_setsJobToFailedOnParseError() throws Exception {
        UUID jobId = UUID.randomUUID();
        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setStatus(IngestionJob.IngestionStatus.QUEUED);

        when(jobRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            IngestionJob j = inv.getArgument(0);
                            if (j.getId() == null) j.setId(jobId);
                            return j;
                        });
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(tika.parseToString(any(java.net.URL.class)))
                .thenThrow(new java.io.IOException("connection refused"));

        service.submitUrlIngestion("https://unreachable.invalid", null);

        await().atMost(5, SECONDS)
                .untilAsserted(
                        () -> {
                            ArgumentCaptor<IngestionJob> captor =
                                    ArgumentCaptor.forClass(IngestionJob.class);
                            verify(jobRepository, atLeastOnce()).save(captor.capture());
                            assertThat(captor.getAllValues())
                                    .anyMatch(
                                            j ->
                                                    j.getStatus()
                                                                    == IngestionJob.IngestionStatus
                                                                            .FAILED
                                                            && j.getErrorMessage() != null);
                        });
    }
}
