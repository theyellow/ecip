package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

import io.emcip.common.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PolicyDecisionConsumerTest {

    @Mock private LlmCallService llmCallService;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private PolicyDecisionConsumer consumer;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        LlmResponseValidator responseValidator = new LlmResponseValidator(2000);
        consumer =
                new PolicyDecisionConsumer(
                        objectMapper, llmCallService, kafkaTemplate, responseValidator);
    }

    @AfterEach
    void tearDown() {
        // Guard against test pollution if the consumer under test fails to clear
        // TenantContext (this is exactly the bug RT2-009 describes).
        TenantContext.clear();
    }

    @Test
    void bindsTenantContextFromKafkaHeader() {
        UUID tenantId = UUID.randomUUID();
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("policies.decisions", 0, 0L, "key", validDecisionJson());
        record.headers()
                .add(
                        TenantContext.KAFKA_HEADER,
                        tenantId.toString().getBytes(StandardCharsets.UTF_8));

        AtomicReference<String> boundTenant = new AtomicReference<>();
        // capture the tenant visible to downstream work
        doAnswer(
                        inv -> {
                            boundTenant.set(TenantContext.getTenantId());
                            return Optional.empty();
                        })
                .when(llmCallService)
                .callForTask(anyString(), anyString(), anyString(), any(), anyString(), any());

        consumer.consume(record);

        assertThat(boundTenant.get()).isEqualTo(tenantId.toString());
        // the finally block must clear the context once the record is done
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void rejectsRecordWithoutTenantHeader() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("policies.decisions", 0, 0L, "key", validDecisionJson());

        consumer.consume(record);

        verifyNoInteractions(llmCallService);
    }

    private String validDecisionJson() {
        return """
               {
                 "eventId": "evt-1",
                 "timestamp": "2026-07-22T10:00:00Z",
                 "schemaVersion": "1.0.0",
                 "eventType": "PolicyDecision",
                 "sourceEventId": "src-1",
                 "policyId": "policy-1",
                 "decision": "RESPOND",
                 "reason": "auto-response",
                 "context": {"text": "Hello there"},
                 "actions": [],
                 "messageText": "Hello there"
               }
               """;
    }
}
