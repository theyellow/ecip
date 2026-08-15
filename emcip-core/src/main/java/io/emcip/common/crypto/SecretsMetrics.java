package io.emcip.common.crypto;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;

/**
 * Publishes the self-check result as gauges. This is the surface that alerts (P3.22) — the health
 * indicator only reports, and a log line cannot be alerted on.
 *
 * <p>Gauges read {@link SecretsSelfCheck#lastResults()} on scrape rather than caching a value, so a
 * repaired row clears the metric at the next scheduled re-scan without a pod restart.
 */
public class SecretsMetrics {

    private static final double KEY_OK = 0;
    private static final double KEY_MISMATCH = 1;
    private static final double KEY_UNVERIFIED = 2;

    public SecretsMetrics(
            MeterRegistry registry, SecretsSelfCheck selfCheck, List<SecretColumn> columns) {
        for (SecretColumn column : columns) {
            Gauge.builder(
                            "emcip.secrets.plaintext_count",
                            selfCheck,
                            check -> value(check, column, r -> (double) r.plaintextCount()))
                    .description("Rows in this column stored without the v1: encryption prefix")
                    .tag("column", column.location())
                    .register(registry);

            Gauge.builder(
                            "emcip.secrets.key_status",
                            selfCheck,
                            check -> value(check, column, SecretsMetrics::keyStatus))
                    .description("0 = key decrypts stored data, 1 = mismatch, 2 = unverified")
                    .tag("column", column.location())
                    .register(registry);
        }
    }

    /**
     * Resolves this column's gauge value from the latest scan.
     *
     * <p>Returns {@link Double#NaN} when this column has no matching {@link ColumnResult} - before
     * the first scan completes, or permanently when {@link
     * SecretsSelfCheckProperties.SelfCheckMode#OFF} keeps {@link SecretsSelfCheck#lastResults()}
     * empty. NaN means "not scanned" and must stay distinct from a measured zero: Prometheus stores
     * it, a {@code == 0} alert will not fire on it, and it will not render as a false-confidence
     * "clean" reading for a column nobody looked at.
     */
    private static double value(
            SecretsSelfCheck selfCheck,
            SecretColumn column,
            java.util.function.ToDoubleFunction<ColumnResult> extractor) {
        return selfCheck.lastResults().stream()
                .filter(r -> r.column().equals(column))
                .findFirst()
                .map(extractor::applyAsDouble)
                .orElse(Double.NaN);
    }

    private static double keyStatus(ColumnResult result) {
        return switch (result.outcome()) {
            case KEY_MISMATCH -> KEY_MISMATCH;
            case UNVERIFIED -> KEY_UNVERIFIED;
            case OK, PLAINTEXT -> KEY_OK;
        };
    }
}
