package io.emcip.knowledge.engine.model;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

public record SearchRequest(
        @NotBlank String query,
        SearchType searchType,
        UUID tenantId,
        List<String> conceptTypes,
        List<String> sourceTypes,
        int limit) {

    public enum SearchType {
        GRAPH,
        VECTOR,
        HYBRID
    }

    public SearchRequest {
        if (limit <= 0) limit = 20;
        if (searchType == null) searchType = SearchType.HYBRID;
    }
}
