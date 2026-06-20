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
public class CoreConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;

    public CoreConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${connector.core.base-url:https://api.core.ac.uk}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "core";
    }

    @Override
    public String displayName() {
        return "CORE";
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        if (request.query() == null) return List.of();
        String encoded = URLEncoder.encode(request.query(), StandardCharsets.UTF_8);
        String uri = baseUrl + "/v3/search/works?limit=10&q=" + encoded;
        try {
            var reqSpec = restClient.get().uri(uri);
            if (ctx.apiKey() != null) {
                reqSpec = reqSpec.header("Authorization", "Bearer " + ctx.apiKey());
            }
            JsonNode root = reqSpec.retrieve().body(JsonNode.class);
            if (root == null) return List.of();
            List<EnrichmentResult> results = new ArrayList<>();
            root.path("results")
                    .forEach(
                            item -> {
                                String id = item.path("id").asText();
                                String title = item.path("title").asText();
                                String abst = item.path("abstract").asText(null);
                                String downloadUrl = item.path("downloadUrl").asText(null);
                                String pubDate = item.path("publishedDate").asText(null);
                                String url =
                                        downloadUrl != null
                                                ? downloadUrl
                                                : "https://core.ac.uk/works/" + id;
                                Instant publishedAt =
                                        pubDate != null
                                                ? Instant.parse(
                                                        pubDate.endsWith("Z")
                                                                ? pubDate
                                                                : pubDate.length() == 10
                                                                        ? pubDate + "T00:00:00Z"
                                                                        : pubDate + "Z")
                                                : Instant.now();
                                results.add(
                                        new EnrichmentResult(
                                                "core:" + id,
                                                title,
                                                abst,
                                                url,
                                                vendorId(),
                                                publishedAt,
                                                Map.of("source", "core", "coreId", id)));
                            });
            return results;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("CORE rate limit hit");
                return List.of();
            }
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED
                    || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new ConnectorException("CORE auth error: " + e.getStatusCode(), e);
            }
            throw new ConnectorException("CORE HTTP error: " + e.getStatusCode(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("CORE fetch failed: " + e.getMessage(), e);
        }
    }
}
