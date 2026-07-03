package io.emcip.llm.orchestrator.client;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client for any OpenAI-compatible LLM API (e.g. LiteLLM proxy). Calls POST
 * /v1/chat/completions with system + user messages.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OpenAiCompatibleLlmClient {

    private final LlmProviderConfigService providerConfigService;
    private final ObjectMapper objectMapper;

    /**
     * Call the OpenAI-compatible chat completions endpoint.
     *
     * @param model Model name as configured in LiteLLM (e.g. "qwen3-30b-a3b")
     * @param systemPrompt System instructions
     * @param userContent User message
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0–2.0)
     * @return LlmResponse with content and token counts
     */
    public LlmResponse call(
            String model,
            String systemPrompt,
            String userContent,
            int maxTokens,
            double temperature) {

        LlmProviderConfig provider =
                providerConfigService
                        .getActiveProvider()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No active LLM provider configured — set one via"
                                                        + " Admin UI > AI Config > LLM Provider"));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)));

        log.debug(
                "Calling LiteLLM: url={}, model={}, maxTokens={}",
                provider.getBaseUrl(),
                model,
                maxTokens);

        try {
            String apiKey = provider.getApiKey();
            RestClient restClient = RestClient.create();
            String responseJson =
                    restClient
                            .post()
                            .uri(provider.getBaseUrl() + "/v1/chat/completions")
                            .headers(
                                    h -> {
                                        h.setContentType(MediaType.APPLICATION_JSON);
                                        if (apiKey != null && !apiKey.isBlank()) {
                                            h.setBearerAuth(apiKey);
                                        }
                                    })
                            .body(body)
                            .retrieve()
                            .body(String.class);

            JsonNode root = objectMapper.readTree(responseJson);
            String content = root.path("choices").get(0).path("message").path("content").asText();
            int inputTokens = root.path("usage").path("prompt_tokens").asInt();
            int outputTokens = root.path("usage").path("completion_tokens").asInt();
            String modelUsed = root.path("model").asText(model);

            log.debug(
                    "LiteLLM response: model={}, input_tokens={}, output_tokens={}",
                    modelUsed,
                    inputTokens,
                    outputTokens);

            return new LlmResponse(content, inputTokens, outputTokens, modelUsed);

        } catch (Exception e) {
            throw new RuntimeException(
                    "LiteLLM API call failed [" + provider.getBaseUrl() + "]: " + e.getMessage(),
                    e);
        }
    }

    /**
     * Call the OpenAI-compatible chat completions endpoint with a pre-built messages array.
     * Supports multi-turn conversations.
     */
    public LlmResponse chat(
            String model, List<Map<String, String>> messages, int maxTokens, double temperature) {

        LlmProviderConfig provider =
                providerConfigService
                        .getActiveProvider()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No active LLM provider configured — set one via"
                                                        + " Admin UI > AI Config > LLM Provider"));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("messages", messages);

        log.debug(
                "Calling LiteLLM chat: url={}, model={}, turns={}, maxTokens={}",
                provider.getBaseUrl(),
                model,
                messages.size(),
                maxTokens);

        try {
            String apiKey = provider.getApiKey();
            RestClient restClient = RestClient.create();
            String responseJson =
                    restClient
                            .post()
                            .uri(provider.getBaseUrl() + "/v1/chat/completions")
                            .headers(
                                    h -> {
                                        h.setContentType(MediaType.APPLICATION_JSON);
                                        if (apiKey != null && !apiKey.isBlank()) {
                                            h.setBearerAuth(apiKey);
                                        }
                                    })
                            .body(body)
                            .retrieve()
                            .body(String.class);

            JsonNode root = objectMapper.readTree(responseJson);
            String content = root.path("choices").get(0).path("message").path("content").asText();
            int inputTokens = root.path("usage").path("prompt_tokens").asInt();
            int outputTokens = root.path("usage").path("completion_tokens").asInt();
            String modelUsed = root.path("model").asText(model);

            log.debug(
                    "LiteLLM chat response: model={}, input_tokens={}, output_tokens={}",
                    modelUsed,
                    inputTokens,
                    outputTokens);

            return new LlmResponse(content, inputTokens, outputTokens, modelUsed);

        } catch (Exception e) {
            throw new RuntimeException(
                    "LiteLLM API call failed [" + provider.getBaseUrl() + "]: " + e.getMessage(),
                    e);
        }
    }

    /**
     * Call the OpenAI-compatible embeddings endpoint.
     *
     * @param model Model name as configured in LiteLLM (e.g. "bge-m3")
     * @param input Text to embed
     * @return float array of the embedding vector
     */
    public float[] embed(String model, String input) {
        LlmProviderConfig provider =
                providerConfigService
                        .getActiveProvider()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No active LLM provider configured — set one via"
                                                        + " Admin UI > AI Config > LLM Provider"));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", input);

        log.debug("Calling LiteLLM embeddings: url={}, model={}", provider.getBaseUrl(), model);

        try {
            String apiKey = provider.getApiKey();
            RestClient restClient = RestClient.create();
            String responseJson =
                    restClient
                            .post()
                            .uri(provider.getBaseUrl() + "/v1/embeddings")
                            .headers(
                                    h -> {
                                        h.setContentType(MediaType.APPLICATION_JSON);
                                        if (apiKey != null && !apiKey.isBlank()) {
                                            h.setBearerAuth(apiKey);
                                        }
                                    })
                            .body(body)
                            .retrieve()
                            .body(String.class);

            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode embeddingArray = root.path("data").get(0).path("embedding");

            List<Float> values = new ArrayList<>();
            for (JsonNode val : embeddingArray) {
                values.add((float) val.asDouble());
            }

            float[] result = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                result[i] = values.get(i);
            }

            log.debug(
                    "LiteLLM embeddings response: model={}, dimensions={}",
                    root.path("model").asText(model),
                    result.length);

            return result;

        } catch (Exception e) {
            throw new RuntimeException(
                    "LiteLLM embeddings call failed ["
                            + provider.getBaseUrl()
                            + "]: "
                            + e.getMessage(),
                    e);
        }
    }
}
