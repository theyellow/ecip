package io.emcip.llm.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        // Try database config first (preferred - consistent with existing approach)
        var dbConfig = providerConfigRepository.findByName("local-litellm");

        String proxyUrl;
        String apiKey;

        if (dbConfig.isPresent()) {
            proxyUrl = dbConfig.get().getBaseUrl();
            apiKey = dbConfig.get().getApiKey();

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

            syncModelConfigs(availableModels);

            log.info("✓ Model sync complete: {} models available on proxy", availableModels.size());
        } catch (Exception e) {
            log.warn("Could not sync models from LiteLLM proxy: {}", e.getMessage());
            log.debug("Sync error details", e);
            log.warn("   Starting with existing database configuration");
        }
    }

    private List<String> fetchAvailableModels(String endpoint, String apiKey) {
        RestTemplate restTemplate = new RestTemplate();

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

    private void syncModelConfigs(List<String> availableModels) {
        // Get existing models from database
        List<ModelConfig> existingModels = modelConfigRepository.findAll();
        Map<String, ModelConfig> existingByKey =
                existingModels.stream().collect(Collectors.toMap(ModelConfig::getModelKey, m -> m));

        Set<String> availableSet = availableModels.stream().collect(Collectors.toSet());

        int created = 0;
        int updated = 0;
        int unchanged = 0;

        for (String modelName : availableModels) {
            // Use the exact model name from proxy as the key
            String modelKey = modelName;

            ModelConfig existing = existingByKey.get(modelKey);

            if (existing == null) {
                // Create new model config
                ModelConfig newModel = new ModelConfig();
                newModel.setId(UUID.randomUUID());
                newModel.setModelKey(modelKey);
                newModel.setProvider("litellm");
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

                modelConfigRepository.save(newModel);
                created++;
                log.info("  Created: {} ({})", modelKey, modelName);
            } else {
                // Update existing if modelName changed
                if (!existing.getModelName().equals(modelName)) {
                    existing.setModelName(modelName);
                    existing.setUpdatedAt(Instant.now());
                    modelConfigRepository.save(existing);
                    updated++;
                    log.info("  Updated: {} -> {}", modelKey, modelName);
                } else {
                    unchanged++;
                }
            }
        }

        // Deactivate models no longer available on proxy
        for (ModelConfig existing : existingModels) {
            if (!availableSet.contains(existing.getModelName())) {
                existing.setActive(false);
                existing.setUpdatedAt(Instant.now());
                modelConfigRepository.save(existing);
                log.info("  Deactivated: {} (no longer on proxy)", existing.getModelKey());
            }
        }

        log.info(
                "Sync summary: {} created, {} updated, {} unchanged, {} deactivated",
                created,
                updated,
                unchanged,
                existingModels.size() - unchanged - updated);
    }
}
