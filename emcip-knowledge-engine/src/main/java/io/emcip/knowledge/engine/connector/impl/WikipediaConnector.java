package io.emcip.knowledge.engine.connector.impl;

import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.ConnectorException;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.KnowledgeConnector;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
public class WikipediaConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;

    public WikipediaConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${connector.wikipedia.base-url:https://en.wikipedia.org}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "wikipedia";
    }

    @Override
    public String displayName() {
        return "Wikipedia";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        if (request.query() == null) return List.of();
        String encoded = URLEncoder.encode(request.query(), StandardCharsets.UTF_8);
        try {
            JsonNode node =
                    restClient
                            .get()
                            .uri(baseUrl + "/api/rest_v1/page/summary/" + encoded)
                            .retrieve()
                            .body(JsonNode.class);

            if (node == null) return List.of();

            String title = node.path("title").asText();
            String extract = node.path("extract").asText(null);
            String url = node.path("content_urls").path("desktop").path("page").asText();
            String ts = node.path("timestamp").asText(null);
            Instant published = ts != null ? Instant.parse(ts) : Instant.now();

            return List.of(
                    new EnrichmentResult(
                            "wikipedia:" + title.replace(' ', '_'),
                            title,
                            extract,
                            url,
                            vendorId(),
                            published,
                            Map.of("source", "wikipedia")));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("Wikipedia rate limit hit for query '{}'", request.query());
                return List.of();
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return List.of();
            }
            throw new ConnectorException("Wikipedia HTTP error: " + e.getStatusCode(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("Wikipedia fetch failed: " + e.getMessage(), e);
        }
    }
}
