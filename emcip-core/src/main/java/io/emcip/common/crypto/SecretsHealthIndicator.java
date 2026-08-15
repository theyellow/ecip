package io.emcip.common.crypto;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports the self-check result at {@code /actuator/health}, for an operator who is looking.
 *
 * <p><strong>Always UP.</strong> Health indicators in this project feed the Kubernetes readiness
 * probe, so reporting DOWN would pull the pod out of rotation and make the Admin UI repair path
 * unreachable — the deadlock the {@code warn} default exists to prevent. Alerting belongs on {@link
 * SecretsMetrics}; this is a reporting surface, not a gate.
 */
@RequiredArgsConstructor
public class SecretsHealthIndicator implements HealthIndicator {

    private final SecretsSelfCheck selfCheck;

    @Override
    public Health health() {
        Map<String, Object> columns = new LinkedHashMap<>();
        long plaintextTotal = 0;
        for (ColumnResult result : selfCheck.lastResults()) {
            plaintextTotal += result.plaintextCount();
            // Primary keys, counts and outcomes only. Never a value or a ciphertext.
            columns.put(
                    result.column().location(),
                    Map.of(
                            "outcome", result.outcome().name(),
                            "plaintext", result.plaintextCount(),
                            "encrypted", result.encryptedRows()));
        }
        return Health.up()
                .withDetail("mode", selfCheck.mode().name().toLowerCase(Locale.ROOT))
                .withDetail("plaintextCount", plaintextTotal)
                .withDetail("columns", columns)
                .build();
    }
}
