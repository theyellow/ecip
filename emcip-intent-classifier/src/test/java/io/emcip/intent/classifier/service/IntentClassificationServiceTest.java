package io.emcip.intent.classifier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.common.events.EventSchemas;
import io.emcip.intent.classifier.repository.IntentRuleRepository;
import io.emcip.intent.classifier.repository.IntentSignalConfigRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    @Mock private IntentRuleRepository ruleRepository;
    @Mock private IntentSignalConfigRepository signalConfigRepository;

    private IntentClassificationService service;

    /**
     * Build a default signal config equivalent to the old hardcoded thresholds.
     *
     * <p>Thresholds are tuned so the existing signal-chain tests pass: lookalikeSuspicion=1 (fires
     * on a single mixed-script word, matching old {@code i > 0} behaviour) and zeroWidthAbuse=1
     * (fires on a single zero-width char, matching old {@code i >= 1} behaviour).
     */
    private static io.emcip.intent.classifier.entity.IntentSignalConfig defaultSignalConfig() {
        var cfg = new io.emcip.intent.classifier.entity.IntentSignalConfig();
        cfg.setForeignScriptRatio(0.6);
        cfg.setCyrillicRatio(0.6);
        cfg.setLookalikeSuspicion(1);
        cfg.setZeroWidthAbuse(1);
        cfg.setCapsRatio(0.7);
        cfg.setToxicityWords(
                List.of(
                        "nigger",
                        "nigga",
                        "faggot",
                        "cunt",
                        "kike",
                        "spic",
                        "chink",
                        "wetback",
                        "gook",
                        "towelhead",
                        "raghead",
                        "hurensohn",
                        "wichser",
                        "fotze",
                        "arschloch"));
        return cfg;
    }

    /** Build a REGEX-mode IntentRule replicating the old hardcoded compiled patterns. */
    private static io.emcip.intent.classifier.entity.IntentRule regexRule(
            String name, String pattern, String intent, double confidence, int priority) {
        var r = new io.emcip.intent.classifier.entity.IntentRule();
        r.setId(UUID.randomUUID().toString());
        r.setName(name);
        r.setMatchMode("REGEX");
        r.setPattern(pattern);
        r.setIntent(intent);
        r.setConfidence(confidence);
        r.setPriority(priority);
        r.setActive(true);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    @BeforeEach
    void setUp() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Seed 6 rules that replicate the old hardcoded rule behaviour using REGEX mode so that
        // anchoring semantics are preserved (some old rules used ^ anchors).
        var rules =
                List.of(
                        regexRule(
                                "GREETING",
                                "^(?i)(hello|hi|hey|greetings|good\\s+(morning|afternoon|evening))",
                                "GREETING",
                                0.8,
                                10),
                        regexRule(
                                "QUESTION",
                                "^(?i)(what|how|why|when|where|who|is|are|can|do|does|did|will|would|could)",
                                "QUESTION",
                                0.75,
                                20),
                        regexRule(
                                "COMMAND",
                                "^(?i)(start|stop|help|status|config|set|get|show|list|create|delete|update)",
                                "COMMAND",
                                0.85,
                                30),
                        regexRule("THANKS", "(?i)(thank|thanks|thx|appreciate)", "THANKS", 0.9, 40),
                        regexRule(
                                "GOODBYE",
                                "^(?i)(bye|goodbye|see\\s+you|later|cya)",
                                "GOODBYE",
                                0.85,
                                50));

        // SPAM also uses REGEX mode
        var spamRule = new io.emcip.intent.classifier.entity.IntentRule();
        spamRule.setId(UUID.randomUUID().toString());
        spamRule.setName("SPAM");
        spamRule.setMatchMode("REGEX");
        spamRule.setPattern(
                "(?i)(click\\s+here|buy\\s+now|limited\\s+offer|earn\\s+money|make\\s+money\\s+fast|viagra|casino|crypto\\s+investment)");
        spamRule.setIntent("SPAM");
        spamRule.setConfidence(0.95);
        spamRule.setPriority(60);
        spamRule.setActive(true);
        spamRule.setCreatedAt(Instant.now());
        spamRule.setUpdatedAt(Instant.now());

        var allRules = new ArrayList<>(rules);
        allRules.add(spamRule);

        when(ruleRepository.findByTenantIdIsNullAndActiveTrueOrderByPriorityAsc())
                .thenReturn(allRules);
        when(ruleRepository.findAll()).thenReturn(List.of());
        when(signalConfigRepository.findByTenantIdIsNull())
                .thenReturn(Optional.of(defaultSignalConfig()));
        when(signalConfigRepository.findAll()).thenReturn(List.of());

        service =
                new IntentClassificationService(
                        kafkaTemplate,
                        new ObjectMapper(),
                        new SignalDetector(),
                        ruleRepository,
                        signalConfigRepository);
        // @PostConstruct is not invoked by 'new' in unit tests; call init() explicitly
        service.init();
    }

    @Test
    void classify_greeting_returnsGreetingIntent() {
        var event = buildMessage("src-1", "hello there");

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("GREETING");
        assertThat(result.confidence()).isEqualTo(0.8);
        assertThat(result.matchedRules()).contains("GREETING");
    }

    @Test
    void classify_question_returnsQuestionIntent() {
        var event = buildMessage("src-2", "what is the status?");

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("QUESTION");
        assertThat(result.confidence()).isEqualTo(0.75);
    }

    @Test
    void classify_command_returnsCommandIntent() {
        var event = buildMessage("src-3", "start the service");

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("COMMAND");
        assertThat(result.confidence()).isEqualTo(0.85);
    }

    @Test
    void classify_thanks_returnsHighestConfidenceAmongMatches() {
        // THANKS (0.9) and GOODBYE (0.85) both match — THANKS wins
        // KEYWORD matching uses contains(), so "bye" and "thanks" both match
        var event = buildMessage("src-4", "bye, thanks for everything");

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("THANKS");
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.matchedRules()).containsExactlyInAnyOrder("THANKS", "GOODBYE");
    }

    @Test
    void classify_spam_returnsSpamIntent() {
        var event = buildMessage("src-5", "click here to earn money fast!");

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("SPAM");
        assertThat(result.confidence()).isEqualTo(0.95);
    }

    @Test
    void classify_noMatch_returnsUnknown() {
        var event = buildMessage("src-6", "random message with no recognizable pattern");

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("UNKNOWN");
        assertThat(result.confidence()).isEqualTo(0.0);
        assertThat(result.matchedRules()).isEmpty();
        assertThat(result.parameters()).containsKey("foreignScriptRatio");
    }

    @Test
    void classify_publishesClassificationEventToKafka() {
        var event = buildMessage("src-7", "hello");

        service.classify(event, null);

        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @Test
    void classify_populatesSourceEventIdAndMetadata() {
        var event = buildMessage("src-8", "hi there");

        var result = service.classify(event, null);

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

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.parameters()).doesNotContainKey("telegramMessageId");
    }

    @Test
    void signals_mergedIntoParams_forTextMessage() {
        var event = buildMessage("sig-1", "a regular message");

        var result = service.classify(event, null);

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

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("FORMAT_STICKER_ONLY");
    }

    @Test
    void imageOnly_signal_overridesNullIntent() {
        var event = buildMessageWithMetadata("sig-3", "", Map.of("contentType", "photo"));

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("FORMAT_IMAGE_ONLY");
    }

    @Test
    void emojiOnly_signal_overridesNullIntent() {
        // U+1F600 = 😀
        var event = buildMessage("sig-4", "\uD83D\uDE00 \uD83D\uDE01");

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("FORMAT_EMOJI_ONLY");
    }

    @Test
    void lookalikeSuspicion_overridesNullIntent() {
        // U+0435 is Cyrillic е (lookalike for Latin e), mixed with Latin chars in same word
        var event = buildMessage("sig-5", "h\u0435llo w\u043Frld");

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("LOOKALIKE_ABUSE");
    }

    @Test
    void zeroWidthAbuse_overridesNullIntent() {
        // U+200B = zero-width space
        var event = buildMessage("sig-6", "normal\u200Btext here");

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("FORMAT_ABUSE");
    }

    @Test
    void foreignScript_overridesNullIntent() {
        // Non-lookalike Cyrillic: П и б ж щ ю я
        var event = buildMessage("sig-7", "Пибжщюя пибжщюя пибжщюя");

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("SCRIPT_FOREIGN");
    }

    @Test
    void capsHeavy_overridesNullIntent() {
        // >= 5 letters, >= 70% uppercase, no rule match
        var event = buildMessage("sig-8", "SHOUTING VERY LOUD MESSAGE");

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("CAPS_HEAVY");
    }

    @Test
    void toxicityHint_overridesNullIntent() {
        // Task 6: toxicity patterns are now loaded from IntentSignalConfig in setUp().
        // "cunt" is in the 15-word list → toxicityHint > 0 → intent = TOXICITY_HINT.
        var event = buildMessage("sig-9", "you are a cunt");

        var result = service.classify(event, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("TOXICITY_HINT");
    }

    @Test
    void ruleMatch_preventsSignalChainFromOverridingIntent() {
        // "hello" triggers GREETING rule; signal chain must not override it
        // Add a zero-width char to ensure a signal would otherwise fire
        var event = buildMessage("sig-10", "hello\u200B there");

        var result = service.classify(event, null);

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
