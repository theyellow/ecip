package io.emcip.intent.classifier.service;

import io.emcip.common.events.EventSchemas;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Rule-based intent classification service. Implements simple pattern matching for initial intent
 * types.
 */
@Service
public class IntentClassificationService {

    private static final Logger log = LoggerFactory.getLogger(IntentClassificationService.class);
    private static final String TOPIC_OUTPUT = "messages.classified";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final SignalDetector signalDetector;

    // Simple rule patterns (Phase 2 - basic rules)
    private final List<IntentRule> rules =
            List.of(
                    new IntentRule(
                            "GREETING",
                            Pattern.compile(
                                    "^(?i)(hello|hi|hey|greetings|good\\s+(morning|afternoon|evening))"),
                            0.8),
                    new IntentRule(
                            "QUESTION",
                            Pattern.compile(
                                    "^(?i)(what|how|why|when|where|who|is|are|can|do|does|did|will|would|could)"),
                            0.75),
                    new IntentRule(
                            "COMMAND",
                            Pattern.compile(
                                    "^(?i)(start|stop|help|status|config|set|get|show|list|create|delete|update)"),
                            0.85),
                    new IntentRule(
                            "THANKS", Pattern.compile("(?i)(thank|thanks|thx|appreciate)"), 0.9),
                    new IntentRule(
                            "GOODBYE",
                            Pattern.compile("^(?i)(bye|goodbye|see\\s+you|later|cya)"),
                            0.85),
                    new IntentRule(
                            "SPAM",
                            Pattern.compile(
                                    "(?i)(click\\s+here|buy\\s+now|limited\\s+offer|earn\\s+money|make\\s+money\\s+fast|viagra|casino|crypto\\s+investment)"),
                            0.95));

    public IntentClassificationService(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            SignalDetector signalDetector) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.signalDetector = signalDetector;
    }

    /** Classify a Telegram message and publish the result. */
    public Mono<EventSchemas.IntentClassifiedEvent> classify(
            EventSchemas.TelegramMessageEvent message, String tenantId) {
        return Mono.fromCallable(
                () -> {
                    String text = message.text() != null ? message.text() : "";
                    String matchedIntent = null;
                    double highestConfidence = 0.0;
                    List<String> matchedRules = new ArrayList<>();

                    // Apply rules
                    for (IntentRule rule : rules) {
                        if (rule.pattern.matcher(text).find()) {
                            matchedRules.add(rule.name);
                            if (rule.confidence > highestConfidence) {
                                highestConfidence = rule.confidence;
                                matchedIntent = rule.name;
                            }
                        }
                    }

                    // Detect structural/script signals
                    Map<String, Object> signals = signalDetector.detect(text, message.metadata());

                    // Apply signal priority chain when no rule matched
                    if (matchedIntent == null) {
                        if (Boolean.TRUE.equals(signals.get("stickerOnly"))) {
                            matchedIntent = "FORMAT_STICKER_ONLY";
                        } else if (Boolean.TRUE.equals(signals.get("imageOnly"))) {
                            matchedIntent = "FORMAT_IMAGE_ONLY";
                        } else if (Boolean.TRUE.equals(signals.get("emojiOnly"))) {
                            matchedIntent = "FORMAT_EMOJI_ONLY";
                        } else if (signals.get("lookalikeSuspicion") instanceof Double d
                                && d > 0.0) {
                            matchedIntent = "LOOKALIKE_ABUSE";
                        } else if (Boolean.TRUE.equals(signals.get("zeroWidthAbuse"))) {
                            matchedIntent = "FORMAT_ABUSE";
                        } else if (signals.get("foreignScriptRatio") instanceof Double d
                                && d >= 0.6) {
                            matchedIntent = "SCRIPT_FOREIGN";
                        } else if (signals.get("capsRatio") instanceof Double d && d >= 0.7) {
                            matchedIntent = "CAPS_HEAVY";
                        } else if (signals.get("toxicityHint") instanceof Double d && d > 0.0) {
                            matchedIntent = "TOXICITY_HINT";
                        } else {
                            matchedIntent = "UNKNOWN";
                        }
                    }

                    // Create classification event
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("textLength", text.length());
                    params.put("chatId", message.chatId());
                    params.put("senderId", message.senderId() != null ? message.senderId() : "");
                    params.put("messageText", text);
                    if (message.telegramMessageId() != null) {
                        params.put("telegramMessageId", message.telegramMessageId());
                    }
                    params.putAll(signals);
                    var classification =
                            new EventSchemas.IntentClassifiedEvent(
                                    UUID.randomUUID().toString(),
                                    Instant.now().toString(),
                                    EventSchemas.INTENT_CLASSIFIED_V1,
                                    "IntentClassified",
                                    message.eventId(),
                                    matchedIntent,
                                    highestConfidence,
                                    params,
                                    matchedRules);

                    // Publish to Kafka, forwarding tenant_id header if present
                    String json = objectMapper.writeValueAsString(classification);
                    ProducerRecord<String, String> producerRecord =
                            new ProducerRecord<>(TOPIC_OUTPUT, null, message.eventId(), json);
                    if (tenantId != null) {
                        producerRecord
                                .headers()
                                .add("tenant_id", tenantId.getBytes(StandardCharsets.UTF_8));
                    }
                    kafkaTemplate.send(producerRecord);

                    log.debug(
                            "Published classification for message {}: {}",
                            message.eventId(),
                            matchedIntent);
                    return classification;
                });
    }

    private record IntentRule(String name, Pattern pattern, double confidence) {}
}
