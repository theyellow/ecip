package io.emcip.knowledge.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * P3.8a: findSimilarNodes against real pgvector — own + global with a tenant, global only without.
 */
@IntegrationTest
class GraphNodeEmbeddingTenantScopeTest {

    private static final int PROBE_DIM = 777;

    @Autowired private GraphNodeEmbeddingRepository repository;

    @Value("${knowledge.embedding.dimension}")
    private int embeddingDimension;

    private UUID seed(UUID tenantId) {
        UUID nodeId = UUID.randomUUID();
        float[] embedding = new float[embeddingDimension];
        embedding[PROBE_DIM] = 1.0f;
        repository.storeEmbeddingWithNodeId(nodeId, "p38a-" + nodeId, "Topic", tenantId, embedding);
        return nodeId;
    }

    private List<UUID> similar(UUID tenantId) {
        float[] q = new float[embeddingDimension];
        q[PROBE_DIM] = 1.0f;
        return repository.findSimilarNodes(q, tenantId, 50).stream().map(r -> r.nodeId()).toList();
    }

    @Test
    void withTenant_returnsOwnAndGlobalButNeverAnotherTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID own = seed(tenantA);
        UUID global = seed(null);
        UUID other = seed(UUID.randomUUID());

        assertThat(similar(tenantA)).contains(own, global).doesNotContain(other);
    }

    @Test
    void withNullTenant_returnsGlobalOnly() {
        UUID tenantOwned = seed(UUID.randomUUID());
        UUID global = seed(null);

        assertThat(similar(null)).contains(global).doesNotContain(tenantOwned);
    }
}
