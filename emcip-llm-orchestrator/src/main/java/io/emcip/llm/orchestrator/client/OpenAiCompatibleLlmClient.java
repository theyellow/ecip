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
     * @param temperature Sampling temperature (0.0–2.0), or null to omit
     * @return LlmResponse with content and token counts
     */
    public LlmResponse call(
            String model,
            String systemPrompt,
            String userContent,
            int maxTokens,
            Double temperature) {

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
        if (temperature != null) {
            body.put("temperature", temperature);
        }
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
            String content = extractContent(root);
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
            String model, List<Map<String, String>> messages, int maxTokens, Double temperature) {

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
        if (temperature != null) {
            body.put("temperature", temperature);
        }
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
            String content = extractContent(root);
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
     * Extract content from the LLM response message. For models with thinking mode enabled (e.g.
     * qwen3), the actual output may be in the {@code reasoning_content} field instead of {@code
     * content}. Falls back to reasoning_content when content is empty, extracting just the JSON
     * portion from the chain-of-thought reasoning.
     */
    private String extractContent(JsonNode root) {
        JsonNode message = root.path("choices").get(0).path("message");
        String content = message.path("content").asText("");
        if (content.isBlank()) {
            String reasoning = message.path("reasoning_content").asText("");
            if (!reasoning.isBlank()) {
                log.debug(
                        "Content field empty, extracting JSON from reasoning_content ({} chars)",
                        reasoning.length());
                return extractJsonFromReasoning(reasoning);
            }
        }
        return content;
    }

    /**
     * Extract the last JSON object from chain-of-thought reasoning text. Thinking models put their
     * step-by-step reasoning followed by the final JSON output in reasoning_content.
     */
    private String extractJsonFromReasoning(String reasoning) {
        // Find the last top-level JSON object by scanning for the last '{' that starts valid JSON
        int lastBrace = reasoning.lastIndexOf('{');
        while (lastBrace >= 0) {
            String candidate = reasoning.substring(lastBrace);
            // Quick check: does it end with '}' (possibly with trailing whitespace)?
            String trimmed = candidate.trim();
            if (trimmed.endsWith("}")) {
                try {
                    // Validate it's parseable JSON
                    objectMapper.readTree(trimmed);
                    return trimmed;
                } catch (Exception ignored) {
                    // Not valid JSON from this position, try earlier
                }
            }
            lastBrace = reasoning.lastIndexOf('{', lastBrace - 1);
        }
        // No valid JSON found — return the full reasoning as fallback
        log.warn("No valid JSON found in reasoning_content, returning raw text");
        return reasoning;
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

    /**
     * Call the OpenAI-compatible embeddings endpoint with a batch of inputs.
     *
     * @param model Model name as configured in LiteLLM (e.g. "bge-m3")
     * @param inputs List of texts to embed
     * @return list of float arrays, one per input, in the same order
     */
    public List<float[]> embedBatch(String model, List<String> inputs) {
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
        body.put("input", inputs);

        log.debug(
                "Calling LiteLLM batch embeddings: url={}, model={}, count={}",
                provider.getBaseUrl(),
                model,
                inputs.size());

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
            JsonNode dataArray = root.path("data");

            List<float[]> results = new ArrayList<>();
            for (JsonNode item : dataArray) {
                JsonNode embeddingArray = item.path("embedding");
                float[] emb = new float[embeddingArray.size()];
                for (int i = 0; i < embeddingArray.size(); i++) {
                    emb[i] = (float) embeddingArray.get(i).asDouble();
                }
                results.add(emb);
            }

            log.debug(
                    "LiteLLM batch embeddings response: model={}, count={}",
                    root.path("model").asText(model),
                    results.size());

            return results;

        } catch (Exception e) {
            throw new RuntimeException(
                    "LiteLLM batch embeddings call failed ["
                            + provider.getBaseUrl()
                            + "]: "
                            + e.getMessage(),
                    e);
        }
    }
}
