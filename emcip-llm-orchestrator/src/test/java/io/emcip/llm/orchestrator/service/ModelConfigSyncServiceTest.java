package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ModelConfigSyncService}.
 *
 * <p>The proxy is simulated with {@link MockWebServer} (a fake {@code /models} endpoint) and the
 * two repositories are Mockito mocks. These tests pin down the reconciliation semantics:
 *
 * <ul>
 *   <li>a served model that an owned row already references is never duplicated, even when it is
 *       exposed under a custom routing key;
 *   <li>auto-created rows are tagged with the provider taken from the DB config row ({@code
 *       local-litellm}), not a hardcoded value;
 *   <li>deactivation is scoped to owned rows only and never touches other providers.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ModelConfigSyncServiceTest {

    private static final String PROVIDER = "local-litellm";
    private static final String OTHER_PROVIDER = "anthropic";

    @Mock private ModelConfigRepository modelConfigRepository;
    @Mock private LlmProviderConfigRepository providerConfigRepository;

    private MockWebServer server;
    private ModelConfigSyncService service;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        service = new ModelConfigSyncService(modelConfigRepository, providerConfigRepository);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    void run_createsOnlyUnconfiguredModels_preservesCustomRoutingAndOtherProviders() {
        // A served model already configured under a custom routing key...
        ModelConfig alreadyConfigured = ownedRow("custom-key-a", "model-a", true);
        // ...and a row for a different provider that shares no models with the proxy.
        ModelConfig anthropic = otherProviderRow("claude-x", "claude-model", true);
        when(modelConfigRepository.findAll()).thenReturn(List.of(alreadyConfigured, anthropic));
        stubProvider(provider());
        serveModels("model-a", "model-b");

        service.run(null);

        // Only the genuinely new model is created - model-a is NOT duplicated.
        ArgumentCaptor<ModelConfig> saved = ArgumentCaptor.forClass(ModelConfig.class);
        verify(modelConfigRepository, times(1)).save(saved.capture());
        ModelConfig created = saved.getValue();
        assertThat(created.getModelName()).isEqualTo("model-b");
        assertThat(created.getModelKey()).isEqualTo("model-b");
        // The provider tag comes from the DB config row (regression: was hard-coded 'litellm').
        assertThat(created.getProvider()).isEqualTo(PROVIDER);

        // Existing rows are left untouched: the custom-routing row and the other provider.
        assertThat(alreadyConfigured.getActive()).isTrue();
        assertThat(alreadyConfigured.getModelName()).isEqualTo("model-a");
        assertThat(anthropic.getActive()).isTrue();
        assertThat(anthropic.getModelName()).isEqualTo("claude-model");
    }

    @Test
    void run_deactivatesOnlyStaleOwnedRows_neverOtherProviders() {
        ModelConfig kept = ownedRow("keep-model", "served-model", true);
        // Owned but no longer on the proxy.
        ModelConfig stale = ownedRow("old-model", "stale-model", true);
        // Also not on the proxy, but belongs to another provider so it must NOT be touched.
        ModelConfig anthropic = otherProviderRow("claude-y", "claude-model", true);
        when(modelConfigRepository.findAll()).thenReturn(List.of(kept, stale, anthropic));
        stubProvider(provider());
        serveModels("served-model");

        service.run(null);

        // No creates (served-model is already configured); exactly one deactivation.
        ArgumentCaptor<ModelConfig> saved = ArgumentCaptor.forClass(ModelConfig.class);
        verify(modelConfigRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(stale);
        assertThat(stale.getActive()).isFalse();
        assertThat(kept.getActive()).isTrue();
        assertThat(anthropic.getActive()).isTrue();
    }

    @Test
    void run_skipsWhenNoLiteLLMProviderConfigured() {
        when(providerConfigRepository.findByName(PROVIDER)).thenReturn(Optional.empty());

        service.run(null);

        // Early return: no model listing and no writes.
        verify(modelConfigRepository, never()).findAll();
        verify(modelConfigRepository, never()).save(any());
    }

    @Test
    void run_leavesDatabaseUntouchedWhenProxyServesNoModels() {
        stubProvider(provider());
        serveModels(); // empty "data" array

        service.run(null);

        // The fetch succeeded but reported nothing, so nothing is read/written.
        verify(modelConfigRepository, never()).findAll();
        verify(modelConfigRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ helpers

    private LlmProviderConfig provider() {
        String baseUrl = server.url("").toString().replaceAll("/$", "");
        return LlmProviderConfig.builder()
                .id(UUID.randomUUID())
                .name(PROVIDER)
                .baseUrl(baseUrl)
                .apiKey("test-api-key")
                .active(true)
                .build();
    }

    private void stubProvider(LlmProviderConfig config) {
        when(providerConfigRepository.findByName(PROVIDER)).thenReturn(Optional.of(config));
    }

    private void serveModels(String... ids) {
        server.enqueue(
                new MockResponse.Builder()
                        .body(modelsJson(ids))
                        .addHeader("Content-Type", "application/json")
                        .build());
    }

    private String modelsJson(String... ids) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"").append(ids[i]).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private ModelConfig ownedRow(String key, String name, boolean active) {
        return ModelConfig.builder()
                .id(UUID.randomUUID())
                .modelKey(key)
                .provider(PROVIDER)
                .modelName(name)
                .description("existing row")
                .taskType("GENERAL")
                .inputCostPer1kTokens(0.0)
                .outputCostPer1kTokens(0.0)
                .contextWindow(128000)
                .maxOutputTokens(8192)
                .avgLatencyMs(500.0)
                .supportsStreaming(true)
                .active(active)
                .priority(100)
                .build();
    }

    private ModelConfig otherProviderRow(String key, String name, boolean active) {
        return ModelConfig.builder()
                .id(UUID.randomUUID())
                .modelKey(key)
                .provider(OTHER_PROVIDER)
                .modelName(name)
                .description("other provider row")
                .taskType("GENERAL")
                .inputCostPer1kTokens(0.0)
                .outputCostPer1kTokens(0.0)
                .contextWindow(128000)
                .maxOutputTokens(8192)
                .avgLatencyMs(500.0)
                .supportsStreaming(false)
                .active(active)
                .priority(100)
                .build();
    }
}
