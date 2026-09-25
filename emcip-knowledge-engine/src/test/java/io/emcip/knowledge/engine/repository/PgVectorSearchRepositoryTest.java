package io.emcip.knowledge.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.SearchResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@IntegrationTest
class PgVectorSearchRepositoryTest {

    @Autowired private VectorSearchRepository vectorSearchRepository;
    @Autowired private KnowledgeDocumentRepository documentRepository;

    @Value("${knowledge.embedding.dimension}")
    private int embeddingDimension;

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

        float[] closeEmbedding = new float[embeddingDimension];
        closeEmbedding[0] = 1.0f;
        float[] farEmbedding = new float[embeddingDimension];
        farEmbedding[1] = 1.0f; // orthogonal dimension

        vectorSearchRepository.storeEmbedding(savedA.getId(), closeEmbedding);
        vectorSearchRepository.storeEmbedding(savedB.getId(), farEmbedding);

        float[] queryEmbedding = new float[embeddingDimension];
        queryEmbedding[0] = 1.0f; // identical to docA

        // Only this test's two documents: a tenant search also returns global documents, and
        // other tests in the shared database may have seeded some (P3.8a).
        List<SearchResult<KnowledgeDocument>> results =
                vectorSearchRepository.search(queryEmbedding, 10, tenantId).stream()
                        .filter(r -> r.item().getTenantId() != null)
                        .toList();

        assertThat(results).hasSize(2);
        // First result should be docA with score near 1.0
        assertThat(results.getFirst().item().getId()).isEqualTo(savedA.getId());
        assertThat(results.getFirst().score()).isGreaterThan(0.99);
        // Second result should score lower
        assertThat(results.get(1).score()).isLessThan(results.getFirst().score());
    }

    // ---- P3.8a tenant rule: own + global when a tenant is set, global only when null ----

    private static final int PROBE_DIM = 777;

    private UUID seedDocument(UUID tenantId, String content) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTenantId(tenantId);
        doc.setSourceType("CHAT_MESSAGE");
        doc.setSourceRef("p38a-" + UUID.randomUUID());
        doc.setContent(content);
        doc.setChunkIndex(0);
        KnowledgeDocument saved = documentRepository.save(doc);
        float[] embedding = new float[embeddingDimension];
        embedding[PROBE_DIM] = 1.0f;
        vectorSearchRepository.storeEmbedding(saved.getId(), embedding);
        return saved.getId();
    }

    private float[] probeQuery() {
        float[] q = new float[embeddingDimension];
        q[PROBE_DIM] = 1.0f;
        return q;
    }

    @Test
    void search_withTenant_returnsOwnAndGlobalButNeverAnotherTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID ownDoc = seedDocument(tenantA, "p38a own");
        UUID globalDoc = seedDocument(null, "p38a global");
        UUID otherDoc = seedDocument(tenantB, "p38a other");

        List<UUID> ids =
                vectorSearchRepository.search(probeQuery(), 50, tenantA).stream()
                        .map(r -> r.item().getId())
                        .toList();

        assertThat(ids).contains(ownDoc, globalDoc).doesNotContain(otherDoc);
    }

    @Test
    void search_withNullTenant_returnsGlobalOnly() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantDoc = seedDocument(tenantA, "p38a tenant-owned");
        UUID globalDoc = seedDocument(null, "p38a global-null");

        List<UUID> ids =
                vectorSearchRepository.search(probeQuery(), 50, null).stream()
                        .map(r -> r.item().getId())
                        .toList();

        assertThat(ids).contains(globalDoc).doesNotContain(tenantDoc);
    }

    @Test
    void hybridSearch_withNullTenant_returnsGlobalOnly() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantDoc = seedDocument(tenantA, "p38a-hybrid-marker tenant");
        UUID globalDoc = seedDocument(null, "p38a-hybrid-marker global");

        List<UUID> ids =
                vectorSearchRepository
                        .hybridSearch("p38a-hybrid-marker", probeQuery(), 50, null)
                        .stream()
                        .map(KnowledgeDocument::getId)
                        .toList();

        assertThat(ids).contains(globalDoc).doesNotContain(tenantDoc);
    }
}
