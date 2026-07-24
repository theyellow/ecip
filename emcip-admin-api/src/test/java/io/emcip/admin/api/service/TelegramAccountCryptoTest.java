package io.emcip.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.common.crypto.SecretCipher;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Covers the crypto boundary only. The tdlib payload paths are exercised through the existing
 * TelegramAccountService tests.
 */
class TelegramAccountCryptoTest {

    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private final SecretCipher cipher = new SecretCipher(KEY);

    @Test
    void apiHash_roundTripsThroughTheCipher() {
        String stored = cipher.encrypt("telegram-api-hash-value");

        assertThat(stored).startsWith("v1:");
        assertThat(stored).doesNotContain("telegram-api-hash-value");
        assertThat(cipher.decrypt(stored, "telegram_accounts.api_hash"))
                .isEqualTo("telegram-api-hash-value");
    }

    @Test
    void nullSessionString_decryptsToNullWithoutThrowing() {
        // No code path writes session_string today, so every row has NULL here. Strict mode
        // must not turn that into a failure.
        TelegramAccount account = TelegramAccount.builder().sessionString(null).build();

        assertThat(cipher.decrypt(account.getSessionString(), "telegram_accounts.session_string"))
                .isNull();
    }

    @Test
    void encryptedSessionString_roundTrips() {
        // Proves the column is correct the day a writer is added.
        String stored = cipher.encrypt("1BQANOTEuMTA4LjU2LjE...");

        assertThat(cipher.decrypt(stored, "telegram_accounts.session_string"))
                .isEqualTo("1BQANOTEuMTA4LjU2LjE...");
    }
}
