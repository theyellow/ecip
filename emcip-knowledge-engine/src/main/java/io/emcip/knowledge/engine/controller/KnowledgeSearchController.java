package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.model.SearchRequest;
import io.emcip.knowledge.engine.model.SearchResponse;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.service.KnowledgeQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Knowledge Search", description = "Search the knowledge base")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@Validated
public class KnowledgeSearchController {

    private final KnowledgeQueryService queryService;
    private final GraphRepository graphRepository;

    @Operation(summary = "Search the knowledge base (vector, graph, or hybrid)")
    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        return queryService.search(request);
    }

    @Operation(summary = "List graph nodes by concept type")
    @GetMapping("/graph/topics")
    public List<GraphNode> listTopics(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return graphRepository.findNodesByType("Topic", tenantId, limit);
    }

    @Operation(summary = "List graph nodes of type Person")
    @GetMapping("/graph/persons")
    public List<GraphNode> listPersons(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return graphRepository.findNodesByType("Person", tenantId, limit);
    }

    @Operation(summary = "Get neighbors of a graph node")
    @GetMapping("/graph/node/{id}/neighbors")
    public List<GraphNode> getNeighbors(
            @PathVariable UUID id,
            @Pattern(regexp = "[a-zA-Z_]{1,100}") @RequestParam(required = false)
                    String relationshipType,
            @RequestParam(defaultValue = "1") int depth) {
        return graphRepository.findConnected(id, relationshipType, depth);
    }
}
