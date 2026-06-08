package io.emcip.intent.classifier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.common.events.EventSchemas;
import java.util.Map;
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
        service =
                new IntentClassificationService(
                        kafkaTemplate, new ObjectMapper(), new SignalDetector());
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
        assertThat(result.parameters()).containsKey("foreignScriptRatio");
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

    @Test
    void signals_mergedIntoParams_forTextMessage() {
        var event = buildMessage("sig-1", "a regular message");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.parameters())
                .containsKeys(
                        "foreignScriptRatio",
                        "cyrillicRatio",
                        "lookalikeSuspicion",
                        "zeroWidthAbuse",
                        "capsRatio",
                        "emojiOnly",
                        "stickerOnly",
                        "imageOnly",
                        "toxicityHint");
    }

    @Test
    void stickerOnly_signal_overridesNullIntent() {
        var event = buildMessageWithMetadata("sig-2", "", Map.of("contentType", "sticker"));

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("FORMAT_STICKER_ONLY");
    }

    @Test
    void imageOnly_signal_overridesNullIntent() {
        var event = buildMessageWithMetadata("sig-3", "", Map.of("contentType", "photo"));

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("FORMAT_IMAGE_ONLY");
    }

    @Test
    void emojiOnly_signal_overridesNullIntent() {
        // U+1F600 = 😀
        var event = buildMessage("sig-4", "\uD83D\uDE00 \uD83D\uDE01");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("FORMAT_EMOJI_ONLY");
    }

    @Test
    void lookalikeSuspicion_overridesNullIntent() {
        // U+0435 is Cyrillic е (lookalike for Latin e), mixed with Latin chars in same word
        var event = buildMessage("sig-5", "h\u0435llo w\u043Frld");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("LOOKALIKE_ABUSE");
    }

    @Test
    void zeroWidthAbuse_overridesNullIntent() {
        // U+200B = zero-width space
        var event = buildMessage("sig-6", "normal\u200Btext here");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("FORMAT_ABUSE");
    }

    @Test
    void foreignScript_overridesNullIntent() {
        // Non-lookalike Cyrillic: П(041F) р(0440-lookalike, skip) и(0438) в(0432) е(0435-lookalike)
        // Use clearly non-lookalike Cyrillic letters: П и б ж щ ю я
        var event = buildMessage("sig-7", "Пибжщюя пибжщюя пибжщюя");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("SCRIPT_FOREIGN");
    }

    @Test
    void capsHeavy_overridesNullIntent() {
        // >= 5 letters, >= 70% uppercase, no rule match
        var event = buildMessage("sig-8", "SHOUTING VERY LOUD MESSAGE");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("CAPS_HEAVY");
    }

    @Test
    void toxicityHint_overridesNullIntent() {
        // "cunt" is in the toxicity list; lowercase so no caps signal; plain ASCII so no other
        // signals
        var event = buildMessage("sig-9", "you are a cunt");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("TOXICITY_HINT");
    }

    @Test
    void ruleMatch_preventsSignalChainFromOverridingIntent() {
        // "hello" triggers GREETING rule; signal chain must not override it
        // Add a zero-width char to ensure a signal would otherwise fire
        var event = buildMessage("sig-10", "hello\u200B there");

        var result = service.classify(event, null).block();

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("GREETING");
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

    private EventSchemas.TelegramMessageEvent buildMessageWithMetadata(
            String eventId, String text, Map<String, Object> metadata) {
        return new EventSchemas.TelegramMessageEvent(
                eventId,
                "2026-05-13T10:00:00Z",
                null,
                null,
                1L,
                100L,
                "user-1",
                "USER",
                text,
                1000,
                null,
                false,
                null,
                null,
                metadata,
                null,
                null,
                null,
                null);
    }
}
