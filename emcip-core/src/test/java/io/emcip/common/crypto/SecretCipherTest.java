package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String LOCATION = "ke_vendor_api_keys.api_key";

    private final SecretCipher cipher = new SecretCipher(KEY);

    @Test
    void encryptThenDecrypt_returnsOriginalPlaintext() {
        String encrypted = cipher.encrypt("sk-super-secret-value");

        assertThat(encrypted).startsWith("v1:");
        assertThat(encrypted).doesNotContain("sk-super-secret-value");
        assertThat(cipher.decrypt(encrypted, LOCATION)).isEqualTo("sk-super-secret-value");
    }

    @Test
    void encrypt_sameInputTwice_producesDifferentCiphertext() {
        String first = cipher.encrypt("same-input");
        String second = cipher.encrypt("same-input");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first, LOCATION)).isEqualTo(cipher.decrypt(second, LOCATION));
    }

    @Test
    void decrypt_plaintextValue_throwsNamingTheColumnButNotTheValue() {
        assertThatThrownBy(() -> cipher.decrypt("sk-legacy-plaintext", LOCATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ke_vendor_api_keys.api_key")
                .hasMessageNotContaining("sk-legacy-plaintext")
                .hasMessageNotContaining("sk-legacy");
    }

    @Test
    void decrypt_prefixedButTooShortToContainIv_throwsIllegalStateNamingColumn() {
        // "v1:" + base64 of 4 bytes — shorter than the 12-byte IV.
        String malformed =
                "v1:" + java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4});

        assertThatThrownBy(() -> cipher.decrypt(malformed, LOCATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LOCATION);
    }

    @Test
    void decrypt_tamperedCiphertext_failsWithAeadBadTag() {
        String encrypted = cipher.encrypt("tamper-me");
        byte[] raw = Base64.getDecoder().decode(encrypted.substring(3));
        raw[raw.length - 1] ^= 0x01;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered, LOCATION))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    void decrypt_wrongKey_failsAndDoesNotReturnGarbage() {
        String encrypted = cipher.encrypt("secret");
        SecretCipher other =
                new SecretCipher(
                        "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> other.decrypt(encrypted, LOCATION))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullValues_passThroughBothDirections() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null, LOCATION)).isNull();
    }

    @Test
    void isEncrypted_detectsPrefix() {
        assertThat(SecretCipher.isEncrypted(cipher.encrypt("x"))).isTrue();
        assertThat(SecretCipher.isEncrypted("plaintext")).isFalse();
        assertThat(SecretCipher.isEncrypted(null)).isFalse();
    }

    @Test
    void constructor_rejectsKeyThatIsNot32Bytes() {
        assertThatThrownBy(() -> new SecretCipher("too-short".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");

        assertThatThrownBy(() -> new SecretCipher(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
