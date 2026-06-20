package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.VendorApiKey;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorApiKeyRepository extends JpaRepository<VendorApiKey, UUID> {

    Optional<VendorApiKey> findByVendorIdAndTenantId(String vendorId, UUID tenantId);

    Optional<VendorApiKey> findByVendorIdAndTenantIdIsNull(String vendorId);
}
