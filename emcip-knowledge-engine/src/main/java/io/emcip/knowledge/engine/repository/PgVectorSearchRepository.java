package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.SearchResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
@Slf4j
@RequiredArgsConstructor
public class PgVectorSearchRepository implements VectorSearchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void storeEmbedding(UUID documentId, float[] embedding) {
        String vectorStr = toVectorString(embedding);
        jdbcTemplate.update(
                "UPDATE ke_knowledge_documents SET embedding = ?::vector WHERE id = ?",
                vectorStr,
                documentId);
        log.debug("Stored embedding for document {}", documentId);
    }

    @Override
    public List<SearchResult<KnowledgeDocument>> search(
            float[] queryEmbedding, int topK, UUID tenantId) {
        String vectorStr = toVectorString(queryEmbedding);
        String sql;
        Object[] params;

        if (tenantId != null) {
            sql =
                    """
                    SELECT id, tenant_id, source_type, source_ref, content, chunk_index,
                           metadata, created_at,
                           1 - (embedding <=> ?::vector) AS score
                    FROM ke_knowledge_documents
                    WHERE embedding IS NOT NULL AND (tenant_id = ? OR tenant_id IS NULL)
                    ORDER BY embedding <=> ?::vector ASC
                    LIMIT ?
                    """;
            params = new Object[] {vectorStr, tenantId, vectorStr, topK};
        } else {
            sql =
                    """
                    SELECT id, tenant_id, source_type, source_ref, content, chunk_index,
                           metadata, created_at,
                           1 - (embedding <=> ?::vector) AS score
                    FROM ke_knowledge_documents
                    WHERE embedding IS NOT NULL
                    ORDER BY embedding <=> ?::vector ASC
                    LIMIT ?
                    """;
            params = new Object[] {vectorStr, vectorStr, topK};
        }

        return jdbcTemplate.query(sql, this::mapRowWithScore, params);
    }

    @Override
    public List<KnowledgeDocument> hybridSearch(
            String textQuery, float[] queryEmbedding, int topK, UUID tenantId) {
        String vectorStr = toVectorString(queryEmbedding);
        String sql;
        Object[] params;

        if (tenantId != null) {
            sql =
                    """
                    SELECT id, tenant_id, source_type, source_ref, content, chunk_index,
                           metadata, created_at, embedding <=> ?::vector AS distance
                    FROM ke_knowledge_documents
                    WHERE embedding IS NOT NULL
                      AND (tenant_id = ? OR tenant_id IS NULL)
                      AND content ILIKE '%' || ? || '%'
                    ORDER BY distance ASC
                    LIMIT ?
                    """;
            params = new Object[] {vectorStr, tenantId, textQuery, topK};
        } else {
            sql =
                    """
                    SELECT id, tenant_id, source_type, source_ref, content, chunk_index,
                           metadata, created_at, embedding <=> ?::vector AS distance
                    FROM ke_knowledge_documents
                    WHERE embedding IS NOT NULL AND content ILIKE '%' || ? || '%'
                    ORDER BY distance ASC
                    LIMIT ?
                    """;
            params = new Object[] {vectorStr, textQuery, topK};
        }

        return jdbcTemplate.query(sql, this::mapRow, params);
    }

    private SearchResult<KnowledgeDocument> mapRowWithScore(ResultSet rs, int rowNum)
            throws SQLException {
        KnowledgeDocument doc = mapRow(rs, rowNum);
        double score = rs.getDouble("score");
        return new SearchResult<>(doc, score);
    }

    // rowNum is required by the RowMapper<T> functional-interface signature (used as
    // this::mapRow), even though this implementation does not need it.
    @SuppressWarnings({"unchecked", "PMD.UnusedFormalParameter"})
    private KnowledgeDocument mapRow(ResultSet rs, int rowNum) throws SQLException {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(UUID.fromString(rs.getString("id")));
        String tenantStr = rs.getString("tenant_id");
        if (tenantStr != null) doc.setTenantId(UUID.fromString(tenantStr));
        doc.setSourceType(rs.getString("source_type"));
        doc.setSourceRef(rs.getString("source_ref"));
        doc.setContent(rs.getString("content"));
        doc.setChunkIndex(rs.getInt("chunk_index"));
        String metaJson = rs.getString("metadata");
        if (metaJson != null) {
            try {
                doc.setMetadata(objectMapper.readValue(metaJson, Map.class));
            } catch (Exception e) {
                log.warn("Failed to parse metadata JSON", e);
            }
        }
        doc.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return doc;
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
