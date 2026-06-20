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
public class PubMedConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;

    public PubMedConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${connector.pubmed.base-url:https://eutils.ncbi.nlm.nih.gov}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "pubmed";
    }

    @Override
    public String displayName() {
        return "PubMed";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        if (request.query() == null) return List.of();
        String apiKeyParam = ctx.apiKey() != null ? "&api_key=" + ctx.apiKey() : "";
        try {
            // Step 1: search for IDs
            String searchUri =
                    baseUrl
                            + "/entrez/eutils/esearch.fcgi?db=pubmed&retmax=10&retmode=json"
                            + "&term="
                            + URLEncoder.encode(request.query(), StandardCharsets.UTF_8)
                            + apiKeyParam;
            JsonNode searchResult = restClient.get().uri(searchUri).retrieve().body(JsonNode.class);
            if (searchResult == null) return List.of();

            List<String> ids = new ArrayList<>();
            searchResult.path("esearchresult").path("idlist").forEach(n -> ids.add(n.asText()));
            if (ids.isEmpty()) return List.of();

            // Step 2: fetch summaries
            String summaryUri =
                    baseUrl
                            + "/entrez/eutils/esummary.fcgi?db=pubmed&retmode=json"
                            + "&id="
                            + String.join(",", ids)
                            + apiKeyParam;
            JsonNode summaryResult =
                    restClient.get().uri(summaryUri).retrieve().body(JsonNode.class);
            if (summaryResult == null) return List.of();

            List<EnrichmentResult> results = new ArrayList<>();
            JsonNode resultNode = summaryResult.path("result");
            for (String id : ids) {
                JsonNode item = resultNode.path(id);
                if (item.isMissingNode()) continue;
                String title = item.path("title").asText();
                String pubdate = item.path("pubdate").asText(null);
                String url = "https://pubmed.ncbi.nlm.nih.gov/" + id + "/";
                results.add(
                        new EnrichmentResult(
                                "pubmed:" + id,
                                title,
                                null,
                                url,
                                vendorId(),
                                Instant.now(),
                                Map.of("pmid", id, "pubdate", pubdate != null ? pubdate : "")));
            }
            return results;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("PubMed rate limit hit");
                return List.of();
            }
            throw new ConnectorException("PubMed HTTP error: " + e.getStatusCode(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("PubMed fetch failed: " + e.getMessage(), e);
        }
    }
}
