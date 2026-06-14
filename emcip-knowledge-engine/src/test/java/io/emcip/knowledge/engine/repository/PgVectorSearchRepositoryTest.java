package io.emcip.knowledge.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class PgVectorSearchRepositoryTest {

    @Autowired private VectorSearchRepository vectorSearchRepository;
    @Autowired private KnowledgeDocumentRepository documentRepository;

    @Test
    void shouldStoreAndSearchByEmbedding() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTenantId(UUID.randomUUID());
        doc.setSourceType("CHAT_MESSAGE");
        doc.setSourceRef("msg-123");
        doc.setContent("Artificial intelligence is transforming industries");
        doc.setChunkIndex(0);
        doc.setMetadata(Map.of("author", "testUser"));
        KnowledgeDocument saved = documentRepository.save(doc);

        float[] embedding = new float[1536];
        embedding[0] = 0.1f;
        embedding[1] = 0.2f;
        embedding[2] = 0.3f;
        vectorSearchRepository.storeEmbedding(saved.getId(), embedding);

        float[] queryEmbedding = new float[1536];
        queryEmbedding[0] = 0.1f;
        queryEmbedding[1] = 0.2f;
        queryEmbedding[2] = 0.29f;
        List<KnowledgeDocument> results =
                vectorSearchRepository.search(queryEmbedding, 5, saved.getTenantId());

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().getId()).isEqualTo(saved.getId());
    }
}
