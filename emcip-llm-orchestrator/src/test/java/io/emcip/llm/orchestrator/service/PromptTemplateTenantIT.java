package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.tenant.TenantContext;
import io.emcip.llm.orchestrator.TestcontainersInitializer;
import io.emcip.llm.orchestrator.entity.PromptTemplate;
import io.emcip.llm.orchestrator.repository.PromptTemplateRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * Template visibility under the Hibernate {@code tenantFilter}, exercised through the same
 * transactional service call {@code LlmCallService} uses, so {@code TenantFilterAspect} enables the
 * filter exactly as it does for a Kafka-triggered call.
 *
 * <p>PROMPT-TENANT (2026-09-24): system templates are global, but the filter matched on {@code
 * tenant_id} alone, and no template belonged to a real tenant - migration 006 back-filled random
 * ids, later seeds used the global sentinel. Every tenant-scoped lookup came back empty, so no
 * automated LLM response had ever run.
 */
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersInitializer.class)
class PromptTemplateTenantIT {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();

    @Autowired LlmOrchestratorService orchestratorService;
    @Autowired PromptTemplateRepository templateRepository;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        templateRepository.deleteAll();
        // Owned by a tenant that does not exist - what migration 006 left on the live rows.
        templateRepository.save(template("system-template", UUID.randomUUID(), true));
        templateRepository.save(template("other-tenant-template", OTHER_TENANT, false));
        templateRepository.save(template("own-tenant-template", TENANT, false));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void aSystemTemplateIsVisibleToEveryTenant() {
        TenantContext.setTenantId(TENANT.toString());

        assertThat(orchestratorService.getPromptTemplate("system-template")).isPresent();
    }

    @Test
    void aTenantsOwnTemplateIsVisibleToIt() {
        TenantContext.setTenantId(TENANT.toString());

        assertThat(orchestratorService.getPromptTemplate("own-tenant-template")).isPresent();
    }

    @Test
    void anotherTenantsNonSystemTemplateStaysInvisible() {
        TenantContext.setTenantId(TENANT.toString());

        assertThat(orchestratorService.getPromptTemplate("other-tenant-template")).isEmpty();
    }

    private static PromptTemplate template(String name, UUID tenantId, boolean system) {
        return PromptTemplate.builder()
                .name(name)
                .tenantId(tenantId)
                .system(system)
                .version("1")
                .description("test template")
                .systemPrompt("You are a test.")
                .build();
    }
}
