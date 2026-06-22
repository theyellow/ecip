package io.emcip.knowledge.engine.connector.impl;

import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.KnowledgeConnector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class SearXngConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SearXngConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${web.search.searxng.base-url:}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "searxng";
    }

    @Override
    public String displayName() {
        return "SearXNG (self-hosted)";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.debug("SearXNG base URL not configured, skipping");
            return List.of();
        }

        String query = request.query();
        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            String json =
                    restClient
                            .get()
                            .uri(baseUrl + "/search?q={q}&format=json", query)
                            .retrieve()
                            .body(String.class);

            if (json == null) return List.of();

            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.path("results");
            if (results.isMissingNode() || !results.isArray()) return List.of();

            List<EnrichmentResult> output = new ArrayList<>();
            for (JsonNode r : results) {
                String url = r.path("url").asText("");
                output.add(
                        new EnrichmentResult(
                                url,
                                r.path("title").asText(""),
                                r.path("content").asText(null),
                                url,
                                "searxng",
                                null,
                                Map.of("engine", r.path("engine").asText(""))));
            }
            log.debug("SearXNG returned {} results for query '{}'", output.size(), query);
            return output;
        } catch (Exception e) {
            log.warn("SearXNG search failed for query '{}': {}", query, e.getMessage());
            return List.of();
        }
    }
}
