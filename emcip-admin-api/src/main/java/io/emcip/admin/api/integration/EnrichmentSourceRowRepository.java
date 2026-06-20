package io.emcip.admin.api.integration;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface EnrichmentSourceRowRepository
        extends ReactiveCrudRepository<EnrichmentSourceRow, UUID> {

    Flux<EnrichmentSourceRow> findAllByTenantIdIsNull();

    Flux<EnrichmentSourceRow> findAllByTenantId(UUID tenantId);
}
