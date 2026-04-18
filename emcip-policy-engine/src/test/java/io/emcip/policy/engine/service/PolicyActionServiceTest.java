package io.emcip.policy.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.emcip.policy.engine.entity.PolicyDecision;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

/** Unit tests for PolicyActionService. */
@ExtendWith(MockitoExtension.class)
class PolicyActionServiceTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;
    private PolicyActionService actionService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        actionService = new PolicyActionService(kafkaTemplate, objectMapper);
    }

    @Test
    @DisplayName("Should publish BLOCK action to moderation topic")
    void shouldPublishBlockAction() throws Exception {
        // Given
        PolicyDecision decision = createDecision("BLOCK", "Spam detected");
        Map<String, Object> context = Map.of("key", "value");

        // When
        actionService.executeAction(decision, context);

        // Then
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);

        verify(kafkaTemplate)
                .send(topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo(PolicyActionService.TOPIC_MODERATION);
        assertThat(keyCaptor.getValue()).isEqualTo("evt-source-001");

        Map<String, Object> event =
                objectMapper.readValue(valueCaptor.getValue(), new TypeReference<>() {});
        assertThat(event.get("actionType")).isEqualTo("BLOCK");
        assertThat(event.get("severity")).isEqualTo("HIGH");
        assertThat(event.get("reason")).isEqualTo("Spam detected");
    }

    @Test
    @DisplayName("Should publish RESPOND action to responses topic")
    void shouldPublishRespondAction() throws Exception {
        // Given
        PolicyDecision decision = createDecision("RESPOND", "Greeting detected");
        Map<String, Object> context = Map.of("intent", "GREETING");

        // When
        actionService.executeAction(decision, context);

        // Then
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), anyString(), anyString());

        assertThat(topicCaptor.getValue()).isEqualTo(PolicyActionService.TOPIC_RESPONSES);
    }

    @Test
    @DisplayName("Should publish ESCALATE action to escalation topic")
    void shouldPublishEscalateAction() throws Exception {
        // Given
        PolicyDecision decision = createDecision("ESCALATE", "Question needs human");
        decision.setConfidence(0.8);
        Map<String, Object> context = Map.of();

        // When
        actionService.executeAction(decision, context);

        // Then
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), anyString(), valueCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo(PolicyActionService.TOPIC_ESCALATION);

        Map<String, Object> event =
                objectMapper.readValue(valueCaptor.getValue(), new TypeReference<>() {});
        assertThat(event.get("actionType")).isEqualTo("ESCALATE");
        assertThat(event.get("escalationLevel")).isEqualTo("HUMAN_REVIEW");
        assertThat(event.get("priority")).isEqualTo("MEDIUM");
    }

    @Test
    @DisplayName("Should publish EXECUTE action to commands topic")
    void shouldPublishExecuteAction() throws Exception {
        // Given
        PolicyDecision decision = createDecision("EXECUTE", "Execute command");
        Map<String, Object> context =
                Map.of("parameters", Map.of("command", "ban", "userId", "12345"));

        // When
        actionService.executeAction(decision, context);

        // Then
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), anyString(), valueCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo(PolicyActionService.TOPIC_COMMANDS);

        Map<String, Object> event =
                objectMapper.readValue(valueCaptor.getValue(), new TypeReference<>() {});
        assertThat(event.get("actionType")).isEqualTo("EXECUTE_COMMAND");
        assertThat(event.get("command")).isEqualTo("ban");
    }

    @Test
    @DisplayName("Should publish REVIEW action to review topic")
    void shouldPublishReviewAction() throws Exception {
        // Given
        PolicyDecision decision = createDecision("REVIEW", "Low confidence");
        Map<String, Object> context = Map.of();

        // When
        actionService.executeAction(decision, context);

        // Then
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), anyString(), valueCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo(PolicyActionService.TOPIC_REVIEW);

        Map<String, Object> event =
                objectMapper.readValue(valueCaptor.getValue(), new TypeReference<>() {});
        assertThat(event.get("actionType")).isEqualTo("REVIEW");
        assertThat(event.get("reviewQueue")).isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    @DisplayName("Should log but not publish for ALLOW action")
    void shouldNotPublishForAllowAction() {
        // Given
        PolicyDecision decision = createDecision("ALLOW", "No action needed");
        Map<String, Object> context = Map.of();

        // When
        actionService.executeAction(decision, context);

        // Then
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Should handle unknown action gracefully")
    void shouldHandleUnknownAction() {
        // Given
        PolicyDecision decision = createDecision("UNKNOWN_ACTION", "Unknown");
        Map<String, Object> context = Map.of();

        // When
        actionService.executeAction(decision, context);

        // Then
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Should calculate HIGH priority for high confidence")
    void shouldCalculateHighPriority() throws Exception {
        // Given
        PolicyDecision decision = createDecision("ESCALATE", "High priority issue");
        decision.setConfidence(0.95);
        Map<String, Object> context = Map.of();

        // When
        actionService.executeAction(decision, context);

        // Then
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), valueCaptor.capture());

        Map<String, Object> event =
                objectMapper.readValue(valueCaptor.getValue(), new TypeReference<>() {});
        assertThat(event.get("priority")).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("Should calculate LOW priority for low confidence")
    void shouldCalculateLowPriority() throws Exception {
        // Given
        PolicyDecision decision = createDecision("ESCALATE", "Low priority issue");
        decision.setConfidence(0.5);
        Map<String, Object> context = Map.of();

        // When
        actionService.executeAction(decision, context);

        // Then
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), valueCaptor.capture());

        Map<String, Object> event =
                objectMapper.readValue(valueCaptor.getValue(), new TypeReference<>() {});
        assertThat(event.get("priority")).isEqualTo("LOW");
    }

    private PolicyDecision createDecision(String decision, String reason) {
        PolicyDecision d = new PolicyDecision();
        d.setId("dec-001");
        d.setEventId("evt-001");
        d.setSourceEventId("evt-source-001");
        d.setPolicyId("policy-001");
        d.setDecision(decision);
        d.setReason(reason);
        d.setOriginalIntent("TEST");
        d.setConfidence(0.8);
        d.setTimestamp(Instant.now());
        return d;
    }
}
