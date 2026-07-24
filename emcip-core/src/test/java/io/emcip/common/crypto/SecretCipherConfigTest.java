package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherConfigTest {

    private final SecretCipherConfig config = new SecretCipherConfig();

    private static String validKey() {
        return Base64.getEncoder()
                .encodeToString(
                        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validKey_producesWorkingCipher() {
        SecretCipher cipher = config.secretCipher(validKey());

        assertThat(cipher.decrypt(cipher.encrypt("round-trip"), "test.column"))
                .isEqualTo("round-trip");
    }

    @Test
    void validKey_toleratesSurroundingWhitespace() {
        SecretCipher cipher = config.secretCipher("  " + validKey() + "\n");

        assertThat(cipher.encrypt("x")).startsWith("v1:");
    }

    @Test
    void missingKey_failsFastWithActionableMessage() {
        assertThatThrownBy(() -> config.secretCipher(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMCIP_SECRET_KEY");

        assertThatThrownBy(() -> config.secretCipher(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMCIP_SECRET_KEY");
    }

    @Test
    void nonBase64Key_failsWithoutEchoingTheValue() {
        assertThatThrownBy(() -> config.secretCipher("not!valid!base64!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not!valid!base64!");
    }

    @Test
    void wrongLengthKey_failsFast() {
        String sixteenBytes =
                Base64.getEncoder()
                        .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> config.secretCipher(sixteenBytes))
                .isInstanceOf(IllegalStateException.class);
    }
}
