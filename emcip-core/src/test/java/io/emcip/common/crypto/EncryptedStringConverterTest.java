package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EncryptedStringConverterTest {

    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    /** Stand-in for the per-column subclasses that live in the JPA services. */
    private static class TestConverter extends EncryptedStringConverter {
        TestConverter(SecretCipher cipher) {
            super(cipher, "some_table.some_column");
        }

        /** Mirrors the no-arg constructor Hibernate's AOT metamodel instantiates reflectively. */
        TestConverter() {
            super("some_table.some_column");
        }
    }

    private final TestConverter converter = new TestConverter(new SecretCipher(KEY));

    @Test
    void writeThenRead_roundTripsThroughTheColumn() {
        String columnValue = converter.convertToDatabaseColumn("sk-secret");

        assertThat(columnValue).startsWith("v1:");
        assertThat(columnValue).doesNotContain("sk-secret");
        assertThat(converter.convertToEntityAttribute(columnValue)).isEqualTo("sk-secret");
    }

    @Test
    void readingLegacyPlaintext_throwsNamingTheColumn() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("sk-legacy"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("some_table.some_column")
                .hasMessageNotContaining("sk-legacy");
    }

    @Test
    void nullValues_passThroughBothDirections() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void aotMetamodelInstance_hasNoCipherAndRefusesToConvert() {
        // Hibernate's build-time AOT metamodel instantiates converters via the no-arg constructor,
        // with no Spring bean container to inject the cipher. Such an instance must never encrypt
        // or
        // decrypt real data — it exists only to resolve the converted type.
        TestConverter aotInstance = new TestConverter();

        assertThatThrownBy(() -> aotInstance.convertToDatabaseColumn("sk-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("some_table.some_column")
                .hasMessageNotContaining("sk-secret");
        assertThatThrownBy(() -> aotInstance.convertToEntityAttribute("v1:whatever"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("some_table.some_column");
    }
}
