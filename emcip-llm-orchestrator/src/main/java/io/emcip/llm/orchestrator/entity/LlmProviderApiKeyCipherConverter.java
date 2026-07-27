package io.emcip.llm.orchestrator.entity;

import io.emcip.common.crypto.EncryptedStringConverter;
import io.emcip.common.crypto.SecretCipher;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Encrypts {@code llm_provider_configs.api_key} at rest. */
@Component
@Converter
public class LlmProviderApiKeyCipherConverter extends EncryptedStringConverter {

    /**
     * Runtime constructor. {@code @Autowired} is required because this class has a second (no-arg)
     * constructor: with two constructors and none annotated, Spring silently picks the no-arg one
     * and builds a cipher-less bean.
     */
    @Autowired
    public LlmProviderApiKeyCipherConverter(SecretCipher cipher) {
        super(cipher, "llm_provider_configs.api_key");
    }

    /**
     * Build-time constructor for the Spring Data JPA AOT metamodel; see {@link
     * EncryptedStringConverter}.
     */
    public LlmProviderApiKeyCipherConverter() {
        super("llm_provider_configs.api_key");
    }
}
