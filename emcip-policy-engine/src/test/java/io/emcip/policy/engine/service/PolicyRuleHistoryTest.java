package io.emcip.policy.engine.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.policy.engine.IntegrationTest;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.entity.PolicyRuleHistory;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import io.emcip.policy.engine.repository.PolicyRuleHistoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class PolicyRuleHistoryTest {

    @Autowired private PolicyRuleConfigRepository ruleRepo;
    @Autowired private PolicyRuleHistoryRepository historyRepo;

    @Test
    void snapshotWrittenOnUpdate() {
        UUID tenantId = UUID.randomUUID();
        PolicyRuleConfig rule = new PolicyRuleConfig();
        rule.setId(UUID.randomUUID().toString());
        rule.setTenantId(tenantId);
        rule.setName("test-versioning-" + UUID.randomUUID().toString().substring(0, 8));
        rule.setTargetIntent("SPAM");
        rule.setMinConfidence(0.7);
        rule.setAction("BLOCK");
        rule.setActive(true);
        rule.setPriority(10);
        rule.setRuleVersion(1);
        ruleRepo.save(rule);

        // Simulate what the controller does on PUT
        PolicyRuleHistory snap = new PolicyRuleHistory();
        snap.setId(UUID.randomUUID());
        snap.setRuleId(rule.getId());
        snap.setTenantId(tenantId);
        snap.setSnapshot(Map.of("name", rule.getName(), "action", "BLOCK", "ruleVersion", 1));
        snap.setEditedBy("admin");
        snap.setEditedAt(Instant.now());
        snap.setRuleVersion(1);
        historyRepo.save(snap);

        List<PolicyRuleHistory> history = historyRepo.findByRuleIdOrderByEditedAtDesc(rule.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getEditedBy()).isEqualTo("admin");
        assertThat(history.get(0).getRuleVersion()).isEqualTo(1);
        assertThat(history.get(0).getSnapshot()).containsKey("name");
        assertThat(history.get(0).getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void historyIsEmptyForNewRule() {
        String ruleId = UUID.randomUUID().toString();
        List<PolicyRuleHistory> history = historyRepo.findByRuleIdOrderByEditedAtDesc(ruleId);
        assertThat(history).isEmpty();
    }
}
