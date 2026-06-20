package io.emcip.knowledge.engine.connector.impl;

import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.ConnectorException;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.KnowledgeConnector;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Component
@Slf4j
public class ExaConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;

    public ExaConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${connector.exa.base-url:https://api.exa.ai}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "exa";
    }

    @Override
    public String displayName() {
        return "Exa Search";
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        if (request.query() == null) return List.of();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", request.query());
            body.put("numResults", 10);
            body.put("type", "neural");

            var reqSpec =
                    restClient
                            .post()
                            .uri(baseUrl + "/search")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body);
            if (ctx.apiKey() != null) {
                reqSpec = reqSpec.header("x-api-key", ctx.apiKey());
            }
            JsonNode root = reqSpec.retrieve().body(JsonNode.class);
            if (root == null) return List.of();

            List<EnrichmentResult> results = new ArrayList<>();
            root.path("results")
                    .forEach(
                            item -> {
                                String id = item.path("id").asText();
                                String title = item.path("title").asText();
                                String text = item.path("text").asText(null);
                                String url = item.path("url").asText(id);
                                String pubDate = item.path("publishedDate").asText(null);
                                Instant publishedAt =
                                        pubDate != null ? Instant.parse(pubDate) : Instant.now();
                                results.add(
                                        new EnrichmentResult(
                                                id,
                                                title,
                                                text,
                                                url,
                                                vendorId(),
                                                publishedAt,
                                                Map.of("source", "exa")));
                            });
            return results;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("Exa rate limit hit");
                return List.of();
            }
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED
                    || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new ConnectorException("Exa auth error: " + e.getStatusCode(), e);
            }
            throw new ConnectorException("Exa HTTP error: " + e.getStatusCode(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("Exa fetch failed: " + e.getMessage(), e);
        }
    }
}
