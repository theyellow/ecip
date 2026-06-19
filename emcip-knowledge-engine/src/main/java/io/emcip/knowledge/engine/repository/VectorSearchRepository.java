package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.SearchResult;
import java.util.List;
import java.util.UUID;

public interface VectorSearchRepository {
    void storeEmbedding(UUID documentId, float[] embedding);

    List<SearchResult<KnowledgeDocument>> search(float[] queryEmbedding, int topK, UUID tenantId);

    List<KnowledgeDocument> hybridSearch(
            String textQuery, float[] queryEmbedding, int topK, UUID tenantId);
}
