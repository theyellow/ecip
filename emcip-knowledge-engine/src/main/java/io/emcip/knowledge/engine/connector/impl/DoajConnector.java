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
import java.util.UUID;
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
public class DoajConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;

    public DoajConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${connector.doaj.base-url:https://doaj.org}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "doaj";
    }

    @Override
    public String displayName() {
        return "DOAJ";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        if (request.query() == null) return List.of();
        String encoded = URLEncoder.encode(request.query(), StandardCharsets.UTF_8);
        String uri = baseUrl + "/api/search/articles/" + encoded + "?pageSize=10";
        try {
            JsonNode root = restClient.get().uri(uri).retrieve().body(JsonNode.class);
            if (root == null) return List.of();
            List<EnrichmentResult> results = new ArrayList<>();
            root.path("results")
                    .forEach(
                            item -> {
                                JsonNode bib = item.path("bibjson");

                                // title is an array of {"text": "..."}
                                JsonNode titleArray = bib.path("title");
                                String title =
                                        titleArray.isArray() && titleArray.size() > 0
                                                ? titleArray.get(0).path("text").asText()
                                                : bib.path("title").asText("");

                                String abst = bib.path("abstract").asText(null);

                                // extract DOI from identifiers array
                                String doi = null;
                                for (JsonNode id : bib.path("identifier")) {
                                    if ("doi".equals(id.path("type").asText())) {
                                        doi = id.path("id").asText();
                                        break;
                                    }
                                }

                                // extract URL from links array
                                String url = null;
                                JsonNode links = bib.path("link");
                                if (links.isArray() && links.size() > 0) {
                                    url = links.get(0).path("url").asText(null);
                                }
                                if (url == null) {
                                    url = doi != null ? "https://doi.org/" + doi : "";
                                }

                                String externalId =
                                        doi != null ? "doaj:" + doi : "doaj:" + UUID.randomUUID();
                                results.add(
                                        new EnrichmentResult(
                                                externalId,
                                                title,
                                                abst,
                                                url,
                                                vendorId(),
                                                Instant.now(),
                                                Map.of("source", "doaj")));
                            });
            return results;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("DOAJ rate limit hit");
                return List.of();
            }
            throw new ConnectorException("DOAJ HTTP error: " + e.getStatusCode(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("DOAJ fetch failed: " + e.getMessage(), e);
        }
    }
}
