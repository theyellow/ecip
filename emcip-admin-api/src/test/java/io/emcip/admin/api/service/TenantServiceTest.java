package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.repository.TenantRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private R2dbcEntityTemplate r2dbcEntityTemplate;

    @InjectMocks private TenantService tenantService;

    @Test
    void findAll_returnsTenants() {
        Tenant t = new Tenant();
        t.setName("acme");
        when(tenantRepository.findAll()).thenReturn(Flux.just(t));

        StepVerifier.create(tenantService.findAll())
                .assertNext(tenant -> assertThat(tenant.getName()).isEqualTo("acme"))
                .verifyComplete();
    }

    @Test
    void create_assignsIdAndTimestamp() {
        Tenant input = new Tenant();
        input.setName("new-tenant");
        when(r2dbcEntityTemplate.insert(any(Tenant.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(tenantService.create(input))
                .assertNext(
                        tenant -> {
                            assertThat(tenant.getId()).isNotNull();
                            assertThat(tenant.getCreatedAt()).isNotNull();
                            assertThat(tenant.getName()).isEqualTo("new-tenant");
                        })
                .verifyComplete();
    }

    @Test
    void delete_delegatesToRepository() {
        UUID id = UUID.randomUUID();
        when(tenantRepository.deleteById(id)).thenReturn(Mono.empty());

        StepVerifier.create(tenantService.delete(id)).verifyComplete();

        verify(tenantRepository).deleteById(id);
    }
}
