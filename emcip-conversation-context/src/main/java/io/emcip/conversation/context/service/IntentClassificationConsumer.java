package io.emcip.conversation.context.service;

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
 * updates message records.
 */
@Service
public class IntentClassificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(IntentClassificationConsumer.class);
    private static final String TOPIC = "messages.classified";

    private final ObjectMapper objectMapper;
    private final EventValidator eventValidator;
    private final ConversationContextService contextService;

    public IntentClassificationConsumer(
            ObjectMapper objectMapper,
            EventValidator eventValidator,
            ConversationContextService contextService) {
        this.objectMapper = objectMapper;
        this.eventValidator = eventValidator;
        this.contextService = contextService;
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = "conversation-context",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        log.debug(
                "Received classification from partition {} offset {}",
                record.partition(),
                record.offset());

        Mono.fromCallable(
                        () -> {
                            // Validate event structure
                            var validation =
                                    eventValidator.validateJson(record.value(), "IntentClassified");
                            if (!validation.valid()) {
                                log.error(
                                        "Invalid classification received: {}",
                                        validation.getErrorMessage());
                                return null;
                            }

                            // Parse and process
                            EventSchemas.IntentClassifiedEvent event =
                                    objectMapper.readValue(
                                            record.value(),
                                            EventSchemas.IntentClassifiedEvent.class);

                            contextService.updateIntentClassification(event);
                            return event;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(
                        result -> {
                            if (result != null) {
                                log.info(
                                        "Updated intent classification for source event {}: {}",
                                        result.sourceEventId(),
                                        result.intent());
                            }
                        })
                .doOnError(e -> log.error("Error processing classification: {}", e.getMessage(), e))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }
}
