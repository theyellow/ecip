package io.emcip.llm.orchestrator.entity;

import io.emcip.common.crypto.EncryptedStringConverter;
import io.emcip.common.crypto.SecretCipher;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** Encrypts {@code llm_provider_configs.api_key} at rest. */
@Component
@Converter
public class LlmProviderApiKeyCipherConverter extends EncryptedStringConverter {

    public LlmProviderApiKeyCipherConverter(SecretCipher cipher) {
        super(cipher, "llm_provider_configs.api_key");
    }
}
