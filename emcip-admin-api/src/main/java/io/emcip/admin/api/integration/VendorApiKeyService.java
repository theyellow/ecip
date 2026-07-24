package io.emcip.admin.api.integration;

import io.emcip.admin.api.integration.dto.VendorApiKeyRequest;
import io.emcip.admin.api.integration.dto.VendorApiKeyResponse;
import io.emcip.common.crypto.SecretCipher;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
@RequiredArgsConstructor
public class VendorApiKeyService {

    private final VendorApiKeyRowRepository repo;

    private final SecretCipher cipher;

    private static final String API_KEY_LOCATION = "ke_vendor_api_keys.api_key";

    private VendorApiKeyResponse toResponse(VendorApiKeyRow row) {
        return VendorApiKeyResponse.from(row, cipher.decrypt(row.getApiKey(), API_KEY_LOCATION));
    }

    public Flux<VendorApiKeyResponse> listGlobal() {
        return repo.findAllByTenantIdIsNull().map(this::toResponse);
    }

    public Flux<VendorApiKeyResponse> listByTenant(UUID tenantId) {
        return repo.findAllByTenantId(tenantId).map(this::toResponse);
    }

    public Mono<VendorApiKeyResponse> createGlobal(VendorApiKeyRequest req) {
        VendorApiKeyRow row =
                VendorApiKeyRow.builder()
                        .vendorId(req.vendorId())
                        .apiKey(cipher.encrypt(req.apiKey()))
                        .enabled(req.enabled())
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        return repo.save(row).map(this::toResponse);
    }

    public Mono<VendorApiKeyResponse> upsertForTenant(
            String vendorId, UUID tenantId, VendorApiKeyRequest req) {
        return repo.findByVendorIdAndTenantId(vendorId, tenantId)
                .flatMap(
                        existing -> {
                            existing.setApiKey(cipher.encrypt(req.apiKey()));
                            existing.setEnabled(req.enabled());
                            existing.setUpdatedAt(Instant.now());
                            return repo.save(existing);
                        })
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    VendorApiKeyRow row =
                                            VendorApiKeyRow.builder()
                                                    .vendorId(vendorId)
                                                    .tenantId(tenantId)
                                                    .apiKey(cipher.encrypt(req.apiKey()))
                                                    .enabled(req.enabled())
                                                    .createdAt(Instant.now())
                                                    .updatedAt(Instant.now())
                                                    .build();
                                    return repo.save(row);
                                }))
                .map(this::toResponse);
    }

    public Mono<VendorApiKeyResponse> update(UUID id, VendorApiKeyRequest req) {
        return repo.findById(id)
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(HttpStatus.NOT_FOUND, "Key not found")))
                .flatMap(
                        row -> {
                            row.setApiKey(cipher.encrypt(req.apiKey()));
                            row.setEnabled(req.enabled());
                            row.setUpdatedAt(Instant.now());
                            return repo.save(row);
                        })
                .map(this::toResponse);
    }

    public Mono<Void> delete(UUID id) {
        return repo.deleteById(id);
    }

    public Mono<Void> deleteByVendorAndTenant(String vendorId, UUID tenantId) {
        return repo.findByVendorIdAndTenantId(vendorId, tenantId)
                .flatMap(row -> repo.deleteById(row.getId()));
    }
}
