package io.emcip.knowledge.engine.model;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

/**
 * Knowledge search request. {@code tenantId}: with a tenant, results are that tenant's rows plus
 * global ({@code tenant_id IS NULL}) rows; {@code null} means global rows only — never another
 * tenant's (P3.8a).
 */
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
