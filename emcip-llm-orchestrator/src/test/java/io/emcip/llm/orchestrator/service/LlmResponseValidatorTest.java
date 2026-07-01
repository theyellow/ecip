package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmResponseValidatorTest {

    private final LlmResponseValidator validator = new LlmResponseValidator(2000);

    @Test
    void validate_normalResponse_passes() {
        var result = validator.validate("This is a normal response.", null);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void validate_exceedsMaxLength_fails() {
        String longResponse = "x".repeat(2001);
        var result = validator.validate(longResponse, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("length");
    }

    @Test
    void validate_containsSystemPromptFragment_fails() {
        var result = validator.validate("You are a helpful AI assistant. Now do this:", null);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("blocked pattern");
    }

    @Test
    void validate_containsHtmlTags_fails() {
        var result = validator.validate("Here is a <script>alert('xss')</script> response", null);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("blocked pattern");
    }

    @Test
    void validate_nullResponse_fails() {
        var result = validator.validate(null, null);
        assertThat(result.valid()).isFalse();
    }

    @Test
    void validate_emptyResponse_passes() {
        var result = validator.validate("", null);
        assertThat(result.valid()).isTrue();
    }
}
