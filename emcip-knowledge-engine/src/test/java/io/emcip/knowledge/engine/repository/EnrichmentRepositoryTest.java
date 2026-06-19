package io.emcip.knowledge.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.EnrichmentRun;
import io.emcip.knowledge.engine.entity.EnrichmentSource;
import io.emcip.knowledge.engine.entity.RunStatus;
import io.emcip.knowledge.engine.entity.TriggerType;
import io.emcip.knowledge.engine.entity.VendorApiKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class EnrichmentRepositoryTest {

    @Autowired VendorApiKeyRepository keyRepo;
    @Autowired EnrichmentSourceRepository sourceRepo;
    @Autowired EnrichmentRunRepository runRepo;

    @Test
    void vendorApiKey_tenantLookupThenGlobalFallback() {
        UUID tenantId = UUID.randomUUID();
        VendorApiKey global = new VendorApiKey();
        global.setVendorId("exa");
        global.setApiKey("global-key");
        keyRepo.save(global);

        VendorApiKey tenant = new VendorApiKey();
        tenant.setVendorId("exa");
        tenant.setTenantId(tenantId);
        tenant.setApiKey("tenant-key");
        keyRepo.save(tenant);

        Optional<VendorApiKey> found = keyRepo.findByVendorIdAndTenantId("exa", tenantId);
        assertThat(found).isPresent();
        assertThat(found.get().getApiKey()).isEqualTo("tenant-key");

        Optional<VendorApiKey> fallback = keyRepo.findByVendorIdAndTenantIdIsNull("exa");
        assertThat(fallback).isPresent();
        assertThat(fallback.get().getApiKey()).isEqualTo("global-key");
    }

    @Test
    void enrichmentSource_findEnabled() {
        // The 13 seeded rows are enabled=true globally; we just check they loaded.
        List<EnrichmentSource> all = sourceRepo.findAllByEnabledTrue();
        assertThat(all).isNotEmpty();
        assertThat(all).allMatch(s -> s.getScheduleCron() != null);
    }

    @Test
    void enrichmentRun_saveAndFindBySourceId() {
        EnrichmentSource src = sourceRepo.findAll().get(0);

        EnrichmentRun run = new EnrichmentRun();
        run.setSourceId(src.getId());
        run.setTriggerType(TriggerType.MANUAL);
        run.setStatus(RunStatus.RUNNING);
        EnrichmentRun saved = runRepo.save(run);

        List<EnrichmentRun> runs = runRepo.findBySourceIdOrderByStartedAtDesc(src.getId());
        assertThat(runs).anyMatch(r -> r.getId().equals(saved.getId()));
    }
}
