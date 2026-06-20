package io.emcip.admin.api.integration;

import io.emcip.admin.api.integration.dto.VendorApiKeyRequest;
import io.emcip.admin.api.integration.dto.VendorApiKeyResponse;
import io.emcip.common.tenant.ReactorTenantContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/tenant/integrations/keys")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('INTEGRATIONS_TENANT_MANAGE')")
@Tag(name = "Integrations — Tenant Keys", description = "Manage own tenant vendor API keys")
public class TenantApiKeyController {

    private final VendorApiKeyService service;

    @GetMapping
    public Flux<VendorApiKeyResponse> listOwn() {
        return Mono.deferContextual(
                        ctx -> Mono.just(UUID.fromString(ReactorTenantContext.getTenantId(ctx))))
                .flatMapMany(service::listByTenant);
    }

    @PutMapping("/{vendorId}")
    public Mono<VendorApiKeyResponse> upsert(
            @PathVariable String vendorId, @Valid @RequestBody VendorApiKeyRequest req) {
        return Mono.deferContextual(
                        ctx -> Mono.just(UUID.fromString(ReactorTenantContext.getTenantId(ctx))))
                .flatMap(tenantId -> service.upsertForTenant(vendorId, tenantId, req));
    }

    @DeleteMapping("/{vendorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String vendorId) {
        return Mono.deferContextual(
                        ctx -> Mono.just(UUID.fromString(ReactorTenantContext.getTenantId(ctx))))
                .flatMap(tenantId -> service.deleteByVendorAndTenant(vendorId, tenantId));
    }
}
