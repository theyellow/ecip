package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.client.AnthropicLlmClient;
import io.emcip.llm.orchestrator.client.LlmCallResult;
import io.emcip.llm.orchestrator.client.LlmResponse;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.entity.PromptTemplate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for LlmCallService. Tests model routing, template rendering, LLM calls, and cost
 * tracking integration.
 */
@ExtendWith(MockitoExtension.class)
class LlmCallServiceTest {

    @Mock private LlmOrchestratorService orchestratorService;

    @Mock private AnthropicLlmClient anthropicClient;

    @Mock private CostTrackingService costTrackingService;

    @InjectMocks private LlmCallService service;

    // Test data builders

    private ModelConfig createTestModelConfig() {
        return ModelConfig.builder()
                .id(UUID.randomUUID())
                .modelKey("claude-haiku")
                .provider("anthropic")
                .modelName("claude-haiku-4-5-20251001")
                .description("Fast model for low-latency tasks")
                .taskType("response")
                .inputCostPer1kTokens(0.80)
                .outputCostPer1kTokens(4.00)
                .contextWindow(200000)
                .maxOutputTokens(4096)
                .avgLatencyMs(500.0)
                .supportsStreaming(false)
                .active(true)
                .priority(1)
                .build();
    }

    private PromptTemplate createTestTemplate() {
        return PromptTemplate.builder()
                .id(UUID.randomUUID())
                .name("response-template")
                .version("1.0")
                .description("Template for generating responses")
                .modelProvider("anthropic")
                .modelName("claude-haiku-4-5-20251001")
                .systemPrompt("You are a helpful AI assistant.")
                .userPromptTemplate("Please respond to the following: {{content}}")
                .temperature(0.7)
                .maxTokens(2048)
                .active(true)
                .priority(1)
                .build();
    }

    private LlmResponse createTestResponse(String content, int inputTokens, int outputTokens) {
        return new LlmResponse(content, inputTokens, outputTokens, "claude-haiku-4-5-20251001");
    }

    // Tests for callForTask method

    @Test
    void shouldCallForTask_whenModelNotFound_returnsEmpty() {
        // given
        String taskType = "response";
        String templateName = "response-template";
        String userContent = "Hello";
        Map<String, String> contextVars = Map.of();
        String sourceEventId = UUID.randomUUID().toString();

        when(orchestratorService.selectModelForTask(taskType)).thenReturn(Optional.empty());
        when(orchestratorService.getPromptTemplate(templateName))
                .thenReturn(Optional.of(createTestTemplate()));

        // when
        Optional<LlmCallResult> result =
                service.callForTask(
                        taskType, templateName, userContent, contextVars, sourceEventId, null);

        // then
        assertThat(result).isEmpty();
        verify(anthropicClient, never())
                .call(anyString(), anyString(), anyString(), anyInt(), anyDouble());
    }

    @Test
    void shouldCallForTask_whenTemplateNotFound_returnsEmpty() {
        // given
        String taskType = "response";
        String templateName = "response-template";
        String userContent = "Hello";
        Map<String, String> contextVars = Map.of();
        String sourceEventId = UUID.randomUUID().toString();
        ModelConfig modelConfig = createTestModelConfig();

        when(orchestratorService.selectModelForTask(taskType)).thenReturn(Optional.of(modelConfig));
        when(orchestratorService.getPromptTemplate(templateName)).thenReturn(Optional.empty());

        // when
        Optional<LlmCallResult> result =
                service.callForTask(
                        taskType, templateName, userContent, contextVars, sourceEventId, null);

        // then
        assertThat(result).isEmpty();
        verify(anthropicClient, never())
                .call(anyString(), anyString(), anyString(), anyInt(), anyDouble());
    }

    @Test
    void shouldCallForTask_success_returnsCallResult() {
        // given
        String taskType = "response";
        String templateName = "response-template";
        String userContent = "Hello";
        Map<String, String> contextVars = Map.of();
        String sourceEventId = UUID.randomUUID().toString();
        String conversationId = UUID.randomUUID().toString();
        ModelConfig modelConfig = createTestModelConfig();
        PromptTemplate template = createTestTemplate();
        LlmResponse response = createTestResponse("Response content", 10, 50);

        when(orchestratorService.selectModelForTask(taskType)).thenReturn(Optional.of(modelConfig));
        when(orchestratorService.getPromptTemplate(templateName)).thenReturn(Optional.of(template));
        when(orchestratorService.renderPromptTemplate(eq(template), eq(contextVars)))
                .thenReturn("Please respond to the following: {{content}}");
        when(anthropicClient.call(
                        "claude-haiku-4-5-20251001",
                        "You are a helpful AI assistant.",
                        "Please respond to the following: Hello",
                        2048,
                        0.7))
                .thenReturn(response);

        // when
        Optional<LlmCallResult> result =
                service.callForTask(
                        taskType,
                        templateName,
                        userContent,
                        contextVars,
                        sourceEventId,
                        conversationId);

        // then
        assertThat(result)
                .isPresent()
                .hasValueSatisfying(
                        callResult -> {
                            assertThat(callResult.success()).isTrue();
                            assertThat(callResult.content()).isEqualTo("Response content");
                            assertThat(callResult.modelUsed())
                                    .isEqualTo("claude-haiku-4-5-20251001");
                            assertThat(callResult.requestId()).isNotBlank();
                        });

        verify(costTrackingService)
                .logSuccessfulCall(
                        anyString(),
                        eq(modelConfig),
                        eq("response-template"),
                        eq(10),
                        eq(50),
                        anyLong(),
                        eq(sourceEventId),
                        eq(conversationId));
    }

    // Tests for call method

    @Test
    void shouldCall_success_returnsSuccessResult() {
        // given
        ModelConfig modelConfig = createTestModelConfig();
        PromptTemplate template = createTestTemplate();
        String userContent = "Hello";
        Map<String, String> contextVars = Map.of();
        String sourceEventId = UUID.randomUUID().toString();
        String conversationId = UUID.randomUUID().toString();
        LlmResponse response = createTestResponse("Response content", 10, 50);

        when(orchestratorService.renderPromptTemplate(eq(template), eq(contextVars)))
                .thenReturn("Please respond to the following: {{content}}");
        when(anthropicClient.call(
                        "claude-haiku-4-5-20251001",
                        "You are a helpful AI assistant.",
                        "Please respond to the following: Hello",
                        2048,
                        0.7))
                .thenReturn(response);

        // when
        LlmCallResult result =
                service.call(
                        modelConfig,
                        template,
                        userContent,
                        contextVars,
                        sourceEventId,
                        conversationId);

        // then
        assertThat(result)
                .satisfies(
                        callResult -> {
                            assertThat(callResult.success()).isTrue();
                            assertThat(callResult.content()).isEqualTo("Response content");
                            assertThat(callResult.modelUsed())
                                    .isEqualTo("claude-haiku-4-5-20251001");
                            assertThat(callResult.requestId()).isNotBlank();
                        });

        verify(costTrackingService)
                .logSuccessfulCall(
                        anyString(),
                        eq(modelConfig),
                        eq("response-template"),
                        eq(10),
                        eq(50),
                        anyLong(),
                        eq(sourceEventId),
                        eq(conversationId));
    }

    @Test
    void shouldCall_whenClientThrows_returnsFailureResult() {
        // given
        ModelConfig modelConfig = createTestModelConfig();
        PromptTemplate template = createTestTemplate();
        String userContent = "Hello";
        Map<String, String> contextVars = Map.of();
        String sourceEventId = UUID.randomUUID().toString();
        String conversationId = UUID.randomUUID().toString();

        when(orchestratorService.renderPromptTemplate(eq(template), eq(contextVars)))
                .thenReturn("Please respond to the following: {{content}}");
        when(anthropicClient.call(anyString(), anyString(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("API call failed"));

        // when
        LlmCallResult result =
                service.call(
                        modelConfig,
                        template,
                        userContent,
                        contextVars,
                        sourceEventId,
                        conversationId);

        // then
        assertThat(result)
                .satisfies(
                        callResult -> {
                            assertThat(callResult.success()).isFalse();
                            assertThat(callResult.content()).isNull();
                            assertThat(callResult.modelUsed())
                                    .isEqualTo("claude-haiku-4-5-20251001");
                            assertThat(callResult.requestId()).isNotBlank();
                        });

        verify(costTrackingService)
                .logFailedCall(
                        anyString(),
                        eq(modelConfig),
                        eq("response-template"),
                        anyString(),
                        eq(sourceEventId),
                        eq(conversationId));
    }

    @Test
    void shouldCall_rendersTemplateCorrectly_substitutesContent() {
        // given
        ModelConfig modelConfig = createTestModelConfig();
        PromptTemplate template = createTestTemplate();
        String userContent = "What is 2+2?";
        Map<String, String> contextVars = Map.of();
        String sourceEventId = UUID.randomUUID().toString();
        LlmResponse response = createTestResponse("4", 5, 2);

        String renderedTemplate = "Please respond to the following: {{content}}";

        when(orchestratorService.renderPromptTemplate(eq(template), eq(contextVars)))
                .thenReturn(renderedTemplate);
        when(anthropicClient.call(
                        "claude-haiku-4-5-20251001",
                        "You are a helpful AI assistant.",
                        "Please respond to the following: What is 2+2?",
                        2048,
                        0.7))
                .thenReturn(response);

        // when
        LlmCallResult result =
                service.call(modelConfig, template, userContent, contextVars, sourceEventId, null);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("4");

        // Verify the client was called with the correct substituted content
        verify(anthropicClient)
                .call(
                        "claude-haiku-4-5-20251001",
                        "You are a helpful AI assistant.",
                        "Please respond to the following: What is 2+2?",
                        2048,
                        0.7);
    }

    @Test
    void shouldCall_withEmptyTemplateRendering_usesUserContentDirectly() {
        // given
        ModelConfig modelConfig = createTestModelConfig();
        PromptTemplate template = createTestTemplate();
        String userContent = "Hello";
        Map<String, String> contextVars = Map.of();
        String sourceEventId = UUID.randomUUID().toString();
        LlmResponse response = createTestResponse("Hi there", 5, 3);

        // Empty rendered template means use userContent directly
        when(orchestratorService.renderPromptTemplate(eq(template), eq(contextVars)))
                .thenReturn("");
        when(anthropicClient.call(
                        "claude-haiku-4-5-20251001",
                        "You are a helpful AI assistant.",
                        "Hello",
                        2048,
                        0.7))
                .thenReturn(response);

        // when
        LlmCallResult result =
                service.call(modelConfig, template, userContent, contextVars, sourceEventId, null);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("Hi there");

        verify(anthropicClient)
                .call(
                        "claude-haiku-4-5-20251001",
                        "You are a helpful AI assistant.",
                        "Hello",
                        2048,
                        0.7);
    }

    @Test
    void shouldCall_withContextVariables_substitutesAllVariables() {
        // given
        ModelConfig modelConfig = createTestModelConfig();
        PromptTemplate template = createTestTemplate();
        String userContent = "Specific request";
        Map<String, String> contextVars = new HashMap<>();
        contextVars.put("context", "Important context");
        contextVars.put("tone", "formal");
        String sourceEventId = UUID.randomUUID().toString();
        LlmResponse response = createTestResponse("Formal response", 15, 30);

        String renderedTemplate =
                "Context: Important context\nTone: formal\nPlease respond: {{content}}";

        when(orchestratorService.renderPromptTemplate(eq(template), eq(contextVars)))
                .thenReturn(renderedTemplate);
        when(anthropicClient.call(anyString(), anyString(), anyString(), anyInt(), anyDouble()))
                .thenReturn(response);

        // when
        LlmCallResult result =
                service.call(modelConfig, template, userContent, contextVars, sourceEventId, null);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("Formal response");

        verify(anthropicClient)
                .call(
                        "claude-haiku-4-5-20251001",
                        "You are a helpful AI assistant.",
                        "Context: Important context\n"
                                + "Tone: formal\n"
                                + "Please respond: Specific request",
                        2048,
                        0.7);
    }

    @Test
    void shouldCall_logsCostWithCorrectTokenCounts() {
        // given
        ModelConfig modelConfig = createTestModelConfig();
        PromptTemplate template = createTestTemplate();
        String userContent = "Test";
        Map<String, String> contextVars = Map.of();
        String sourceEventId = UUID.randomUUID().toString();
        String conversationId = UUID.randomUUID().toString();
        LlmResponse response = createTestResponse("Output", 100, 200);

        when(orchestratorService.renderPromptTemplate(eq(template), eq(contextVars)))
                .thenReturn("Please respond to the following: {{content}}");
        when(anthropicClient.call(anyString(), anyString(), anyString(), anyInt(), anyDouble()))
                .thenReturn(response);

        // when
        service.call(
                modelConfig, template, userContent, contextVars, sourceEventId, conversationId);

        // then
        verify(costTrackingService)
                .logSuccessfulCall(
                        anyString(),
                        eq(modelConfig),
                        eq("response-template"),
                        eq(100),
                        eq(200),
                        anyLong(),
                        eq(sourceEventId),
                        eq(conversationId));
    }

    @Test
    void shouldCall_withoutConversationId_logsSuccessfully() {
        // given
        ModelConfig modelConfig = createTestModelConfig();
        PromptTemplate template = createTestTemplate();
        String userContent = "Test";
        Map<String, String> contextVars = Map.of();
        String sourceEventId = UUID.randomUUID().toString();
        LlmResponse response = createTestResponse("Output", 50, 75);

        when(orchestratorService.renderPromptTemplate(eq(template), eq(contextVars)))
                .thenReturn("Please respond to the following: {{content}}");
        when(anthropicClient.call(anyString(), anyString(), anyString(), anyInt(), anyDouble()))
                .thenReturn(response);

        // when
        LlmCallResult result =
                service.call(modelConfig, template, userContent, contextVars, sourceEventId, null);

        // then
        assertThat(result.success()).isTrue();

        verify(costTrackingService)
                .logSuccessfulCall(
                        anyString(),
                        eq(modelConfig),
                        eq("response-template"),
                        eq(50),
                        eq(75),
                        anyLong(),
                        eq(sourceEventId),
                        eq(null));
    }

    @Test
    void shouldCallForTask_propagatesExceptionAsFailure() {
        // given
        String taskType = "response";
        String templateName = "response-template";
        String userContent = "Test";
        Map<String, String> contextVars = Map.of();
        String sourceEventId = UUID.randomUUID().toString();
        String conversationId = UUID.randomUUID().toString();
        ModelConfig modelConfig = createTestModelConfig();
        PromptTemplate template = createTestTemplate();

        when(orchestratorService.selectModelForTask(taskType)).thenReturn(Optional.of(modelConfig));
        when(orchestratorService.getPromptTemplate(templateName)).thenReturn(Optional.of(template));
        when(orchestratorService.renderPromptTemplate(eq(template), eq(contextVars)))
                .thenReturn("Please respond to the following: {{content}}");
        when(anthropicClient.call(anyString(), anyString(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("Network error"));

        // when
        Optional<LlmCallResult> result =
                service.callForTask(
                        taskType,
                        templateName,
                        userContent,
                        contextVars,
                        sourceEventId,
                        conversationId);

        // then
        assertThat(result)
                .isPresent()
                .hasValueSatisfying(
                        callResult -> {
                            assertThat(callResult.success()).isFalse();
                            assertThat(callResult.content()).isNull();
                        });

        verify(costTrackingService)
                .logFailedCall(
                        anyString(),
                        eq(modelConfig),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
    }
}
