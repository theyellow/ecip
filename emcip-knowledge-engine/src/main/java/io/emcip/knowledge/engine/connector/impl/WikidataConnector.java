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
public class WikidataConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;

    public WikidataConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${connector.wikidata.base-url:https://www.wikidata.org}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "wikidata";
    }

    @Override
    public String displayName() {
        return "Wikidata";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        if (request.query() == null) return List.of();
        String encoded = URLEncoder.encode(request.query(), StandardCharsets.UTF_8);
        String uri =
                baseUrl
                        + "/w/api.php?action=wbsearchentities&language=en&format=json&search="
                        + encoded;
        try {
            JsonNode root = restClient.get().uri(uri).retrieve().body(JsonNode.class);
            if (root == null) return List.of();
            List<EnrichmentResult> results = new ArrayList<>();
            root.path("search")
                    .forEach(
                            item -> {
                                String id = item.path("id").asText();
                                String label = item.path("label").asText();
                                String description = item.path("description").asText(null);
                                String url =
                                        item.path("url")
                                                .asText("https://www.wikidata.org/wiki/" + id);
                                results.add(
                                        new EnrichmentResult(
                                                id,
                                                label,
                                                description,
                                                url,
                                                vendorId(),
                                                Instant.now(),
                                                Map.of("source", "wikidata")));
                            });
            return results;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("Wikidata rate limit hit");
                return List.of();
            }
            throw new ConnectorException("Wikidata HTTP error: " + e.getStatusCode(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("Wikidata fetch failed: " + e.getMessage(), e);
        }
    }
}
