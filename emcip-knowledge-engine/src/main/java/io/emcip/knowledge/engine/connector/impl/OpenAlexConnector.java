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
public class OpenAlexConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;

    public OpenAlexConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${connector.openalex.base-url:https://api.openalex.org}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "openalex";
    }

    @Override
    public String displayName() {
        return "OpenAlex";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        if (request.query() == null) return List.of();
        String encoded = URLEncoder.encode(request.query(), StandardCharsets.UTF_8);
        String uri = baseUrl + "/works?per-page=10&mailto=emcip@example.com&search=" + encoded;
        try {
            JsonNode root = restClient.get().uri(uri).retrieve().body(JsonNode.class);
            if (root == null) return List.of();
            List<EnrichmentResult> results = new ArrayList<>();
            root.path("results")
                    .forEach(
                            item -> {
                                String rawId = item.path("id").asText();
                                // extract W-number from URL like
                                // https://openalex.org/W2741809807
                                String id = rawId.replaceAll(".*/", "");
                                String title = item.path("title").asText();
                                String doi = item.path("doi").asText(null);
                                String pubDate = item.path("publication_date").asText(null);
                                String url = doi != null ? doi : "https://openalex.org/" + id;
                                Instant publishedAt =
                                        pubDate != null
                                                ? Instant.parse(pubDate + "T00:00:00Z")
                                                : Instant.now();
                                results.add(
                                        new EnrichmentResult(
                                                id,
                                                title,
                                                null,
                                                url,
                                                vendorId(),
                                                publishedAt,
                                                Map.of("source", "openalex", "openalexId", rawId)));
                            });
            return results;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("OpenAlex rate limit hit");
                return List.of();
            }
            throw new ConnectorException("OpenAlex HTTP error: " + e.getStatusCode(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("OpenAlex fetch failed: " + e.getMessage(), e);
        }
    }
}
