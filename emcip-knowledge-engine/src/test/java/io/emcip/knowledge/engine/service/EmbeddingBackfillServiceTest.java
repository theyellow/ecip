package io.emcip.knowledge.engine.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
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
class EmbeddingBackfillServiceTest {

    @Mock KnowledgeDocumentRepository documentRepository;
    @Mock VectorSearchRepository vectorSearchRepository;
    @Mock LlmOrchestratorClient llmClient;

    EmbeddingBackfillService service;

    @BeforeEach
    void setUp() {
        service =
                new EmbeddingBackfillService(documentRepository, vectorSearchRepository, llmClient);
    }

    @Test
    void preview_returnsCountOfDocumentsWithNullEmbedding() {
        when(documentRepository.countWithNullEmbedding()).thenReturn(5L);

        var status = service.preview();

        assertThat(status.status()).isEqualTo("PREVIEW");
        assertThat(status.total()).isEqualTo(5);
        assertThat(status.processed()).isZero();
    }

    @Test
    void triggerBackfill_embedsDocumentsWithNullEmbedding() {
        KnowledgeDocument doc1 = new KnowledgeDocument();
        doc1.setId(UUID.randomUUID());
        doc1.setContent("Hello world");

        KnowledgeDocument doc2 = new KnowledgeDocument();
        doc2.setId(UUID.randomUUID());
        doc2.setContent("Second document");

        when(documentRepository.findAllWithNullEmbedding()).thenReturn(List.of(doc1, doc2));
        when(llmClient.embed("Hello world")).thenReturn(new float[] {0.1f, 0.2f});
        when(llmClient.embed("Second document")).thenReturn(new float[] {0.3f, 0.4f});

        String backfillId = service.triggerBackfill();

        await().atMost(5, SECONDS)
                .untilAsserted(
                        () -> {
                            var status = service.getStatus(backfillId);
                            assertThat(status.status()).isEqualTo("COMPLETED");
                            assertThat(status.processed()).isEqualTo(2);
                            assertThat(status.failed()).isZero();
                        });

        verify(vectorSearchRepository).storeEmbedding(eq(doc1.getId()), any());
        verify(vectorSearchRepository).storeEmbedding(eq(doc2.getId()), any());
    }

    @Test
    void triggerBackfill_skipsDocumentsWithEmptyContent() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(UUID.randomUUID());
        doc.setContent("   ");

        when(documentRepository.findAllWithNullEmbedding()).thenReturn(List.of(doc));

        String backfillId = service.triggerBackfill();

        await().atMost(5, SECONDS)
                .untilAsserted(
                        () -> {
                            var status = service.getStatus(backfillId);
                            assertThat(status.status()).isEqualTo("FAILED");
                            assertThat(status.failed()).isEqualTo(1);
                        });

        verify(vectorSearchRepository, never()).storeEmbedding(any(), any());
    }

    @Test
    void triggerBackfill_countsFailedEmbeddings() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(UUID.randomUUID());
        doc.setContent("Some text");

        when(documentRepository.findAllWithNullEmbedding()).thenReturn(List.of(doc));
        when(llmClient.embed("Some text")).thenReturn(new float[0]);

        String backfillId = service.triggerBackfill();

        await().atMost(5, SECONDS)
                .untilAsserted(
                        () -> {
                            var status = service.getStatus(backfillId);
                            assertThat(status.status()).isEqualTo("FAILED");
                            assertThat(status.failed()).isEqualTo(1);
                        });

        verify(vectorSearchRepository, never()).storeEmbedding(any(), any());
    }

    @Test
    void getStatus_returnsNotFoundForUnknownId() {
        var status = service.getStatus("unknown-id");
        assertThat(status.status()).isEqualTo("NOT_FOUND");
    }
}
