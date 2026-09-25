package io.emcip.knowledge.engine.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.repository.GraphRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** KNOW-F1: graph neighbours never reach another tenant's nodes. */
@IntegrationTest
class GraphNeighborsTenantScopeTest {

    @Autowired KnowledgeSearchController controller;
    @Autowired GraphRepository graph;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @Test
    void neighboursOfOwnNodeExcludeAnotherTenantsNodes() {
        GraphNode start = graph.createNode("KnowF1Probe", "start", Map.of(), tenantA);
        GraphNode global = graph.createNode("KnowF1Probe", "global", Map.of(), null);
        GraphNode other = graph.createNode("KnowF1Probe", "other", Map.of(), tenantB);
        graph.createRelationship("RELATES_TO", start.id(), global.id(), Map.of(), null);
        graph.createRelationship("RELATES_TO", start.id(), other.id(), Map.of(), null);

        var ids =
                controller.getNeighbors(start.id(), null, 1, tenantA).stream()
                        .map(GraphNode::id)
                        .toList();

        assertThat(ids).contains(global.id()).doesNotContain(other.id());
    }

    @Test
    void neighboursOfAnotherTenantsNodeAreNotFound() {
        GraphNode other = graph.createNode("KnowF1Probe", "other-start", Map.of(), tenantB);

        assertThatThrownBy(() -> controller.getNeighbors(other.id(), null, 1, tenantA))
                .isInstanceOf(ResponseStatusException.class);
    }
}
