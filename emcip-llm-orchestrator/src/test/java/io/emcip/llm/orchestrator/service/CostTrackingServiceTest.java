package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.entity.ModelCostLog;
import io.emcip.llm.orchestrator.repository.ModelCostLogRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CostTrackingServiceTest {

    @Mock private ModelCostLogRepository costLogRepository;

    @InjectMocks private CostTrackingService service;

    private ModelConfig modelConfig(double inputCostPer1k, double outputCostPer1k) {
        return ModelConfig.builder()
                .id(UUID.randomUUID())
                .modelKey("claude-haiku")
                .provider("anthropic")
                .modelName("claude-haiku-4-5-20251001")
                .description("Test model")
                .taskType("CLASSIFICATION")
                .inputCostPer1kTokens(inputCostPer1k)
                .outputCostPer1kTokens(outputCostPer1k)
                .contextWindow(200000)
                .maxOutputTokens(4096)
                .avgLatencyMs(500.0)
                .active(true)
                .build();
    }

    // --- logSuccessfulCall tests ---

    @Test
    void logSuccessfulCall_calculatesInputCostCorrectly() {
        ModelConfig model = modelConfig(1.0, 0.0);
        when(costLogRepository.save(any(ModelCostLog.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelCostLog result =
                service.logSuccessfulCall("req-1", model, "template", 1000, 0, 100L, "evt-1", null);

        assertThat(result.getInputCostUsd()).isEqualTo(1.0);
        assertThat(result.getOutputCostUsd()).isEqualTo(0.0);
        assertThat(result.getTotalCostUsd()).isEqualTo(1.0);
    }

    @Test
    void logSuccessfulCall_calculatesOutputCostCorrectly() {
        ModelConfig model = modelConfig(0.0, 4.0);
        when(costLogRepository.save(any(ModelCostLog.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelCostLog result =
                service.logSuccessfulCall("req-2", model, "template", 0, 500, 100L, "evt-2", null);

        assertThat(result.getOutputCostUsd()).isEqualTo(2.0);
        assertThat(result.getTotalCostUsd()).isEqualTo(2.0);
    }

    @Test
    void logSuccessfulCall_sumsTotalTokens() {
        ModelConfig model = modelConfig(1.0, 2.0);
        when(costLogRepository.save(any(ModelCostLog.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelCostLog result =
                service.logSuccessfulCall(
                        "req-3", model, "template", 300, 200, 100L, "evt-3", null);

        assertThat(result.getTotalTokens()).isEqualTo(500);
    }

    @Test
    void logSuccessfulCall_setsStatusSuccess() {
        ModelConfig model = modelConfig(1.0, 2.0);
        when(costLogRepository.save(any(ModelCostLog.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelCostLog result =
                service.logSuccessfulCall(
                        "req-4", model, "template", 100, 100, 100L, "evt-4", null);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void logSuccessfulCall_setsAllFields() {
        ModelConfig model = modelConfig(1.0, 2.0);
        when(costLogRepository.save(any(ModelCostLog.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelCostLog result =
                service.logSuccessfulCall(
                        "req-1", model, "template", 100, 100, 300L, "evt-1", "conv-1");

        assertThat(result.getRequestId()).isEqualTo("req-1");
        assertThat(result.getSourceEventId()).isEqualTo("evt-1");
        assertThat(result.getConversationId()).isEqualTo("conv-1");
        assertThat(result.getLatencyMs()).isEqualTo(300L);
        assertThat(result.getModelProvider()).isEqualTo(model.getProvider());
        assertThat(result.getModelName()).isEqualTo(model.getModelName());
    }

    // --- logFailedCall tests ---

    @Test
    void logFailedCall_setsStatusFailed() {
        ModelConfig model = modelConfig(1.0, 2.0);
        when(costLogRepository.save(any(ModelCostLog.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelCostLog result =
                service.logFailedCall("req-5", model, "template", "error", "evt-5", null);

        assertThat(result.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void logFailedCall_zerosAllCostsAndTokens() {
        ModelConfig model = modelConfig(1.0, 2.0);
        when(costLogRepository.save(any(ModelCostLog.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelCostLog result =
                service.logFailedCall("req-6", model, "template", "error", "evt-6", null);

        assertThat(result.getInputTokens()).isEqualTo(0);
        assertThat(result.getOutputTokens()).isEqualTo(0);
        assertThat(result.getTotalCostUsd()).isEqualTo(0.0);
    }

    @Test
    void logFailedCall_setsErrorMessage() {
        ModelConfig model = modelConfig(1.0, 2.0);
        when(costLogRepository.save(any(ModelCostLog.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelCostLog result =
                service.logFailedCall("req-7", model, "template", "API timeout", "evt-7", null);

        assertThat(result.getErrorMessage()).isEqualTo("API timeout");
    }

    // --- getTotalCostForPeriod tests ---

    @Test
    void getTotalCostForPeriod_repositoryReturnsValue_returnsValue() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-01-31T23:59:59Z");
        when(costLogRepository.calculateTotalCostForPeriod(start, end)).thenReturn(42.5);

        Double result = service.getTotalCostForPeriod(start, end);

        assertThat(result).isEqualTo(42.5);
    }

    @Test
    void getTotalCostForPeriod_repositoryReturnsNull_returnsZero() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-01-31T23:59:59Z");
        when(costLogRepository.calculateTotalCostForPeriod(start, end)).thenReturn(null);

        Double result = service.getTotalCostForPeriod(start, end);

        assertThat(result).isEqualTo(0.0);
    }

    // --- estimateCost tests ---

    @Test
    void estimateCost_calculatesCorrectly() {
        ModelConfig model = modelConfig(1.0, 2.0);

        double result = service.estimateCost(model, 500, 1000);

        assertThat(result).isCloseTo(2.5, within(0.0001));
    }
}
