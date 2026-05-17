package io.emcip.moderation.service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.emcip.common.tenant.TenantContext;
import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ModerationRuleControllerTest {

    private static final UUID TEST_TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private ModerationRuleRepository repository;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        repository = mock(ModerationRuleRepository.class);
        ModerationRuleController controller = new ModerationRuleController(repository);
        WebFilter tenantFilter =
                (ServerWebExchange exchange, WebFilterChain chain) -> {
                    TenantContext.setTenantId(TEST_TENANT_ID.toString());
                    return chain.filter(exchange).doFinally(s -> TenantContext.clear());
                };
        client = WebTestClient.bindToController(controller).webFilter(tenantFilter).build();
    }

    private ModerationRule rule(Long id, String name) {
        ModerationRule r = new ModerationRule();
        r.setId(id);
        r.setName(name);
        r.setRuleType("KEYWORD");
        r.setPattern("spam");
        r.setSeverity("HIGH");
        r.setAction("FLAG");
        r.setEnabled(true);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    @Test
    void list_returnsAllRulesOrdered() {
        when(repository.findAllOrderedByTenantId(TEST_TENANT_ID))
                .thenReturn(Flux.just(rule(1L, "spam-rule")));
        client.get()
                .uri("/api/moderation-rules")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(ModerationRule.class)
                .hasSize(1);
    }

    @Test
    void create_returns201() {
        ModerationRule r = rule(null, "new-rule");
        when(repository.save(any())).thenReturn(Mono.just(rule(2L, "new-rule")));
        client.post()
                .uri("/api/moderation-rules")
                .bodyValue(r)
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void update_returns200() {
        ModerationRule existing = rule(1L, "old");
        ModerationRule update = rule(1L, "updated");
        when(repository.findByIdAndTenantId(1L, TEST_TENANT_ID)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenReturn(Mono.just(update));
        client.put()
                .uri("/api/moderation-rules/1")
                .bodyValue(update)
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void update_notFound_returns404() {
        when(repository.findByIdAndTenantId(eq(999L), eq(TEST_TENANT_ID))).thenReturn(Mono.empty());

        client.put()
                .uri("/api/moderation-rules/999")
                .bodyValue(rule(null, "irrelevant"))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void delete_returns204() {
        ModerationRule existing = rule(1L, "to-delete");
        when(repository.findByIdAndTenantId(1L, TEST_TENANT_ID)).thenReturn(Mono.just(existing));
        when(repository.deleteById(1L)).thenReturn(Mono.empty());
        client.delete().uri("/api/moderation-rules/1").exchange().expectStatus().isNoContent();
    }
}
