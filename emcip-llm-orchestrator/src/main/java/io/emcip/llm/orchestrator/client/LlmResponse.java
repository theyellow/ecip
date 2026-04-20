package io.emcip.llm.orchestrator.client;

/** Response from an LLM provider call. */
public record LlmResponse(String content, int inputTokens, int outputTokens, String model) {}
