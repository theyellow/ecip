package io.emcip.policy.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.policy.engine.IntegrationTest;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for PolicyRuleConfigRepository.
 */
@IntegrationTest
@Transactional
class PolicyRuleConfigRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(PolicyRuleConfigRepositoryTest.class);

    @Autowired
    private PolicyRuleConfigRepository ruleConfigRepository;

    @Test
    @DisplayName("Should save and find policy rule by ID")
    void shouldSaveAndFindById() {
        // Given
        PolicyRuleConfig rule = createTestRule("SPAM_BLOCK", "SPAM", "BLOCK");

        // When
        PolicyRuleConfig saved = ruleConfigRepository.save(rule);
        Optional<PolicyRuleConfig> found = ruleConfigRepository.findById(saved.getId());

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("SPAM_BLOCK");
        assertThat(found.get().getAction()).isEqualTo("BLOCK");
        log.info("Policy rule saved and retrieved: {}", found.get().getId());
    }

    @Test
    @DisplayName("Should find active rules ordered by priority")
    void shouldFindByActiveTrueOrderByPriorityAsc() {
        // Given
        PolicyRuleConfig high = createTestRule("HIGH", "SPAM", "BLOCK");
        high.setPriority(10);
        ruleConfigRepository.save(high);

        PolicyRuleConfig low = createTestRule("LOW", "GREETING", "RESPOND");
        low.setPriority(100);
        ruleConfigRepository.save(low);

        PolicyRuleConfig medium = createTestRule("MEDIUM", "QUESTION", "ESCALATE");
        medium.setPriority(50);
        ruleConfigRepository.save(medium);

        PolicyRuleConfig inactive = createTestRule("INACTIVE", "TEST", "ALLOW");
        inactive.setActive(false);
        ruleConfigRepository.save(inactive);

        // When
        List<PolicyRuleConfig> active = ruleConfigRepository.findByActiveTrueOrderByPriorityAsc();

        // Then
        assertThat(active).hasSize(3);
        assertThat(active.get(0).getName()).isEqualTo("HIGH");
        assertThat(active.get(1).getName()).isEqualTo("MEDIUM");
        assertThat(active.get(2).getName()).isEqualTo("LOW");
        log.info("Found {} active rules in priority order", active.size());
    }

    @Test
    @DisplayName("Should find rules by target intent")
    void shouldFindByTargetIntentAndActiveTrueOrderByPriorityAsc() {
        // Given
        PolicyRuleConfig rule1 = createTestRule("SPAM_1", "SPAM", "BLOCK");
        rule1.setPriority(10);
        ruleConfigRepository.save(rule1);

        PolicyRuleConfig rule2 = createTestRule("SPAM_2", "SPAM", "REVIEW");
        rule2.setPriority(20);
        ruleConfigRepository.save(rule2);

        // When
        List<PolicyRuleConfig> spamRules = ruleConfigRepository
                .findByTargetIntentAndActiveTrueOrderByPriorityAsc("SPAM");

        // Then
        assertThat(spamRules).hasSize(2);
        assertThat(spamRules.get(0).getName()).isEqualTo("SPAM_1");
        log.info("Found {} SPAM rules", spamRules.size());
    }

    @Test
    @DisplayName("Should find rule by name")
    void shouldFindByName() {
        // Given
        PolicyRuleConfig rule = createTestRule("UNIQUE_NAME", "TEST", "ALLOW");
        ruleConfigRepository.save(rule);

        // When
        Optional<PolicyRuleConfig> found = ruleConfigRepository.findByName("UNIQUE_NAME");

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getTargetIntent()).isEqualTo("TEST");
        log.info("Found rule by name: {}", found.get().getName());
    }

    @Test
    @DisplayName("Should activate and deactivate rules")
    void shouldActivateAndDeactivate() {
        // Given
        PolicyRuleConfig rule = createTestRule("TOGGLE", "TEST", "ALLOW");
        rule.setActive(false);
        ruleConfigRepository.save(rule);

        // When - Activate
        int activated = ruleConfigRepository.activate(rule.getId());
        Optional<PolicyRuleConfig> found = ruleConfigRepository.findById(rule.getId());

        // Then
        assertThat(activated).isEqualTo(1);
        assertThat(found).isPresent();
        assertThat(found.get().getActive()).isTrue();
        log.info("Rule activated successfully");
    }

    private PolicyRuleConfig createTestRule(String name, String intent, String action) {
        PolicyRuleConfig rule = new PolicyRuleConfig();
        rule.setId(UUID.randomUUID().toString());
        rule.setName(name);
        rule.setDescription("Test rule for " + name);
        rule.setTargetIntent(intent);
        rule.setMinConfidence(0.5);
        rule.setMaxConfidence(1.0);
        rule.setAction(action);
        rule.setReason("Test reason");
        rule.setPriority(100);
        rule.setActive(true);
        rule.setCreatedAt(Instant.now());
        return rule;
    }
}
