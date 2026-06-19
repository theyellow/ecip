package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.model.SearchRequest;
import io.emcip.knowledge.engine.model.SearchRequest.SearchType;
import io.emcip.knowledge.engine.model.SearchResponse;
import io.emcip.knowledge.engine.model.SearchResponse.DocumentResult;
import io.emcip.knowledge.engine.model.SearchResponse.GraphNodeResult;
import io.emcip.knowledge.engine.model.SearchResult;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeQueryService {

    private final VectorSearchRepository vectorSearchRepository;
    private final GraphRepository graphRepository;
    private final LlmOrchestratorClient llmClient;

    public SearchResponse search(SearchRequest request) {
        List<GraphNodeResult> graphResults = new ArrayList<>();
        List<DocumentResult> documentResults = new ArrayList<>();

        float[] queryEmbedding = llmClient.embed(request.query());

        if (request.searchType() == SearchType.VECTOR
                || request.searchType() == SearchType.HYBRID) {
            List<SearchResult<KnowledgeDocument>> scored =
                    vectorSearchRepository.search(
                            queryEmbedding, request.limit(), request.tenantId());
            for (SearchResult<KnowledgeDocument> sr : scored) {
                documentResults.add(new DocumentResult(sr.item(), sr.score()));
            }
        }

        if (request.searchType() == SearchType.GRAPH || request.searchType() == SearchType.HYBRID) {
            if (request.conceptTypes() != null) {
                for (String conceptType : request.conceptTypes()) {
                    List<GraphNode> nodes =
                            graphRepository.findNodesByType(
                                    conceptType, request.tenantId(), request.limit());
                    for (GraphNode node : nodes) {
                        List<GraphNode> connections =
                                graphRepository.findConnected(node.id(), null, 1);
                        graphResults.add(new GraphNodeResult(node, connections, 0.5));
                    }
                }
            }
        }

        log.info(
                "Search completed: query='{}', type={}, graphResults={}, docResults={}",
                request.query(),
                request.searchType(),
                graphResults.size(),
                documentResults.size());

        return new SearchResponse(graphResults, documentResults);
    }
}
