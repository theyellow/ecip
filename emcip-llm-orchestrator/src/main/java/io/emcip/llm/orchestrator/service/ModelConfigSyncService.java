package io.emcip.llm.orchestrator.service;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reconciles {@code model_configs} with the models the LiteLLM proxy serves, once at startup.
 *
 * <p>The proxy URL and API key come from the {@code local-litellm} row of {@code
 * llm_provider_configs}. Only rows tagged with that provider ("owned" rows) are ever touched:
 *
 * <ul>
 *   <li>a served model no owned row references gets a default row;
 *   <li>an owned row whose model is not served is deactivated;
 *   <li>an owned, inactive row whose model is served again is reactivated. This deliberately also
 *       reactivates a row an operator switched off by hand — the proxy's model list is treated as
 *       the source of truth for owned rows.
 * </ul>
 *
 * <p>The sync is best-effort: any failure (proxy unreachable or slow, provider row undecryptable,
 * database error) is logged and startup continues with the existing configuration. It must never
 * fail boot — the in-product credential repair paths need this service running.
 */
@Slf4j
@Service
@Order(100)
public class ModelConfigSyncService implements ApplicationRunner {

    static final String PROVIDER_NAME = "local-litellm";

    private final ModelConfigRepository modelConfigRepository;
    private final LlmProviderConfigRepository providerConfigRepository;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public ModelConfigSyncService(
            ModelConfigRepository modelConfigRepository,
            LlmProviderConfigRepository providerConfigRepository,
            ObjectMapper objectMapper,
            @Value("${emcip.llm.model-sync.timeout:10s}") Duration timeout) {
        this.modelConfigRepository = modelConfigRepository;
        this.providerConfigRepository = providerConfigRepository;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            sync();
        } catch (Exception e) {
            log.warn(
                    "Model sync from LiteLLM proxy skipped, keeping existing configuration: {}",
                    e.getMessage());
            log.debug("Model sync failure", e);
        }
    }

    private void sync() {
        Optional<LlmProviderConfig> config = providerConfigRepository.findByName(PROVIDER_NAME);
        if (config.isEmpty()) {
            log.info("No '{}' provider configured; model sync skipped", PROVIDER_NAME);
            return;
        }
        String baseUrl = config.get().getBaseUrl();
        String apiKey = config.get().getApiKey();
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            log.warn("'{}' provider has no base URL or API key; model sync skipped", PROVIDER_NAME);
            return;
        }

        List<String> served = fetchServedModels(baseUrl + "/models", apiKey);
        if (served.isEmpty()) {
            // An empty list is far more likely a proxy still loading than a real "no models";
            // reconciling against it would deactivate every owned row.
            log.warn("LiteLLM proxy reported no models; model sync skipped");
            return;
        }
        reconcile(served, config.get().getName());
    }

    private List<String> fetchServedModels(String endpoint, String apiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        String body =
                RestClient.builder()
                        .requestFactory(requestFactory)
                        .build()
                        .get()
                        .uri(endpoint)
                        .headers(h -> h.setBearerAuth(apiKey))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(String.class);

        JsonNode data = objectMapper.readTree(body).path("data");
        return StreamSupport.stream(data.spliterator(), false)
                .map(node -> node.path("id"))
                .filter(JsonNode::isString)
                .map(JsonNode::asString)
                .toList();
    }

    private void reconcile(List<String> served, String providerName) {
        List<ModelConfig> existing = modelConfigRepository.findAll();
        Set<String> servedSet = Set.copyOf(served);

        // Scoped to rows this sync owns: other providers are never touched, even when a model
        // name happens to overlap the proxy's list.
        List<ModelConfig> owned =
                existing.stream().filter(m -> providerName.equals(m.getProvider())).toList();

        // Matched on model_name (the served model), not model_key (the routing key the UI
        // manages, which may differ, and may wrap the same served model several times).
        Set<String> configured =
                owned.stream().map(ModelConfig::getModelName).collect(Collectors.toSet());
        // Every routing key in use, so a create cannot collide with the unique constraint.
        Set<String> usedKeys =
                existing.stream().map(ModelConfig::getModelKey).collect(Collectors.toSet());

        int created = 0;
        for (String modelName : served) {
            if (configured.contains(modelName)) {
                continue;
            }
            if (usedKeys.contains(modelName)) {
                log.warn("Model sync: not creating {}, routing key already in use", modelName);
                continue;
            }
            if (save(newRow(modelName, providerName), "create")) {
                created++;
            }
        }

        int deactivated = 0;
        int reactivated = 0;
        for (ModelConfig row : owned) {
            boolean active = Boolean.TRUE.equals(row.getActive());
            boolean isServed = servedSet.contains(row.getModelName());
            if (active == isServed) {
                continue;
            }
            row.setActive(isServed);
            row.setUpdatedAt(Instant.now());
            if (save(row, isServed ? "reactivate" : "deactivate")) {
                if (isServed) {
                    reactivated++;
                } else {
                    deactivated++;
                }
            }
        }

        log.info(
                "Model sync: {} served, {} created, {} reactivated, {} deactivated",
                served.size(),
                created,
                reactivated,
                deactivated);
    }

    /**
     * One row failing (e.g. a concurrent replica won the optimistic lock) must not stop the rest.
     */
    private boolean save(ModelConfig row, String action) {
        try {
            modelConfigRepository.save(row);
            log.info("Model sync: {} {} ({})", action, row.getModelKey(), row.getModelName());
            return true;
        } catch (Exception e) {
            log.warn("Model sync: could not {} {}: {}", action, row.getModelKey(), e.getMessage());
            return false;
        }
    }

    private static ModelConfig newRow(String modelName, String providerName) {
        Instant now = Instant.now();
        ModelConfig row = new ModelConfig();
        row.setId(UUID.randomUUID());
        row.setModelKey(modelName);
        row.setProvider(providerName);
        row.setModelName(modelName);
        row.setDescription("Auto-synced from LiteLLM proxy");
        row.setTaskType("GENERAL");
        row.setInputCostPer1kTokens(0.0);
        row.setOutputCostPer1kTokens(0.0);
        row.setContextWindow(128000);
        row.setMaxOutputTokens(8192);
        row.setAvgLatencyMs(500.0);
        row.setSupportsStreaming(true);
        row.setActive(true);
        row.setPriority(100);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setVersionLock(0L);
        return row;
    }
}
