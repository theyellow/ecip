package io.emcip.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls what the startup self-check does when it finds a problem.
 *
 * <p>The default is {@link SelfCheckMode#WARN} rather than {@code FAIL} on purpose. PRs #241 and
 * #243 built in-product repair paths for plaintext secrets — a 409 that opens a Credentials dialog
 * — and those paths need the service running. Refusing to start would deadlock exactly the case
 * they were built to fix, leaving direct database access as the only recovery.
 *
 * @param selfCheck what to do on a finding; null binds to {@code WARN}
 */
@ConfigurationProperties("emcip.secrets")
public record SecretsSelfCheckProperties(SelfCheckMode selfCheck) {

    public SecretsSelfCheckProperties {
        if (selfCheck == null) {
            selfCheck = SelfCheckMode.WARN;
        }
    }

    public enum SelfCheckMode {
        /** Log, record metrics, start normally. The shipping default. */
        WARN,
        /** Log, then refuse to start. Opt-in, per environment, after it has reported clean. */
        FAIL,
        /** Skip entirely. Local dev and tests that do not exercise this. */
        OFF
    }
}
