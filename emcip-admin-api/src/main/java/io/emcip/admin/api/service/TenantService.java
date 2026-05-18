package io.emcip.admin.api.service;

import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.repository.TenantRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcEntityTemplate;

    public Flux<Tenant> findAll() {
        return tenantRepository.findAll();
    }

    public Mono<Tenant> create(Tenant tenant) {
        tenant.setId(UUID.randomUUID());
        tenant.setCreatedAt(Instant.now());
        return r2dbcEntityTemplate.insert(tenant);
    }

    public Mono<Void> delete(UUID id) {
        return tenantRepository.deleteById(id);
    }
}
