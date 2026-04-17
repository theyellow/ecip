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
 * Kafka consumer for Telegram messages. Consumes from telegram.raw.messages topic and persists to
 * database.
 */
@Service
public class TelegramMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelegramMessageConsumer.class);
    private static final String TOPIC = "telegram.raw.messages";

    private final ObjectMapper objectMapper;
    private final EventValidator eventValidator;
    private final ConversationContextService contextService;

    public TelegramMessageConsumer(
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
                "Received message from partition {} offset {}",
                record.partition(),
                record.offset());

        Mono.fromCallable(
                        () -> {
                            // Validate event structure
                            var validation =
                                    eventValidator.validateJson(record.value(), "TelegramMessage");
                            if (!validation.valid()) {
                                log.error(
                                        "Invalid message received: {}",
                                        validation.getErrorMessage());
                                return null;
                            }

                            // Parse and process
                            EventSchemas.TelegramMessageEvent event =
                                    objectMapper.readValue(
                                            record.value(),
                                            EventSchemas.TelegramMessageEvent.class);

                            return contextService.processTelegramMessage(event);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(
                        result -> {
                            if (result != null) {
                                log.info(
                                        "Persisted message {} to conversation context",
                                        result.getEventId());
                            }
                        })
                .doOnError(e -> log.error("Error processing message: {}", e.getMessage(), e))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }
}
