package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.connector.ConnectorException;
import io.emcip.knowledge.engine.connector.EnrichmentConnectorRegistry;
import io.emcip.knowledge.engine.connector.EnrichmentResult;
import io.emcip.knowledge.engine.connector.KnowledgeConnector;
import io.emcip.knowledge.engine.connector.TriggerMode;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.entity.RunStatus;
import io.emcip.knowledge.engine.repository.EnrichmentRunRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Characterisation tests for {@link EnrichmentPipelineService}. Covers the pure-branching stages
 * (missing connector, missing API key, connector fetch failure, dedup skip, successful ingest) with
 * mocked collaborators — no database. Full end-to-end persistence behaviour is covered by {@link
 * EnrichmentPipelineIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class EnrichmentPipelineServiceTest {

    @Mock private EnrichmentConnectorRegistry connectorRegistry;
    @Mock private ApiKeyResolver apiKeyResolver;
    @Mock private KnowledgeDocumentRepository documentRepository;
    @Mock private EnrichmentRunRepository runRepository;
    @Mock private VectorSearchRepository vectorSearchRepository;
    @Mock private LlmOrchestratorClient llmClient;
    @Mock private KnowledgeConnector connector;

    private EnrichmentPipelineService service;
    private EnrichmentSource source;
    private EnrichmentRun run;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new EnrichmentPipelineService(
                        connectorRegistry,
                        apiKeyResolver,
                        documentRepository,
                        runRepository,
                        vectorSearchRepository,
                        llmClient);
        source = new EnrichmentSource();
        source.setId(UUID.randomUUID());
        source.setVendorId("acme-vendor");
        run = new EnrichmentRun();
        run.setId(UUID.randomUUID());
        run.setStatus(RunStatus.RUNNING);
    }

    private EnrichmentRun capturedRun() {
        ArgumentCaptor<EnrichmentRun> captor = ArgumentCaptor.forClass(EnrichmentRun.class);
        verify(runRepository).save(captor.capture());
        return captor.getValue();
    }

    private EnrichmentResult resultWith(String externalId) {
        return new EnrichmentResult(
                externalId,
                "Title " + externalId,
                "Content " + externalId,
                "https://example.test/" + externalId,
                "acme-vendor",
                Instant.now(),
                Map.of());
    }

    @Test
    void execute_failsRunWhenNoConnectorRegisteredForVendor() {
        when(connectorRegistry.find("acme-vendor")).thenReturn(Optional.empty());

        service.execute(source, run, TriggerMode.TOPIC_DRIVEN, "Acme", null, tenantId);

        EnrichmentRun saved = capturedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILURE);
        assertThat(saved.getItemsFetched()).isZero();
        assertThat(saved.getItemsIngested()).isZero();
        assertThat(saved.getCompletedAt()).isNotNull();
        assertThat(saved.getErrorMessage()).contains("acme-vendor");
        verify(documentRepository, never()).save(any());
    }

    @Test
    void execute_failsRunWhenApiKeyRequiredButMissing() {
        when(connectorRegistry.find("acme-vendor")).thenReturn(Optional.of(connector));
        when(connector.requiresApiKey()).thenReturn(true);
        when(apiKeyResolver.resolve("acme-vendor", tenantId)).thenReturn(Optional.empty());

        service.execute(source, run, TriggerMode.TOPIC_DRIVEN, "Acme", null, tenantId);

        EnrichmentRun saved = capturedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILURE);
        assertThat(saved.getErrorMessage()).contains("acme-vendor");
        verify(connector, never()).fetch(any(), any());
    }

    @Test
    void execute_proceedsToFetchWhenConnectorNeedsNoApiKey() {
        when(connectorRegistry.find("acme-vendor")).thenReturn(Optional.of(connector));
        when(connector.requiresApiKey()).thenReturn(false);
        when(connector.fetch(any(), any())).thenReturn(List.of());

        service.execute(source, run, TriggerMode.TOPIC_DRIVEN, "Acme", null, tenantId);

        verify(connector).fetch(any(), any());
        verify(apiKeyResolver, never()).resolve(any(), any());
        EnrichmentRun saved = capturedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.SUCCESS);
        assertThat(saved.getItemsFetched()).isZero();
        assertThat(saved.getItemsIngested()).isZero();
    }

    @Test
    void execute_failsRunWhenConnectorFetchThrows() {
        when(connectorRegistry.find("acme-vendor")).thenReturn(Optional.of(connector));
        when(connector.requiresApiKey()).thenReturn(false);
        when(connector.fetch(any(), any())).thenThrow(new ConnectorException("upstream boom"));

        service.execute(source, run, TriggerMode.TOPIC_DRIVEN, "Acme", null, tenantId);

        EnrichmentRun saved = capturedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILURE);
        assertThat(saved.getErrorMessage()).isEqualTo("upstream boom");
        verify(documentRepository, never()).save(any());
    }

    @Test
    void execute_skipsAlreadyIngestedDuplicateAndReportsSuccessWithZeroIngested() {
        when(connectorRegistry.find("acme-vendor")).thenReturn(Optional.of(connector));
        when(connector.requiresApiKey()).thenReturn(false);
        EnrichmentResult result = resultWith("dup-1");
        when(connector.fetch(any(), any())).thenReturn(List.of(result));
        when(documentRepository.existsBySourceRefAndSourceType("dup-1", "acme-vendor"))
                .thenReturn(true);

        service.execute(source, run, TriggerMode.TOPIC_DRIVEN, "Acme", null, tenantId);

        verify(documentRepository, never()).save(any());
        EnrichmentRun saved = capturedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.SUCCESS);
        assertThat(saved.getItemsFetched()).isEqualTo(1);
        assertThat(saved.getItemsIngested()).isZero();
    }

    @Test
    void execute_ingestsNewResultAndBestEffortEmbedsIt() {
        when(connectorRegistry.find("acme-vendor")).thenReturn(Optional.of(connector));
        when(connector.requiresApiKey()).thenReturn(false);
        EnrichmentResult result = resultWith("new-1");
        when(connector.fetch(any(), any())).thenReturn(List.of(result));
        when(documentRepository.existsBySourceRefAndSourceType("new-1", "acme-vendor"))
                .thenReturn(false);

        UUID savedDocId = UUID.randomUUID();
        when(documentRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            KnowledgeDocument doc = invocation.getArgument(0);
                            doc.setId(savedDocId);
                            return doc;
                        });
        when(llmClient.embed("Content new-1")).thenReturn(new float[] {0.1f, 0.2f});

        service.execute(source, run, TriggerMode.TOPIC_DRIVEN, "Acme", null, tenantId);

        ArgumentCaptor<KnowledgeDocument> docCaptor =
                ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documentRepository).save(docCaptor.capture());
        assertThat(docCaptor.getValue().getSourceRef()).isEqualTo("new-1");
        assertThat(docCaptor.getValue().getTenantId()).isEqualTo(tenantId);
        verify(vectorSearchRepository).storeEmbedding(savedDocId, new float[] {0.1f, 0.2f});

        EnrichmentRun capturedRunResult = capturedRun();
        assertThat(capturedRunResult.getStatus()).isEqualTo(RunStatus.SUCCESS);
        assertThat(capturedRunResult.getItemsFetched()).isEqualTo(1);
        assertThat(capturedRunResult.getItemsIngested()).isEqualTo(1);
    }
}
