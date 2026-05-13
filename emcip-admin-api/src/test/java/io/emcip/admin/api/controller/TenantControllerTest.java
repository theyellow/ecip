package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.repository.TenantRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TenantControllerTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private R2dbcEntityTemplate r2dbcEntityTemplate;

    private TenantController controller;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        controller = new TenantController(tenantRepository, r2dbcEntityTemplate);
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    private Tenant tenant(UUID id) {
        return Tenant.builder().id(id).name("Acme Corp").build();
    }

    @Test
    void listTenants_returns200() {
        UUID id = UUID.randomUUID();
        when(tenantRepository.findAll()).thenReturn(Flux.just(tenant(id)));
        webTestClient
                .get()
                .uri("/api/tenants")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(Tenant.class)
                .hasSize(1);
    }

    @Test
    void createTenant_setsIdAndCreatedAt() {
        when(r2dbcEntityTemplate.insert(any(Tenant.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        Tenant request = Tenant.builder().name("New Corp").build();

        StepVerifier.create(controller.createTenant(request))
                .assertNext(
                        saved -> {
                            assertThat(saved.getId()).isNotNull();
                            assertThat(saved.getCreatedAt()).isNotNull();
                            assertThat(saved.getName()).isEqualTo("New Corp");
                        })
                .verifyComplete();
    }

    @Test
    void createTenant_returns201() {
        when(r2dbcEntityTemplate.insert(any(Tenant.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        Tenant request = Tenant.builder().name("New Corp").build();
        webTestClient
                .post()
                .uri("/api/tenants")
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void deleteTenant_returns204() {
        UUID id = UUID.randomUUID();
        when(tenantRepository.deleteById(id)).thenReturn(Mono.empty());
        webTestClient.delete().uri("/api/tenants/" + id).exchange().expectStatus().isNoContent();
    }
}
