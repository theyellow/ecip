package io.emcip.policy.engine.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

class PolicyRuleControllerTest {

    private PolicyRuleConfigRepository repository;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        repository = mock(PolicyRuleConfigRepository.class);
        PolicyRuleController controller = new PolicyRuleController(repository);
        client = WebTestClient.bindToController(controller).build();
    }

    private PolicyRuleConfig rule(String id, String name) {
        PolicyRuleConfig r = new PolicyRuleConfig();
        r.setId(id);
        r.setName(name);
        r.setTargetIntent("SPAM");
        r.setAction("BLOCK");
        r.setMinConfidence(0.8);
        r.setPriority(10);
        r.setActive(true);
        r.setRuleVersion(1);
        r.setCreatedAt(Instant.now());
        return r;
    }

    @Test
    void listActive_returnsActiveRules() {
        when(repository.findByActiveTrueOrderByPriorityAsc())
                .thenReturn(List.of(rule("r1", "spam-rule")));
        client.get()
                .uri("/api/policy-rules")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(PolicyRuleConfig.class)
                .hasSize(1);
    }

    @Test
    void create_returns201() {
        PolicyRuleConfig r = rule(null, "new-rule");
        when(repository.save(any())).thenReturn(rule("r2", "new-rule"));
        client.post().uri("/api/policy-rules").bodyValue(r).exchange().expectStatus().isCreated();
    }

    @Test
    void update_returns200() {
        PolicyRuleConfig existing = rule("r1", "old");
        PolicyRuleConfig update = rule("r1", "updated");
        when(repository.findById("r1")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenReturn(update);
        client.put().uri("/api/policy-rules/r1").bodyValue(update).exchange().expectStatus().isOk();
    }

    @Test
    void update_notFound_returns404() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        client.put()
                .uri("/api/policy-rules/" + UUID.randomUUID())
                .bodyValue(rule(null, "ghost-rule"))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void delete_returns204() {
        client.delete().uri("/api/policy-rules/r1").exchange().expectStatus().isNoContent();
    }

    @Test
    void history_returnsRulesByName() {
        when(repository.findAll()).thenReturn(List.of(rule("r1", "spam-rule")));
        client.get().uri("/api/policy-rules/history/spam-rule").exchange().expectStatus().isOk();
    }
}
