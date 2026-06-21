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

        String context = enricher.buildContext("what is climate change?", tenantId);

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

        String context = enricher.buildContext("what is climate change?", tenantId);

        assertThat(context).isEmpty();
    }

    @Test
    void buildContext_returnsEmpty_whenClientReturnsEmpty() {
        when(client.search(any(), any(), any(), anyInt())).thenReturn(SearchResponse.empty());

        String context = enricher.buildContext("some query", UUID.randomUUID());

        assertThat(context).isEmpty();
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

        String context = enricher.buildContext("long query", tenantId);

        assertThat(context.length()).isLessThanOrEqualTo(2000);
    }
}
