package io.emcip.policy.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.emcip.common.events.EventSchemas;
import io.emcip.common.validation.EventValidator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Kafka consumer for intent classification events. Consumes from messages.classified topic and
 * evaluates policies.
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

        Mono.fromCallable(
                        () -> {
                            // Validate
                            var validationResult =
                                    eventValidator.validateJson(record.value(), "IntentClassified");
                            if (!validationResult.valid()) {
                                log.error(
                                        "Invalid classification received: {}",
                                        validationResult.getErrorMessage());
                                return null;
                            }

                            // Parse and evaluate
                            var event =
                                    objectMapper.readValue(
                                            record.value(),
                                            EventSchemas.IntentClassifiedEvent.class);
                            return policyService.evaluate(event).block();
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(
                        result -> {
                            if (result != null) {
                                log.info(
                                        "Evaluated policy for event {}: decision={}",
                                        result.sourceEventId(),
                                        result.decision());
                            }
                        })
                .doOnError(e -> log.error("Error processing classification: {}", e.getMessage(), e))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }
}
