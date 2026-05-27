package io.emcip.intent.classifier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.common.events.EventSchemas;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class IntentClassificationServiceTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private IntentClassificationService service;

    @BeforeEach
    void setUp() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        service = new IntentClassificationService(kafkaTemplate, new ObjectMapper());
    }

    @Test
    void classify_greeting_returnsGreetingIntent() {
        var event = buildMessage("src-1", "hello there");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("GREETING");
        assertThat(result.confidence()).isEqualTo(0.8);
        assertThat(result.matchedRules()).contains("GREETING");
    }

    @Test
    void classify_question_returnsQuestionIntent() {
        var event = buildMessage("src-2", "what is the status?");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("QUESTION");
        assertThat(result.confidence()).isEqualTo(0.75);
    }

    @Test
    void classify_command_returnsCommandIntent() {
        var event = buildMessage("src-3", "start the service");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("COMMAND");
        assertThat(result.confidence()).isEqualTo(0.85);
    }

    @Test
    void classify_thanks_returnsHighestConfidenceAmongMatches() {
        // THANKS (0.9) and GOODBYE (0.85) both match — THANKS wins
        // "bye" is ^-anchored so the text must start with it; "thanks" has no anchor
        var event = buildMessage("src-4", "bye, thanks for everything");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("THANKS");
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.matchedRules()).containsExactlyInAnyOrder("THANKS", "GOODBYE");
    }

    @Test
    void classify_spam_returnsSpamIntent() {
        var event = buildMessage("src-5", "click here to earn money fast!");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("SPAM");
        assertThat(result.confidence()).isEqualTo(0.95);
    }

    @Test
    void classify_noMatch_returnsUnknown() {
        var event = buildMessage("src-6", "random message with no recognizable pattern");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("UNKNOWN");
        assertThat(result.confidence()).isEqualTo(0.0);
        assertThat(result.matchedRules()).isEmpty();
    }

    @Test
    void classify_publishesClassificationEventToKafka() {
        var event = buildMessage("src-7", "hello");

        service.classify(event, null).block();

        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @Test
    void classify_populatesSourceEventIdAndMetadata() {
        var event = buildMessage("src-8", "hi there");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.sourceEventId()).isEqualTo("src-8");
        assertThat(result.parameters()).containsKey("textLength");
        assertThat(result.parameters()).containsKey("chatId");
        assertThat(result.parameters()).containsKey("messageText");
        assertThat(result.parameters()).containsKey("telegramMessageId");
        assertThat(result.parameters().get("telegramMessageId")).isEqualTo(1L);
    }

    @Test
    void classify_omitsTelegramMessageIdWhenNull() {
        var event = buildMessageWithTelegramId("src-9", "hi there", null);

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.parameters()).doesNotContainKey("telegramMessageId");
    }

    private EventSchemas.TelegramMessageEvent buildMessage(String eventId, String text) {
        return buildMessageWithTelegramId(eventId, text, 1L);
    }

    private EventSchemas.TelegramMessageEvent buildMessageWithTelegramId(
            String eventId, String text, Long telegramMessageId) {
        return new EventSchemas.TelegramMessageEvent(
                eventId,
                "2026-05-13T10:00:00Z",
                null,
                null,
                telegramMessageId,
                100L,
                "user-1",
                "USER",
                text,
                1000,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
