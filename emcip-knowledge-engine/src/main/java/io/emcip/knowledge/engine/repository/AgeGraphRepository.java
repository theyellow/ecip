package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.model.GraphEdge;
import io.emcip.knowledge.engine.model.GraphNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Slf4j
@RequiredArgsConstructor
public class AgeGraphRepository implements GraphRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String GRAPH_NAME = "knowledge_graph";

    @Override
    public GraphNode createNode(
            String conceptType, String label, Map<String, Object> properties, UUID tenantId) {
        UUID nodeId = UUID.randomUUID();
        Instant now = Instant.now();

        String propsJson = buildPropertiesJson(nodeId, label, tenantId, properties, now);

        String cypher =
                String.format("CREATE (n:%s %s) RETURN n", sanitizeLabel(conceptType), propsJson);

        executeCypher(cypher);

        log.debug("Created graph node: type={}, label={}, id={}", conceptType, label, nodeId);
        return new GraphNode(nodeId, conceptType, tenantId, label, properties, now, now);
    }

    @Override
    public GraphEdge createRelationship(
            String relationshipType,
            UUID sourceNodeId,
            UUID targetNodeId,
            Map<String, Object> properties,
            UUID sourceMessageId) {
        UUID edgeId = UUID.randomUUID();
        Instant now = Instant.now();

        String propsJson = buildEdgePropertiesJson(edgeId, properties, sourceMessageId, now);

        String cypher =
                String.format(
                        """
                        MATCH (a {node_id: '%s'}), (b {node_id: '%s'})
                        CREATE (a)-[r:%s %s]->(b)
                        RETURN r
                        """,
                        sourceNodeId, targetNodeId, sanitizeLabel(relationshipType), propsJson);

        executeCypher(cypher);

        log.debug(
                "Created graph edge: type={}, {} -> {}",
                relationshipType,
                sourceNodeId,
                targetNodeId);
        return new GraphEdge(
                edgeId,
                relationshipType,
                sourceNodeId,
                targetNodeId,
                properties,
                sourceMessageId,
                now);
    }

    @Override
    public List<GraphNode> findConnected(UUID nodeId, String relationshipType, int depth) {
        String cypher =
                String.format(
                        """
                        MATCH ({node_id: '%s'})-[:%s*1..%d]->(connected)
                        RETURN connected
                        """,
                        nodeId, sanitizeLabel(relationshipType), depth);

        return queryNodes(cypher);
    }

    @Override
    public Optional<GraphNode> findByLabelAndType(String label, String conceptType, UUID tenantId) {
        String cypher =
                String.format(
                        """
                        MATCH (n:%s {label: '%s', tenant_id: '%s'})
                        RETURN n
                        """,
                        sanitizeLabel(conceptType), escapeString(label), tenantId);

        List<GraphNode> results = queryNodes(cypher);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public List<GraphNode> findNodesByType(String conceptType, UUID tenantId, int limit) {
        String cypher;
        if (tenantId != null) {
            cypher =
                    String.format(
                            """
                            MATCH (n:%s {tenant_id: '%s'})
                            RETURN n LIMIT %d
                            """,
                            sanitizeLabel(conceptType), tenantId, limit);
        } else {
            cypher =
                    String.format(
                            "MATCH (n:%s) RETURN n LIMIT %d", sanitizeLabel(conceptType), limit);
        }
        return queryNodes(cypher);
    }

    private void executeCypher(String cypher) {
        String sql =
                String.format(
                        "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (result"
                                + " ag_catalog.agtype)",
                        GRAPH_NAME, cypher);
        try {
            jdbcTemplate.execute("SET search_path = ag_catalog, \"$user\", public");
            jdbcTemplate.execute("LOAD 'age'");
            jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.error("AGE cypher execution failed: {}", e.getMessage(), e);
            throw new RuntimeException("Graph operation failed: " + e.getMessage(), e);
        }
    }

    private List<GraphNode> queryNodes(String cypher) {
        String sql =
                String.format(
                        "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (result"
                                + " ag_catalog.agtype)",
                        GRAPH_NAME, cypher);
        try {
            jdbcTemplate.execute("SET search_path = ag_catalog, \"$user\", public");
            jdbcTemplate.execute("LOAD 'age'");
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            List<GraphNode> nodes = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                GraphNode node = parseNodeFromAgtype(row.get("result"));
                if (node != null) nodes.add(node);
            }
            return nodes;
        } catch (Exception e) {
            log.error("AGE cypher query failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private GraphNode parseNodeFromAgtype(Object agtype) {
        if (agtype == null) return null;
        String str = agtype.toString();
        try {
            Map<String, Object> props = parseAgtypeProperties(str);
            return new GraphNode(
                    UUID.fromString(
                            (String) props.getOrDefault("node_id", UUID.randomUUID().toString())),
                    (String) props.getOrDefault("concept_type", ""),
                    props.containsKey("tenant_id")
                            ? UUID.fromString((String) props.get("tenant_id"))
                            : null,
                    (String) props.getOrDefault("label", ""),
                    filterProperties(props),
                    Instant.now(),
                    Instant.now());
        } catch (Exception e) {
            log.warn("Failed to parse agtype node: {}", str, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAgtypeProperties(String agtype) {
        String json = agtype.replaceAll("::\\w+$", "").trim();
        try {
            tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> filterProperties(Map<String, Object> all) {
        Map<String, Object> props = new HashMap<>(all);
        props.remove("node_id");
        props.remove("label");
        props.remove("tenant_id");
        props.remove("concept_type");
        props.remove("created_at");
        props.remove("updated_at");
        return props;
    }

    private String buildPropertiesJson(
            UUID nodeId, String label, UUID tenantId, Map<String, Object> properties, Instant now) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("node_id: '").append(nodeId).append("', ");
        sb.append("label: '").append(escapeString(label)).append("', ");
        if (tenantId != null) {
            sb.append("tenant_id: '").append(tenantId).append("', ");
        }
        sb.append("created_at: '").append(now).append("', ");
        sb.append("updated_at: '").append(now).append("'");
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            sb.append(", ").append(entry.getKey()).append(": ");
            if (entry.getValue() instanceof String s) {
                sb.append("'").append(escapeString(s)).append("'");
            } else {
                sb.append(entry.getValue());
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildEdgePropertiesJson(
            UUID edgeId, Map<String, Object> properties, UUID sourceMessageId, Instant now) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("edge_id: '").append(edgeId).append("', ");
        if (sourceMessageId != null) {
            sb.append("source_message_id: '").append(sourceMessageId).append("', ");
        }
        sb.append("created_at: '").append(now).append("'");
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            sb.append(", ").append(entry.getKey()).append(": ");
            if (entry.getValue() instanceof String s) {
                sb.append("'").append(escapeString(s)).append("'");
            } else {
                sb.append(entry.getValue());
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String sanitizeLabel(String label) {
        return label.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String escapeString(String input) {
        return input.replace("'", "\\'").replace("\\", "\\\\");
    }
}
