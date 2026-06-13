package io.emcip.knowledge.engine.service;

import io.emcip.common.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeEventPublisher {

    private static final String TOPIC_KNOWLEDGE_EVENTS = "knowledge.events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishExtractionComplete(String sourceRef, UUID tenantId) {
        publishEvent(
                "EXTRACTION_COMPLETE",
                Map.of("sourceRef", sourceRef, "status", "COMPLETE"),
                tenantId);
    }

    public void publishBackfillProgress(String chatId, int processed, int total, UUID tenantId) {
        publishEvent(
                "BACKFILL_PROGRESS",
                Map.of(
                        "chatId", chatId,
                        "processed", processed,
                        "total", total,
                        "percentage", total > 0 ? (processed * 100 / total) : 0),
                tenantId);
    }

    public void publishEnrichmentResponse(
            String requestId, Map<String, Object> results, UUID tenantId) {
        publishEvent(
                "ENRICHMENT_RESPONSE",
                Map.of("requestId", requestId, "results", results),
                tenantId);
    }

    private void publishEvent(String eventType, Map<String, Object> payload, UUID tenantId) {
        try {
            Map<String, Object> event =
                    Map.of(
                            "eventId",
                            UUID.randomUUID().toString(),
                            "eventType",
                            eventType,
                            "timestamp",
                            Instant.now().toString(),
                            "payload",
                            payload);

            String json = objectMapper.writeValueAsString(event);
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(TOPIC_KNOWLEDGE_EVENTS, eventType, json);

            if (tenantId != null) {
                record.headers()
                        .add(
                                TenantContext.KAFKA_HEADER,
                                tenantId.toString().getBytes(StandardCharsets.UTF_8));
            }

            kafkaTemplate.send(record);
            log.debug("Published knowledge event: type={}", eventType);
        } catch (Exception e) {
            log.error("Failed to publish knowledge event: {}", e.getMessage(), e);
        }
    }
}
