package io.emcip.knowledge.engine.connector.impl;

import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.ConnectorException;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.KnowledgeConnector;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Component
@Slf4j
public class ZenodoConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;

    public ZenodoConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${connector.zenodo.base-url:https://zenodo.org}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "zenodo";
    }

    @Override
    public String displayName() {
        return "Zenodo";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        if (request.query() == null) return List.of();
        String encoded = URLEncoder.encode(request.query(), StandardCharsets.UTF_8);
        String uri = baseUrl + "/api/records?size=10&q=" + encoded;
        try {
            JsonNode root = restClient.get().uri(uri).retrieve().body(JsonNode.class);
            if (root == null) return List.of();
            List<EnrichmentResult> results = new ArrayList<>();
            root.path("hits")
                    .path("hits")
                    .forEach(
                            item -> {
                                String id = item.path("id").asText();
                                JsonNode meta = item.path("metadata");
                                String title = meta.path("title").asText();
                                String description = meta.path("description").asText(null);
                                String pubDate = meta.path("publication_date").asText(null);
                                String url =
                                        item.path("links")
                                                .path("html")
                                                .asText("https://zenodo.org/record/" + id);
                                Instant publishedAt =
                                        pubDate != null
                                                ? Instant.parse(pubDate + "T00:00:00Z")
                                                : Instant.now();
                                results.add(
                                        new EnrichmentResult(
                                                "zenodo:" + id,
                                                title,
                                                description,
                                                url,
                                                vendorId(),
                                                publishedAt,
                                                Map.of("source", "zenodo", "zenodoId", id)));
                            });
            return results;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("Zenodo rate limit hit");
                return List.of();
            }
            throw new ConnectorException("Zenodo HTTP error: " + e.getStatusCode(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("Zenodo fetch failed: " + e.getMessage(), e);
        }
    }
}
