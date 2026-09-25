package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.repository.GraphRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** KNOW-F2: a search hit's connections follow the P3.8a search rule. */
@IntegrationTest
class KnowledgeQueryServiceTenantScopeTest {

    @Autowired KnowledgeQueryService queryService;
    @Autowired GraphRepository graph;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @Test
    void searchExpansionFollowsTheSearchRule() {
        GraphNode start = graph.createNode("KnowF1Probe", "s-start", Map.of(), null);
        GraphNode ownOfA = graph.createNode("KnowF1Probe", "s-a", Map.of(), tenantA);
        GraphNode ofB = graph.createNode("KnowF1Probe", "s-b", Map.of(), tenantB);
        graph.createRelationship("RELATES_TO", start.id(), ownOfA.id(), Map.of(), null);
        graph.createRelationship("RELATES_TO", start.id(), ofB.id(), Map.of(), null);

        var forA =
                queryService.connectionsVisibleTo(start.id(), tenantA).stream()
                        .map(GraphNode::id)
                        .toList();
        var forNoTenant =
                queryService.connectionsVisibleTo(start.id(), null).stream()
                        .map(GraphNode::id)
                        .toList();

        assertThat(forA).contains(ownOfA.id()).doesNotContain(ofB.id());
        assertThat(forNoTenant).doesNotContain(ownOfA.id(), ofB.id());
    }
}
