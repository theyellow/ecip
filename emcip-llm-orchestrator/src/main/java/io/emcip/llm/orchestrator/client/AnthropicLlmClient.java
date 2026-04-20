package io.emcip.llm.orchestrator.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the Anthropic Messages API. Implements US-3.2.2: external LLM integration.
 *
 * <p>API docs: https://docs.anthropic.com/en/api/messages
 */
@Service
@Slf4j
public class AnthropicLlmClient {

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Value("${anthropic.api-key:}")
    private String apiKey;

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public AnthropicLlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    /**
     * Call the Anthropic Messages API.
     *
     * @param model Anthropic model ID (e.g. "claude-haiku-4-5-20251001")
     * @param systemPrompt System prompt for the model
     * @param userContent User message content
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0-1.0)
     * @return LlmResponse with generated content and token counts
     */
    public LlmResponse call(
            String model,
            String systemPrompt,
            String userContent,
            int maxTokens,
            double temperature) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Anthropic API key not configured - set ANTHROPIC_API_KEY environment"
                            + " variable");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("system", systemPrompt);
        body.put("messages", List.of(Map.of("role", "user", "content", userContent)));

        log.debug("Calling Anthropic API: model={}, maxTokens={}", model, maxTokens);

        try {
            String responseJson =
                    restClient
                            .post()
                            .uri(ANTHROPIC_API_URL)
                            .header("x-api-key", apiKey)
                            .header("anthropic-version", ANTHROPIC_VERSION)
                            .header("Content-Type", "application/json")
                            .body(body)
                            .retrieve()
                            .body(String.class);

            JsonNode root = objectMapper.readTree(responseJson);
            String content = root.path("content").get(0).path("text").asText();
            int inputTokens = root.path("usage").path("input_tokens").asInt();
            int outputTokens = root.path("usage").path("output_tokens").asInt();
            String modelUsed = root.path("model").asText(model);

            log.debug(
                    "Anthropic response: model={}, input_tokens={}, output_tokens={}",
                    modelUsed,
                    inputTokens,
                    outputTokens);

            return new LlmResponse(content, inputTokens, outputTokens, modelUsed);

        } catch (Exception e) {
            throw new RuntimeException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }
}
