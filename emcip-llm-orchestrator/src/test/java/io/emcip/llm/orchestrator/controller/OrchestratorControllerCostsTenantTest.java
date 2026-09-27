package io.emcip.llm.orchestrator.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.emcip.common.tenant.TenantContext;
import io.emcip.llm.orchestrator.client.OpenAiCompatibleLlmClient;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import io.emcip.llm.orchestrator.repository.PromptTemplateRepository;
import io.emcip.llm.orchestrator.service.CostTrackingService;
import io.emcip.llm.orchestrator.service.LlmOrchestratorService;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * TENANT-AUDIT C-1: the cost endpoints pass the X-Tenant-Id header through to the queries. MockMvc
 * (not direct method calls) so the real header binding and UUID conversion run.
 */
@ExtendWith(MockitoExtension.class)
class OrchestratorControllerCostsTenantTest {

    private static final String RANGE = "?from=2026-09-01T00:00:00Z&to=2026-09-30T00:00:00Z";

    @Mock private LlmOrchestratorService orchestratorService;
    @Mock private CostTrackingService costTrackingService;
    @Mock private ModelConfigRepository modelConfigRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;
    @Mock private LlmProviderConfigService providerConfigService;
    @Mock private LlmProviderConfigRepository providerConfigRepository;
    @Mock private OpenAiCompatibleLlmClient llmClient;
    @InjectMocks private OrchestratorController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void headerTenantReachesEveryCostQuery() throws Exception {
        UUID tenant = UUID.randomUUID();
        when(costTrackingService.getTotals(any(), any(), any())).thenReturn(new LinkedHashMap<>());
        when(costTrackingService.getByModel(any(), any(), any())).thenReturn(List.of());
        when(costTrackingService.getByDay(any(), any(), any())).thenReturn(List.of());
        when(costTrackingService.getTotalCostForPeriod(any(), any(), any())).thenReturn(0.0);

        for (String path : List.of("totals", "by-model", "by-day", "summary")) {
            mvc.perform(
                            get("/api/costs/" + path + RANGE)
                                    .header(TenantContext.HEADER_NAME, tenant.toString()))
                    .andExpect(status().isOk());
        }

        verify(costTrackingService).getTotals(any(), any(), eq(tenant));
        verify(costTrackingService).getByModel(any(), any(), eq(tenant));
        verify(costTrackingService).getByDay(any(), any(), eq(tenant));
        verify(costTrackingService).getTotalCostForPeriod(any(), any(), eq(tenant));
    }

    @Test
    void noHeaderMeansAllTenants() throws Exception {
        when(costTrackingService.getTotals(any(), any(), any())).thenReturn(new LinkedHashMap<>());

        mvc.perform(get("/api/costs/totals" + RANGE)).andExpect(status().isOk());

        verify(costTrackingService).getTotals(any(), any(), isNull());
    }

    @Test
    void malformedHeaderIs400() throws Exception {
        mvc.perform(get("/api/costs/totals" + RANGE).header(TenantContext.HEADER_NAME, "nope"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(costTrackingService);
    }
}
