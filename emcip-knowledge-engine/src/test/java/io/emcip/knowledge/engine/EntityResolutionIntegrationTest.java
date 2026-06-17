package io.emcip.knowledge.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.ResolutionFlagRepository;
import io.emcip.knowledge.engine.service.EntityResolutionService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@IntegrationTest
class EntityResolutionIntegrationTest {

    @Autowired private EntityResolutionService resolutionService;
    @Autowired private ResolutionFlagRepository resolutionFlagRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private GraphRepository graphRepository;
    @MockitoBean private LlmOrchestratorClient llmClient;

    private static final UUID SEEDED_NODE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TEST_CONCEPT_TYPE = "TOPIC";
    private static final String SEEDED_LABEL = "artificial intelligence";

    @BeforeEach
    void clean() {
        resolutionFlagRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM ke_graph_node_embeddings");
    }

    /** Build a 1536-dim float[] with value 1.0 at position 0 and 0.0 elsewhere. */
    private float[] seedVector() {
        float[] v = new float[1536];
        v[0] = 1.0f;
        return v;
    }

    private String toVectorString(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(v[i]);
        }
        return sb.append("]").toString();
    }

    @Test
    void shouldMergeWhenSimilarityAboveMergeThreshold() {
        UUID tenantId = UUID.randomUUID();
        float[] vector = seedVector();

        // Seed the node embedding directly via JDBC
        jdbcTemplate.update(
                "INSERT INTO ke_graph_node_embeddings"
                        + " (node_id, label, concept_type, tenant_id, embedding)"
                        + " VALUES (?::uuid, ?, ?, ?, ?::vector)",
                SEEDED_NODE_ID.toString(),
                SEEDED_LABEL,
                TEST_CONCEPT_TYPE,
                tenantId,
                toVectorString(vector));

        // Level 1: no exact match for normalized "ai"
        when(graphRepository.findByLabelAndType("ai", TEST_CONCEPT_TYPE, tenantId))
                .thenReturn(Optional.empty());

        // Level 3: findEmbedding("ai", ...) returns empty (label "ai" not in DB),
        // so llmClient.embed is called — return the same 1536-dim vector → cosine similarity = 1.0
        when(llmClient.embed("ai")).thenReturn(vector);

        UUID result = resolutionService.resolve("AI", TEST_CONCEPT_TYPE, tenantId);

        // Should return the seeded node ID (merge path: score 1.0 >= merge threshold 0.92)
        assertThat(result).isEqualTo(SEEDED_NODE_ID);

        // No flag written for above-threshold merge
        assertThat(resolutionFlagRepository.count()).isZero();
    }

    @Test
    void shouldCreateSilentlyWhenNoEmbeddingMatch() {
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();
        GraphNode newNode =
                new GraphNode(
                        UUID.randomUUID(),
                        TEST_CONCEPT_TYPE,
                        tenantId,
                        "blockchain",
                        Map.of(),
                        now,
                        now);

        // Level 1: no exact match
        when(graphRepository.findByLabelAndType("blockchain", TEST_CONCEPT_TYPE, tenantId))
                .thenReturn(Optional.empty());

        // Level 4: create new node
        when(graphRepository.createNode(
                        ArgumentMatchers.eq(TEST_CONCEPT_TYPE),
                        ArgumentMatchers.eq("blockchain"),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.eq(tenantId)))
                .thenReturn(newNode);

        // Level 3: embed returns empty → similarity path is skipped entirely
        when(llmClient.embed("blockchain")).thenReturn(new float[0]);

        UUID result = resolutionService.resolve("Blockchain", TEST_CONCEPT_TYPE, tenantId);

        assertThat(result).isEqualTo(newNode.id());
        assertThat(resolutionFlagRepository.count()).isZero();
    }
}
