package io.emcip.policy.engine.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.tenant.TenantContext;
import io.emcip.policy.engine.IntegrationTest;
import io.emcip.policy.engine.config.TenantWebFilter;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.entity.PolicyRuleHistory;
import io.emcip.policy.engine.repository.PolicyDecisionRepository;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import io.emcip.policy.engine.repository.PolicyRuleHistoryRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * TENANT-AUDIT P-1 / F-2: with a tenant asserted, policy-engine must never list, change or reveal
 * another tenant's rules or decisions. Goes through the real {@link TenantWebFilter} and the real
 * controllers against PostgreSQL — the header-to-query path is what was broken, so mocking the
 * repositories would test nothing. Fresh tenants per test keep it independent of other data in the
 * shared database. Ids are asserted on the raw JSON: {@code PolicyRuleConfig.id} is write-only for
 * clients ({@code READ_ONLY}), so decoding into the entity would drop it.
 */
@IntegrationTest
class PolicyTenantScopingIT {

    @Autowired PolicyRuleController ruleController;
    @Autowired PolicyDecisionController decisionController;
    @Autowired PolicyRuleConfigRepository ruleRepository;
    @Autowired PolicyRuleHistoryRepository historyRepository;
    @Autowired PolicyDecisionRepository decisionRepository;

    UUID a;
    UUID b;
    WebTestClient client;
    String ruleOfA;
    String ruleOfB;

    @BeforeEach
    void setUp() {
        a = UUID.randomUUID();
        b = UUID.randomUUID();
        client =
                WebTestClient.bindToController(ruleController, decisionController)
                        .webFilter(new TenantWebFilter())
                        .build();
        ruleOfA = saveRule(a, "rule-a").getId();
        ruleOfB = saveRule(b, "rule-b").getId();
        saveHistory(ruleOfB, b);
    }

    @Test
    void listShowsOnlyTheCallersRules() {
        client.get()
                .uri("/api/policy-rules")
                .header(TenantContext.HEADER_NAME, a.toString())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[*].id")
                .value(ids -> assertThat(ids).asList().containsExactly(ruleOfA));
    }

    @Test
    void listWithoutATenantIsUnchanged() {
        client.get()
                .uri("/api/policy-rules")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[*].id")
                .value(ids -> assertThat(ids).asList().contains(ruleOfA, ruleOfB));
    }

    @Test
    void updatingAnotherTenantsRuleIs404AndLeavesItUnchanged() {
        client.put()
                .uri("/api/policy-rules/{id}", ruleOfB)
                .header(TenantContext.HEADER_NAME, a.toString())
                .bodyValue(Map.of("name", "hijacked", "action", "ALLOW", "priority", 1))
                .exchange()
                .expectStatus()
                .isNotFound();

        assertThat(ruleRepository.findById(ruleOfB))
                .get()
                .extracting(PolicyRuleConfig::getName)
                .isEqualTo("rule-b");
        assertThat(historyRepository.findByRuleIdOrderByEditedAtDesc(ruleOfB)).hasSize(1);
    }

    @Test
    void updatingOwnRuleWorks() {
        client.put()
                .uri("/api/policy-rules/{id}", ruleOfA)
                .header(TenantContext.HEADER_NAME, a.toString())
                .bodyValue(Map.of("name", "renamed", "action", "FLAG", "priority", 1))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(ruleRepository.findById(ruleOfA))
                .get()
                .extracting(PolicyRuleConfig::getName)
                .isEqualTo("renamed");
    }

    @Test
    void deletingAnotherTenantsRuleIs404AndKeepsIt() {
        client.delete()
                .uri("/api/policy-rules/{id}", ruleOfB)
                .header(TenantContext.HEADER_NAME, a.toString())
                .exchange()
                .expectStatus()
                .isNotFound();

        assertThat(ruleRepository.findById(ruleOfB)).isPresent();
    }

    @Test
    void deletingOwnRuleWorks() {
        client.delete()
                .uri("/api/policy-rules/{id}", ruleOfA)
                .header(TenantContext.HEADER_NAME, a.toString())
                .exchange()
                .expectStatus()
                .isNoContent();

        assertThat(ruleRepository.findById(ruleOfA)).isEmpty();
    }

    @Test
    void historyOfAnotherTenantsRuleIs404() {
        client.get()
                .uri("/api/policy-rules/{id}/history", ruleOfB)
                .header(TenantContext.HEADER_NAME, a.toString())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void historyOfOwnRuleWorks() {
        saveHistory(ruleOfA, a);

        client.get()
                .uri("/api/policy-rules/{id}/history", ruleOfA)
                .header(TenantContext.HEADER_NAME, a.toString())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(PolicyRuleHistory.class)
                .hasSize(1);
    }

    private PolicyRuleConfig saveRule(UUID tenant, String name) {
        PolicyRuleConfig rule = new PolicyRuleConfig();
        rule.setId(UUID.randomUUID().toString());
        rule.setTenantId(tenant);
        rule.setName(name);
        rule.setTargetIntent("*");
        rule.setMinConfidence(0.5);
        rule.setAction("FLAG");
        rule.setPriority(10);
        rule.setActive(true);
        rule.setRuleVersion(1);
        return ruleRepository.save(rule);
    }

    private void saveHistory(String ruleId, UUID tenant) {
        PolicyRuleHistory snap = new PolicyRuleHistory();
        snap.setId(UUID.randomUUID());
        snap.setRuleId(ruleId);
        snap.setTenantId(tenant);
        snap.setSnapshot(Map.of("name", "old"));
        snap.setEditedBy("seed");
        snap.setEditedAt(Instant.now());
        snap.setRuleVersion(1);
        historyRepository.save(snap);
    }
}
