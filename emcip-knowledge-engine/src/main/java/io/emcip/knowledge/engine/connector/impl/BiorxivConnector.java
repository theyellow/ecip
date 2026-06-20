package io.emcip.knowledge.engine.connector.impl;

import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.KnowledgeConnector;
import java.time.Instant;
import java.time.LocalDate;
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
public class BiorxivConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;

    public BiorxivConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${connector.biorxiv.base-url:https://api.biorxiv.org}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "biorxiv";
    }

    @Override
    public String displayName() {
        return "bioRxiv / medRxiv";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        String from = ctx.since().toString().substring(0, 10);
        String to = LocalDate.now().toString();
        List<EnrichmentResult> all = new ArrayList<>();
        for (String server : List.of("biorxiv", "medrxiv")) {
            String uri = baseUrl + "/details/" + server + "/" + from + "/" + to + "/0/json";
            try {
                JsonNode root = restClient.get().uri(uri).retrieve().body(JsonNode.class);
                if (root == null) continue;
                root.path("collection")
                        .forEach(
                                item -> {
                                    String doi = item.path("doi").asText();
                                    String title = item.path("title").asText();
                                    String abst = item.path("abstract").asText(null);
                                    String date = item.path("date").asText(null);
                                    String url = "https://www.biorxiv.org/content/" + doi + "v1";
                                    Instant publishedAt =
                                            date != null
                                                    ? Instant.parse(date + "T00:00:00Z")
                                                    : Instant.now();
                                    all.add(
                                            new EnrichmentResult(
                                                    doi,
                                                    title,
                                                    abst,
                                                    url,
                                                    vendorId(),
                                                    publishedAt,
                                                    Map.of("source", server)));
                                });
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    log.warn("bioRxiv/{} rate limit hit", server);
                } else {
                    log.warn("bioRxiv/{} error: {}", server, e.getStatusCode());
                }
            }
        }
        return all;
    }
}
