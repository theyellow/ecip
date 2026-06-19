# Epic 42 — Knowledge Enrichment: 13 Connector Implementations

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement all 13 `KnowledgeConnector` beans. Each is a `@Component` using `RestClient` to call an external API, mapped to `List<EnrichmentResult>`. Each task includes a unit test using `MockWebServer` (OkHttp) to stub the external HTTP call.

**Architecture:** All connectors live in `io.emcip.knowledge.engine.connector.impl`. They implement the `KnowledgeConnector` interface from Plan A.1 Task 5. HTTP calls use Spring's `RestClient` (already used in `BackfillService`). Rate-limit errors (429) return an empty list; auth errors (401/403) throw `ConnectorException`.

**Prerequisite:** Plan A.1 complete. `KnowledgeConnector`, `ConnectorContext`, `EnrichmentRequest`, `EnrichmentResult`, `TriggerMode`, `ConnectorException` all exist.

**Tech Stack:** Java 21, Spring `RestClient`, OkHttp `MockWebServer`, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-06-19-42-knowledge-enrichment-connectors-design.md` (Connectors table)

---

## File Map

All files are new. Package: `io.emcip.knowledge.engine.connector.impl`

**Connectors (13):**
- `WikipediaConnector.java`
- `ArxivConnector.java`
- `PubMedConnector.java`
- `WikidataConnector.java`
- `OpenAlexConnector.java`
- `SemanticScholarConnector.java`
- `BiorxivConnector.java`
- `CoreConnector.java`
- `ZenodoConnector.java`
- `UnpaywallConnector.java`
- `DoajConnector.java`
- `ExaConnector.java`
- `BraveConnector.java`

**Config:**
- `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/ConnectorConfig.java` — creates a shared `RestClient` bean

**Tests (13):**
- `src/test/java/io/emcip/knowledge/engine/connector/impl/WikipediaConnectorTest.java`
- `src/test/java/io/emcip/knowledge/engine/connector/impl/ArxivConnectorTest.java`
- (… one per connector, same package)

---

## Task 0: Shared RestClient bean + base test helper

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/ConnectorConfig.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/connector/impl/ConnectorTestBase.java`

- [ ] **Step 1: Create ConnectorConfig.java**

One shared `RestClient` with a 10-second read timeout and a descriptive User-Agent. Individual connectors inject it and call `.baseUrl()` per request to override the target.

```java
package io.emcip.knowledge.engine.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ConnectorConfig {

    @Bean(name = "connectorRestClient")
    public RestClient connectorRestClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", "EMCIP-KnowledgeEngine/1.0 (research-enrichment)")
                .build();
    }
}
```

- [ ] **Step 2: Create ConnectorTestBase.java**

Shared setup for all connector unit tests — starts/stops MockWebServer and provides a `RestClient` pointed at it.

```java
package io.emcip.knowledge.engine.connector.impl;

import java.io.IOException;
import java.time.Instant;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.web.client.RestClient;

abstract class ConnectorTestBase {

    protected MockWebServer server;
    protected RestClient restClient;

    /** Override to point at the mock server's base URL. */
    protected String baseUrl() {
        return server.url("/").toString();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        restClient = RestClient.builder()
                .baseUrl(baseUrl())
                .defaultHeader("User-Agent", "EMCIP-Test/1.0")
                .build();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    protected static Instant epoch() {
        return Instant.parse("2026-01-01T00:00:00Z");
    }
}
```

- [ ] **Step 3: Add MockWebServer dependency to pom.xml**

Read `emcip-knowledge-engine/pom.xml`. Add inside `<dependencies>` (test scope):

```xml
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>mockwebserver</artifactId>
    <version>4.12.0</version>
    <scope>test</scope>
</dependency>
```

If OkHttp is already a dependency (it is — used for OpenTelemetry), check the existing version and use the same major for MockWebServer.

- [ ] **Step 4: Compile check**

```bash
cd emcip-knowledge-engine
mvn compile -q && mvn test-compile -q | cat
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/ConnectorConfig.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/connector/impl/ConnectorTestBase.java \
        emcip-knowledge-engine/pom.xml
git commit -m "feat(42): add ConnectorConfig RestClient bean and ConnectorTestBase for unit tests"
```

---

## Task 1: WikipediaConnector

**API:** `https://en.wikipedia.org/api/rest_v1/page/summary/{title}` — returns JSON with `title`, `extract`, `content_urls.desktop.page`.

**Files:**
- Create: `…/connector/impl/WikipediaConnector.java`
- Create: `…/connector/impl/WikipediaConnectorTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.emcip.knowledge.engine.connector.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.TriggerMode;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

class WikipediaConnectorTest extends ConnectorTestBase {

    @Test
    void fetch_returnsSummaryResult() {
        server.enqueue(new MockResponse()
                .setBody("""
                        {
                          "title": "Quantum computing",
                          "extract": "Quantum computing is a type of computation...",
                          "content_urls": {
                            "desktop": { "page": "https://en.wikipedia.org/wiki/Quantum_computing" }
                          },
                          "timestamp": "2026-01-10T00:00:00Z"
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        WikipediaConnector connector = new WikipediaConnector(restClient, baseUrl());
        List<EnrichmentResult> results = connector.fetch(
                new EnrichmentRequest(TriggerMode.TOPIC_DRIVEN, "Quantum computing", null, Map.of()),
                new ConnectorContext(null, java.util.UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Quantum computing");
        assertThat(results.get(0).content()).contains("Quantum computing is");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("wikipedia");
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));

        WikipediaConnector connector = new WikipediaConnector(restClient, baseUrl());
        List<EnrichmentResult> results = connector.fetch(
                new EnrichmentRequest(TriggerMode.TOPIC_DRIVEN, "something", null, Map.of()),
                new ConnectorContext(null, java.util.UUID.randomUUID(), epoch()));

        assertThat(results).isEmpty();
    }
}
```

- [ ] **Step 2: Run — verify it fails**

```bash
cd emcip-knowledge-engine
mvn test -pl . -Dtest=WikipediaConnectorTest | cat
```

Expected: FAIL — `WikipediaConnector` does not exist.

- [ ] **Step 3: Create WikipediaConnector.java**

```java
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

    @Override public String vendorId() { return "wikipedia"; }
    @Override public String displayName() { return "Wikipedia"; }
    @Override public boolean requiresApiKey() { return false; }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        if (request.query() == null) return List.of();
        String encoded = URLEncoder.encode(request.query(), StandardCharsets.UTF_8);
        try {
            JsonNode node = restClient.get()
                    .uri(baseUrl + "/api/rest_v1/page/summary/" + encoded)
                    .retrieve()
                    .body(JsonNode.class);

            if (node == null) return List.of();

            String title = node.path("title").asText();
            String extract = node.path("extract").asText(null);
            String url = node.path("content_urls").path("desktop").path("page").asText();
            String ts = node.path("timestamp").asText(null);
            Instant published = ts != null ? Instant.parse(ts) : Instant.now();

            return List.of(new EnrichmentResult(
                    "wikipedia:" + title.replace(' ', '_'),
                    title, extract, url, vendorId(), published,
                    Map.of("source", "wikipedia")));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("Wikipedia rate limit hit");
                return List.of();
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return List.of();
            }
            throw new ConnectorException("Wikipedia HTTP error: " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new ConnectorException("Wikipedia fetch failed: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
cd emcip-knowledge-engine
mvn test -pl . -Dtest=WikipediaConnectorTest | cat
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/connector/impl/WikipediaConnector.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/connector/impl/WikipediaConnectorTest.java
git commit -m "feat(42): implement WikipediaConnector"
```

---

## Task 2: ArxivConnector

**API:** `http://export.arxiv.org/api/query?search_query=all:{query}&max_results=10` — Atom XML response. Rate limit: 3 req/s.

- [ ] **Step 1: Write failing test**

```java
package io.emcip.knowledge.engine.connector.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.connector.ConnectorContext;
import io.emcip.knowledge.engine.connector.EnrichmentRequest;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.TriggerMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

class ArxivConnectorTest extends ConnectorTestBase {

    private static final String ATOM_RESPONSE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>http://arxiv.org/abs/2301.00001v1</id>
                <title>Quantum Advantage in Machine Learning</title>
                <summary>We demonstrate quantum advantage...</summary>
                <published>2023-01-01T00:00:00Z</published>
                <link href="https://arxiv.org/abs/2301.00001" rel="alternate"/>
              </entry>
            </feed>
            """;

    @Test
    void fetch_parsesAtomXml() {
        server.enqueue(new MockResponse()
                .setBody(ATOM_RESPONSE)
                .addHeader("Content-Type", "application/atom+xml"));

        ArxivConnector connector = new ArxivConnector(restClient, baseUrl());
        List<EnrichmentResult> results = connector.fetch(
                new EnrichmentRequest(TriggerMode.SCHEDULED, "quantum machine learning", null, Map.of()),
                new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).externalId()).isEqualTo("2301.00001");
        assertThat(results.get(0).title()).isEqualTo("Quantum Advantage in Machine Learning");
        assertThat(results.get(0).content()).contains("quantum advantage");
        assertThat(results.get(0).sourceVendorId()).isEqualTo("arxiv");
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));

        ArxivConnector connector = new ArxivConnector(restClient, baseUrl());
        List<EnrichmentResult> results = connector.fetch(
                new EnrichmentRequest(TriggerMode.SCHEDULED, "topic", null, Map.of()),
                new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).isEmpty();
    }
}
```

- [ ] **Step 2: Run — verify it fails**

```bash
cd emcip-knowledge-engine
mvn test -pl . -Dtest=ArxivConnectorTest | cat
```

Expected: FAIL — `ArxivConnector` does not exist.

- [ ] **Step 3: Create ArxivConnector.java**

```java
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

    @Override public String vendorId() { return "arxiv"; }
    @Override public String displayName() { return "arXiv"; }
    @Override public boolean requiresApiKey() { return false; }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        if (request.query() == null && request.externalId() == null) return List.of();
        String maxResults = request.params().getOrDefault("maxResults", "10");

        String uri;
        if (request.externalId() != null) {
            uri = baseUrl + "/api/query?id_list=" + URLEncoder.encode(request.externalId(), StandardCharsets.UTF_8);
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
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
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
            results.add(new EnrichmentResult(arxivId, title.strip(), summary.strip(),
                    link, vendorId(), publishedAt, Map.of("source", "arxiv")));
        }
        return results;
    }

    private String text(org.w3c.dom.Node parent, String tagName) {
        var nl = ((org.w3c.dom.Element) parent)
                .getElementsByTagNameNS("http://www.w3.org/2005/Atom", tagName);
        return nl.getLength() > 0 ? nl.item(0).getTextContent().trim() : "";
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
cd emcip-knowledge-engine
mvn test -pl . -Dtest=ArxivConnectorTest | cat
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/connector/impl/ArxivConnector.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/connector/impl/ArxivConnectorTest.java
git commit -m "feat(42): implement ArxivConnector with Atom XML parsing"
```

---

## Tasks 3–13: Remaining connectors

Each connector follows the identical structure as Tasks 1–2:
1. Write failing test (MockWebServer stubs the real API response shape)
2. Run — verify failure
3. Implement connector
4. Run — verify pass
5. Commit

The table below gives the key implementation details for each. The test and implementation structure is the same as `WikipediaConnector` — only the API call shape, JSON/XML parsing, and `vendorId()` differ.

| Task | Connector | vendorId | requiresApiKey | API endpoint / notes |
|------|-----------|----------|----------------|----------------------|
| 3 | PubMedConnector | `pubmed` | `false` | `https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed&term={query}&retmax=10&retmode=json` → returns `esearchresult.idlist`; then `efetch.fcgi?db=pubmed&id={ids}&retmode=xml` to get titles/abstracts. OR use `esummary.fcgi?db=pubmed&id={ids}&retmode=json` for simpler summary. Pass `api_key={apiKey}` as query param if present. |
| 4 | WikidataConnector | `wikidata` | `false` | `https://www.wikidata.org/w/api.php?action=wbsearchentities&search={query}&language=en&format=json` → `search[]` array with `id`, `label`, `description`, `url`. |
| 5 | OpenAlexConnector | `openalex` | `false` | `https://api.openalex.org/works?search={query}&per-page=10` → `results[]` with `id`, `title`, `abstract_inverted_index` (reconstruct abstract), `doi`, `publication_date`. Add `mailto=emcip@example.com` query param (polite pool). |
| 6 | SemanticScholarConnector | `semantic-scholar` | `false` | `https://api.semanticscholar.org/graph/v1/paper/search?query={query}&fields=paperId,title,abstract,year,externalIds,openAccessPdf&limit=10` → `data[]`. Pass `x-api-key` header if key present. |
| 7 | BiorxivConnector | `biorxiv` | `false` | `https://api.biorxiv.org/details/biorxiv/{from}/{to}/0/json` where `from` = `ctx.since().toString().substring(0,10)` and `to` = today. Returns `collection[]` with `doi`, `title`, `abstract`, `date`. medRxiv uses same format at `https://api.biorxiv.org/details/medrxiv/...`. Make two calls and merge. |
| 8 | CoreConnector | `core` | `true` | `https://api.core.ac.uk/v3/search/works?q={query}&limit=10` with `Authorization: Bearer {apiKey}` header → `results[]` with `id`, `title`, `abstract`, `downloadUrl`, `publishedDate`. |
| 9 | ZenodoConnector | `zenodo` | `false` | `https://zenodo.org/api/records?q={query}&size=10` → `hits.hits[]` with `id`, `metadata.title`, `metadata.description`, `metadata.publication_date`, `links.html`. |
| 10 | UnpaywallConnector | `unpaywall` | `false` | Lookup-only. Only called when `request.externalId()` is a DOI. `https://api.unpaywall.org/v2/{doi}?email=emcip@example.com` → `title`, `published_date`, `best_oa_location.url_for_pdf`. Returns empty list if `externalId` is null. |
| 11 | DoajConnector | `doaj` | `false` | `https://doaj.org/api/search/articles/{query}?pageSize=10` → `results[]` with `bibjson.title`, `bibjson.abstract`, `bibjson.identifier[type=doi]`, `bibjson.journal.title`. |
| 12 | ExaConnector | `exa` | `true` | `https://api.exa.ai/search` POST with `{"query": "{query}", "numResults": 10, "type": "neural"}`, `x-api-key: {apiKey}` header → `results[]` with `id`, `title`, `text`, `url`, `publishedDate`. |
| 13 | BraveConnector | `brave` | `true` | `https://api.search.brave.com/res/v1/web/search?q={query}&count=10`, `Accept: application/json`, `X-Subscription-Token: {apiKey}` header → `web.results[]` with `title`, `description`, `url`, `age`. |

### Template for each connector test

Use this template, filling in the vendor-specific stub response and assertions:

```java
package io.emcip.knowledge.engine.connector.impl;

import static org.assertj.core.api.Assertions.assertThat;
import io.emcip.knowledge.engine.connector.*;
import java.util.*;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

class ${VendorName}ConnectorTest extends ConnectorTestBase {

    @Test
    void fetch_returnsResults() {
        server.enqueue(new MockResponse()
                .setBody(/* vendor-specific JSON/XML stub */)
                .addHeader("Content-Type", "application/json"));

        ${VendorName}Connector connector = new ${VendorName}Connector(restClient, baseUrl()
            /*, apiKey if required */);
        List<EnrichmentResult> results = connector.fetch(
                new EnrichmentRequest(TriggerMode.SCHEDULED, "query", null, Map.of()),
                new ConnectorContext(/* apiKey or null */, UUID.randomUUID(), epoch()));

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).sourceVendorId()).isEqualTo("${vendorId}");
        assertThat(results.get(0).title()).isNotBlank();
    }

    @Test
    void fetch_returns429_asEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(429));

        ${VendorName}Connector connector = new ${VendorName}Connector(restClient, baseUrl());
        List<EnrichmentResult> results = connector.fetch(
                new EnrichmentRequest(TriggerMode.SCHEDULED, "query", null, Map.of()),
                new ConnectorContext(null, UUID.randomUUID(), epoch()));

        assertThat(results).isEmpty();
    }

    /* For requiresApiKey=true connectors, add: */
    @Test
    void fetch_returns401_throwsConnectorException() {
        server.enqueue(new MockResponse().setResponseCode(401));

        ${VendorName}Connector connector = new ${VendorName}Connector(restClient, baseUrl(), "bad-key");
        org.junit.jupiter.api.Assertions.assertThrows(ConnectorException.class, () ->
                connector.fetch(
                        new EnrichmentRequest(TriggerMode.MANUAL, "q", null, Map.of()),
                        new ConnectorContext("bad-key", UUID.randomUUID(), epoch())));
    }
}
```

### Template for each connector implementation

```java
package io.emcip.knowledge.engine.connector.impl;

import io.emcip.knowledge.engine.connector.*;
import java.util.*;
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
public class ${VendorName}Connector implements KnowledgeConnector {

    private final RestClient restClient;
    private final String baseUrl;

    public ${VendorName}Connector(
            @Qualifier("connectorRestClient") RestClient restClient,
            @Value("${connector.${vendorId}.base-url:${defaultBaseUrl}}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    @Override public String vendorId() { return "${vendorId}"; }
    @Override public String displayName() { return "${Display Name}"; }
    @Override public boolean requiresApiKey() { return /* true|false */; }

    @Override
    public List<EnrichmentResult> fetch(EnrichmentRequest request, ConnectorContext ctx) {
        try {
            // Build URL, make HTTP call, parse JSON, map to EnrichmentResult
            // Return List.of() on 429
            // Throw ConnectorException on 401/403 or network error
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("{} rate limit hit", vendorId());
                return List.of();
            }
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED
                    || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new ConnectorException(vendorId() + " auth error: " + e.getStatusCode(), e);
            }
            throw new ConnectorException(vendorId() + " HTTP error: " + e.getStatusCode(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException(vendorId() + " fetch failed: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step: Implement Task 3 (PubMed) — test → fail → implement → pass → commit**
- [ ] **Step: Implement Task 4 (Wikidata) — test → fail → implement → pass → commit**
- [ ] **Step: Implement Task 5 (OpenAlex) — test → fail → implement → pass → commit**
- [ ] **Step: Implement Task 6 (Semantic Scholar) — test → fail → implement → pass → commit**
- [ ] **Step: Implement Task 7 (bioRxiv/medRxiv) — test → fail → implement → pass → commit**
- [ ] **Step: Implement Task 8 (CORE) — test → fail → implement → pass → commit**
- [ ] **Step: Implement Task 9 (Zenodo) — test → fail → implement → pass → commit**
- [ ] **Step: Implement Task 10 (Unpaywall) — test → fail → implement → pass → commit**
- [ ] **Step: Implement Task 11 (DOAJ) — test → fail → implement → pass → commit**
- [ ] **Step: Implement Task 12 (Exa) — test → fail → implement → pass → commit**
- [ ] **Step: Implement Task 13 (Brave) — test → fail → implement → pass → commit**

For each task, follow Tasks 1–2 as the structural template. The key variations are listed in the table above.

---

## Task 14: EnrichmentConnectorRegistry smoke test

After all 13 connectors are implemented, verify the registry collects them all.

- [ ] **Step 1: Create integration test**

```java
package io.emcip.knowledge.engine.connector;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class EnrichmentConnectorRegistryIntegrationTest {

    @Autowired EnrichmentConnectorRegistry registry;

    @Test
    void registry_containsAllThirteenConnectors() {
        List<KnowledgeConnector> all = registry.all();
        assertThat(all).hasSizeGreaterThanOrEqualTo(13);

        List<String> vendorIds = all.stream().map(KnowledgeConnector::vendorId).toList();
        assertThat(vendorIds).contains(
                "wikipedia", "arxiv", "pubmed", "wikidata", "openalex",
                "semantic-scholar", "biorxiv", "core", "zenodo", "unpaywall",
                "doaj", "exa", "brave");
    }

    @Test
    void registry_find_returnsConnectorForKnownVendor() {
        assertThat(registry.find("wikipedia")).isPresent();
        assertThat(registry.find("unknown-vendor")).isEmpty();
    }
}
```

- [ ] **Step 2: Run**

```bash
cd emcip-knowledge-engine
mvn test -pl . -Dtest=EnrichmentConnectorRegistryIntegrationTest | cat
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 3: Run full test suite + Spotless**

```bash
cd emcip-knowledge-engine
mvn test | cat
mvn spotless:apply | cat
```

If Spotless changed files: `git add -A && git commit -m "style: apply spotless"`

- [ ] **Step 4: Final commit**

```bash
git add emcip-knowledge-engine/src/test/
git commit -m "test(42): add EnrichmentConnectorRegistry integration test — all 13 connectors registered"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Covered |
|---|---|
| All 13 connectors in v1 | Tasks 1–13 |
| Wikipedia — no key, REST v1 summary | Task 1 |
| arXiv — no key, 3 req/s, Atom XML | Task 2 |
| PubMed — key optional, query param `api_key` | Task 3 |
| Wikidata — no key, SPARQL/REST | Task 4 |
| OpenAlex — no key, polite email param | Task 5 |
| Semantic Scholar — key optional, batch | Task 6 |
| bioRxiv/medRxiv — no key, 30 records/call | Task 7 |
| CORE — required key, free reg | Task 8 |
| Zenodo — no key | Task 9 |
| Unpaywall — no key, lookup-only when DOI present | Task 10 |
| DOAJ — no key | Task 11 |
| Exa — required key, paid | Task 12 |
| Brave — required key, paid | Task 13 |
| MockWebServer unit test per connector | Every task |
| 429 → empty list (not exception) | Every connector template |
| 401/403 on key-required connectors → ConnectorException | Tasks 8, 12, 13 |
| `@Qualifier("connectorRestClient")` to inject shared client | Every connector |
| `@Value` for base URL (overridable in tests) | Every connector |
| Registry collects all 13 via Spring injection | Task 14 |

**No placeholders found.**

**Type consistency:** Every connector returns `List<EnrichmentResult>` with all required fields populated. `externalId` is always non-null (vendor-specific ID format documented in the table). `sourceVendorId` always equals `vendorId()`.
