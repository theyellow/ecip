package io.emcip.policy.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.policy.engine.IntegrationTest;
import io.emcip.policy.engine.entity.PolicyDecision;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Integration tests for PolicyDecisionRepository. */
@IntegrationTest
@Transactional
class PolicyDecisionRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(PolicyDecisionRepositoryTest.class);

    @Autowired private PolicyDecisionRepository decisionRepository;

    @Test
    @DisplayName("Should save and find policy decision by ID")
    void shouldSaveAndFindById() {
        // Given
        PolicyDecision decision = createTestDecision("ALLOW", "No policy matched");

        // When
        PolicyDecision saved = decisionRepository.save(decision);
        Optional<PolicyDecision> found = decisionRepository.findById(saved.getId());

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getDecision()).isEqualTo("ALLOW");
        assertThat(found.get().getSourceEventId()).isEqualTo("evt-test-001");
        log.info("Policy decision saved and retrieved: {}", found.get().getId());
    }

    @Test
    @DisplayName("Should find decisions by source event ID")
    void shouldFindBySourceEventId() {
        // Given
        PolicyDecision decision1 = createTestDecision("BLOCK", "Spam detected");
        decision1.setSourceEventId("evt-common");
        decisionRepository.save(decision1);

        PolicyDecision decision2 = createTestDecision("ALLOW", "No issues");
        decision2.setSourceEventId("evt-common");
        decisionRepository.save(decision2);

        // When
        List<PolicyDecision> found = decisionRepository.findBySourceEventId("evt-common");

        // Then
        assertThat(found).hasSize(2);
        log.info("Found {} decisions for source event", found.size());
    }

    @Test
    @DisplayName("Should find decisions by decision type")
    void shouldFindByDecision() {
        // Given
        decisionRepository.save(createTestDecision("BLOCK", "Spam"));
        decisionRepository.save(createTestDecision("BLOCK", "Abuse"));
        decisionRepository.save(createTestDecision("ALLOW", "OK"));

        // When
        List<PolicyDecision> blocked = decisionRepository.findByDecision("BLOCK");

        // Then
        assertThat(blocked).hasSize(2);
        log.info("Found {} BLOCK decisions", blocked.size());
    }

    @Test
    @DisplayName("Should find most recent decision for source event")
    void shouldFindTopBySourceEventIdOrderByTimestampDesc() {
        // Given
        PolicyDecision old = createTestDecision("ALLOW", "Old");
        old.setSourceEventId("evt-recent");
        old.setTimestamp(Instant.now().minusSeconds(3600));
        decisionRepository.save(old);

        PolicyDecision recent = createTestDecision("BLOCK", "Recent");
        recent.setSourceEventId("evt-recent");
        recent.setTimestamp(Instant.now());
        decisionRepository.save(recent);

        // When
        Optional<PolicyDecision> found =
                decisionRepository.findTopBySourceEventIdOrderByTimestampDesc("evt-recent");

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getDecision()).isEqualTo("BLOCK");
        log.info("Most recent decision found: {}", found.get().getDecision());
    }

    @Test
    @DisplayName("Should find decisions by original intent")
    void shouldFindByOriginalIntent() {
        // Given
        PolicyDecision d1 = createTestDecision("BLOCK", "Spam");
        d1.setOriginalIntent("SPAM");
        decisionRepository.save(d1);

        PolicyDecision d2 = createTestDecision("RESPOND", "Hello");
        d2.setOriginalIntent("GREETING");
        decisionRepository.save(d2);

        // When
        List<PolicyDecision> spamDecisions = decisionRepository.findByOriginalIntent("SPAM");

        // Then
        assertThat(spamDecisions).hasSize(1);
        assertThat(spamDecisions.get(0).getDecision()).isEqualTo("BLOCK");
        log.info("Found {} SPAM decisions", spamDecisions.size());
    }

    @Test
    @DisplayName("Should find decisions with confidence above threshold")
    void shouldFindByConfidenceGreaterThan() {
        // Given
        PolicyDecision high = createTestDecision("BLOCK", "High confidence");
        high.setConfidence(0.95);
        decisionRepository.save(high);

        PolicyDecision low = createTestDecision("ALLOW", "Low confidence");
        low.setConfidence(0.45);
        decisionRepository.save(low);

        // When
        List<PolicyDecision> highConfidence = decisionRepository.findByConfidenceGreaterThan(0.8);

        // Then
        assertThat(highConfidence).hasSize(1);
        assertThat(highConfidence.get(0).getConfidence()).isGreaterThan(0.8);
        log.info("Found {} high confidence decisions", highConfidence.size());
    }

    private PolicyDecision createTestDecision(String decision, String reason) {
        PolicyDecision d = new PolicyDecision();
        d.setEventId(UUID.randomUUID().toString());
        d.setSourceEventId("evt-test-001");
        d.setPolicyId("policy-001");
        d.setDecision(decision);
        d.setReason(reason);
        d.setOriginalIntent("TEST");
        d.setConfidence(0.85);
        d.setMatchedRules(Map.of("rule1", "value1"));
        d.setMetadata(Map.of("key", "value"));
        d.setTimestamp(Instant.now());
        d.setTenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        return d;
    }
}
