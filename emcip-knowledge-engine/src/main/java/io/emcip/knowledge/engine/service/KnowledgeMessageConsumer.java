package io.emcip.knowledge.engine.service;

import io.emcip.common.events.EventSchemas;
import io.emcip.common.tenant.TenantAwareKafkaSupport;
import io.emcip.common.tenant.TenantContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeMessageConsumer {

    private final KnowledgeExtractionService extractionService;
    private final KnowledgeEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "knowledge.raw.messages",
            groupId = "knowledge-engine",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        UUID tenantId;
        try {
            tenantId = TenantAwareKafkaSupport.validateTenantHeader(record);
        } catch (IllegalStateException e) {
            log.error("Rejecting record: {}", e.getMessage());
            return;
        }

        try {
            TenantContext.setTenantId(tenantId.toString());

            EventSchemas.TelegramMessageEvent event =
                    objectMapper.readValue(record.value(), EventSchemas.TelegramMessageEvent.class);

            if (event.text() == null || event.text().isBlank()) {
                log.debug("Skipping non-text message: {}", event.telegramMessageId());
                return;
            }

            String sourceRef = String.format("tg:%d:%d", event.chatId(), event.telegramMessageId());

            extractionService.processMessage(
                    event.text(),
                    sourceRef,
                    tenantId,
                    event.chatId(),
                    event.senderId(),
                    event.senderDisplayName(),
                    event.chatTitle(),
                    event.date());

            eventPublisher.publishExtractionComplete(sourceRef, tenantId);

            log.info(
                    "Processed knowledge message: chat={}, msg={}",
                    event.chatId(),
                    event.telegramMessageId());

        } finally {
            TenantContext.clear();
        }
    }
}
