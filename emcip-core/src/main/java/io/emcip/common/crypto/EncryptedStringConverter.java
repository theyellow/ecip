package io.emcip.common.crypto;

import jakarta.persistence.AttributeConverter;

/**
 * Base for JPA attribute converters that transparently encrypt a String column.
 *
 * <p>Subclass once per encrypted column and annotate the subclass with {@code @Converter}, so that
 * the strict-mode error message can name the exact column:
 *
 * <pre>
 * &#64;Converter
 * public class VendorApiKeyCipherConverter extends EncryptedStringConverter {
 *     public VendorApiKeyCipherConverter(SecretCipher cipher) {
 *         super(cipher, "ke_vendor_api_keys.api_key");
 *     }
 * }
 * </pre>
 *
 * <p>Hibernate instantiates converters through Spring's bean container, which Spring Boot
 * configures automatically, so constructor injection of {@link SecretCipher} works.
 */
public abstract class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final SecretCipher cipher;
    private final String location;

    /**
     * @param cipher shared cipher bean
     * @param location {@code table.column}, used only in error messages
     */
    protected EncryptedStringConverter(SecretCipher cipher, String location) {
        this.cipher = cipher;
        this.location = location;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData, location);
    }
}
