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

    /**
     * Reroutes all edges from candidateNodeId to targetNodeId in the AGE graph, then deletes the
     * candidate node. Throws RuntimeException on any failure (triggers rollback at service layer).
     */
    void mergeNodes(UUID candidateNodeId, UUID targetNodeId);

    /** Delete all edges whose source_message_id is in the given list of document IDs. */
    void deleteEdgesBySourceMessageIds(List<UUID> documentIds);

    /** Find all edges originating from given document IDs, returning source/target node info. */
    List<GraphEdge> findEdgesBySourceMessageIds(List<UUID> documentIds);

    Optional<GraphNode> findNodeById(UUID nodeId);
}
