package io.emcip.knowledge.engine.service;

import io.emcip.common.events.EventSchemas;
import io.emcip.common.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
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
        UUID tenantId = extractTenantId(record);

        try {
            TenantContext.setTenantId(tenantId != null ? tenantId.toString() : null);

            EventSchemas.TelegramMessageEvent event =
                    objectMapper.readValue(record.value(), EventSchemas.TelegramMessageEvent.class);

            if (event.text() == null || event.text().isBlank()) {
                log.debug("Skipping non-text message: {}", event.telegramMessageId());
                return;
            }

            String sourceRef = String.format("tg:%d:%d", event.chatId(), event.telegramMessageId());

            extractionService.processMessage(event.text(), sourceRef, tenantId);

            eventPublisher.publishExtractionComplete(sourceRef, tenantId);

            log.info(
                    "Processed knowledge message: chat={}, msg={}",
                    event.chatId(),
                    event.telegramMessageId());

        } finally {
            TenantContext.clear();
        }
    }

    private UUID extractTenantId(ConsumerRecord<String, String> record) {
        Header tenantHeader = record.headers().lastHeader(TenantContext.KAFKA_HEADER);
        if (tenantHeader != null) {
            try {
                return UUID.fromString(new String(tenantHeader.value(), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid tenant ID in Kafka header");
            }
        }
        return null;
    }
}
