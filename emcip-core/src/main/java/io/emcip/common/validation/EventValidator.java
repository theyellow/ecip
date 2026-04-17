package io.emcip.common.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.emcip.common.events.EventSchemas;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates EMCIP events against their schemas. Performs structural validation and required field
 * checks.
 */
@Component
public class EventValidator {

    private static final Logger log = LoggerFactory.getLogger(EventValidator.class);
    private final ObjectMapper objectMapper;

    public EventValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Validates a Telegram message event. */
    public ValidationResult validateTelegramMessage(JsonNode event) {
        List<String> errors = new ArrayList<>();

        // Required base fields
        validateBaseFields(event, errors);

        // Required Telegram-specific fields
        requireField(event, "telegramMessageId", errors);
        requireField(event, "chatId", errors);
        requireField(event, "senderId", errors);
        requireField(event, "text", errors);
        requireField(event, "date", errors);

        // Validate schema version
        validateSchemaVersion(event, EventSchemas.TELEGRAM_MESSAGE_V1, errors);

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /** Validates an intent classified event. */
    public ValidationResult validateIntentClassified(JsonNode event) {
        List<String> errors = new ArrayList<>();

        validateBaseFields(event, errors);
        requireField(event, "sourceEventId", errors);
        requireField(event, "intent", errors);
        requireField(event, "confidence", errors);

        // Validate confidence is between 0 and 1
        if (event.has("confidence")) {
            double confidence = event.get("confidence").asDouble();
            if (confidence < 0 || confidence > 1) {
                errors.add("confidence must be between 0 and 1");
            }
        }

        validateSchemaVersion(event, EventSchemas.INTENT_CLASSIFIED_V1, errors);

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /** Validates a policy decision event. */
    public ValidationResult validatePolicyDecision(JsonNode event) {
        List<String> errors = new ArrayList<>();

        validateBaseFields(event, errors);
        requireField(event, "sourceEventId", errors);
        requireField(event, "policyId", errors);
        requireField(event, "decision", errors);

        validateSchemaVersion(event, EventSchemas.POLICY_DECISION_V1, errors);

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /** Generic validation for any event type. */
    public ValidationResult validateEvent(JsonNode event, String expectedType) {
        return switch (expectedType) {
            case "TelegramMessage" -> validateTelegramMessage(event);
            case "IntentClassified" -> validateIntentClassified(event);
            case "PolicyDecision" -> validatePolicyDecision(event);
            default -> {
                List<String> errors = new ArrayList<>();
                validateBaseFields(event, errors);
                yield new ValidationResult(errors.isEmpty(), errors);
            }
        };
    }

    /** Validates a serialized JSON string. */
    public ValidationResult validateJson(String json, String expectedType) {
        try {
            JsonNode event = objectMapper.readTree(json);
            return validateEvent(event, expectedType);
        } catch (Exception e) {
            return new ValidationResult(false, List.of("Invalid JSON: " + e.getMessage()));
        }
    }

    private void validateBaseFields(JsonNode event, List<String> errors) {
        requireField(event, "eventId", errors);
        requireField(event, "timestamp", errors);
        requireField(event, "schemaVersion", errors);
        requireField(event, "eventType", errors);

        // Validate eventId is a valid UUID format (basic check)
        if (event.has("eventId")) {
            String eventId = event.get("eventId").asText();
            if (!eventId.matches(
                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
                errors.add("eventId must be a valid UUID format");
            }
        }

        // Validate timestamp is ISO-8601 format (basic check)
        if (event.has("timestamp")) {
            String timestamp = event.get("timestamp").asText();
            if (!timestamp.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*")) {
                errors.add("timestamp must be in ISO-8601 format");
            }
        }
    }

    private void requireField(JsonNode event, String field, List<String> errors) {
        if (!event.has(field) || event.get(field).isNull()) {
            errors.add("Required field missing: " + field);
        }
    }

    private void validateSchemaVersion(
            JsonNode event, String expectedVersion, List<String> errors) {
        if (event.has("schemaVersion")) {
            String version = event.get("schemaVersion").asText();
            if (!isCompatibleVersion(version, expectedVersion)) {
                errors.add(
                        "Incompatible schema version: "
                                + version
                                + " (expected: "
                                + expectedVersion
                                + ")");
            }
        }
    }

    /**
     * Checks if event version is compatible with expected version. Major version must match,
     * minor/patch can be less than or equal.
     */
    private boolean isCompatibleVersion(String eventVersion, String expectedVersion) {
        try {
            String[] eventParts = eventVersion.split("\\.");
            String[] expectedParts = expectedVersion.split("\\.");

            // Major version must match
            if (!eventParts[0].equals(expectedParts[0])) {
                return false;
            }

            // Event minor version should be <= expected minor version
            int eventMinor = Integer.parseInt(eventParts[1]);
            int expectedMinor = Integer.parseInt(expectedParts[1]);

            return eventMinor <= expectedMinor;
        } catch (Exception e) {
            return false;
        }
    }

    /** Validation result containing status and error messages. */
    public record ValidationResult(boolean valid, List<String> errors) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public String getErrorMessage() {
            return String.join("; ", errors);
        }
    }
}
