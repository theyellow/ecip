package io.emcip.admin.api.config;

import io.emcip.common.crypto.SecretCipherConfig;
import io.emcip.common.crypto.SecretColumn;
import io.emcip.common.crypto.SecretsSelfCheckConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Activates the shared {@code SecretCipher} in admin-api, and registers the encrypted columns this
 * service is responsible for with the startup self-check.
 *
 * <p>emcip-core sits outside this service's component-scan base package, so the cipher must be
 * imported explicitly. Importing it makes {@code EMCIP_SECRET_KEY} mandatory for this service.
 */
@Configuration
@Import({SecretCipherConfig.class, SecretsSelfCheckConfig.class})
public class CryptoConfig {

    /** See TelegramAccountService, which encrypts/decrypts this column manually (R2DBC). */
    @Bean
    public SecretColumn telegramApiHashColumn() {
        return new SecretColumn("telegram_accounts", "api_hash", "id");
    }

    /** See TelegramAccountService, which encrypts/decrypts this column manually (R2DBC). */
    @Bean
    public SecretColumn telegramSessionStringColumn() {
        return new SecretColumn("telegram_accounts", "session_string", "id");
    }
}
