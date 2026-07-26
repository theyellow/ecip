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
 * <p>At runtime Hibernate instantiates converters through Spring's bean container, which Spring
 * Boot configures automatically, so constructor injection of {@link SecretCipher} works.
 *
 * <p>The build-time Spring Data JPA AOT metamodel (native image {@code process-aot}) is different:
 * it boots a standalone Hibernate with no Spring bean container and instantiates converters
 * reflectively through their <em>no-arg</em> constructor, purely to resolve the converted type.
 * Each concrete subclass must therefore also expose a public no-arg constructor that calls {@link
 * #EncryptedStringConverter(String)}. Such a cipher-less instance is never used for a real
 * conversion; the guard in {@link #cipher()} makes any accidental use fail loudly instead of NPE.
 */
public abstract class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final SecretCipher cipher;
    private final String location;

    /**
     * Runtime constructor, invoked by Spring's Hibernate bean container.
     *
     * @param cipher shared cipher bean
     * @param location {@code table.column}, used only in error messages
     */
    protected EncryptedStringConverter(SecretCipher cipher, String location) {
        this.cipher = cipher;
        this.location = location;
    }

    /**
     * Build-time constructor for the Spring Data JPA AOT metamodel, which instantiates converters
     * without a cipher. The resulting instance can only resolve the converted type; calling a
     * conversion method throws (see {@link #cipher()}).
     *
     * @param location {@code table.column}, used only in error messages
     */
    protected EncryptedStringConverter(String location) {
        this.cipher = null;
        this.location = location;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher().decrypt(dbData, location);
    }

    private SecretCipher cipher() {
        if (cipher == null) {
            throw new IllegalStateException(
                    "EncryptedStringConverter for "
                            + location
                            + " has no cipher: this is the build-time AOT metamodel instance and"
                            + " must not perform conversions. The runtime instance is created by"
                            + " Spring with constructor injection.");
        }
        return cipher;
    }
}
