package io.emcip.llm.orchestrator.service;

import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validates LLM responses against security constraints (RT-002/RT-003). Checks maximum length,
 * blocked HTML/script patterns, and system prompt fragment leakage.
 */
@Slf4j
@Component
public class LlmResponseValidator {

    private final int maxLength;

    private static final List<Pattern> BLOCKED_PATTERNS =
            List.of(
                    Pattern.compile("<script[^>]*>", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("</script>", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("<iframe[^>]*>", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("You are a helpful AI", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("You are an AI assistant", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("<<<USER_CONTENT_BEGIN>>>"),
                    Pattern.compile("<<<KNOWLEDGE_SOURCE_BEGIN"));

    public LlmResponseValidator(@Value("${llm.response.max-length:2000}") int maxLength) {
        this.maxLength = maxLength;
    }

    /**
     * Validates the given LLM response string.
     *
     * @param response the raw LLM response content
     * @param expectedFormat optional format hint (currently unused, reserved for future checks)
     * @return a {@link ValidationResult} indicating whether the response is acceptable
     */
    public ValidationResult validate(String response, String expectedFormat) {
        if (response == null) {
            log.warn("LLM response is null");
            return new ValidationResult(false, "null response");
        }
        if (response.length() > maxLength) {
            log.warn(
                    "LLM response length {} exceeds configured max {}",
                    response.length(),
                    maxLength);
            return new ValidationResult(
                    false, "response length " + response.length() + " exceeds max " + maxLength);
        }
        for (Pattern pattern : BLOCKED_PATTERNS) {
            if (pattern.matcher(response).find()) {
                log.warn("LLM response contains blocked pattern: {}", pattern.pattern());
                return new ValidationResult(
                        false, "blocked pattern detected: " + pattern.pattern());
            }
        }
        return new ValidationResult(true, null);
    }

    /** Immutable result of a response validation check. */
    public record ValidationResult(boolean valid, String reason) {}
}
