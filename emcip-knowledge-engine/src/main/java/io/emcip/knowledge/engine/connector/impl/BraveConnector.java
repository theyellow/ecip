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
public class BraveConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;

    public BraveConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${connector.brave.base-url:https://api.search.brave.com}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "brave";
    }

    @Override
    public String displayName() {
        return "Brave Search";
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        if (request.query() == null) return List.of();
        String encoded = URLEncoder.encode(request.query(), StandardCharsets.UTF_8);
        String uri = baseUrl + "/res/v1/web/search?count=10&q=" + encoded;
        try {
            var reqSpec = restClient.get().uri(uri).header("Accept", "application/json");
            if (ctx.apiKey() != null) {
                reqSpec = reqSpec.header("X-Subscription-Token", ctx.apiKey());
            }
            JsonNode root = reqSpec.retrieve().body(JsonNode.class);
            if (root == null) return List.of();

            List<EnrichmentResult> results = new ArrayList<>();
            root.path("web")
                    .path("results")
                    .forEach(
                            item -> {
                                String title = item.path("title").asText();
                                String description = item.path("description").asText(null);
                                String url = item.path("url").asText();
                                String age = item.path("age").asText(null);
                                Instant publishedAt;
                                try {
                                    publishedAt =
                                            age != null && age.matches("\\d{4}-\\d{2}-\\d{2}")
                                                    ? Instant.parse(age + "T00:00:00Z")
                                                    : Instant.now();
                                } catch (Exception ignored) {
                                    publishedAt = Instant.now();
                                }
                                results.add(
                                        new EnrichmentResult(
                                                url,
                                                title,
                                                description,
                                                url,
                                                vendorId(),
                                                publishedAt,
                                                Map.of("source", "brave")));
                            });
            return results;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("Brave Search rate limit hit");
                return List.of();
            }
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED
                    || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new ConnectorException("Brave Search auth error: " + e.getStatusCode(), e);
            }
            throw new ConnectorException("Brave Search HTTP error: " + e.getStatusCode(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("Brave Search fetch failed: " + e.getMessage(), e);
        }
    }
}
