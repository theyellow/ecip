package io.emcip.admin.api.service;

import io.emcip.admin.api.dto.TenantUpdateRequest;
import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.repository.TenantRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
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

    public Mono<Tenant> update(UUID id, TenantUpdateRequest req) {
        return tenantRepository
                .findById(id)
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Tenant not found: " + id)))
                .flatMap(
                        existing -> {
                            existing.setDescription(req.getDescription());
                            existing.setLlmModelOverride(req.getLlmModelOverride());
                            return r2dbcEntityTemplate.update(existing);
                        });
    }

    public Mono<Void> delete(UUID id) {
        return tenantRepository.deleteById(id);
    }
}
