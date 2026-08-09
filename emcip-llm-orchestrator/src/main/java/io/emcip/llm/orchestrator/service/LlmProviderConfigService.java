package io.emcip.llm.orchestrator.service;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Manages LLM provider configuration and connectivity checks. */
@Service
@Slf4j
@RequiredArgsConstructor
public class LlmProviderConfigService {

    private final LlmProviderConfigRepository repository;
    private final ObjectMapper objectMapper;

    public Optional<LlmProviderConfig> getActiveProvider() {
        return repository.findFirstByActiveTrueOrderByUpdatedAtDesc();
    }

    /**
     * Persists a config, clearing the active flag on the others when this one is active.
     *
     * <p>Deactivation is a bulk update rather than a load-modify-save loop. Loading the other rows
     * would decrypt their keys, so a single config whose key predates secrets encryption would make
     * saving <em>any</em> provider fail - including the save that replaces that very key.
     */
    @Transactional
    public LlmProviderConfig saveProvider(LlmProviderConfig config) {
        if (Boolean.TRUE.equals(config.getActive())) {
            repository.deactivateAllExcept(config.getId(), Instant.now());
        }
        return repository.save(config);
    }

    /**
     * Applies an edit to an existing config without ever loading its stored key.
     *
     * <p>The obvious implementation - load the entity, mutate it, save - cannot work here: the load
     * decrypts {@code api_key}, so editing a config whose key predates secrets encryption fails on
     * the key the edit is trying to replace. Writing through explicit queries keeps the repair path
     * open. The converter still applies to the bound key, so the replacement is stored encrypted.
     *
     * @param newApiKey replacement key, or null to leave the stored key untouched
     * @return the updated config as a projection, or empty if no config has that id
     */
    @Transactional
    public Optional<LlmProviderConfigRepository.Summary> updateProvider(
            UUID id, String name, String baseUrl, Boolean active, String newApiKey) {
        if (repository.findSummaryById(id).isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        repository.updateDetails(id, name, baseUrl, active, now);
        if (newApiKey != null) {
            repository.updateApiKey(id, newApiKey, now);
        }
        if (Boolean.TRUE.equals(active)) {
            repository.deactivateAllExcept(id, now);
        }
        return repository.findSummaryById(id);
    }

    /**
     * Calls GET {baseUrl}/v1/models on the given URL and returns the list of model IDs. Returns
     * empty list if the endpoint is unreachable.
     */
    public List<String> fetchAvailableModels(String baseUrl, String apiKey) {
        try {
            RestClient restClient = RestClient.create();
            String responseJson =
                    restClient
                            .get()
                            .uri(baseUrl + "/v1/models")
                            .headers(
                                    h -> {
                                        if (apiKey != null && !apiKey.isBlank()) {
                                            h.setBearerAuth(apiKey);
                                        }
                                    })
                            .retrieve()
                            .body(String.class);
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode data = root.path("data");
            return data.isArray() ? data.findValuesAsString("id") : List.of();
        } catch (Exception e) {
            log.warn("Failed to fetch models from {}: {}", baseUrl, e.getMessage());
            return List.of();
        }
    }
}
