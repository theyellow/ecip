package io.emcip.intent.classifier.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.emcip.common.events.EventSchemas;
import io.emcip.common.events.EventSchemas.IntentClassifiedEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Rule-based intent classification service.
 * Implements simple pattern matching for initial intent types.
 */
@Service
public class IntentClassificationService {

    private static final Logger log = LoggerFactory.getLogger(IntentClassificationService.class);
    private static final String TOPIC_OUTPUT = "messages.classified";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // Simple rule patterns (Phase 2 - basic rules)
    private final List<IntentRule> rules = List.of(
        new IntentRule("GREETING", Pattern.compile("^(?i)(hello|hi|hey|greetings|good\\s+(morning|afternoon|evening))"), 0.8),
        new IntentRule("QUESTION", Pattern.compile("^(?i)(what|how|why|when|where|who|is|are|can|do|does|did|will|would|could)"), 0.75),
        new IntentRule("COMMAND", Pattern.compile("^(?i)(start|stop|help|status|config|set|get|show|list|create|delete|update)"), 0.85),
        new IntentRule("THANKS", Pattern.compile("(?i)(thank|thanks|thx|appreciate)"), 0.9),
        new IntentRule("GOODBYE", Pattern.compile("^(?i)(bye|goodbye|see\\s+you|later|cya)"), 0.85),
        new IntentRule("SPAM", Pattern.compile("(?i)(click\\s+here|buy\\s+now|limited\\s+offer|earn\\s+money|make\\s+money\\s+fast|viagra|casino|crypto\\s+investment)"), 0.95)
    );

    public IntentClassificationService(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Classify a Telegram message and publish the result.
     */
    public Mono<EventSchemas.IntentClassifiedEvent> classify(EventSchemas.TelegramMessageEvent message) {
        return Mono.fromCallable(() -> {
            String text = message.text();
            String matchedIntent = "UNKNOWN";
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

            // Create classification event
            var classification = new EventSchemas.IntentClassifiedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                EventSchemas.INTENT_CLASSIFIED_V1,
                "IntentClassified",
                message.eventId(),
                matchedIntent,
                highestConfidence,
                Map.of("textLength", text.length(), "chatId", message.chatId()),
                matchedRules
            );

            // Publish to Kafka
            String json = objectMapper.writeValueAsString(classification);
            kafkaTemplate.send(TOPIC_OUTPUT, message.eventId(), json);

            log.debug("Published classification for message {}: {}", message.eventId(), matchedIntent);
            return classification;
        });
    }

    private record IntentRule(String name, Pattern pattern, double confidence) {}
}
