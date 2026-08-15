package io.emcip.common.crypto;

import io.emcip.common.crypto.SecretsSelfCheckProperties.SelfCheckMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Verifies at boot that every registered secret column is fully encrypted and that the mounted
 * {@code EMCIP_SECRET_KEY} actually decrypts stored data.
 *
 * <p>An {@link ApplicationRunner} rather than an {@code ApplicationReadyEvent} listener for two
 * reasons: an exception thrown from a runner fails {@code SpringApplication.run()} cleanly, which
 * is what {@link SelfCheckMode#FAIL} needs, and runners execute before the application reports
 * ready, so a failing check never briefly serves traffic.
 *
 * <p>The scheduled re-scan exists so the metric can *clear*. A gauge pinned at 1 until the next pod
 * restart, long after an operator repaired the row, is an alert that cannot go green — nearly as
 * harmful as a check that never fires.
 */
@Slf4j
public class SecretsSelfCheck implements ApplicationRunner {

    private final SecretColumnScanner scanner;
    private final List<SecretColumn> columns;
    private final SecretsSelfCheckProperties properties;

    private volatile List<ColumnResult> lastResults = List.of();

    public SecretsSelfCheck(
            SecretColumnScanner scanner,
            List<SecretColumn> columns,
            SecretsSelfCheckProperties properties) {
        this.scanner = scanner;
        this.columns = List.copyOf(columns);
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ColumnResult> results = scanAndLog();
        if (properties.selfCheck() == SelfCheckMode.FAIL) {
            List<ColumnResult> problems = results.stream().filter(ColumnResult::isProblem).toList();
            if (!problems.isEmpty()) {
                throw new IllegalStateException(
                        "Secret self-check failed in "
                                + problems.size()
                                + " column(s): "
                                + problems.stream()
                                        .map(r -> r.column().location() + " [" + r.outcome() + "]")
                                        .toList()
                                + ". Set emcip.secrets.self-check=warn to start anyway and repair"
                                + " via the Admin UI. See docs/operations/secrets-encryption.md");
            }
        }
    }

    /** Scheduled re-scan. Never throws: {@code FAIL} governs startup only. */
    @Scheduled(cron = "23 17 * * * *")
    public void rescan() {
        try {
            scanAndLog();
        } catch (RuntimeException e) {
            log.warn("Secret self-check re-scan failed; keeping previous results", e);
        }
    }

    public List<ColumnResult> lastResults() {
        return lastResults;
    }

    public SelfCheckMode mode() {
        return properties.selfCheck();
    }

    private List<ColumnResult> scanAndLog() {
        if (properties.selfCheck() == SelfCheckMode.OFF) {
            return List.of();
        }
        List<ColumnResult> results = new ArrayList<>();
        for (SecretColumn column : columns) {
            results.add(scanner.scan(column));
        }
        lastResults = List.copyOf(results);
        logResults(results);
        return lastResults;
    }

    private void logResults(List<ColumnResult> results) {
        long problems = results.stream().filter(ColumnResult::isProblem).count();
        StringBuilder report =
                new StringBuilder("SECRET SELF-CHECK  mode=")
                        .append(properties.selfCheck().name().toLowerCase(Locale.ROOT));
        for (ColumnResult r : results) {
            report.append(
                    String.format(
                            "%n  %-34s %4d encrypted, %4d plaintext  [%s]",
                            r.column().location(),
                            r.encryptedRows(),
                            r.plaintextCount(),
                            r.outcome()));
            // Primary keys only. A secret value must never reach a log.
            if (!r.plaintextIds().isEmpty()) {
                report.append(
                        String.format(
                                "%n    offending %s: %s", r.column().pkColumn(), r.plaintextIds()));
            }
        }
        if (problems == 0) {
            log.info("{}", report);
        } else {
            report.append(
                    String.format(
                            "%n  Repair plaintext values via Admin UI -> Credentials."
                                    + " See docs/operations/secrets-encryption.md"));
            log.error("{}", report);
        }
    }
}
