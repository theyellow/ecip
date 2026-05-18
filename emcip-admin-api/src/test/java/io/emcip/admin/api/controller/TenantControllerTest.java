package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.service.TenantService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class TenantControllerTest {

    @Mock private TenantService tenantService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(new TenantController(tenantService)).build();
    }

    private Tenant tenant(String name) {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setName(name);
        t.setCreatedAt(Instant.now());
        return t;
    }

    @Test
    void listTenants_returns200() {
        when(tenantService.findAll()).thenReturn(Flux.just(tenant("acme"), tenant("beta")));

        webTestClient.get().uri("/api/tenants").exchange().expectStatus().isOk();
    }

    @Test
    void createTenant_returns201() {
        when(tenantService.create(any())).thenReturn(Mono.just(tenant("new")));

        webTestClient
                .post()
                .uri("/api/tenants")
                .bodyValue(new Tenant())
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void deleteTenant_returns204() {
        when(tenantService.delete(any())).thenReturn(Mono.empty());

        webTestClient
                .delete()
                .uri("/api/tenants/" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNoContent();
    }
}
