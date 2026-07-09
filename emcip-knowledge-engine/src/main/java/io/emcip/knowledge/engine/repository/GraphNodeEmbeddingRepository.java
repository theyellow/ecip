package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.model.NodeSimilarityResult;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Slf4j
@RequiredArgsConstructor
public class GraphNodeEmbeddingRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<float[]> findEmbedding(String label, String conceptType, UUID tenantId) {
        try {
            String raw;
            if (tenantId != null) {
                raw =
                        jdbcTemplate.queryForObject(
                                """
                                SELECT embedding::text
                                FROM ke_graph_node_embeddings
                                WHERE label = ? AND concept_type = ? AND tenant_id = ?
                                  AND embedding IS NOT NULL
                                """,
                                String.class,
                                label,
                                conceptType,
                                tenantId);
            } else {
                raw =
                        jdbcTemplate.queryForObject(
                                """
                                SELECT embedding::text
                                FROM ke_graph_node_embeddings
                                WHERE label = ? AND concept_type = ? AND tenant_id IS NULL
                                  AND embedding IS NOT NULL
                                """,
                                String.class,
                                label,
                                conceptType);
            }
            return Optional.of(parseVector(raw));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void storeEmbedding(String label, String conceptType, UUID tenantId, float[] embedding) {
        String vectorStr = toVectorString(embedding);
        try {
            if (tenantId != null) {
                jdbcTemplate.update(
                        """
INSERT INTO ke_graph_node_embeddings (node_id, label, concept_type, tenant_id, embedding)
VALUES (gen_random_uuid(), ?, ?, ?, ?::vector)
ON CONFLICT (label, concept_type, tenant_id)
  DO UPDATE SET embedding = EXCLUDED.embedding
""",
                        label,
                        conceptType,
                        tenantId,
                        vectorStr);
            } else {
                jdbcTemplate.update(
                        """
INSERT INTO ke_graph_node_embeddings (node_id, label, concept_type, embedding)
VALUES (gen_random_uuid(), ?, ?, ?::vector)
ON CONFLICT (label, concept_type, tenant_id)
  WHERE tenant_id IS NULL
  DO UPDATE SET embedding = EXCLUDED.embedding
""",
                        label,
                        conceptType,
                        vectorStr);
            }
            log.debug("Stored node embedding: label={}, type={}", label, conceptType);
        } catch (Exception e) {
            log.warn(
                    "Failed to store node embedding: label={}, type={}: {}",
                    label,
                    conceptType,
                    e.getMessage());
        }
    }

    public Optional<NodeSimilarityResult> findNearestNeighbour(
            float[] embedding, String conceptType, UUID tenantId) {
        String vectorStr = toVectorString(embedding);
        try {
            if (tenantId != null) {
                return jdbcTemplate
                        .query(
                                """
                                SELECT node_id, label, 1 - (embedding <=> ?::vector) AS score
                                FROM ke_graph_node_embeddings
                                WHERE concept_type = ? AND tenant_id = ?
                                  AND embedding IS NOT NULL
                                ORDER BY embedding <=> ?::vector
                                LIMIT 1
                                """,
                                (rs, rowNum) ->
                                        new NodeSimilarityResult(
                                                UUID.fromString(rs.getString("node_id")),
                                                rs.getString("label"),
                                                rs.getDouble("score")),
                                vectorStr,
                                conceptType,
                                tenantId,
                                vectorStr)
                        .stream()
                        .findFirst();
            } else {
                return jdbcTemplate
                        .query(
                                """
                                SELECT node_id, label, 1 - (embedding <=> ?::vector) AS score
                                FROM ke_graph_node_embeddings
                                WHERE concept_type = ? AND tenant_id IS NULL
                                  AND embedding IS NOT NULL
                                ORDER BY embedding <=> ?::vector
                                LIMIT 1
                                """,
                                (rs, rowNum) ->
                                        new NodeSimilarityResult(
                                                UUID.fromString(rs.getString("node_id")),
                                                rs.getString("label"),
                                                rs.getDouble("score")),
                                vectorStr,
                                conceptType,
                                vectorStr)
                        .stream()
                        .findFirst();
            }
        } catch (Exception e) {
            log.warn("Similarity query failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            float value = embedding[i];
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Embedding contains non-finite value at index " + i + ": " + value);
            }
            sb.append(value);
        }
        return sb.append("]").toString();
    }

    private float[] parseVector(String raw) {
        String cleaned = raw.replaceAll("[\\[\\]\\s]", "");
        String[] parts = cleaned.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
