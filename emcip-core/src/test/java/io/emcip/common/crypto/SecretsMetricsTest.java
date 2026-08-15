package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.emcip.common.crypto.ColumnResult.Outcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretsMetricsTest {

    private static final SecretColumn COLUMN =
            new SecretColumn("llm_provider_configs", "api_key", "id");

    @Test
    void gaugesReflectTheLatestScanAndCanClear() {
        SecretsSelfCheck check = mock(SecretsSelfCheck.class);
        when(check.lastResults())
                .thenReturn(
                        List.of(new ColumnResult(COLUMN, Outcome.PLAINTEXT, 2, 1, List.of("a"))));

        MeterRegistry registry = new SimpleMeterRegistry();
        new SecretsMetrics(registry, check, List.of(COLUMN));

        assertThat(
                        registry.get("emcip.secrets.plaintext_count")
                                .tag("column", "llm_provider_configs.api_key")
                                .gauge()
                                .value())
                .isEqualTo(1.0);

        // The repair case: the gauge must be able to go back to zero without a restart.
        when(check.lastResults())
                .thenReturn(List.of(new ColumnResult(COLUMN, Outcome.OK, 3, 0, List.of())));

        assertThat(
                        registry.get("emcip.secrets.plaintext_count")
                                .tag("column", "llm_provider_configs.api_key")
                                .gauge()
                                .value())
                .isZero();
    }

    @Test
    void keyStatusEncodesOkMismatchAndUnverifiedDistinctly() {
        SecretsSelfCheck check = mock(SecretsSelfCheck.class);
        MeterRegistry registry = new SimpleMeterRegistry();
        new SecretsMetrics(registry, check, List.of(COLUMN));

        when(check.lastResults())
                .thenReturn(List.of(new ColumnResult(COLUMN, Outcome.OK, 3, 0, List.of())));
        assertThat(keyStatus(registry)).isZero();

        when(check.lastResults())
                .thenReturn(
                        List.of(new ColumnResult(COLUMN, Outcome.KEY_MISMATCH, 3, 0, List.of())));
        assertThat(keyStatus(registry)).isEqualTo(1.0);

        when(check.lastResults())
                .thenReturn(List.of(new ColumnResult(COLUMN, Outcome.UNVERIFIED, 0, 0, List.of())));
        assertThat(keyStatus(registry)).isEqualTo(2.0);
    }

    private double keyStatus(MeterRegistry registry) {
        return registry.get("emcip.secrets.key_status")
                .tag("column", "llm_provider_configs.api_key")
                .gauge()
                .value();
    }
}
