package io.emcip.knowledge.engine.connector.impl;

import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.ConnectorException;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.KnowledgeConnector;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Component
@Slf4j
public class ArxivConnector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;

    public ArxivConnector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${connector.arxiv.base-url:https://export.arxiv.org}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String vendorId() {
        return "arxiv";
    }

    @Override
    public String displayName() {
        return "arXiv";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        if (request.query() == null && request.externalId() == null) return List.of();
        String maxResults = request.params().getOrDefault("maxResults", "10");

        String uri;
        if (request.externalId() != null) {
            uri =
                    baseUrl
                            + "/api/query?id_list="
                            + URLEncoder.encode(request.externalId(), StandardCharsets.UTF_8);
        } else {
            String q = URLEncoder.encode("all:" + request.query(), StandardCharsets.UTF_8);
            uri = baseUrl + "/api/query?search_query=" + q + "&max_results=" + maxResults;
        }

        try {
            String xml = restClient.get().uri(uri).retrieve().body(String.class);
            if (xml == null) return List.of();
            return parseAtom(xml);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("arXiv rate limit hit");
                return List.of();
            }
            throw new ConnectorException("arXiv HTTP error: " + e.getStatusCode(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("arXiv fetch failed: " + e.getMessage(), e);
        }
    }

    private List<EnrichmentResult> parseAtom(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList entries = doc.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "entry");
        List<EnrichmentResult> results = new ArrayList<>();
        for (int i = 0; i < entries.getLength(); i++) {
            var entry = entries.item(i);
            String id = text(entry, "id");
            String arxivId = id.replaceAll(".*abs/", "").replaceAll("v\\d+$", "");
            String title = text(entry, "title");
            String summary = text(entry, "summary");
            String published = text(entry, "published");
            String link = "https://arxiv.org/abs/" + arxivId;
            Instant publishedAt = published.isBlank() ? Instant.now() : Instant.parse(published);
            results.add(
                    new EnrichmentResult(
                            arxivId,
                            title.strip(),
                            summary.strip(),
                            link,
                            vendorId(),
                            publishedAt,
                            Map.of("source", "arxiv")));
        }
        return results;
    }

    private String text(org.w3c.dom.Node parent, String tagName) {
        var nl =
                ((org.w3c.dom.Element) parent)
                        .getElementsByTagNameNS("http://www.w3.org/2005/Atom", tagName);
        return nl.getLength() > 0 ? nl.item(0).getTextContent().trim() : "";
    }
}
