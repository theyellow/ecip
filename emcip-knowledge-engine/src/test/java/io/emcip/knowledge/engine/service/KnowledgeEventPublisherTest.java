package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.emcip.common.tenant.TenantContext;
import io.emcip.knowledge.engine.entity.ResearchStatus;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class KnowledgeEventPublisherTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private KnowledgeEventPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        publisher = new KnowledgeEventPublisher(kafkaTemplate, objectMapper);
    }

    private ProducerRecord<String, String> capture() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void publishExtractionComplete_sendsTypedEventToKnowledgeEventsTopic() {
        publisher.publishExtractionComplete("doc-42", UUID.randomUUID());

        ProducerRecord<String, String> record = capture();
        assertThat(record.topic()).isEqualTo("knowledge.events");
        assertThat(record.key()).isEqualTo("EXTRACTION_COMPLETE");

        Map<String, Object> event = objectMapper.readValue(record.value(), Map.class);
        assertThat(event.get("eventType")).isEqualTo("EXTRACTION_COMPLETE");
        assertThat(event.get("eventId")).isNotNull();
        assertThat(event.get("timestamp")).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertThat(payload)
                .containsEntry("sourceRef", "doc-42")
                .containsEntry("status", "COMPLETE");
    }

    @Test
    void publishEvent_attachesTenantHeaderWhenTenantPresent() {
        UUID tenantId = UUID.randomUUID();

        publisher.publishExtractionComplete("doc-1", tenantId);

        ProducerRecord<String, String> record = capture();
        var header = record.headers().lastHeader(TenantContext.KAFKA_HEADER);
        assertThat(header).isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8))
                .isEqualTo(tenantId.toString());
    }

    @Test
    void publishEvent_omitsTenantHeaderWhenTenantNull() {
        publisher.publishExtractionComplete("doc-1", null);

        ProducerRecord<String, String> record = capture();
        assertThat(record.headers().lastHeader(TenantContext.KAFKA_HEADER)).isNull();
    }

    @Test
    void publishBackfillProgress_computesPercentage() {
        publisher.publishBackfillProgress("chat-1", 25, 200, UUID.randomUUID());

        @SuppressWarnings("unchecked")
        Map<String, Object> event = objectMapper.readValue(capture().value(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertThat(payload).containsEntry("percentage", 12);
    }

    @Test
    void publishBackfillProgress_doesNotDivideByZeroWhenTotalIsZero() {
        publisher.publishBackfillProgress("chat-1", 0, 0, UUID.randomUUID());

        @SuppressWarnings("unchecked")
        Map<String, Object> event = objectMapper.readValue(capture().value(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertThat(payload).containsEntry("percentage", 0);
    }

    @Test
    void publishEntityCreated_writesEmptyTenantStringWhenTenantNull() {
        publisher.publishEntityCreated("Acme Corp", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> event = objectMapper.readValue(capture().value(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertThat(payload).containsEntry("entityName", "Acme Corp").containsEntry("tenantId", "");
    }

    @Test
    void publishResearchCompleted_serialisesSessionAndStatus() {
        UUID sessionId = UUID.randomUUID();

        publisher.publishResearchCompleted(sessionId, ResearchStatus.COMPLETED, UUID.randomUUID());

        @SuppressWarnings("unchecked")
        Map<String, Object> event = objectMapper.readValue(capture().value(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertThat(payload)
                .containsEntry("sessionId", sessionId.toString())
                .containsEntry("status", "COMPLETED");
    }

    @Test
    void publishEvent_swallowsSerialisationFailureWithoutThrowing() {
        // A payload whose getter throws during serialisation must not propagate out of the
        // publisher: event publication is best-effort and must never break the calling pipeline.
        // (A bare `new Object() {}` does NOT trigger this with tools.jackson's default
        // ObjectMapper config, since FAIL_ON_EMPTY_BEANS is not enabled by default there —
        // hence the explicit throwing getter below to force a genuine serialisation failure.)
        Object explodingValue =
                new Object() {
                    @SuppressWarnings("unused")
                    public String getValue() {
                        throw new RuntimeException("boom");
                    }
                };

        publisher.publishEnrichmentResponse(
                "req-1", Map.of("bad", explodingValue), UUID.randomUUID());

        verifyNoInteractions(kafkaTemplate);
    }
}
