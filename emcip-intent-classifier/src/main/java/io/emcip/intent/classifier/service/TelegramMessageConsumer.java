package io.emcip.intent.classifier.service;

import io.emcip.common.events.EventSchemas;
import io.emcip.common.tenant.TenantAwareKafkaSupport;
import io.emcip.common.validation.EventValidator;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka consumer for Telegram messages. Consumes from telegram.raw.messages topic and performs
 * intent classification.
 */
@Service
public class TelegramMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelegramMessageConsumer.class);
    private static final String TOPIC = "telegram.raw.messages";

    private final ObjectMapper objectMapper;
    private final EventValidator eventValidator;
    private final IntentClassificationService classificationService;

    public TelegramMessageConsumer(
            ObjectMapper objectMapper,
            EventValidator eventValidator,
            IntentClassificationService classificationService) {
        this.objectMapper = objectMapper;
        this.eventValidator = eventValidator;
        this.classificationService = classificationService;
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = "intent-classifier",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        log.debug(
                "Received message from partition {} offset {}",
                record.partition(),
                record.offset());

        UUID tenantId;
        try {
            tenantId = TenantAwareKafkaSupport.validateTenantHeader(record);
        } catch (IllegalStateException e) {
            log.error("Rejecting record: {}", e.getMessage());
            return;
        }

        try {
            // Validate JSON structure
            EventValidator.ValidationResult validationResult =
                    eventValidator.validateJson(record.value(), "TelegramMessage");
            if (!validationResult.valid()) {
                log.error("Invalid message received: {}", validationResult.getErrorMessage());
                return;
            }

            // Parse and classify
            EventSchemas.TelegramMessageEvent event =
                    objectMapper.readValue(record.value(), EventSchemas.TelegramMessageEvent.class);
            EventSchemas.IntentClassifiedEvent result =
                    classificationService.classify(event, tenantId.toString());

            log.info("Classified message {} as intent {}", result.sourceEventId(), result.intent());
        } catch (Exception e) {
            log.error("Error processing message: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
