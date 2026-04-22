package io.emcip.llm.orchestrator.client;

/** Result of an LLM call, including success status and tracking information. */
public record LlmCallResult(boolean success, String content, String modelUsed, String requestId) {}
