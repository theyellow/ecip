package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.tenant.TenantAwareKafkaSupport;
import io.emcip.common.tenant.TenantContext;
import io.emcip.llm.orchestrator.TestcontainersInitializer;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.repository.ModelCostLogRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * Persists cost logs through real JPA and PostgreSQL.
 *
 * <p>{@link CostTrackingServiceTest} mocks the repository and so cannot see whether Hibernate
 * accepts the entity. It did not: the service assigned the {@code @GeneratedValue} id by hand,
 * which Hibernate 7 rejects, so no cost log was ever written ({@code model_cost_logs} was empty on
 * the live cluster on 2026-09-24) — the same defect as in {@link ModelConfigSyncServiceIT}. It also
 * never set {@code tenant_id}, which is {@code NOT NULL}: the row belongs to the tenant bound by
 * the Kafka consumer that triggered the call (COST-LOG).
 */
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersInitializer.class)
class CostTrackingServiceIT {

    @Autowired CostTrackingService costTrackingService;
    @Autowired ModelCostLogRepository costLogRepository;

    private static final UUID TENANT = UUID.randomUUID();

    @BeforeEach
    void clean() {
        TenantContext.clear();
        costLogRepository.deleteAll();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void persistsASuccessfulCallForTheBoundTenant() {
        TenantContext.setTenantId(TENANT.toString());
        costTrackingService.logSuccessfulCall(
                "req-1", model(), "template-a", 100, 50, 1200L, "event-1", "conv-1");

        assertThat(costLogRepository.findAll())
                .singleElement()
                .satisfies(
                        log -> {
                            assertThat(log.getRequestId()).isEqualTo("req-1");
                            assertThat(log.getStatus()).isEqualTo("SUCCESS");
                            assertThat(log.getTotalTokens()).isEqualTo(150);
                            assertThat(log.getTenantId()).isEqualTo(TENANT);
                        });
    }

    @Test
    void persistsAFailedCallForTheBoundTenant() {
        TenantContext.setTenantId(TENANT.toString());
        costTrackingService.logFailedCall(
                "req-2", model(), "template-a", "proxy timed out", "event-2", "conv-2");

        assertThat(costLogRepository.findAll())
                .singleElement()
                .satisfies(
                        log -> {
                            assertThat(log.getStatus()).isEqualTo("FAILED");
                            assertThat(log.getTenantId()).isEqualTo(TENANT);
                        });
    }

    @Test
    void fallsBackToTheGlobalSentinelWhenNoTenantIsBound() {
        costTrackingService.logSuccessfulCall(
                "req-3", model(), "template-a", 1, 1, 10L, "event-3", null);

        assertThat(costLogRepository.findAll())
                .singleElement()
                .satisfies(
                        log ->
                                assertThat(log.getTenantId())
                                        .isEqualTo(TenantAwareKafkaSupport.GLOBAL_TENANT_SENTINEL));
    }

    private static ModelConfig model() {
        ModelConfig model = new ModelConfig();
        model.setModelKey("model-a");
        model.setProvider("local-litellm");
        model.setModelName("model-a");
        model.setInputCostPer1kTokens(0.0);
        model.setOutputCostPer1kTokens(0.0);
        return model;
    }
}
