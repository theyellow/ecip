package io.emcip.llm.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Synchronizes model configurations from LiteLLM proxy at startup. Auto-creates model_configs
 * entries for available models, preserving manual task assignments.
 *
 * <p>Configuration: - Reads LiteLLM proxy URL and API key from llm_provider_configs table
 * (provider_name = 'local-litellm') - Consistent with existing database-driven configuration
 * approach
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Order(100) // Run early in startup
public class ModelConfigSyncService implements ApplicationRunner {

    private final ModelConfigRepository modelConfigRepository;
    private final LlmProviderConfigRepository providerConfigRepository;

    @Override
    public void run(ApplicationArguments args) {
        // Try database config first (preferred - consistent with existing approach)
        var dbConfig = providerConfigRepository.findByName("local-litellm");

        String proxyUrl;
        String apiKey;
        String providerName;

        if (dbConfig.isPresent()) {
            proxyUrl = dbConfig.get().getBaseUrl();
            apiKey = dbConfig.get().getApiKey();
            // The provider tag written to model_configs comes from the DB config row, so
            // auto-created rows stay consistent with existing rows (both 'local-litellm').
            providerName = dbConfig.get().getName();

            if (proxyUrl == null || proxyUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
                log.warn("⚠️  local-litellm provider configured but missing URL or API key");
                log.warn("   Check llm_provider_configs table for 'local-litellm' entry");
                log.warn("   Skipping model sync - using existing database configuration.");
                return;
            }

            log.info("Using LiteLLM proxy configuration from database (local-litellm)");
        } else {
            log.warn("⚠️  No 'local-litellm' provider found in llm_provider_configs table");
            log.warn(
                    "   Please add a provider entry with name='local-litellm' and set base_url and"
                            + " api_key");
            log.warn("   Skipping model sync - using existing database configuration.");
            return;
        }

        String modelsEndpoint = proxyUrl + "/models";

        try {
            List<String> availableModels = fetchAvailableModels(modelsEndpoint, apiKey);

            if (availableModels.isEmpty()) {
                log.warn("⚠️  No models found on LiteLLM proxy at {}", modelsEndpoint);
                log.warn("   Check proxy connectivity and configuration.");
                return;
            }

            syncModelConfigs(availableModels, providerName);

            log.info("✓ Model sync complete: {} models available on proxy", availableModels.size());
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            log.warn("Optimistic locking conflict during model sync: {}", e.getMessage());
            log.debug("Sync error details", e);
            log.warn("   Starting with existing database configuration");
        } catch (Exception e) {
            log.warn("Could not sync models from LiteLLM proxy: {}", e.getMessage());
            log.debug("Sync error details", e);
            log.warn("   Starting with existing database configuration");
        }
    }

    private List<String> fetchAvailableModels(String endpoint, String apiKey) {
        RestTemplate restTemplate = new RestTemplate();
        ObjectMapper objectMapper = new ObjectMapper();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response =
                    restTemplate.exchange(endpoint, HttpMethod.GET, entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode data = root.get("data");

            if (data != null && data.isArray()) {
                return java.util.stream.StreamSupport.stream(data.spliterator(), false)
                        .map(node -> node.get("id"))
                        .filter(JsonNode::isTextual)
                        .map(JsonNode::asText)
                        .collect(Collectors.toList());
            }

            return List.of();
        } catch (Exception e) {
            log.error("Failed to fetch models from proxy: {}", e.getMessage());
            throw new RuntimeException("Could not fetch models from LiteLLM proxy", e);
        }
    }

    private void syncModelConfigs(List<String> availableModels, String providerName) {
        try {
            List<ModelConfig> existingModels = modelConfigRepository.findAll();

            // Rows this sync owns share the source config's provider tag. Matching and
            // deactivation stay scoped to these - we must never touch other providers
            // (e.g. anthropic) even if a name happens to overlap the proxy model set.
            List<ModelConfig> ownedModels =
                    existingModels.stream()
                            .filter(m -> providerName.equals(m.getProvider()))
                            .collect(Collectors.toList());

            // Served model names already configured by an owned row. Matched on model_name
            // (the link to the served model), deliberately NOT model_key (the app-level
            // routing key the UI manages, which may differ - or wrap the same model - so the
            // same served model can appear under several routing keys).
            Set<String> alreadyConfigured =
                    ownedModels.stream().map(ModelConfig::getModelName).collect(Collectors.toSet());

            // Every routing key in use, so a create can't collide with the unique constraint.
            Set<String> existingKeys =
                    existingModels.stream()
                            .map(ModelConfig::getModelKey)
                            .collect(Collectors.toSet());

            Set<String> availableSet = availableModels.stream().collect(Collectors.toSet());

            int created = 0;

            // Create a default row only for served models no owned row references yet,
            // preserving any existing manual routing for models that are already configured.
            for (String modelName : availableModels) {
                if (alreadyConfigured.contains(modelName)) {
                    continue; // already configured (possibly under a custom routing key) - leave it
                }

                String modelKey = modelName;
                if (existingKeys.contains(modelKey)) {
                    log.warn("  Skipped create for {}: routing key already in use", modelKey);
                    continue;
                }

                ModelConfig newModel = new ModelConfig();
                newModel.setId(UUID.randomUUID());
                newModel.setModelKey(modelKey);
                newModel.setProvider(providerName);
                newModel.setModelName(modelName);
                newModel.setDescription("Auto-synced from LiteLLM proxy");
                newModel.setTaskType("GENERAL"); // Default task type
                newModel.setInputCostPer1kTokens(0.0);
                newModel.setOutputCostPer1kTokens(0.0);
                newModel.setContextWindow(128000);
                newModel.setMaxOutputTokens(8192);
                newModel.setAvgLatencyMs(500.0);
                newModel.setSupportsStreaming(true);
                newModel.setActive(true);
                newModel.setPriority(100);
                newModel.setCreatedAt(Instant.now());
                newModel.setUpdatedAt(Instant.now());
                newModel.setVersionLock(0L);

                try {
                    modelConfigRepository.save(newModel);
                    created++;
                    log.info("  Created: {} ({})", modelKey, modelName);
                } catch (Exception e) {
                    log.debug("Version conflict while creating {}: {}", modelKey, e.getMessage());
                }
            }

            // Deactivate owned rows whose served model is no longer on the proxy.
            int deactivated = 0;
            for (ModelConfig existing : ownedModels) {
                if (Boolean.TRUE.equals(existing.getActive())
                        && !availableSet.contains(existing.getModelName())) {
                    existing.setActive(false);
                    existing.setUpdatedAt(Instant.now());
                    try {
                        modelConfigRepository.save(existing);
                        deactivated++;
                        log.info("  Deactivated: {} (no longer on proxy)", existing.getModelKey());
                    } catch (Exception e) {
                        log.debug(
                                "Version conflict while deactivating {}: {}",
                                existing.getModelKey(),
                                e.getMessage());
                    }
                }
            }

            log.info(
                    "Sync summary: {} served models, {} created, {} already configured, {}"
                            + " deactivated",
                    availableModels.size(),
                    created,
                    availableModels.size() - created,
                    deactivated);
        } catch (Exception e) {
            // If sync fails completely, log and continue with existing config
            log.warn("Model sync failed: {}", e.getMessage());
            log.debug("Full sync error", e);
        }
    }
}
