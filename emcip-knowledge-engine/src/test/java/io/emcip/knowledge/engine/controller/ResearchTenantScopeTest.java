package io.emcip.knowledge.engine.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.ReportTemplate;
import io.emcip.knowledge.engine.entity.ResearchReport;
import io.emcip.knowledge.engine.entity.ResearchSession;
import io.emcip.knowledge.engine.entity.ResearchStatus;
import io.emcip.knowledge.engine.repository.ResearchReportRepository;
import io.emcip.knowledge.engine.repository.ResearchSessionRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/** KNOW-F1: research sessions are readable and changeable only by their own tenant. */
@IntegrationTest
class ResearchTenantScopeTest {

    @Autowired ResearchController controller;
    @Autowired ResearchSessionRepository sessions;
    @Autowired ResearchReportRepository reports;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    private ResearchSession session(UUID tenant, ResearchStatus status) {
        ResearchSession s = new ResearchSession();
        s.setTenantId(tenant);
        s.setQuestion("know-f1 probe");
        s.setStatus(status);
        return sessions.save(s);
    }

    @Test
    void anotherTenantsSessionIsNotFound() {
        ResearchSession own = session(tenantA, ResearchStatus.CREATED);
        ResearchSession other = session(tenantB, ResearchStatus.CREATED);

        assertThat(controller.getSession(own.getId(), tenantA).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(controller.getSession(other.getId(), tenantA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // no tenant asserted = trusted caller, unchanged
        assertThat(controller.getSession(other.getId(), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void anotherTenantCannotPauseASession() {
        ResearchSession other = session(tenantB, ResearchStatus.RUNNING);

        assertThat(controller.pauseSession(other.getId(), tenantA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(sessions.findById(other.getId()).orElseThrow().getStatus())
                .isEqualTo(ResearchStatus.RUNNING);
    }

    @Test
    void anotherTenantsReportIsNotFound() {
        ResearchSession other = session(tenantB, ResearchStatus.COMPLETED);
        ResearchReport r = new ResearchReport();
        r.setTenantId(tenantB);
        r.setSession(other);
        r.setTemplate(ReportTemplate.TOPIC);
        r.setTitle("t");
        r.setContent("secret findings");
        reports.save(r);

        assertThat(controller.getReport(other.getId(), tenantB).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(controller.getReport(other.getId(), tenantA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.getReportMarkdown(other.getId(), tenantA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
