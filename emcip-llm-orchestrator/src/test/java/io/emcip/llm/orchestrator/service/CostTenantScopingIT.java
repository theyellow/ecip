package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.tenant.TenantContext;
import io.emcip.llm.orchestrator.TestcontainersInitializer;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.repository.ModelCostLogRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * TENANT-AUDIT C-1: a tenant's cost views aggregate only its own cost logs — including the native
 * by-day query, which no Hibernate filter would ever reach. No tenant = all tenants (trusted
 * caller, ADR-009 rule 5).
 */
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersInitializer.class)
class CostTenantScopingIT {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Autowired CostTrackingService service;
    @Autowired ModelCostLogRepository repository;

    private Instant from;
    private Instant to;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        repository.deleteAll();
        logCallFor(A, "req-a");
        logCallFor(B, "req-b1");
        logCallFor(B, "req-b2");
        from = Instant.now().minus(1, ChronoUnit.HOURS);
        to = Instant.now().plus(1, ChronoUnit.HOURS);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void totalsCountOnlyTheTenantsCalls() {
        assertThat(service.getTotals(from, to, A)).containsEntry("callCount", 1L);
        assertThat(service.getTotals(from, to, null)).containsEntry("callCount", 3L);
    }

    @Test
    void summaryCostsOnlyTheTenantsCalls() {
        double own = service.getTotalCostForPeriod(from, to, A);
        double all = service.getTotalCostForPeriod(from, to, null);

        assertThat(own).isPositive();
        assertThat(all).isCloseTo(3 * own, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void byModelCountsOnlyTheTenantsCalls() {
        List<Map<String, Object>> rows = service.getByModel(from, to, A);

        assertThat(rows)
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("callCount", 1L));
    }

    @Test
    void byDayNativeQueryCountsOnlyTheTenantsCalls() {
        // Summed over days: a midnight between seeding and the query window may split the rows.
        assertThat(callCount(service.getByDay(from, to, A))).isEqualTo(1L);
        assertThat(callCount(service.getByDay(from, to, null))).isEqualTo(3L);
    }

    private static long callCount(List<Map<String, Object>> rows) {
        return rows.stream().mapToLong(r -> (Long) r.get("callCount")).sum();
    }

    private void logCallFor(UUID tenant, String requestId) {
        TenantContext.setTenantId(tenant.toString());
        try {
            service.logSuccessfulCall(
                    requestId, model(), "template-a", 1000, 1000, 100L, "e", null);
        } finally {
            TenantContext.clear();
        }
    }

    private static ModelConfig model() {
        ModelConfig model = new ModelConfig();
        model.setModelKey("model-a");
        model.setProvider("local-litellm");
        model.setModelName("model-a");
        model.setInputCostPer1kTokens(0.01);
        model.setOutputCostPer1kTokens(0.02);
        return model;
    }
}
