package io.emcip.policy.engine.service;

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
 * Kafka consumer for intent classification events. Consumes from messages.classified topic and
 * evaluates policies synchronously.
 */
@Service
public class IntentClassificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(IntentClassificationConsumer.class);
    private static final String TOPIC = "messages.classified";

    private final ObjectMapper objectMapper;
    private final EventValidator eventValidator;
    private final PolicyEvaluationService policyService;

    public IntentClassificationConsumer(
            ObjectMapper objectMapper,
            EventValidator eventValidator,
            PolicyEvaluationService policyService) {
        this.objectMapper = objectMapper;
        this.eventValidator = eventValidator;
        this.policyService = policyService;
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = "policy-engine",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        log.debug(
                "Received classification from partition {} offset {}",
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
            // Validate
            var validationResult = eventValidator.validateJson(record.value(), "IntentClassified");
            if (!validationResult.valid()) {
                log.error(
                        "Invalid classification received: {}", validationResult.getErrorMessage());
                return;
            }

            // Parse and evaluate
            var event =
                    objectMapper.readValue(
                            record.value(), EventSchemas.IntentClassifiedEvent.class);
            var result = policyService.evaluate(event, tenantId);

            log.info(
                    "Evaluated policy for event {}: decision={}",
                    result.getSourceEventId(),
                    result.getDecision());

        } catch (Exception e) {
            log.error("Error processing classification: {}", e.getMessage(), e);
        }
    }
}
