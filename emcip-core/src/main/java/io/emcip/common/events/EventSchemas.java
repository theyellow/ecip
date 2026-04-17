package io.emcip.common.events;

/**
 * Event schema definitions and versioning for EMCIP event backbone. All events follow semantic
 * versioning (MAJOR.MINOR.PATCH).
 *
 * <p>Version rules: - MAJOR: Breaking changes (incompatible schema changes) - MINOR: New fields
 * added (backward compatible) - PATCH: Bug fixes, documentation updates
 */
public final class EventSchemas {

    private EventSchemas() {}

    // Schema versions
    public static final String TELEGRAM_MESSAGE_V1 = "1.0.0";
    public static final String INTENT_CLASSIFIED_V1 = "1.0.0";
    public static final String POLICY_DECISION_V1 = "1.0.0";
    public static final String RESPONSE_GENERATED_V1 = "1.0.0";
    public static final String MODERATION_FLAG_V1 = "1.0.0";
    public static final String AUDIT_EVENT_V1 = "1.0.0";

    /** Base interface for all EMCIP events. */
    public interface Event {
        /** Unique event identifier (UUID). */
        String eventId();

        /** Event timestamp in ISO-8601 format. */
        String timestamp();

        /** Schema version of this event. */
        String schemaVersion();

        /** Event type identifier. */
        String eventType();
    }

    /** Telegram message received from TDLib. */
    public record TelegramMessageEvent(
            String eventId,
            String timestamp,
            String schemaVersion,
            String eventType,
            Long telegramMessageId,
            Long chatId,
            String senderId,
            String senderType,
            String text,
            Integer date,
            Integer editDate,
            Boolean isOutgoing,
            Long replyToMessageId,
            Long replyInChatId,
            java.util.Map<String, Object> metadata)
            implements Event {

        public TelegramMessageEvent {
            if (schemaVersion == null) {
                schemaVersion = TELEGRAM_MESSAGE_V1;
            }
            if (eventType == null) {
                eventType = "TelegramMessage";
            }
        }
    }

    /** Intent classification result. */
    public record IntentClassifiedEvent(
            String eventId,
            String timestamp,
            String schemaVersion,
            String eventType,
            String sourceEventId,
            String intent,
            Double confidence,
            java.util.Map<String, Object> parameters,
            java.util.List<String> matchedRules)
            implements Event {

        public IntentClassifiedEvent {
            if (schemaVersion == null) {
                schemaVersion = INTENT_CLASSIFIED_V1;
            }
            if (eventType == null) {
                eventType = "IntentClassified";
            }
        }
    }

    /** Policy engine decision. */
    public record PolicyDecisionEvent(
            String eventId,
            String timestamp,
            String schemaVersion,
            String eventType,
            String sourceEventId,
            String policyId,
            String decision,
            String reason,
            java.util.Map<String, Object> context,
            java.util.List<String> actions)
            implements Event {

        public PolicyDecisionEvent {
            if (schemaVersion == null) {
                schemaVersion = POLICY_DECISION_V1;
            }
            if (eventType == null) {
                eventType = "PolicyDecision";
            }
        }
    }

    /** LLM-generated response. */
    public record ResponseGeneratedEvent(
            String eventId,
            String timestamp,
            String schemaVersion,
            String eventType,
            String sourceEventId,
            String responseText,
            String modelUsed,
            Integer tokenCount,
            java.util.Map<String, Object> metadata)
            implements Event {

        public ResponseGeneratedEvent {
            if (schemaVersion == null) {
                schemaVersion = RESPONSE_GENERATED_V1;
            }
            if (eventType == null) {
                eventType = "ResponseGenerated";
            }
        }
    }

    /** Moderation flag raised. */
    public record ModerationFlagEvent(
            String eventId,
            String timestamp,
            String schemaVersion,
            String eventType,
            String sourceEventId,
            String flagType,
            String severity,
            String reason,
            java.util.Map<String, Object> details)
            implements Event {

        public ModerationFlagEvent {
            if (schemaVersion == null) {
                schemaVersion = MODERATION_FLAG_V1;
            }
            if (eventType == null) {
                eventType = "ModerationFlag";
            }
        }
    }

    /** Audit event for compliance tracking. */
    public record AuditEvent(
            String eventId,
            String timestamp,
            String schemaVersion,
            String eventType,
            String sourceEventId,
            String action,
            String actor,
            String resourceType,
            String resourceId,
            java.util.Map<String, Object> changes,
            String outcome)
            implements Event {

        public AuditEvent {
            if (schemaVersion == null) {
                schemaVersion = AUDIT_EVENT_V1;
            }
            if (eventType == null) {
                eventType = "Audit";
            }
        }
    }
}
