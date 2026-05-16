package io.emcip.llm.orchestrator.service;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import java.util.List;
import java.util.Optional;
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

    @Transactional
    public LlmProviderConfig saveProvider(LlmProviderConfig config) {
        if (Boolean.TRUE.equals(config.getActive())) {
            List<LlmProviderConfig> all = repository.findAll();
            for (LlmProviderConfig existing : all) {
                if (Boolean.TRUE.equals(existing.getActive())) {
                    existing.setActive(false);
                    repository.save(existing);
                }
            }
        }
        return repository.save(config);
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
