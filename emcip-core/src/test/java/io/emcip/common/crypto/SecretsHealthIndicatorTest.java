package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.emcip.common.crypto.ColumnResult.Outcome;
import io.emcip.common.crypto.SecretsSelfCheckProperties.SelfCheckMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class SecretsHealthIndicatorTest {

    private static final SecretColumn COLUMN =
            new SecretColumn("telegram_accounts", "api_hash", "id");

    private SecretsSelfCheck selfCheckWith(ColumnResult result) {
        SecretsSelfCheck check = mock(SecretsSelfCheck.class);
        when(check.lastResults()).thenReturn(List.of(result));
        when(check.mode()).thenReturn(SelfCheckMode.WARN);
        return check;
    }

    /**
     * Health indicators in this project feed the Kubernetes readiness probe. Reporting DOWN here
     * would pull the pod out of rotation and make the Admin UI repair path unreachable - the exact
     * deadlock the warn default exists to prevent. This indicator reports, it does not gate.
     */
    @Test
    void staysUpEvenWhenPlaintextIsFound() {
        Health health =
                new SecretsHealthIndicator(
                                selfCheckWith(
                                        new ColumnResult(
                                                COLUMN,
                                                Outcome.PLAINTEXT,
                                                11,
                                                1,
                                                List.of("11111111-1111-1111-1111-111111111111"))))
                        .health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void staysUpOnKeyMismatchToo() {
        Health health =
                new SecretsHealthIndicator(
                                selfCheckWith(
                                        new ColumnResult(
                                                COLUMN, Outcome.KEY_MISMATCH, 3, 0, List.of())))
                        .health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsModeCountsAndPerColumnOutcome() {
        Health health =
                new SecretsHealthIndicator(
                                selfCheckWith(
                                        new ColumnResult(
                                                COLUMN,
                                                Outcome.PLAINTEXT,
                                                11,
                                                1,
                                                List.of("11111111-1111-1111-1111-111111111111"))))
                        .health();

        assertThat(health.getDetails()).containsEntry("mode", "warn");
        assertThat(health.getDetails()).containsEntry("plaintextCount", 1L);

        @SuppressWarnings("unchecked")
        Map<String, Object> columns = (Map<String, Object>) health.getDetails().get("columns");
        assertThat(columns).containsKey("telegram_accounts.api_hash");
    }

    /** Primary keys are operational breadcrumbs; a secret value must never reach an endpoint. */
    @Test
    void detailsNeverCarrySecretValues() {
        Health health =
                new SecretsHealthIndicator(
                                selfCheckWith(
                                        new ColumnResult(COLUMN, Outcome.OK, 11, 0, List.of())))
                        .health();

        assertThat(health.getDetails().toString()).doesNotContain("v1:");
    }
}
