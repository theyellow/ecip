package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.Tenant;
import io.emcip.admin.api.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
@Tag(name = "Tenants", description = "Manage EMCIP tenants")
public class TenantController {

    private final TenantService tenantService;

    @Operation(summary = "List all tenants")
    @GetMapping
    public Flux<Tenant> listTenants() {
        return tenantService.findAll();
    }

    @Operation(summary = "Create a new tenant")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Tenant> createTenant(@Valid @RequestBody Tenant tenant) {
        return tenantService.create(tenant);
    }

    @Operation(summary = "Delete a tenant")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteTenant(@PathVariable("id") UUID id) {
        return tenantService.delete(id);
    }
}
