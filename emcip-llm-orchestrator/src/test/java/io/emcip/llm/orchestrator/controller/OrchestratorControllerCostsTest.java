package io.emcip.llm.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.client.OpenAiCompatibleLlmClient;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import io.emcip.llm.orchestrator.repository.PromptTemplateRepository;
import io.emcip.llm.orchestrator.service.CostTrackingService;
import io.emcip.llm.orchestrator.service.LlmOrchestratorService;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrchestratorControllerCostsTest {

    @Mock private LlmOrchestratorService orchestratorService;
    @Mock private CostTrackingService costTrackingService;
    @Mock private ModelConfigRepository modelConfigRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;
    @Mock private LlmProviderConfigService providerConfigService;
    @Mock private LlmProviderConfigRepository providerConfigRepository;
    @Mock private OpenAiCompatibleLlmClient llmClient;
    @InjectMocks private OrchestratorController controller;

    @Test
    void costTotals_returnsTotals() {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("totalCostUsd", 42.5);
        totals.put("totalTokens", 120000L);
        totals.put("callCount", 210L);
        totals.put("avgLatencyMs", 795.0);
        totals.put("successCount", 205L);
        totals.put("failureCount", 5L);
        when(costTrackingService.getTotals(any(), any())).thenReturn(totals);

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T23:59:59Z");

        var response = controller.costTotals(from, to);

        assertThat(response.get("totalCostUsd")).isEqualTo(42.5);
        assertThat(response.get("callCount")).isEqualTo(210L);
        assertThat(response.get("from")).isEqualTo(from.toString());
        assertThat(response.get("to")).isEqualTo(to.toString());
    }

    @Test
    void costByModel_returnsModelBreakdown() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("modelName", "qwen3-30b-a3b");
        model.put("callCount", 142L);
        when(costTrackingService.getByModel(any(), any())).thenReturn(List.of(model));

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T23:59:59Z");

        var response = controller.costByModel(from, to);

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().get("modelName")).isEqualTo("qwen3-30b-a3b");
    }

    @Test
    void costByDay_returnsDailyBreakdown() {
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", "2026-06-14");
        day.put("callCount", 47L);
        when(costTrackingService.getByDay(any(), any())).thenReturn(List.of(day));

        Instant from = Instant.parse("2026-06-14T00:00:00Z");
        Instant to = Instant.parse("2026-06-15T23:59:59Z");

        var response = controller.costByDay(from, to);

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().get("date")).isEqualTo("2026-06-14");
    }
}
