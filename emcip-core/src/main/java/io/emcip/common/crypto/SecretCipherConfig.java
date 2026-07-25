package io.emcip.common.crypto;

import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the {@link SecretCipher} bean from the {@code EMCIP_SECRET_KEY} environment variable.
 *
 * <p>This class lives outside every service's component-scan base package, so it is only active in
 * services that {@code @Import} it explicitly. That is deliberate: it means startup can fail fast
 * on a missing key without forcing a key on the services that store no secrets.
 *
 * <p>Spring's relaxed binding maps the {@code EMCIP_SECRET_KEY} environment variable onto the
 * {@code emcip.secret-key} property. There is intentionally no default — a default here would
 * eventually become somebody's production key.
 */
@Configuration
public class SecretCipherConfig {

    @Bean
    public SecretCipher secretCipher(@Value("${emcip.secret-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "EMCIP_SECRET_KEY is not set. Generate one with 'openssl rand -base64 32' and"
                            + " supply it via the emcip-secret-key Kubernetes Secret. See"
                            + " docs/operations/secrets-encryption.md");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            // Deliberately does not echo the value.
            throw new IllegalStateException(
                    "EMCIP_SECRET_KEY is not valid base64. Generate one with 'openssl rand -base64"
                            + " 32'.");
        }
        try {
            return new SecretCipher(keyBytes);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("EMCIP_SECRET_KEY is invalid: " + e.getMessage(), e);
        }
    }
}
