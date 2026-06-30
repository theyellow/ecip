package io.emcip.policy.engine.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import io.emcip.policy.engine.entity.PolicyDecision;
import io.emcip.policy.engine.repository.PolicyDecisionRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.reactive.server.WebTestClient;

@ExtendWith(MockitoExtension.class)
class PolicyDecisionControllerTest {

    @Mock private PolicyDecisionRepository repository;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new PolicyDecisionController(repository)).build();
    }

    private PolicyDecision decision(String id) {
        PolicyDecision d = new PolicyDecision();
        d.setId(id);
        return d;
    }

    @Test
    void list_returnsPageResponse() {
        when(repository.findByFilters(
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(decision("id-1")), Pageable.ofSize(50), 1L));

        client.get()
                .uri("/api/policy-decisions")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1)
                .jsonPath("$.items[0].id")
                .isEqualTo("id-1");
    }

    @Test
    void list_sizeCapAt200() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(repository.findByFilters(
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(200), 0L));

        client.get().uri("/api/policy-decisions?size=999").exchange().expectStatus().isOk();

        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    void list_filteredByDecision() {
        PolicyDecision d = decision("id-2");
        d.setDecision("FLAG");
        when(repository.findByFilters(
                        isNull(),
                        eq("FLAG"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(d), Pageable.ofSize(50), 1L));

        client.get()
                .uri("/api/policy-decisions?decision=FLAG")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items[0].decision")
                .isEqualTo("FLAG");
    }

    @Test
    void list_blankDecisionTreatedAsNoFilter() {
        when(repository.findByFilters(
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(decision("id-blank")), Pageable.ofSize(50), 1L));

        client.get()
                .uri("/api/policy-decisions?decision=   ")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1);
    }

    @Test
    void list_filteredByIntent() {
        PolicyDecision d = decision("id-3");
        d.setOriginalIntent("SPAM");
        when(repository.findByFilters(
                        isNull(),
                        isNull(),
                        eq("SPAM"),
                        isNull(),
                        isNull(),
                        isNull(),
                        any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(d), Pageable.ofSize(50), 1L));

        client.get()
                .uri("/api/policy-decisions?intent=SPAM")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items[0].originalIntent")
                .isEqualTo("SPAM");
    }

    @Test
    void list_filteredByTimeRange() {
        when(repository.findByFilters(
                        isNull(),
                        isNull(),
                        isNull(),
                        any(Instant.class),
                        any(Instant.class),
                        isNull(),
                        any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(decision("id-4")), Pageable.ofSize(50), 1L));

        client.get()
                .uri("/api/policy-decisions?from=2026-01-01T00:00:00Z&to=2026-06-01T00:00:00Z")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1);
    }

    @Test
    void list_filteredByMinConfidence() {
        when(repository.findByFilters(
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(0.8),
                        any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(decision("id-5")), Pageable.ofSize(50), 1L));

        client.get()
                .uri("/api/policy-decisions?minConfidence=0.8")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1);
    }
}
