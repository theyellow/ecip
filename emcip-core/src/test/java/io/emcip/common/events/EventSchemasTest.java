package io.emcip.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventSchemasTest {

    @Test
    void schemaVersionConstantsAreDefined() {
        assertThat(EventSchemas.TELEGRAM_MESSAGE_V1).isEqualTo("1.0.0");
        assertThat(EventSchemas.INTENT_CLASSIFIED_V1).isEqualTo("1.0.0");
        assertThat(EventSchemas.POLICY_DECISION_V1).isEqualTo("1.0.0");
        assertThat(EventSchemas.RESPONSE_GENERATED_V1).isEqualTo("1.0.0");
        assertThat(EventSchemas.MODERATION_FLAG_V1).isEqualTo("1.0.0");
        assertThat(EventSchemas.AUDIT_EVENT_V1).isEqualTo("1.0.0");
    }

    @Test
    void telegramMessageEvent_defaultsSchemaVersionAndEventType() {
        var event =
                new EventSchemas.TelegramMessageEvent(
                        "id", "ts", null, null, 1L, 2L, "s", "USER", "hello", 0, null, false, null,
                        null, null, null, null, null, null);
        assertThat(event.schemaVersion()).isEqualTo(EventSchemas.TELEGRAM_MESSAGE_V1);
        assertThat(event.eventType()).isEqualTo("TelegramMessage");
    }

    @Test
    void telegramMessageEvent_preservesProvidedSchemaVersionAndEventType() {
        var event =
                new EventSchemas.TelegramMessageEvent(
                        "id", "ts", "2.0.0", "Custom", 1L, 2L, "s", "USER", "hello", 0, null, false,
                        null, null, null, null, null, null, null);
        assertThat(event.schemaVersion()).isEqualTo("2.0.0");
        assertThat(event.eventType()).isEqualTo("Custom");
    }

    @Test
    void intentClassifiedEvent_defaultsSchemaVersionAndEventType() {
        var event =
                new EventSchemas.IntentClassifiedEvent(
                        "id", "ts", null, null, "src", "SPAM", 0.9, null, null);
        assertThat(event.schemaVersion()).isEqualTo(EventSchemas.INTENT_CLASSIFIED_V1);
        assertThat(event.eventType()).isEqualTo("IntentClassified");
    }

    @Test
    void policyDecisionEvent_defaultsSchemaVersionAndEventType() {
        var event =
                new EventSchemas.PolicyDecisionEvent(
                        "id", "ts", null, null, "src", "p1", "ALLOW", "ok", null, null, null);
        assertThat(event.schemaVersion()).isEqualTo(EventSchemas.POLICY_DECISION_V1);
        assertThat(event.eventType()).isEqualTo("PolicyDecision");
    }

    @Test
    void responseGeneratedEvent_defaultsSchemaVersionAndEventType() {
        var event =
                new EventSchemas.ResponseGeneratedEvent(
                        "id", "ts", null, null, "src", "text", "claude", 100, null);
        assertThat(event.schemaVersion()).isEqualTo(EventSchemas.RESPONSE_GENERATED_V1);
        assertThat(event.eventType()).isEqualTo("ResponseGenerated");
    }

    @Test
    void moderationFlagEvent_defaultsSchemaVersionAndEventType() {
        var event =
                new EventSchemas.ModerationFlagEvent(
                        "id", "ts", null, null, "src", "SPAM", "HIGH", "reason", null);
        assertThat(event.schemaVersion()).isEqualTo(EventSchemas.MODERATION_FLAG_V1);
        assertThat(event.eventType()).isEqualTo("ModerationFlag");
    }

    @Test
    void auditEvent_defaultsSchemaVersionAndEventType() {
        var event =
                new EventSchemas.AuditEvent(
                        "id", "ts", null, null, "src", "CREATE", "user1", "TENANT", "r1", null,
                        "SUCCESS");
        assertThat(event.schemaVersion()).isEqualTo(EventSchemas.AUDIT_EVENT_V1);
        assertThat(event.eventType()).isEqualTo("Audit");
    }

    @Test
    void telegramMessageEvent_implementsEventInterface() {
        EventSchemas.Event event =
                new EventSchemas.TelegramMessageEvent(
                        "event-1",
                        "2026-01-01T00:00:00Z",
                        "1.0.0",
                        "TelegramMessage",
                        1L,
                        2L,
                        "s",
                        "USER",
                        "hello",
                        0,
                        null,
                        false,
                        null,
                        null,
                        Map.of(),
                        "2026-01-01T00:00:00Z",
                        null,
                        null,
                        null);
        assertThat(event.eventId()).isEqualTo("event-1");
        assertThat(event.timestamp()).isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    void intentClassifiedEvent_storesAllFields() {
        var event =
                new EventSchemas.IntentClassifiedEvent(
                        "id",
                        "ts",
                        "1.0.0",
                        "IntentClassified",
                        "src",
                        "SPAM",
                        0.95,
                        Map.of("k", "v"),
                        List.of("rule1"));
        assertThat(event.intent()).isEqualTo("SPAM");
        assertThat(event.confidence()).isEqualTo(0.95);
        assertThat(event.matchedRules()).containsExactly("rule1");
    }
}
