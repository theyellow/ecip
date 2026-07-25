package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherCliTest {

    private static final String KEY =
            Base64.getEncoder()
                    .encodeToString(
                            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    void encrypt_outputIsReadableByTheCipherTheServicesUse() {
        String output = SecretCipherCli.run(new String[] {"encrypt", "sk-vendor-key"}, KEY);

        assertThat(output).startsWith("v1:");

        SecretCipher serviceSideCipher = new SecretCipherConfig().secretCipher(KEY);
        assertThat(serviceSideCipher.decrypt(output, "test.column")).isEqualTo("sk-vendor-key");
    }

    @Test
    void isEncrypted_reportsTrueForCiphertextAndFalseForPlaintext() {
        String encrypted = SecretCipherCli.run(new String[] {"encrypt", "value"}, KEY);

        assertThat(SecretCipherCli.run(new String[] {"isEncrypted", encrypted}, KEY))
                .isEqualTo("true");
        assertThat(SecretCipherCli.run(new String[] {"isEncrypted", "plain"}, KEY))
                .isEqualTo("false");
    }

    @Test
    void unknownCommand_reportsUsage() {
        assertThatThrownBy(() -> SecretCipherCli.run(new String[] {"decrypt", "x"}, KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usage");
    }

    @Test
    void wrongArgumentCount_reportsUsage() {
        assertThatThrownBy(() -> SecretCipherCli.run(new String[] {"encrypt"}, KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usage");
    }
}
