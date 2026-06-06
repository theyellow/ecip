package io.emcip.moderation.service.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.common.events.EventSchemas.PolicyDecisionEvent;
import io.emcip.moderation.service.service.RuleEvaluationService;
import io.emcip.moderation.service.service.RuleEvaluationService.EvaluationResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PolicyDecisionConsumerTest {

    @Mock private RuleEvaluationService ruleEvaluationService;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private Acknowledgment acknowledgment;

    private PolicyDecisionConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        consumer =
                new PolicyDecisionConsumer(
                        ruleEvaluationService, kafkaTemplate, new ObjectMapper());
        objectMapper = new ObjectMapper();
    }

    private ConsumerRecord<String, String> toRecord(String key, String value) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("policies.decisions", 0, 0L, key, value);
        record.headers()
                .add(new RecordHeader("tenant_id", "test-tenant".getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private ConsumerRecord<String, String> toRecordNoTenant(String key, String value) {
        return new ConsumerRecord<>("policies.decisions", 0, 0L, key, value);
    }

    private String policyDecisionJson(String sourceEventId, String messageText) throws Exception {
        PolicyDecisionEvent event =
                new PolicyDecisionEvent(
                        UUID.randomUUID().toString(),
                        "2026-06-05T10:00:00Z",
                        null,
                        null,
                        sourceEventId,
                        "policy-001",
                        "BLOCK",
                        "Spam detected",
                        Map.of(
                                "originalIntent",
                                "SPAM",
                                "confidence",
                                0.9,
                                "matchedRules",
                                List.of()),
                        List.of("block"),
                        messageText);
        return objectMapper.writeValueAsString(event);
    }

    @Test
    void consume_messageMatchingKeywordRule_sendsFlagAndAcknowledges() throws Exception {
        String json = policyDecisionJson("evt-001", "this message contains spam");
        EvaluationResult matchResult =
                new EvaluationResult("keyword-spam", "HIGH", "FLAG", "KEYWORD");
        when(ruleEvaluationService.evaluate(eq("this message contains spam"), any()))
                .thenReturn(Optional.of(matchResult));

        consumer.consume(toRecord("evt-001", json), acknowledgment);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        assertThat(recordCaptor.getValue().topic()).isEqualTo("moderation.flags");
        assertThat(recordCaptor.getValue().key()).isEqualTo("evt-001");
        assertThat(recordCaptor.getValue().value()).contains("ModerationFlag");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_messageMatchingNoRules_doesNotSendAndAcknowledges() throws Exception {
        String json = policyDecisionJson("evt-002", "a perfectly clean message");
        when(ruleEvaluationService.evaluate(eq("a perfectly clean message"), any()))
                .thenReturn(Optional.empty());

        consumer.consume(toRecord("evt-002", json), acknowledgment);

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_nullMessageText_skipsEvaluationAndAcknowledges() throws Exception {
        String json = policyDecisionJson("evt-003", null);

        consumer.consume(toRecord("evt-003", json), acknowledgment);

        verify(ruleEvaluationService, never()).evaluate(any(), any());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_noTenantContext_skipsAndAcknowledges() throws Exception {
        String json = policyDecisionJson("evt-no-tenant", "some message");

        consumer.consume(toRecordNoTenant("evt-no-tenant", json), acknowledgment);

        verify(ruleEvaluationService, never()).evaluate(any(), any());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_malformedJson_propagatesException() {
        assertThatThrownBy(
                        () ->
                                consumer.consume(
                                        toRecord("bad-key", "{ not valid json %%% }"),
                                        acknowledgment))
                .isInstanceOf(RuntimeException.class);

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void consume_kafkaTemplateSendFails_propagatesExceptionWithoutAck() throws Exception {
        String json = policyDecisionJson("evt-004", "spam content here");
        EvaluationResult matchResult =
                new EvaluationResult("keyword-spam", "HIGH", "FLAG", "KEYWORD");
        when(ruleEvaluationService.evaluate(eq("spam content here"), any()))
                .thenReturn(Optional.of(matchResult));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenThrow(new RuntimeException("Kafka unavailable"));

        assertThatThrownBy(() -> consumer.consume(toRecord("evt-004", json), acknowledgment))
                .isInstanceOf(RuntimeException.class);

        verify(acknowledgment, never()).acknowledge();
    }
}
