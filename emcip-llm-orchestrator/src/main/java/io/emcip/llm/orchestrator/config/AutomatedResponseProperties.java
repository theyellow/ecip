package io.emcip.llm.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Master switch for LLM work triggered automatically by policy decisions (RESPOND auto-responses,
 * ESCALATE summaries, EXECUTE command validation).
 *
 * <p>Off by default: these paths publish generated content that ends up in live Telegram groups,
 * and they had never actually run in any deployment before 2026-09-24 (prompt templates were hidden
 * by the tenant filter - PROMPT-TENANT). Turning it on is a deliberate product decision.
 *
 * @param enabled whether policy decisions may trigger LLM calls
 */
@ConfigurationProperties("emcip.llm.automated-responses")
public record AutomatedResponseProperties(boolean enabled) {}
