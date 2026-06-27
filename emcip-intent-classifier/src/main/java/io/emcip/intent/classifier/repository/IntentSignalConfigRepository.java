package io.emcip.intent.classifier.repository;

import io.emcip.intent.classifier.entity.IntentSignalConfig;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntentSignalConfigRepository extends JpaRepository<IntentSignalConfig, String> {
    Optional<IntentSignalConfig> findByTenantId(UUID tenantId);

    Optional<IntentSignalConfig> findByTenantIdIsNull();
}
