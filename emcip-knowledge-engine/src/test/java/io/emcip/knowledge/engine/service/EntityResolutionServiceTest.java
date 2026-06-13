package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.EntityAlias;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.repository.EntityAliasRepository;
import io.emcip.knowledge.engine.repository.GraphRepository;
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

    private EntityResolutionService service;

    @BeforeEach
    void setUp() {
        service = new EntityResolutionService(graphRepository, entityAliasRepository, llmClient);
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
        when(graphRepository.createNode(eq("Topic"), eq("quantum computing"), any(), eq(tenantId)))
                .thenReturn(newNode);

        UUID result = service.resolve("Quantum Computing", "Topic", tenantId);

        assertThat(result).isEqualTo(newNodeId);
    }
}
