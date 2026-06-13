package io.emcip.knowledge.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.model.GraphEdge;
import io.emcip.knowledge.engine.model.GraphNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class AgeGraphRepositoryTest {

    @Autowired private GraphRepository graphRepository;

    @Test
    void shouldCreateAndFindNode() {
        UUID tenantId = UUID.randomUUID();
        GraphNode node = graphRepository.createNode("Person", "John Doe", Map.of(), tenantId);

        assertThat(node.id()).isNotNull();
        assertThat(node.conceptType()).isEqualTo("Person");
        assertThat(node.label()).isEqualTo("John Doe");

        var found = graphRepository.findByLabelAndType("John Doe", "Person", tenantId);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(node.id());
    }

    @Test
    void shouldCreateRelationshipBetweenNodes() {
        UUID tenantId = UUID.randomUUID();
        GraphNode person = graphRepository.createNode("Person", "Alice", Map.of(), tenantId);
        GraphNode topic = graphRepository.createNode("Topic", "AI", Map.of(), tenantId);

        GraphEdge edge =
                graphRepository.createRelationship(
                        "DISCUSSES", person.id(), topic.id(), Map.of("confidence", 0.9), null);

        assertThat(edge.id()).isNotNull();
        assertThat(edge.relationshipType()).isEqualTo("DISCUSSES");
    }

    @Test
    void shouldFindConnectedNodes() {
        UUID tenantId = UUID.randomUUID();
        GraphNode person = graphRepository.createNode("Person", "Bob", Map.of(), tenantId);
        GraphNode topic1 = graphRepository.createNode("Topic", "ML", Map.of(), tenantId);
        GraphNode topic2 = graphRepository.createNode("Topic", "NLP", Map.of(), tenantId);

        graphRepository.createRelationship("DISCUSSES", person.id(), topic1.id(), Map.of(), null);
        graphRepository.createRelationship("DISCUSSES", person.id(), topic2.id(), Map.of(), null);

        var connected = graphRepository.findConnected(person.id(), "DISCUSSES", 1);
        assertThat(connected).hasSize(2);
    }
}
