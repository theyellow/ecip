package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.Tenant;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface TenantRepository extends ReactiveCrudRepository<Tenant, UUID> {}
