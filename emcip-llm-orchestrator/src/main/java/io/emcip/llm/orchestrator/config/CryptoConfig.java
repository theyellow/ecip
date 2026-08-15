package io.emcip.llm.orchestrator.config;

import io.emcip.common.crypto.SecretCipherConfig;
import io.emcip.common.crypto.SecretColumn;
import io.emcip.common.crypto.SecretsSelfCheckConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Activates the shared {@code SecretCipher} in llm-orchestrator, and registers the encrypted column
 * this service is responsible for with the startup self-check.
 *
 * <p>emcip-core sits outside this service's component-scan base package, so the cipher must be
 * imported explicitly. Importing it makes {@code EMCIP_SECRET_KEY} mandatory for this service.
 */
@Configuration
@Import({SecretCipherConfig.class, SecretsSelfCheckConfig.class})
public class CryptoConfig {

    /** The one column this service encrypts. See LlmProviderApiKeyCipherConverter. */
    @Bean
    public SecretColumn llmProviderApiKeyColumn() {
        return new SecretColumn("llm_provider_configs", "api_key", "id");
    }
}
