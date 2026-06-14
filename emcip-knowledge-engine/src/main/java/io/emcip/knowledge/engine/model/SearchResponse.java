package io.emcip.knowledge.engine.model;

import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import java.util.List;

public record SearchResponse(
        List<GraphNodeResult> graphResults, List<DocumentResult> documentResults) {

    public record GraphNodeResult(GraphNode node, List<GraphNode> connections, double score) {}

    public record DocumentResult(KnowledgeDocument document, double similarity) {}
}
