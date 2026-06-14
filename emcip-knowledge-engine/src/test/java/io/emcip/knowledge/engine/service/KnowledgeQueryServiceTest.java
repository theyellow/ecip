package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.SearchRequest;
import io.emcip.knowledge.engine.model.SearchRequest.SearchType;
import io.emcip.knowledge.engine.model.SearchResponse;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeQueryServiceTest {

    @Mock private VectorSearchRepository vectorSearchRepository;
    @Mock private GraphRepository graphRepository;
    @Mock private LlmOrchestratorClient llmClient;

    private KnowledgeQueryService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeQueryService(vectorSearchRepository, graphRepository, llmClient);
    }

    @Test
    void shouldPerformVectorSearch() {
        UUID tenantId = UUID.randomUUID();
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(UUID.randomUUID());
        doc.setContent("AI discussion");
        doc.setCreatedAt(Instant.now());

        when(llmClient.embed("Tell me about AI")).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(vectorSearchRepository.search(any(), eq(20), eq(tenantId))).thenReturn(List.of(doc));

        SearchRequest request =
                new SearchRequest("Tell me about AI", SearchType.VECTOR, tenantId, null, null, 20);

        SearchResponse response = service.search(request);

        assertThat(response.documentResults()).hasSize(1);
    }
}
