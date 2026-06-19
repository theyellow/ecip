package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.repository.VendorApiKeyRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ApiKeyResolver {

    private final VendorApiKeyRepository repo;

    /**
     * Returns the API key for the given vendor, preferring a tenant-specific key over the global
     * fallback. Returns empty if no key exists.
     */
    public Optional<String> resolve(String vendorId, UUID tenantId) {
        return repo.findByVendorIdAndTenantId(vendorId, tenantId)
                .or(() -> repo.findByVendorIdAndTenantIdIsNull(vendorId))
                .map(k -> k.getApiKey());
    }
}
