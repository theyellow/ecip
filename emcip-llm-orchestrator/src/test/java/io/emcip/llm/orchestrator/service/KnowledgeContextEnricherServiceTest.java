package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.emcip.llm.orchestrator.client.KnowledgeEngineClient;
import io.emcip.llm.orchestrator.client.KnowledgeEngineClient.DocumentResult;
import io.emcip.llm.orchestrator.client.KnowledgeEngineClient.KnowledgeDocument;
import io.emcip.llm.orchestrator.client.KnowledgeEngineClient.SearchResponse;
import io.emcip.llm.orchestrator.config.KnowledgeEnrichmentProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeContextEnricherServiceTest {

    private KnowledgeEngineClient client;
    private KnowledgeContextEnricherService enricher;

    @BeforeEach
    void setUp() {
        client = mock(KnowledgeEngineClient.class);
        KnowledgeEnrichmentProperties props =
                new KnowledgeEnrichmentProperties(true, 0.70, 5, 2000);
        enricher = new KnowledgeContextEnricherService(client, props);
    }

    @Test
    void buildContext_returnsFormattedContext_whenResultsAboveThreshold() {
        UUID tenantId = UUID.randomUUID();
        KnowledgeDocument doc =
                new KnowledgeDocument(
                        UUID.randomUUID(),
                        "Climate change increases sea levels.",
                        "https://ipcc.ch",
                        "WEBPAGE");
        DocumentResult result = new DocumentResult(doc, 0.85);
        when(client.search(anyString(), eq("HYBRID"), eq(tenantId), eq(5)))
                .thenReturn(new SearchResponse(List.of(), List.of(result)));

        String context = enricher.buildContext("what is climate change?", tenantId, "testnonce");

        assertThat(context).contains("Climate change increases sea levels.");
        assertThat(context).contains("https://ipcc.ch");
    }

    @Test
    void buildContext_excludesResultsBelowThreshold() {
        UUID tenantId = UUID.randomUUID();
        KnowledgeDocument doc =
                new KnowledgeDocument(
                        UUID.randomUUID(),
                        "Vaguely related text.",
                        "https://example.com",
                        "WEBPAGE");
        DocumentResult lowScore = new DocumentResult(doc, 0.50); // below 0.70 threshold
        when(client.search(anyString(), eq("HYBRID"), eq(tenantId), eq(5)))
                .thenReturn(new SearchResponse(List.of(), List.of(lowScore)));

        String context = enricher.buildContext("what is climate change?", tenantId, "testnonce");

        assertThat(context).isEmpty();
    }

    @Test
    void buildContext_returnsEmpty_whenClientReturnsEmpty() {
        when(client.search(any(), any(), any(), anyInt())).thenReturn(SearchResponse.empty());

        String context = enricher.buildContext("some query", UUID.randomUUID(), "testnonce");

        assertThat(context).isEmpty();
    }

    @Test
    void buildContext_wrapsEachDocumentWithBoundaryMarkers() {
        UUID tenantId = UUID.randomUUID();
        var doc1 =
                new KnowledgeDocument(
                        UUID.randomUUID(), "doc1-content", "https://example.com/1", "WEBPAGE");
        var doc2 =
                new KnowledgeDocument(
                        UUID.randomUUID(), "doc2-content", "https://example.com/2", "WEBPAGE");
        var result1 = new DocumentResult(doc1, 0.9);
        var result2 = new DocumentResult(doc2, 0.85);
        KnowledgeEnrichmentProperties props =
                new KnowledgeEnrichmentProperties(true, 0.70, 5, 2000);
        var response =
                new KnowledgeEngineClient.SearchResponse(List.of(), List.of(result1, result2));

        when(client.search("query", "HYBRID", tenantId, props.maxResults())).thenReturn(response);

        String context = enricher.buildContext("query", tenantId, "testnonce");

        assertThat(context).contains("<<<KNOWLEDGE_SOURCE_BEGIN n=testnonce>>>");
        assertThat(context).contains("<<<KNOWLEDGE_SOURCE_END n=testnonce>>>");
        assertThat(context).contains("doc1-content");
        assertThat(context)
                .contains("source=https://example.com/1"); // sourceRef now inside the fence body
    }

    @Test
    void buildContext_truncatesContextToMaxChars() {
        UUID tenantId = UUID.randomUUID();
        String longContent = "A".repeat(3000);
        KnowledgeDocument doc =
                new KnowledgeDocument(
                        UUID.randomUUID(), longContent, "https://example.com", "WEBPAGE");
        DocumentResult result = new DocumentResult(doc, 0.90);
        when(client.search(anyString(), eq("HYBRID"), eq(tenantId), eq(5)))
                .thenReturn(new SearchResponse(List.of(), List.of(result)));

        String context = enricher.buildContext("long query", tenantId, "testnonce");

        assertThat(context.length()).isLessThanOrEqualTo(2000);
    }
}
