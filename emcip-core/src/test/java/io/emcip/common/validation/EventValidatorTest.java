package io.emcip.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class EventValidatorTest {

    private EventValidator validator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        validator = new EventValidator(objectMapper);
    }

    private ObjectNode validBase() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventId", "550e8400-e29b-41d4-a716-446655440000");
        node.put("timestamp", "2026-01-01T00:00:00Z");
        node.put("schemaVersion", "1.0.0");
        node.put("eventType", "TelegramMessage");
        return node;
    }

    // --- validateTelegramMessage ---

    @Test
    void validateTelegramMessage_valid() {
        ObjectNode node = validBase();
        node.put("telegramMessageId", 123L);
        node.put("chatId", 456L);
        node.put("senderId", "user1");
        node.put("text", "hello");
        node.put("date", 1700000000);

        var result = validator.validateTelegramMessage(node);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validateTelegramMessage_missingRequiredFields_returnsErrors() {
        ObjectNode node = objectMapper.createObjectNode();

        var result = validator.validateTelegramMessage(node);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.getErrorMessage()).contains("eventId");
    }

    @Test
    void validateTelegramMessage_invalidUuid_returnsError() {
        ObjectNode node = validBase();
        node.put("eventId", "not-a-uuid");
        node.put("telegramMessageId", 1L);
        node.put("chatId", 1L);
        node.put("senderId", "s");
        node.put("text", "t");
        node.put("date", 0);

        var result = validator.validateTelegramMessage(node);

        assertThat(result.valid()).isFalse();
        assertThat(result.getErrorMessage()).contains("UUID");
    }

    @Test
    void validateTelegramMessage_invalidTimestamp_returnsError() {
        ObjectNode node = validBase();
        node.put("timestamp", "not-a-timestamp");
        node.put("telegramMessageId", 1L);
        node.put("chatId", 1L);
        node.put("senderId", "s");
        node.put("text", "t");
        node.put("date", 0);

        var result = validator.validateTelegramMessage(node);

        assertThat(result.valid()).isFalse();
        assertThat(result.getErrorMessage()).contains("ISO-8601");
    }

    // --- validateIntentClassified ---

    @Test
    void validateIntentClassified_valid() {
        ObjectNode node = validBase();
        node.put("eventType", "IntentClassified");
        node.put("sourceEventId", "src-1");
        node.put("intent", "SPAM");
        node.put("confidence", 0.85);
        node.put("schemaVersion", "1.0.0");

        var result = validator.validateIntentClassified(node);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void validateIntentClassified_confidenceTooHigh_returnsError() {
        ObjectNode node = validBase();
        node.put("eventType", "IntentClassified");
        node.put("sourceEventId", "src-1");
        node.put("intent", "SPAM");
        node.put("confidence", 1.5);

        var result = validator.validateIntentClassified(node);

        assertThat(result.valid()).isFalse();
        assertThat(result.getErrorMessage()).contains("confidence");
    }

    @Test
    void validateIntentClassified_confidenceNegative_returnsError() {
        ObjectNode node = validBase();
        node.put("eventType", "IntentClassified");
        node.put("sourceEventId", "src-1");
        node.put("intent", "SPAM");
        node.put("confidence", -0.1);

        var result = validator.validateIntentClassified(node);

        assertThat(result.valid()).isFalse();
        assertThat(result.getErrorMessage()).contains("confidence");
    }

    @Test
    void validateIntentClassified_missingConfidence_returnsError() {
        ObjectNode node = validBase();
        node.put("eventType", "IntentClassified");
        node.put("sourceEventId", "src-1");
        node.put("intent", "SPAM");

        var result = validator.validateIntentClassified(node);

        assertThat(result.valid()).isFalse();
    }

    // --- validatePolicyDecision ---

    @Test
    void validatePolicyDecision_valid() {
        ObjectNode node = validBase();
        node.put("eventType", "PolicyDecision");
        node.put("sourceEventId", "src-1");
        node.put("policyId", "policy-1");
        node.put("decision", "ALLOW");
        node.put("schemaVersion", "1.0.0");

        var result = validator.validatePolicyDecision(node);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void validatePolicyDecision_missingPolicyId_returnsError() {
        ObjectNode node = validBase();
        node.put("eventType", "PolicyDecision");
        node.put("sourceEventId", "src-1");
        node.put("decision", "ALLOW");

        var result = validator.validatePolicyDecision(node);

        assertThat(result.valid()).isFalse();
        assertThat(result.getErrorMessage()).contains("policyId");
    }

    // --- validateEvent dispatch ---

    @Test
    void validateEvent_dispatchesToTelegramMessage() {
        ObjectNode node = validBase();
        node.put("telegramMessageId", 1L);
        node.put("chatId", 1L);
        node.put("senderId", "s");
        node.put("text", "t");
        node.put("date", 0);

        var result = validator.validateEvent(node, "TelegramMessage");

        assertThat(result.valid()).isTrue();
    }

    @Test
    void validateEvent_unknownType_validatesBaseFieldsOnly() {
        ObjectNode node = validBase();

        var result = validator.validateEvent(node, "UnknownType");

        assertThat(result.valid()).isTrue();
    }

    @Test
    void validateEvent_unknownType_missingBase_returnsErrors() {
        ObjectNode node = objectMapper.createObjectNode();

        var result = validator.validateEvent(node, "UnknownType");

        assertThat(result.valid()).isFalse();
    }

    // --- validateJson ---

    @Test
    void validateJson_validJson_delegates() {
        String json =
                """
                {
                  "eventId": "550e8400-e29b-41d4-a716-446655440000",
                  "timestamp": "2026-01-01T00:00:00Z",
                  "schemaVersion": "1.0.0",
                  "eventType": "PolicyDecision",
                  "sourceEventId": "src",
                  "policyId": "p1",
                  "decision": "ALLOW"
                }
                """;

        var result = validator.validateJson(json, "PolicyDecision");

        assertThat(result.valid()).isTrue();
    }

    @Test
    void validateJson_invalidJson_returnsError() {
        var result = validator.validateJson("{not json}", "TelegramMessage");

        assertThat(result.valid()).isFalse();
        assertThat(result.getErrorMessage()).contains("Invalid JSON");
    }

    // --- ValidationResult helpers ---

    @Test
    void validationResult_hasErrors_trueWhenErrorsPresent() {
        var result = new EventValidator.ValidationResult(false, java.util.List.of("err"));
        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void validationResult_hasErrors_falseWhenNoErrors() {
        var result = new EventValidator.ValidationResult(true, java.util.List.of());
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void validationResult_getErrorMessage_joinsWithSemicolon() {
        var result = new EventValidator.ValidationResult(false, java.util.List.of("err1", "err2"));
        assertThat(result.getErrorMessage()).isEqualTo("err1; err2");
    }

    // --- schema version compatibility ---

    @Test
    void validateTelegramMessage_incompatibleMajorVersion_returnsError() {
        ObjectNode node = validBase();
        node.put("schemaVersion", "2.0.0");
        node.put("telegramMessageId", 1L);
        node.put("chatId", 1L);
        node.put("senderId", "s");
        node.put("text", "t");
        node.put("date", 0);

        var result = validator.validateTelegramMessage(node);

        assertThat(result.valid()).isFalse();
        assertThat(result.getErrorMessage()).contains("Incompatible schema version");
    }
}
