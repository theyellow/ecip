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
public class UnpaywallConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;

    public UnpaywallConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${connector.unpaywall.base-url:https://api.unpaywall.org}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "unpaywall";
    }

    @Override
    public String displayName() {
        return "Unpaywall";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        // Lookup-only connector — requires a DOI as externalId
        if (request.externalId() == null) return List.of();
        String doi = URLEncoder.encode(request.externalId(), StandardCharsets.UTF_8);
        String uri = baseUrl + "/v2/" + doi + "?email=emcip@example.com";
        try {
            JsonNode root = restClient.get().uri(uri).retrieve().body(JsonNode.class);
            if (root == null) return List.of();
            String title = root.path("title").asText();
            String pubDate = root.path("published_date").asText(null);
            String pdfUrl = root.path("best_oa_location").path("url_for_pdf").asText(null);
            Instant publishedAt =
                    pubDate != null ? Instant.parse(pubDate + "T00:00:00Z") : Instant.now();
            String url = pdfUrl != null ? pdfUrl : "https://unpaywall.org/" + request.externalId();
            return List.of(
                    new EnrichmentResult(
                            request.externalId(),
                            title,
                            null,
                            url,
                            vendorId(),
                            publishedAt,
                            Map.of("source", "unpaywall")));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("Unpaywall rate limit hit");
                return List.of();
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return List.of();
            }
            throw new ConnectorException("Unpaywall HTTP error: " + e.getStatusCode(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("Unpaywall fetch failed: " + e.getMessage(), e);
        }
    }
}
