package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.client.AuditServiceClient;
import io.emcip.admin.api.dto.SimulateMessageRequest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class SimulationServiceTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private AuditServiceClient auditServiceClient;

    private SimulationService simulationService;
    private ObjectMapper objectMapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        objectMapper = new ObjectMapper();
        simulationService = new SimulationService(kafkaTemplate, objectMapper, auditServiceClient);
        SendResult<String, String> sendResult =
                new SendResult<>(null, new RecordMetadata(null, 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
    }

    private SimulateMessageRequest request(long chatId) {
        SimulateMessageRequest req = new SimulateMessageRequest();
        req.setChatId(chatId);
        req.setText("hello world");
        return req;
    }

    /** Builds a JsonNode with all four pipeline stage event types present. */
    private JsonNode allStagesJson() {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("items");
        for (String type :
                new String[] {
                    "TelegramMessage", "IntentClassified", "PolicyDecision", "ModerationFlag"
                }) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("eventType", type);
            item.put("eventId", "fake-id-" + type);
            item.put("correlationId", "sim-uuid");
            item.set(
                    "details",
                    objectMapper
                            .createObjectNode()
                            .put("intent", "SPAM")
                            .put("confidence", 0.95)
                            .put("decision", "BLOCK")
                            .put("policyId", "spam-policy")
                            .put("flagType", "SPAM")
                            .put("severity", "HIGH")
                            .put("reason", "test"));
            items.add(item);
        }
        root.put("total", 4);
        return root;
    }

    /** Builds a JsonNode with no items (pipeline not yet complete). */
    private JsonNode emptyStagesJson() {
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("items");
        root.put("total", 0);
        return root;
    }

    @Test
    void simulate_publishesToKafka() {
        when(auditServiceClient.findByCorrelationId(anyString()))
                .thenReturn(Mono.just(allStagesJson()));

        StepVerifier.withVirtualTime(() -> simulationService.simulate(request(99L)))
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(r -> assertThat(r.eventId()).isNotNull())
                .verifyComplete();

        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @Test
    void simulate_returnsAllStagesWhenPipelineCompletes() {
        when(auditServiceClient.findByCorrelationId(anyString()))
                .thenReturn(Mono.just(allStagesJson()));

        StepVerifier.withVirtualTime(() -> simulationService.simulate(request(12345L)))
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(
                        result -> {
                            assertThat(result.eventId()).isNotNull();
                            assertThat(result.topic()).isEqualTo(SimulationService.TOPIC);
                            assertThat(result.partial()).isFalse();
                            assertThat(result.stages()).hasSize(4);
                            assertThat(result.stages())
                                    .extracting(SimulationService.TraceStage::stage)
                                    .containsExactlyInAnyOrder(
                                            "PUBLISH", "CLASSIFIER", "POLICY", "MODERATION");
                        })
                .verifyComplete();
    }

    @Test
    void simulate_returnsPartialWhenPipelineTimesOut() {
        when(auditServiceClient.findByCorrelationId(anyString()))
                .thenReturn(Mono.just(emptyStagesJson()));

        StepVerifier.withVirtualTime(() -> simulationService.simulate(request(77L)))
                .thenAwait(Duration.ofSeconds(16))
                .assertNext(
                        result -> {
                            assertThat(result.partial()).isTrue();
                            assertThat(result.stages()).isEmpty();
                        })
                .verifyComplete();
    }
}
