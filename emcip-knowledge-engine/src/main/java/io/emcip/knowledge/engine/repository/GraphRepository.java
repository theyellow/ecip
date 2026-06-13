package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.model.GraphEdge;
import io.emcip.knowledge.engine.model.GraphNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface GraphRepository {

    GraphNode createNode(
            String conceptType, String label, Map<String, Object> properties, UUID tenantId);

    GraphEdge createRelationship(
            String relationshipType,
            UUID sourceNodeId,
            UUID targetNodeId,
            Map<String, Object> properties,
            UUID sourceMessageId);

    List<GraphNode> findConnected(UUID nodeId, String relationshipType, int depth);

    Optional<GraphNode> findByLabelAndType(String label, String conceptType, UUID tenantId);

    List<GraphNode> findNodesByType(String conceptType, UUID tenantId, int limit);
}
