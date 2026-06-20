package io.emcip.admin.api.integration;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface EnrichmentRunRowRepository extends ReactiveCrudRepository<EnrichmentRunRow, UUID> {

    Flux<EnrichmentRunRow> findBySourceIdOrderByStartedAtDesc(UUID sourceId, Pageable pageable);
}
