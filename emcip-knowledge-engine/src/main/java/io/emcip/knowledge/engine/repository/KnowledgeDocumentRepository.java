package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {
    List<KnowledgeDocument> findBySourceRef(String sourceRef);

    boolean existsBySourceRefAndChunkIndex(String sourceRef, Integer chunkIndex);

    boolean existsBySourceRefAndSourceType(String sourceRef, String sourceType);

    @Query(
            value = "SELECT * FROM ke_knowledge_documents WHERE embedding IS NULL",
            nativeQuery = true)
    List<KnowledgeDocument> findAllWithNullEmbedding();

    @Query(
            value = "SELECT COUNT(*) FROM ke_knowledge_documents WHERE embedding IS NULL",
            nativeQuery = true)
    long countWithNullEmbedding();
}
