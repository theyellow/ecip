package io.emcip.admin.api.integration;

import io.emcip.admin.api.integration.dto.VendorApiKeyRequest;
import io.emcip.admin.api.integration.dto.VendorApiKeyResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/admin/integrations/keys")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('INTEGRATIONS_GLOBAL_MANAGE')")
@Tag(name = "Integrations — Global Keys", description = "Manage global vendor API keys")
public class VendorApiKeyController {

    private final VendorApiKeyService service;

    @GetMapping
    public Flux<VendorApiKeyResponse> list(@RequestParam(required = false) UUID tenantId) {
        return tenantId != null ? service.listByTenant(tenantId) : service.listGlobal();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<VendorApiKeyResponse> create(@Valid @RequestBody VendorApiKeyRequest req) {
        return service.createGlobal(req);
    }

    @PutMapping("/{id}")
    public Mono<VendorApiKeyResponse> update(
            @PathVariable UUID id, @Valid @RequestBody VendorApiKeyRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }
}
