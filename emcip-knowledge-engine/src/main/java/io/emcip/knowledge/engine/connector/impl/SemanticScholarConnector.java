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
public class SemanticScholarConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;

    public SemanticScholarConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${connector.semantic-scholar.base-url:https://api.semanticscholar.org}")
                    String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "semantic-scholar";
    }

    @Override
    public String displayName() {
        return "Semantic Scholar";
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
                        + "/graph/v1/paper/search?limit=10&fields=paperId,title,abstract,year,openAccessPdf&query="
                        + encoded;
        try {
            var reqSpec = restClient.get().uri(uri);
            if (ctx.apiKey() != null) {
                reqSpec = reqSpec.header("x-api-key", ctx.apiKey());
            }
            JsonNode root = reqSpec.retrieve().body(JsonNode.class);
            if (root == null) return List.of();
            List<EnrichmentResult> results = new ArrayList<>();
            root.path("data")
                    .forEach(
                            item -> {
                                String paperId = item.path("paperId").asText();
                                String title = item.path("title").asText();
                                String abst = item.path("abstract").asText(null);
                                int year = item.path("year").asInt(0);
                                String pdfUrl = item.path("openAccessPdf").path("url").asText(null);
                                String url =
                                        pdfUrl != null
                                                ? pdfUrl
                                                : "https://www.semanticscholar.org/paper/"
                                                        + paperId;
                                Instant publishedAt =
                                        year > 0
                                                ? Instant.parse(year + "-01-01T00:00:00Z")
                                                : Instant.now();
                                results.add(
                                        new EnrichmentResult(
                                                paperId,
                                                title,
                                                abst,
                                                url,
                                                vendorId(),
                                                publishedAt,
                                                Map.of(
                                                        "source",
                                                        "semantic-scholar",
                                                        "year",
                                                        String.valueOf(year))));
                            });
            return results;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("Semantic Scholar rate limit hit");
                return List.of();
            }
            throw new ConnectorException("Semantic Scholar HTTP error: " + e.getStatusCode(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("Semantic Scholar fetch failed: " + e.getMessage(), e);
        }
    }
}
