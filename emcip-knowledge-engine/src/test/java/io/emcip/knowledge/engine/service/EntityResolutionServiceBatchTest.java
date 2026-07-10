package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.config.ResolutionConfig.ResolutionProperties;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.repository.EntityAliasRepository;
import io.emcip.knowledge.engine.repository.GraphNodeEmbeddingRepository;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.ResolutionFlagRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntityResolutionServiceBatchTest {

    @Mock private GraphRepository graphRepository;
    @Mock private GraphNodeEmbeddingRepository nodeEmbeddingRepository;
    @Mock private EntityAliasRepository entityAliasRepository;
    @Mock private ResolutionFlagRepository resolutionFlagRepository;
    @Mock private LlmOrchestratorClient llmClient;
    @Mock private ResolutionProperties resolutionProperties;
    @InjectMocks private EntityResolutionService service;

    @Test
    void resolve_withPrecomputedEmbedding_skipsLlmCall() {
        UUID tenantId = UUID.randomUUID();
        float[] precomputed = {0.1f, 0.2f, 0.3f};
        UUID existingNodeId = UUID.randomUUID();

        when(graphRepository.findByLabelAndType("berlin", "LOCATION", tenantId))
                .thenReturn(Optional.empty());
        when(entityAliasRepository.findByConceptTypeAndAliasAndTenantId(
                        "LOCATION", "berlin", tenantId))
                .thenReturn(Optional.empty());
        when(nodeEmbeddingRepository.findNearestNeighbour(precomputed, "LOCATION", tenantId))
                .thenReturn(Optional.empty());
        when(graphRepository.createNode("LOCATION", "berlin", Map.of(), tenantId))
                .thenReturn(
                        new GraphNode(
                                existingNodeId,
                                "LOCATION",
                                tenantId,
                                "berlin",
                                Map.of(),
                                Instant.now(),
                                Instant.now()));

        UUID result = service.resolve("Berlin", "LOCATION", tenantId, precomputed);

        assertThat(result).isEqualTo(existingNodeId);
        // Must NOT call llmClient.embed — embedding was precomputed
        verify(llmClient, never()).embed("berlin");
        // Must store the precomputed embedding with the AGE node ID
        verify(nodeEmbeddingRepository)
                .storeEmbeddingWithNodeId(
                        existingNodeId, "berlin", "LOCATION", tenantId, precomputed);
    }
}
