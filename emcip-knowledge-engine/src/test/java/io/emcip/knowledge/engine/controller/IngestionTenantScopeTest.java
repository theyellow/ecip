package io.emcip.knowledge.engine.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.IngestionJob;
import io.emcip.knowledge.engine.repository.IngestionJobRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

/** KNOW-F1: ingestion jobs — read own + global, change own only, others (and missing) are 404. */
@IntegrationTest
class IngestionTenantScopeTest {

    @Autowired DocumentIngestionController controller;
    @Autowired IngestionJobRepository jobs;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    private IngestionJob job(UUID tenant) {
        IngestionJob j = new IngestionJob();
        j.setTenantId(tenant);
        j.setSourceType(IngestionJob.SourceType.URL);
        j.setSourceRef("https://know-f1.example/" + UUID.randomUUID());
        j.setStatus(IngestionJob.IngestionStatus.COMPLETED);
        return jobs.save(j);
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(
                        e ->
                                assertThat(((ResponseStatusException) e).getStatusCode().value())
                                        .isEqualTo(404));
    }

    @Test
    void readsOwnAndGlobalButNotAnotherTenants() {
        IngestionJob own = job(tenantA);
        IngestionJob global = job(null);
        IngestionJob other = job(tenantB);

        assertThat(controller.getJob(own.getId(), tenantA).jobId())
                .isEqualTo(own.getId().toString());
        assertThat(controller.getJob(global.getId(), tenantA).jobId())
                .isEqualTo(global.getId().toString());
        assertNotFound(() -> controller.getJob(other.getId(), tenantA));
        assertNotFound(() -> controller.getJobDetails(other.getId(), tenantA));
    }

    @Test
    void deletesOnlyOwnJobs() {
        IngestionJob global = job(null);
        IngestionJob other = job(tenantB);

        assertNotFound(() -> controller.deleteJob(other.getId(), tenantA));
        assertNotFound(() -> controller.deleteJob(global.getId(), tenantA));
        assertThat(jobs.findById(other.getId())).isPresent();
        assertThat(jobs.findById(global.getId())).isPresent();
    }

    @Test
    void cannotReingestAGlobalJob() {
        IngestionJob global = job(null);
        assertNotFound(() -> controller.reingestJob(global.getId(), tenantA));
    }

    @Test
    void aMissingJobIsNotFoundNotAServerError() {
        assertNotFound(() -> controller.getJob(UUID.randomUUID(), null));
    }

    @Test
    void listIsOwnPlusGlobal() {
        IngestionJob own = job(tenantA);
        IngestionJob global = job(null);
        IngestionJob other = job(tenantB);

        var ids =
                controller.listJobs(tenantA, PageRequest.of(0, 200)).getContent().stream()
                        .map(d -> d.jobId())
                        .toList();

        assertThat(ids)
                .contains(own.getId().toString(), global.getId().toString())
                .doesNotContain(other.getId().toString());
    }
}
