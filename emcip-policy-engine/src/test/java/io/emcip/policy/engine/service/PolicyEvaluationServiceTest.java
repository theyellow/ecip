package io.emcip.policy.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.atLeastOnce;

import io.emcip.common.events.EventSchemas;
import io.emcip.policy.engine.condition.ConditionEvaluator;
import io.emcip.policy.engine.condition.ConditionEvaluatorRegistry;
import io.emcip.policy.engine.condition.evaluator.AccountAgeDaysEvaluator;
import io.emcip.policy.engine.condition.evaluator.FlaggedCountEvaluator;
import io.emcip.policy.engine.condition.evaluator.GroupSizeEvaluator;
import io.emcip.policy.engine.condition.evaluator.MessageLanguageEvaluator;
import io.emcip.policy.engine.condition.evaluator.MessageLengthEvaluator;
import io.emcip.policy.engine.condition.evaluator.MinThreadLengthEvaluator;
import io.emcip.policy.engine.condition.evaluator.TimeWindowEvaluator;
import io.emcip.policy.engine.entity.PolicyDecision;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.repository.PolicyDecisionRepository;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/** Unit tests for PolicyEvaluationService with mocked dependencies. */
@ExtendWith(MockitoExtension.class)
class PolicyEvaluationServiceTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    @Mock private PolicyDecisionRepository decisionRepository;

    @Mock private PolicyRuleConfigRepository ruleConfigRepository;

    @Mock private PolicyActionService actionService;

    private ObjectMapper objectMapper;
    private ConditionEvaluatorRegistry registry;
    private PolicyEvaluationService policyService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        List<ConditionEvaluator> evs =
                List.of(
                        new TimeWindowEvaluator(),
                        new MinThreadLengthEvaluator(),
                        new AccountAgeDaysEvaluator(),
                        new MessageLanguageEvaluator(),
                        new GroupSizeEvaluator(),
                        new MessageLengthEvaluator(),
                        new FlaggedCountEvaluator());
        registry = new ConditionEvaluatorRegistry(evs);
        policyService =
                new PolicyEvaluationService(
                        kafkaTemplate,
                        objectMapper,
                        decisionRepository,
                        ruleConfigRepository,
                        actionService,
                        registry);
    }

    @Test
    @DisplayName("Should use default rules when no database rules exist")
    void shouldUseDefaultRulesWhenNoDbRules() {
        // Given
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(decisionRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            PolicyDecision d = inv.getArgument(0);
                            d.setId("test-id");
                            return d;
                        });

        var classification = createClassification("SPAM", 0.9);

        // When
        PolicyDecision result = policyService.evaluate(classification, null);

        // Then
        assertThat(result.getDecision()).isEqualTo("BLOCK");
        assertThat(result.getReason()).contains("Spam detected");
        verify(decisionRepository).save(any());
        verify(kafkaTemplate).send(any(ProducerRecord.class));
        verify(actionService).executeAction(any(PolicyDecision.class), anyMap());
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

        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(List.of(customRule));
        when(decisionRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            PolicyDecision d = inv.getArgument(0);
                            d.setId("test-id");
                            return d;
                        });

        var classification = createClassification("SPAM", 0.6);

        // When
        PolicyDecision result = policyService.evaluate(classification, null);

        // Then
        assertThat(result.getDecision()).isEqualTo("CUSTOM_ACTION");
        assertThat(result.getReason()).isEqualTo("Custom rule matched");
        assertThat(result.getPolicyId()).isEqualTo("custom-001");
    }

    @Test
    @DisplayName("Should match SPAM intent with high confidence")
    void shouldMatchSpamWithHighConfidence() {
        // Given
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("SPAM", 0.85);

        // When
        PolicyDecision result = policyService.evaluate(classification, null);

        // Then
        assertThat(result.getDecision()).isEqualTo("BLOCK");
        assertThat(result.getOriginalIntent()).isEqualTo("SPAM");
    }

    @Test
    @DisplayName("Should not match SPAM with low confidence")
    void shouldNotMatchSpamWithLowConfidence() {
        // Given
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("SPAM", 0.5);

        // When
        PolicyDecision result = policyService.evaluate(classification, null);

        // Then
        assertThat(result.getDecision()).isEqualTo("ALLOW");
        assertThat(result.getReason()).contains("No policy matched");
    }

    @Test
    @DisplayName("Should match GREETING intent with sufficient confidence")
    void shouldMatchGreeting() {
        // Given
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("GREETING", 0.75);

        // When
        PolicyDecision result = policyService.evaluate(classification, null);

        // Then
        assertThat(result.getDecision()).isEqualTo("RESPOND");
    }

    @Test
    @DisplayName("Should match QUESTION intent")
    void shouldMatchQuestion() {
        // Given
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("QUESTION", 0.8);

        // When
        PolicyDecision result = policyService.evaluate(classification, null);

        // Then
        assertThat(result.getDecision()).isEqualTo("ESCALATE");
    }

    @Test
    @DisplayName("Should match COMMAND intent")
    void shouldMatchCommand() {
        // Given
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("COMMAND", 0.85);

        // When
        PolicyDecision result = policyService.evaluate(classification, null);

        // Then
        assertThat(result.getDecision()).isEqualTo("EXECUTE");
    }

    @Test
    @DisplayName("Should trigger moderation check for low confidence")
    void shouldTriggerModerationForLowConfidence() {
        // Given
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("UNKNOWN", 0.2);

        // When
        PolicyDecision result = policyService.evaluate(classification, null);

        // Then
        assertThat(result.getDecision()).isEqualTo("REVIEW");
        assertThat(result.getReason()).contains("Low confidence");
    }

    @Test
    @DisplayName("Should persist decision with correct metadata")
    void shouldPersistDecisionWithCorrectMetadata() {
        // Given
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("SPAM", 0.9);

        // When
        PolicyDecision result = policyService.evaluate(classification, null);

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
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("GREETING", 0.8);

        // When
        policyService.evaluate(classification, null);

        // Then
        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("Should include messageText from parameters in published Kafka event")
    void shouldIncludeMessageTextInPublishedEvent() throws Exception {
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification =
                new EventSchemas.IntentClassifiedEvent(
                        "evt-classify-002",
                        Instant.now().toString(),
                        EventSchemas.INTENT_CLASSIFIED_V1,
                        "IntentClassified",
                        "evt-test-002",
                        "SPAM",
                        0.9,
                        Map.of("messageText", "buy now click here"),
                        List.of());

        policyService.evaluate(classification, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, atLeastOnce()).send(captor.capture());

        String json = captor.getValue().value();
        assertThat(json).contains("messageText");
        assertThat(json).contains("buy now click here");
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

        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(List.of(wildcardRule));
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var classification = createClassification("ANYTHING", 0.05);

        // When
        PolicyDecision result = policyService.evaluate(classification, null);

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

    @Test
    @DisplayName(
            "Should forward signal params to PolicyDecision metadata and PolicyDecisionEvent"
                    + " context")
    @SuppressWarnings("unchecked")
    void shouldForwardSignalParamsToDecisionMetadataAndEventContext() {
        // Given
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(decisionRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            PolicyDecision d = inv.getArgument(0);
                            d.setId("test-signal-id");
                            return d;
                        });

        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("messageText", "Привет мир");
        params.put("chatId", 100L);
        params.put("senderId", "user-1");
        params.put("foreignScriptRatio", 0.8);
        params.put("cyrillicRatio", 0.8);
        params.put("lookalikeSuspicion", 0.0);
        params.put("zeroWidthAbuse", false);
        params.put("capsRatio", 0.0);
        params.put("emojiOnly", false);
        params.put("stickerOnly", false);
        params.put("imageOnly", false);
        params.put("toxicityHint", 0.0);

        var classification =
                new EventSchemas.IntentClassifiedEvent(
                        "evt-sig-1",
                        Instant.now().toString(),
                        EventSchemas.INTENT_CLASSIFIED_V1,
                        "IntentClassified",
                        "src-sig-1",
                        "SCRIPT_FOREIGN",
                        0.8,
                        params,
                        List.of("SCRIPT_FOREIGN"));

        // When
        PolicyDecision result = policyService.evaluate(classification, null);

        // Then: PolicyDecision.metadata contains all 9 signal scores
        assertThat(result.getMetadata())
                .containsKeys(
                        "foreignScriptRatio",
                        "cyrillicRatio",
                        "lookalikeSuspicion",
                        "zeroWidthAbuse",
                        "capsRatio",
                        "emojiOnly",
                        "stickerOnly",
                        "imageOnly",
                        "toxicityHint");
        assertThat(result.getMetadata().get("foreignScriptRatio")).isEqualTo(0.8);

        // And: PolicyDecisionEvent context serialised to Kafka contains signal scores (key + value)
        ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        verify(kafkaTemplate, atLeastOnce()).send(captor.capture());
        assertThat(captor.getValue().value()).contains("\"foreignScriptRatio\":0.8");

        // And: original four fields still forwarded
        assertThat(result.getMetadata()).containsKey("messageText");
        assertThat(result.getMetadata()).containsKey("chatId");
    }

    @Test
    @DisplayName("Rule with no conditions.groups passes (backward compat)")
    void conditionsAbsent_alwaysPasses() {
        PolicyRuleConfig rule = makeRule("SPAM", 0.7, null);
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(List.of(rule));
        stubDecisionSave();

        PolicyDecision d = policyService.evaluate(makeEvent("SPAM", 0.9, Map.of()), UUID.randomUUID());
        assertThat(d.getDecision()).isEqualTo("BLOCK");
    }

    @Test
    @DisplayName("Single OR-group: all conditions pass → rule matches")
    void singleGroup_allPass() {
        Map<String, Object> conditions =
                Map.of(
                        "groups",
                        List.of(
                                Map.of(
                                        "conditions",
                                        List.of(Map.of("type", "MIN_THREAD_LENGTH", "min", 3)))));
        PolicyRuleConfig rule = makeRule("SPAM", 0.7, conditions);
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(List.of(rule));
        stubDecisionSave();

        PolicyDecision d =
                policyService.evaluate(
                        makeEvent("SPAM", 0.9, Map.of("threadLength", 5)), UUID.randomUUID());
        assertThat(d.getDecision()).isEqualTo("BLOCK");
    }

    @Test
    @DisplayName("Single OR-group: one condition fails → no match → fallback ALLOW")
    void singleGroup_condFails_noMatch() {
        Map<String, Object> conditions =
                Map.of(
                        "groups",
                        List.of(
                                Map.of(
                                        "conditions",
                                        List.of(Map.of("type", "MIN_THREAD_LENGTH", "min", 10)))));
        PolicyRuleConfig rule = makeRule("SPAM", 0.7, conditions);
        PolicyRuleConfig fallback = makeRule("*", 0.0, null);
        fallback.setAction("ALLOW");
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(List.of(rule, fallback));
        stubDecisionSave();

        PolicyDecision d =
                policyService.evaluate(
                        makeEvent("SPAM", 0.9, Map.of("threadLength", 2)), UUID.randomUUID());
        assertThat(d.getDecision()).isEqualTo("ALLOW");
    }

    @Test
    @DisplayName("Multi-group OR: first group fails, second passes → rule matches")
    void multiGroup_secondPasses() {
        Map<String, Object> conditions =
                Map.of(
                        "groups",
                        List.of(
                                Map.of(
                                        "conditions",
                                        List.of(Map.of("type", "ACCOUNT_AGE_DAYS", "max", 3))),
                                Map.of(
                                        "conditions",
                                        List.of(Map.of("type", "GROUP_SIZE", "min", 100)))));
        PolicyRuleConfig rule = makeRule("SPAM", 0.7, conditions);
        when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
                .thenReturn(List.of(rule));
        stubDecisionSave();

        PolicyDecision d =
                policyService.evaluate(
                        makeEvent(
                                "SPAM",
                                0.9,
                                Map.of("senderAccountAgeDays", 10, "groupSize", 200)),
                        UUID.randomUUID());
        assertThat(d.getDecision()).isEqualTo("BLOCK");
    }

    // ---- helpers ----

    private PolicyRuleConfig makeRule(
            String intent, double minConf, Map<String, Object> conditions) {
        PolicyRuleConfig r = new PolicyRuleConfig();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(UUID.randomUUID());
        r.setName("test-rule");
        r.setTargetIntent(intent);
        r.setMinConfidence(minConf);
        r.setAction("BLOCK");
        r.setConditions(conditions);
        r.setActive(true);
        r.setPriority(0);
        r.setRuleVersion(1);
        return r;
    }

    private EventSchemas.IntentClassifiedEvent makeEvent(
            String intent, double conf, Map<String, Object> params) {
        return new EventSchemas.IntentClassifiedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                "v1",
                "IntentClassified",
                UUID.randomUUID().toString(),
                intent,
                conf,
                params,
                List.of());
    }

    private void stubDecisionSave() {
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private EventSchemas.IntentClassifiedEvent createClassification(
            String intent, double confidence) {
        return new EventSchemas.IntentClassifiedEvent(
                "evt-classify-001",
                Instant.now().toString(),
                EventSchemas.INTENT_CLASSIFIED_V1,
                "IntentClassified",
                "evt-test-001",
                intent,
                confidence,
                Map.of("param1", "value1", "messageText", "buy now click here"),
                List.of("rule1", "rule2"));
    }
}
