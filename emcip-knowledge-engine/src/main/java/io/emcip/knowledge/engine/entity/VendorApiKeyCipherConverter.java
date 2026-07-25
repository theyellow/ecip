package io.emcip.knowledge.engine.entity;

import io.emcip.common.crypto.EncryptedStringConverter;
import io.emcip.common.crypto.SecretCipher;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** Encrypts {@code ke_vendor_api_keys.api_key} at rest. */
@Component
@Converter
public class VendorApiKeyCipherConverter extends EncryptedStringConverter {

    public VendorApiKeyCipherConverter(SecretCipher cipher) {
        super(cipher, "ke_vendor_api_keys.api_key");
    }
}
