package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.emcip.common.crypto.ColumnResult.Outcome;
import io.emcip.common.crypto.SecretsSelfCheckProperties.SelfCheckMode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SecretsSelfCheckTest {

    private static final SecretColumn COLUMN =
            new SecretColumn("llm_provider_configs", "api_key", "id");

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(SecretsSelfCheck.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(logAppender);
    }

    private SecretColumnScanner scannerReturning(Outcome outcome, long plaintextCount) {
        SecretColumnScanner scanner = mock(SecretColumnScanner.class);
        when(scanner.scan(any()))
                .thenReturn(
                        new ColumnResult(
                                COLUMN,
                                outcome,
                                1,
                                plaintextCount,
                                plaintextCount > 0
                                        ? List.of("11111111-1111-1111-1111-111111111111")
                                        : List.of(),
                                true));
        return scanner;
    }

    private SecretsSelfCheck selfCheck(SelfCheckMode mode, Outcome outcome, long plaintextCount) {
        return new SecretsSelfCheck(
                scannerReturning(outcome, plaintextCount),
                List.of(COLUMN),
                new SecretsSelfCheckProperties(mode));
    }

    @Test
    void warnModeBootsDespiteAFinding() {
        SecretsSelfCheck check = selfCheck(SelfCheckMode.WARN, Outcome.PLAINTEXT, 1);

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
        assertThat(check.lastResults())
                .singleElement()
                .satisfies(r -> assertThat(r.outcome()).isEqualTo(Outcome.PLAINTEXT));
    }

    @Test
    void failModeRefusesToStartOnAFinding() {
        SecretsSelfCheck check = selfCheck(SelfCheckMode.FAIL, Outcome.PLAINTEXT, 1);

        assertThatThrownBy(() -> check.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm_provider_configs.api_key");
    }

    @Test
    void failModeStartsNormallyWhenEverythingIsOk() {
        SecretsSelfCheck check = selfCheck(SelfCheckMode.FAIL, Outcome.OK, 0);

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    }

    @Test
    void failModeAlsoRefusesOnKeyMismatchAndOnUnverified() {
        assertThatThrownBy(() -> selfCheck(SelfCheckMode.FAIL, Outcome.KEY_MISMATCH, 0).run(null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> selfCheck(SelfCheckMode.FAIL, Outcome.UNVERIFIED, 0).run(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void offModeScansNothing() {
        SecretColumnScanner scanner = scannerReturning(Outcome.PLAINTEXT, 1);
        SecretsSelfCheck check =
                new SecretsSelfCheck(
                        scanner,
                        List.of(COLUMN),
                        new SecretsSelfCheckProperties(SelfCheckMode.OFF));

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
        assertThat(check.lastResults()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(scanner);
    }

    /**
     * fail governs startup only. Killing a healthy running pod because a new plaintext row appeared
     * would be a self-inflicted outage; the metric and log already carry the signal.
     */
    @Test
    void scheduledRescanNeverThrowsEvenInFailMode() {
        SecretsSelfCheck check = selfCheck(SelfCheckMode.FAIL, Outcome.PLAINTEXT, 1);

        assertThatCode(check::rescan).doesNotThrowAnyException();
        assertThat(check.lastResults()).hasSize(1);
    }

    @Test
    void rescanSurvivesAScannerFailureAndKeepsTheApplicationAlive() {
        SecretColumnScanner scanner = mock(SecretColumnScanner.class);
        when(scanner.scan(any())).thenThrow(new IllegalStateException("database is down"));
        SecretsSelfCheck check =
                new SecretsSelfCheck(
                        scanner,
                        List.of(COLUMN),
                        new SecretsSelfCheckProperties(SelfCheckMode.WARN));

        assertThatCode(check::rescan).doesNotThrowAnyException();
    }

    @Test
    void warnModeSurvivesAScanThatCannotRunAndStartsAnyway() {
        SecretColumnScanner scanner = mock(SecretColumnScanner.class);
        when(scanner.scan(any())).thenThrow(new IllegalStateException("database is down"));
        SecretsSelfCheck check =
                new SecretsSelfCheck(
                        scanner,
                        List.of(COLUMN),
                        new SecretsSelfCheckProperties(SelfCheckMode.WARN));

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
        assertThat(check.lastResults()).isEmpty();
    }

    @Test
    void failModeRefusesToStartWhenTheScanCannotRun() {
        SecretColumnScanner scanner = mock(SecretColumnScanner.class);
        when(scanner.scan(any())).thenThrow(new IllegalStateException("database is down"));
        SecretsSelfCheck check =
                new SecretsSelfCheck(
                        scanner,
                        List.of(COLUMN),
                        new SecretsSelfCheckProperties(SelfCheckMode.FAIL));

        assertThatThrownBy(() -> check.run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lastResultsIsEmptyBeforeTheFirstScan() {
        assertThat(selfCheck(SelfCheckMode.WARN, Outcome.OK, 0).lastResults()).isEmpty();
    }

    /**
     * Pins the known defect from the plan brief: the report is built with StringBuilder.append,
     * which does NOT interpret "%n" — only String.format does. This asserts on the real captured
     * log message, not by eye: it must contain genuine newline characters and must never contain
     * the literal two-character sequence "%n".
     */
    @Test
    void logReportRendersRealNewlinesNotLiteralPercentN() {
        SecretsSelfCheck check = selfCheck(SelfCheckMode.WARN, Outcome.PLAINTEXT, 1);

        check.run(null);

        assertThat(logAppender.list).isNotEmpty();
        String message = logAppender.list.get(0).getFormattedMessage();
        assertThat(message).doesNotContain("%n");
        assertThat(message).contains(System.lineSeparator());
        assertThat(message.lines().count()).isGreaterThan(1);
        assertThat(message).contains("llm_provider_configs.api_key");
        assertThat(message).contains("offending id: [11111111-1111-1111-1111-111111111111]");
        // NOTE: this fixture's only content is a UUID - no ciphertext or plaintext secret is ever
        // present here, so this assertion cannot fail no matter what the code does. It pins the
        // %n-vs-format defect above, not leak-freedom. The real leak-freedom proof, against a
        // fixture that genuinely contains a plaintext sentinel and v1: ciphertext, lives in
        // llm-orchestrator's SecretsSelfCheckIT
        // (logReportNeverContainsThePlaintextSecretOrTheCiphertextOrTheVersionPrefix).
        assertThat(message).doesNotContain("v1:");
    }
}
