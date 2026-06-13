package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.EntityAlias;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.repository.EntityAliasRepository;
import io.emcip.knowledge.engine.repository.GraphRepository;
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

    public UUID resolve(String label, String conceptType, UUID tenantId) {
        String normalized = label.toLowerCase().trim();

        // Level 1: Exact match
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

        // Level 3: Create new node
        GraphNode newNode = graphRepository.createNode(conceptType, normalized, Map.of(), tenantId);
        log.info(
                "Created new graph node: type={}, label={}, id={}",
                conceptType,
                label,
                newNode.id());
        return newNode.id();
    }
}
