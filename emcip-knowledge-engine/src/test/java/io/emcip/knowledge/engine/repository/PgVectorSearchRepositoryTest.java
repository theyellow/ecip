package io.emcip.knowledge.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.SearchResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class PgVectorSearchRepositoryTest {

    @Autowired private VectorSearchRepository vectorSearchRepository;
    @Autowired private KnowledgeDocumentRepository documentRepository;

    @Test
    void shouldReturnRealSimilarityScores() {
        UUID tenantId = UUID.randomUUID();

        // Doc A — embedding close to query
        KnowledgeDocument docA = new KnowledgeDocument();
        docA.setTenantId(tenantId);
        docA.setSourceType("CHAT_MESSAGE");
        docA.setSourceRef("msg-A");
        docA.setContent("close match");
        docA.setChunkIndex(0);
        KnowledgeDocument savedA = documentRepository.save(docA);

        // Doc B — embedding far from query
        KnowledgeDocument docB = new KnowledgeDocument();
        docB.setTenantId(tenantId);
        docB.setSourceType("CHAT_MESSAGE");
        docB.setSourceRef("msg-B");
        docB.setContent("far match");
        docB.setChunkIndex(0);
        KnowledgeDocument savedB = documentRepository.save(docB);

        float[] closeEmbedding = new float[1024];
        closeEmbedding[0] = 1.0f;
        float[] farEmbedding = new float[1024];
        farEmbedding[1] = 1.0f; // orthogonal dimension

        vectorSearchRepository.storeEmbedding(savedA.getId(), closeEmbedding);
        vectorSearchRepository.storeEmbedding(savedB.getId(), farEmbedding);

        float[] queryEmbedding = new float[1024];
        queryEmbedding[0] = 1.0f; // identical to docA

        List<SearchResult<KnowledgeDocument>> results =
                vectorSearchRepository.search(queryEmbedding, 10, tenantId);

        assertThat(results).hasSize(2);
        // First result should be docA with score near 1.0
        assertThat(results.getFirst().item().getId()).isEqualTo(savedA.getId());
        assertThat(results.getFirst().score()).isGreaterThan(0.99);
        // Second result should score lower
        assertThat(results.get(1).score()).isLessThan(results.getFirst().score());
    }
}
