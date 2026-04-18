package io.emcip.policy.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.emcip.common.events.EventSchemas;
import io.emcip.policy.engine.entity.PolicyDecision;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.repository.PolicyDecisionRepository;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Unit tests for PolicyEvaluationService with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
class PolicyEvaluationServiceTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private PolicyDecisionRepository decisionRepository;

    @Mock
    private PolicyRuleConfigRepository ruleConfigRepository;

    private ObjectMapper objectMapper;
    private PolicyEvaluationService policyService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        policyService = new PolicyEvaluationService(
                kafkaTemplate, objectMapper, decisionRepository, ruleConfigRepository);
    }

    @Test
    @DisplayName("Should use default rules when no database rules exist")
    void shouldUseDefaultRulesWhenNoDbRules() {
        // Given
        when(ruleConfigRepository.findByActiveTrueOrderByPriorityAsc()).thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> {
            PolicyDecision d = inv.getArgument(0);
            d.setId("test-id");
            return d;
        });

        var classification = createClassification("SPAM", 0.9);

        // When
        PolicyDecision result = policyService.evaluate(classification);

        // Then
        assertThat(result.getDecision()).isEqualTo("BLOCK");
        assertThat(result.getReason()).contains("Spam detected");
        verify(decisionRepository).save(any());
        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Should use database rules when available")
    void shouldUseDatabaseRulesWhenAvailable() {
        // Given
        PolicyRuleConfig customRule = new PolicyRuleConfig();
        customRule.setId("custom-001");
        customRule.setName("CUSTOM_BLOCK");
        customRule.setTargetIntent("SPAM");
        customRule.setMinConfidence(0.5);
        customRule.setAction("CUSTOM_ACTION");
        customRule.setReason("Custom rule matched");
        customRule.setPriority(10);
        customRule.setActive(true);

        when(ruleConfigRepository.findByActiveTrueOrderByPriorityAsc()).thenReturn(List.of(customRule));
        when(decisionRepository.save(any())).thenAnswer(inv -> {
            PolicyDecision d = inv.getArgument(0);
            d.setId("test-id");
            return d;
        });

        var classification = createClassification("SPAM", 0.6);

        // When
        PolicyDecision result = policyService.evaluate(classification);

        // Then
        assertThat(result.getDecision()).isEqualTo("CUSTOM_ACTION");
        assertThat(result.getReason()).isEqualTo("Custom rule matched");
        assertThat(result.getPolicyId()).isEqualTo("custom-001");
    }

    @Test
    @DisplayName("Should match SPAM intent with high confidence")
    void shouldMatchSpamWithHighConfidence() {
        // Given
        when(ruleConfigRepository.findByActiveTrueOrderByPriorityAsc()).thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("SPAM", 0.85);

        // When
        PolicyDecision result = policyService.evaluate(classification);

        // Then
        assertThat(result.getDecision()).isEqualTo("BLOCK");
        assertThat(result.getOriginalIntent()).isEqualTo("SPAM");
    }

    @Test
    @DisplayName("Should not match SPAM with low confidence")
    void shouldNotMatchSpamWithLowConfidence() {
        // Given
        when(ruleConfigRepository.findByActiveTrueOrderByPriorityAsc()).thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("SPAM", 0.5);

        // When
        PolicyDecision result = policyService.evaluate(classification);

        // Then
        assertThat(result.getDecision()).isEqualTo("ALLOW");
        assertThat(result.getReason()).contains("No policy matched");
    }

    @Test
    @DisplayName("Should match GREETING intent with sufficient confidence")
    void shouldMatchGreeting() {
        // Given
        when(ruleConfigRepository.findByActiveTrueOrderByPriorityAsc()).thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("GREETING", 0.75);

        // When
        PolicyDecision result = policyService.evaluate(classification);

        // Then
        assertThat(result.getDecision()).isEqualTo("RESPOND");
    }

    @Test
    @DisplayName("Should match QUESTION intent")
    void shouldMatchQuestion() {
        // Given
        when(ruleConfigRepository.findByActiveTrueOrderByPriorityAsc()).thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("QUESTION", 0.8);

        // When
        PolicyDecision result = policyService.evaluate(classification);

        // Then
        assertThat(result.getDecision()).isEqualTo("ESCALATE");
    }

    @Test
    @DisplayName("Should match COMMAND intent")
    void shouldMatchCommand() {
        // Given
        when(ruleConfigRepository.findByActiveTrueOrderByPriorityAsc()).thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("COMMAND", 0.85);

        // When
        PolicyDecision result = policyService.evaluate(classification);

        // Then
        assertThat(result.getDecision()).isEqualTo("EXECUTE");
    }

    @Test
    @DisplayName("Should trigger moderation check for low confidence")
    void shouldTriggerModerationForLowConfidence() {
        // Given
        when(ruleConfigRepository.findByActiveTrueOrderByPriorityAsc()).thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("UNKNOWN", 0.2);

        // When
        PolicyDecision result = policyService.evaluate(classification);

        // Then
        assertThat(result.getDecision()).isEqualTo("REVIEW");
        assertThat(result.getReason()).contains("Low confidence");
    }

    @Test
    @DisplayName("Should persist decision with correct metadata")
    void shouldPersistDecisionWithCorrectMetadata() {
        // Given
        when(ruleConfigRepository.findByActiveTrueOrderByPriorityAsc()).thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("SPAM", 0.9);

        // When
        PolicyDecision result = policyService.evaluate(classification);

        // Then
        ArgumentCaptor<PolicyDecision> captor = ArgumentCaptor.forClass(PolicyDecision.class);
        verify(decisionRepository).save(captor.capture());

        PolicyDecision saved = captor.getValue();
        assertThat(saved.getOriginalIntent()).isEqualTo("SPAM");
        assertThat(saved.getConfidence()).isEqualTo(0.9);
        assertThat(saved.getDecision()).isEqualTo("BLOCK");
        assertThat(saved.getSourceEventId()).isEqualTo("evt-classify-001");
        assertThat(saved.getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("Should publish event to Kafka")
    void shouldPublishEventToKafka() {
        // Given
        when(ruleConfigRepository.findByActiveTrueOrderByPriorityAsc()).thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("GREETING", 0.8);

        // When
        policyService.evaluate(classification);

        // Then
        verify(kafkaTemplate).send(
                eq("policies.decisions"),
                eq("evt-classify-001"),
                anyString()
        );
    }

    @Test
    @DisplayName("Should handle wildcards in intent matching")
    void shouldHandleWildcardIntentMatching() {
        // Given - Create rule with wildcard intent
        PolicyRuleConfig wildcardRule = new PolicyRuleConfig();
        wildcardRule.setId("wildcard-001");
        wildcardRule.setName("CATCH_ALL");
        wildcardRule.setTargetIntent("*");
        wildcardRule.setMinConfidence(0.0);
        wildcardRule.setMaxConfidence(0.1);
        wildcardRule.setAction("ESCALATE");
        wildcardRule.setReason("Very low confidence");
        wildcardRule.setPriority(5);
        wildcardRule.setActive(true);

        when(ruleConfigRepository.findByActiveTrueOrderByPriorityAsc()).thenReturn(List.of(wildcardRule));
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("ANYTHING", 0.05);

        // When
        PolicyDecision result = policyService.evaluate(classification);

        // Then
        assertThat(result.getDecision()).isEqualTo("ESCALATE");
        assertThat(result.getPolicyId()).isEqualTo("wildcard-001");
    }

    @Test
    @DisplayName("Should get active rules from repository")
    void shouldGetActiveRules() {
        // Given
        PolicyRuleConfig rule = new PolicyRuleConfig();
        rule.setName("TEST_RULE");
        when(ruleConfigRepository.findByActiveTrueOrderByPriorityAsc()).thenReturn(List.of(rule));

        // When
        List<PolicyRuleConfig> activeRules = policyService.getActiveRules();

        // Then
        assertThat(activeRules).hasSize(1);
        assertThat(activeRules.get(0).getName()).isEqualTo("TEST_RULE");
        verify(ruleConfigRepository).findByActiveTrueOrderByPriorityAsc();
    }

    private EventSchemas.IntentClassifiedEvent createClassification(String intent, double confidence) {
        return new EventSchemas.IntentClassifiedEvent(
                "evt-classify-001",
                Instant.now().toString(),
                EventSchemas.INTENT_CLASSIFIED_V1,
                "IntentClassified",
                "evt-test-001",
                intent,
                confidence,
                Map.of("param1", "value1"),
                List.of("rule1", "rule2")
        );
    }
}
