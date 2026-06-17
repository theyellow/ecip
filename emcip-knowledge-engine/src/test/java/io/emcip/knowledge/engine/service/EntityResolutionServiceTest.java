package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.config.ResolutionConfig.ResolutionProperties;
import io.emcip.knowledge.engine.entity.EntityAlias;
import io.emcip.knowledge.engine.entity.ResolutionFlag;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.model.NodeSimilarityResult;
import io.emcip.knowledge.engine.repository.EntityAliasRepository;
import io.emcip.knowledge.engine.repository.GraphNodeEmbeddingRepository;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.ResolutionFlagRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntityResolutionServiceTest {

    @Mock private GraphRepository graphRepository;
    @Mock private EntityAliasRepository entityAliasRepository;
    @Mock private LlmOrchestratorClient llmClient;
    @Mock private GraphNodeEmbeddingRepository nodeEmbeddingRepository;
    @Mock private ResolutionFlagRepository resolutionFlagRepository;

    private final ResolutionProperties resolutionProperties = new ResolutionProperties(0.92, 0.80);

    private EntityResolutionService service;

    @BeforeEach
    void setUp() {
        service =
                new EntityResolutionService(
                        graphRepository,
                        entityAliasRepository,
                        llmClient,
                        nodeEmbeddingRepository,
                        resolutionFlagRepository,
                        resolutionProperties);
    }

    @Test
    void shouldResolveByExactMatch() {
        UUID tenantId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        GraphNode existing =
                new GraphNode(
                        nodeId,
                        "Person",
                        tenantId,
                        "alice",
                        Map.of(),
                        Instant.now(),
                        Instant.now());

        when(graphRepository.findByLabelAndType("alice", "Person", tenantId))
                .thenReturn(Optional.of(existing));

        UUID result = service.resolve("Alice", "Person", tenantId);

        assertThat(result).isEqualTo(nodeId);
    }

    @Test
    void shouldResolveByAlias() {
        UUID tenantId = UUID.randomUUID();
        EntityAlias alias = new EntityAlias();
        alias.setCanonicalLabel("Artificial Intelligence");

        when(graphRepository.findByLabelAndType("ai", "Topic", tenantId))
                .thenReturn(Optional.empty());
        when(entityAliasRepository.findByConceptTypeAndAliasAndTenantId("Topic", "ai", tenantId))
                .thenReturn(Optional.of(alias));

        UUID nodeId = UUID.randomUUID();
        GraphNode existing =
                new GraphNode(
                        nodeId,
                        "Topic",
                        tenantId,
                        "artificial intelligence",
                        Map.of(),
                        Instant.now(),
                        Instant.now());
        when(graphRepository.findByLabelAndType("artificial intelligence", "Topic", tenantId))
                .thenReturn(Optional.of(existing));

        UUID result = service.resolve("AI", "Topic", tenantId);

        assertThat(result).isEqualTo(nodeId);
    }

    @Test
    void shouldCreateNewNodeWhenNoMatch() {
        UUID tenantId = UUID.randomUUID();
        UUID newNodeId = UUID.randomUUID();
        GraphNode newNode =
                new GraphNode(
                        newNodeId,
                        "Topic",
                        tenantId,
                        "quantum computing",
                        Map.of(),
                        Instant.now(),
                        Instant.now());

        when(graphRepository.findByLabelAndType("quantum computing", "Topic", tenantId))
                .thenReturn(Optional.empty());
        when(entityAliasRepository.findByConceptTypeAndAliasAndTenantId(
                        "Topic", "quantum computing", tenantId))
                .thenReturn(Optional.empty());
        when(nodeEmbeddingRepository.findEmbedding("quantum computing", "Topic", tenantId))
                .thenReturn(Optional.empty());
        when(llmClient.embed("quantum computing")).thenReturn(new float[0]);
        when(graphRepository.createNode(eq("Topic"), eq("quantum computing"), any(), eq(tenantId)))
                .thenReturn(newNode);

        UUID result = service.resolve("Quantum Computing", "Topic", tenantId);

        assertThat(result).isEqualTo(newNodeId);
    }

    @Test
    void shouldMergeWhenSimilarityAboveMergeThreshold() {
        UUID tenantId = UUID.randomUUID();
        UUID existingNodeId = UUID.randomUUID();
        float[] embedding = new float[] {0.1f, 0.2f, 0.3f};

        when(graphRepository.findByLabelAndType("artificial intelligence", "Topic", tenantId))
                .thenReturn(Optional.empty());
        when(entityAliasRepository.findByConceptTypeAndAliasAndTenantId(
                        "Topic", "artificial intelligence", tenantId))
                .thenReturn(Optional.empty());
        when(nodeEmbeddingRepository.findEmbedding("artificial intelligence", "Topic", tenantId))
                .thenReturn(Optional.of(embedding));
        when(nodeEmbeddingRepository.findNearestNeighbour(embedding, "Topic", tenantId))
                .thenReturn(Optional.of(new NodeSimilarityResult(existingNodeId, "ai", 0.95)));

        UUID result = service.resolve("Artificial Intelligence", "Topic", tenantId);

        assertThat(result).isEqualTo(existingNodeId);
        verify(graphRepository, never()).createNode(any(), any(), any(), any());
        verify(resolutionFlagRepository, never()).save(any());
    }

    @Test
    void shouldCreateAndFlagWhenSimilarityInGreyZone() {
        UUID tenantId = UUID.randomUUID();
        UUID newNodeId = UUID.randomUUID();
        UUID nearNodeId = UUID.randomUUID();
        float[] embedding = new float[] {0.1f, 0.2f, 0.3f};

        when(graphRepository.findByLabelAndType("artificial intelligence", "Topic", tenantId))
                .thenReturn(Optional.empty());
        when(entityAliasRepository.findByConceptTypeAndAliasAndTenantId(
                        "Topic", "artificial intelligence", tenantId))
                .thenReturn(Optional.empty());
        when(nodeEmbeddingRepository.findEmbedding("artificial intelligence", "Topic", tenantId))
                .thenReturn(Optional.of(embedding));
        when(nodeEmbeddingRepository.findNearestNeighbour(embedding, "Topic", tenantId))
                .thenReturn(Optional.of(new NodeSimilarityResult(nearNodeId, "ai", 0.85)));
        GraphNode newNode =
                new GraphNode(
                        newNodeId,
                        "Topic",
                        tenantId,
                        "artificial intelligence",
                        Map.of(),
                        Instant.now(),
                        Instant.now());
        when(graphRepository.createNode(
                        eq("Topic"), eq("artificial intelligence"), any(), eq(tenantId)))
                .thenReturn(newNode);
        when(resolutionFlagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID result = service.resolve("Artificial Intelligence", "Topic", tenantId);

        assertThat(result).isEqualTo(newNodeId);
        verify(graphRepository)
                .createNode(eq("Topic"), eq("artificial intelligence"), any(), eq(tenantId));
        verify(resolutionFlagRepository).save(any(ResolutionFlag.class));
    }

    @Test
    void shouldCreateWithoutFlagWhenSimilarityBelowFlagThreshold() {
        UUID tenantId = UUID.randomUUID();
        UUID newNodeId = UUID.randomUUID();
        float[] embedding = new float[] {0.1f, 0.2f, 0.3f};

        when(graphRepository.findByLabelAndType("quantum entanglement", "Topic", tenantId))
                .thenReturn(Optional.empty());
        when(entityAliasRepository.findByConceptTypeAndAliasAndTenantId(
                        "Topic", "quantum entanglement", tenantId))
                .thenReturn(Optional.empty());
        when(nodeEmbeddingRepository.findEmbedding("quantum entanglement", "Topic", tenantId))
                .thenReturn(Optional.of(embedding));
        when(nodeEmbeddingRepository.findNearestNeighbour(embedding, "Topic", tenantId))
                .thenReturn(
                        Optional.of(
                                new NodeSimilarityResult(
                                        UUID.randomUUID(), "something else", 0.60)));
        GraphNode newNode =
                new GraphNode(
                        newNodeId,
                        "Topic",
                        tenantId,
                        "quantum entanglement",
                        Map.of(),
                        Instant.now(),
                        Instant.now());
        when(graphRepository.createNode(
                        eq("Topic"), eq("quantum entanglement"), any(), eq(tenantId)))
                .thenReturn(newNode);

        UUID result = service.resolve("Quantum Entanglement", "Topic", tenantId);

        assertThat(result).isEqualTo(newNodeId);
        verify(resolutionFlagRepository, never()).save(any());
    }

    @Test
    void shouldSkipSimilarityWhenEmbedFails() {
        UUID tenantId = UUID.randomUUID();
        UUID newNodeId = UUID.randomUUID();

        when(graphRepository.findByLabelAndType("quantum entanglement", "Topic", tenantId))
                .thenReturn(Optional.empty());
        when(entityAliasRepository.findByConceptTypeAndAliasAndTenantId(
                        "Topic", "quantum entanglement", tenantId))
                .thenReturn(Optional.empty());
        when(nodeEmbeddingRepository.findEmbedding("quantum entanglement", "Topic", tenantId))
                .thenReturn(Optional.empty());
        when(llmClient.embed("quantum entanglement")).thenThrow(new RuntimeException("LLM down"));
        GraphNode newNode =
                new GraphNode(
                        newNodeId,
                        "Topic",
                        tenantId,
                        "quantum entanglement",
                        Map.of(),
                        Instant.now(),
                        Instant.now());
        when(graphRepository.createNode(
                        eq("Topic"), eq("quantum entanglement"), any(), eq(tenantId)))
                .thenReturn(newNode);

        UUID result = service.resolve("Quantum Entanglement", "Topic", tenantId);

        assertThat(result).isEqualTo(newNodeId);
        verify(nodeEmbeddingRepository, never()).findNearestNeighbour(any(), any(), any());
        verify(resolutionFlagRepository, never()).save(any());
    }

    @Test
    void shouldSkipSimilarityWhenEmbedReturnsEmpty() {
        UUID tenantId = UUID.randomUUID();
        UUID newNodeId = UUID.randomUUID();

        when(graphRepository.findByLabelAndType("quantum entanglement", "Topic", tenantId))
                .thenReturn(Optional.empty());
        when(entityAliasRepository.findByConceptTypeAndAliasAndTenantId(
                        "Topic", "quantum entanglement", tenantId))
                .thenReturn(Optional.empty());
        when(nodeEmbeddingRepository.findEmbedding("quantum entanglement", "Topic", tenantId))
                .thenReturn(Optional.empty());
        when(llmClient.embed("quantum entanglement")).thenReturn(new float[0]);
        GraphNode newNode =
                new GraphNode(
                        newNodeId,
                        "Topic",
                        tenantId,
                        "quantum entanglement",
                        Map.of(),
                        Instant.now(),
                        Instant.now());
        when(graphRepository.createNode(
                        eq("Topic"), eq("quantum entanglement"), any(), eq(tenantId)))
                .thenReturn(newNode);

        UUID result = service.resolve("Quantum Entanglement", "Topic", tenantId);

        assertThat(result).isEqualTo(newNodeId);
        verify(nodeEmbeddingRepository, never()).findNearestNeighbour(any(), any(), any());
    }
}
