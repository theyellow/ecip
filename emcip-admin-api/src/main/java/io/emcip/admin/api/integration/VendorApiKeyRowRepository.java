package io.emcip.admin.api.integration;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface VendorApiKeyRowRepository extends ReactiveCrudRepository<VendorApiKeyRow, UUID> {

    Flux<VendorApiKeyRow> findAllByTenantIdIsNull();

    Flux<VendorApiKeyRow> findAllByTenantId(UUID tenantId);

    Mono<VendorApiKeyRow> findByVendorIdAndTenantIdIsNull(String vendorId);

    Mono<VendorApiKeyRow> findByVendorIdAndTenantId(String vendorId, UUID tenantId);
}
