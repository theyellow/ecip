package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.config.ResolutionConfig.ResolutionProperties;
import io.emcip.knowledge.engine.entity.EntityAlias;
import io.emcip.knowledge.engine.entity.ResolutionFlag;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.model.NodeSimilarityResult;
import io.emcip.knowledge.engine.repository.EntityAliasRepository;
import io.emcip.knowledge.engine.repository.GraphNodeEmbeddingRepository;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.ResolutionFlagRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class EntityResolutionService {

    private final GraphRepository graphRepository;
    private final EntityAliasRepository entityAliasRepository;
    private final LlmOrchestratorClient llmClient;
    private final GraphNodeEmbeddingRepository nodeEmbeddingRepository;
    private final ResolutionFlagRepository resolutionFlagRepository;
    private final ResolutionProperties resolutionProperties;

    public UUID resolve(String label, String conceptType, UUID tenantId) {
        String normalized = label.toLowerCase().trim();
        float[] embedding = resolveEmbedding(normalized, conceptType, tenantId);
        return resolve(label, conceptType, tenantId, embedding);
    }

    public UUID resolve(
            String label, String conceptType, UUID tenantId, float[] precomputedEmbedding) {
        String normalized = label.toLowerCase().trim();

        // Level 1: Exact match in AGE graph
        Optional<GraphNode> exact =
                graphRepository.findByLabelAndType(normalized, conceptType, tenantId);
        if (exact.isPresent()) {
            log.debug("Entity resolved by exact match: {} -> {}", label, exact.get().id());
            return exact.get().id();
        }

        // Level 2: Alias table
        Optional<EntityAlias> alias =
                entityAliasRepository.findByConceptTypeAndAliasAndTenantId(
                        conceptType, normalized, tenantId);
        if (alias.isPresent()) {
            String canonical = alias.get().getCanonicalLabel().toLowerCase().trim();
            Optional<GraphNode> aliasNode =
                    graphRepository.findByLabelAndType(canonical, conceptType, tenantId);
            if (aliasNode.isPresent()) {
                log.debug(
                        "Entity resolved by alias: {} -> {} -> {}",
                        label,
                        alias.get().getCanonicalLabel(),
                        aliasNode.get().id());
                return aliasNode.get().id();
            }
        }

        // Level 3: Embedding similarity — search BEFORE storing to avoid self-match
        if (precomputedEmbedding.length > 0) {
            Optional<NodeSimilarityResult> nearest =
                    nodeEmbeddingRepository.findNearestNeighbour(
                            precomputedEmbedding, conceptType, tenantId);
            if (nearest.isPresent()) {
                double score = nearest.get().score();
                if (score >= resolutionProperties.mergeThreshold()) {
                    log.debug(
                            "Entity merged by similarity: {} -> {} (score={})",
                            label,
                            nearest.get().label(),
                            score);
                    return nearest.get().nodeId();
                } else if (score >= resolutionProperties.flagThreshold()) {
                    GraphNode newNode =
                            graphRepository.createNode(conceptType, normalized, Map.of(), tenantId);
                    storeEmbeddingForNode(
                            newNode.id(), normalized, conceptType, tenantId, precomputedEmbedding);
                    writeFlagSafely(
                            label, newNode.id(), nearest.get(), conceptType, score, tenantId);
                    log.info(
                            "Created new node and flagged ambiguous similarity:"
                                    + " {} ~ {} (score={})",
                            label,
                            nearest.get().label(),
                            score);
                    return newNode.id();
                }
            }
        }

        // Level 4: Create new node + store embedding
        GraphNode newNode = graphRepository.createNode(conceptType, normalized, Map.of(), tenantId);
        if (precomputedEmbedding.length > 0) {
            storeEmbeddingForNode(
                    newNode.id(), normalized, conceptType, tenantId, precomputedEmbedding);
        }
        log.info(
                "Created new graph node: type={}, label={}, id={}",
                conceptType,
                label,
                newNode.id());
        return newNode.id();
    }

    /**
     * Look up or compute the embedding for a label. Does NOT store it — storage happens after the
     * AGE node is created so the correct node_id is used.
     */
    private float[] resolveEmbedding(String label, String conceptType, UUID tenantId) {
        Optional<float[]> existing =
                nodeEmbeddingRepository.findEmbedding(label, conceptType, tenantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return llmClient.embed(label);
        } catch (Exception e) {
            log.warn(
                    "Embedding failed for label={}, skipping similarity: {}",
                    label,
                    e.getMessage());
            return new float[0];
        }
    }

    private void storeEmbeddingForNode(
            UUID nodeId, String label, String conceptType, UUID tenantId, float[] embedding) {
        nodeEmbeddingRepository.storeEmbeddingWithNodeId(
                nodeId, label, conceptType, tenantId, embedding);
    }

    private void writeFlagSafely(
            String candidateLabel,
            UUID candidateNodeId,
            NodeSimilarityResult nearest,
            String conceptType,
            double score,
            UUID tenantId) {
        try {
            ResolutionFlag flag = new ResolutionFlag();
            flag.setCandidateLabel(candidateLabel);
            flag.setCandidateNodeId(candidateNodeId);
            flag.setSimilarLabel(nearest.label());
            flag.setSimilarNodeId(nearest.nodeId());
            flag.setConceptType(conceptType);
            flag.setSimilarityScore(score);
            flag.setTenantId(tenantId);
            resolutionFlagRepository.save(flag);
        } catch (Exception e) {
            log.warn(
                    "Failed to write resolution flag for candidate={}: {}",
                    candidateLabel,
                    e.getMessage());
        }
    }
}
