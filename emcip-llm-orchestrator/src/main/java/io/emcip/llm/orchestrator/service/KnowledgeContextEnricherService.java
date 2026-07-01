package io.emcip.llm.orchestrator.service;

import io.emcip.llm.orchestrator.client.KnowledgeEngineClient;
import io.emcip.llm.orchestrator.client.KnowledgeEngineClient.DocumentResult;
import io.emcip.llm.orchestrator.config.KnowledgeEnrichmentProperties;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeContextEnricherService {

    private final KnowledgeEngineClient knowledgeEngineClient;
    private final KnowledgeEnrichmentProperties props;

    /**
     * Queries the knowledge engine and returns a formatted context string. Returns an empty string
     * if no results meet the relevance threshold, or if the knowledge engine is unreachable.
     *
     * @param userQuery natural-language user query
     * @param tenantId current tenant (null = cross-tenant)
     * @return formatted context block, or "" if nothing relevant found
     */
    public String buildContext(String userQuery, UUID tenantId) {
        KnowledgeEngineClient.SearchResponse response =
                knowledgeEngineClient.search(userQuery, "HYBRID", tenantId, props.maxResults());

        List<DocumentResult> relevant =
                response.documentResults().stream()
                        .filter(r -> r.similarity() >= props.relevanceThreshold())
                        .toList();

        if (relevant.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (DocumentResult result : relevant) {
            sb.append("<<<KNOWLEDGE_SOURCE_BEGIN source=\"")
                    .append(result.document().sourceRef())
                    .append("\">>>\n");
            sb.append(result.document().content());
            sb.append("\n<<<KNOWLEDGE_SOURCE_END>>>\n\n");
            if (sb.length() >= props.contextMaxChars()) {
                break;
            }
        }

        String raw = sb.toString().stripTrailing();
        if (raw.length() > props.contextMaxChars()) {
            raw = raw.substring(0, props.contextMaxChars());
        }
        return raw;
    }
}
