package io.emcip.policy.engine.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.emcip.policy.engine.entity.PolicyDecision;
import io.emcip.policy.engine.repository.PolicyDecisionRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Test
    void list_returnsPageResponse() {
        PolicyDecision d = new PolicyDecision();
        d.setId("id-1");
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(d), Pageable.ofSize(50), 1L));

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
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(200), 0L));

        client.get().uri("/api/policy-decisions?size=999").exchange().expectStatus().isOk();
    }

    @Test
    void list_filteredByDecision() {
        PolicyDecision d = new PolicyDecision();
        d.setId("id-2");
        d.setDecision("FLAG");
        when(repository.findByDecision(eq("FLAG"), any(Pageable.class)))
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
}
