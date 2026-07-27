package io.emcip.llm.orchestrator.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emcip.common.crypto.SecretCipher;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class LlmProviderApiKeyCipherConverterTest {

    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private final LlmProviderApiKeyCipherConverter converter =
            new LlmProviderApiKeyCipherConverter(new SecretCipher(KEY));

    @Test
    void writeThenRead_roundTrips() {
        String stored = converter.convertToDatabaseColumn("sk-litellm-key");

        assertThat(stored).startsWith("v1:");
        assertThat(stored).doesNotContain("sk-litellm-key");
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("sk-litellm-key");
    }

    @Test
    void legacyPlaintext_throwsNamingTheColumn() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("sk-unmigrated"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm_provider_configs.api_key")
                .hasMessageNotContaining("sk-unmigrated");
    }

    @Test
    void nullApiKey_passesThrough() {
        // api_key is nullable on this table.
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void springCreatesTheBeanWithTheCipherConstructor_notTheNoArgAotConstructor() {
        // At runtime Hibernate's SpringBeanContainer instantiates converters through the
        // application
        // context's autowire-capable bean factory, the same path used here. Because this class also
        // has a no-arg constructor (for the build-time AOT metamodel), Spring must be told to use
        // the SecretCipher constructor via @Autowired; otherwise it silently picks the no-arg one
        // and the resulting bean cannot decrypt. Reproduces that path without a database.
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(SecretCipher.class, () -> new SecretCipher(KEY));
            ctx.refresh();

            LlmProviderApiKeyCipherConverter created =
                    ctx.getAutowireCapableBeanFactory()
                            .createBean(LlmProviderApiKeyCipherConverter.class);

            String stored = created.convertToDatabaseColumn("sk-litellm-key");
            assertThat(created.convertToEntityAttribute(stored)).isEqualTo("sk-litellm-key");
        }
    }
}
