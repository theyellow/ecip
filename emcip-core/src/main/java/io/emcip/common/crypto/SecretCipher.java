package io.emcip.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption for secrets stored in the database.
 *
 * <p>Encrypted values carry a {@code v1:} prefix followed by base64 of {@code iv || ciphertext ||
 * tag}. The prefix is a version marker: it lets a future key-rotation or KMS-backed implementation
 * be introduced without touching already-stored data.
 *
 * <p>Reads are fail-closed. A stored value without the prefix is legacy plaintext and throws,
 * rather than being returned silently — see the migration runbook in {@code
 * docs/operations/secrets-encryption.md}.
 */
public class SecretCipher {

    /** Version marker prefixing every encrypted value. */
    public static final String PREFIX = "v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "Secret key must decode to exactly 32 bytes for AES-256, got "
                            + (keyBytes == null ? "null" : keyBytes.length + " bytes"));
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /** Returns true if the value is already encrypted by this cipher. Null-safe. */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * Encrypts a plaintext secret with a freshly generated IV.
     *
     * @param plaintext value to encrypt; null returns null
     * @return {@code v1:} + base64 of iv || ciphertext || tag
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);

            Cipher jce = Cipher.getInstance(TRANSFORMATION);
            jce.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = jce.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            // Message must never carry the plaintext.
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    /**
     * Decrypts a stored secret. Fail-closed: a value without the {@code v1:} prefix throws.
     *
     * @param stored the stored column value; null returns null
     * @param location logical location for error messages, e.g. {@code
     *     "ke_vendor_api_keys.api_key"}. Never include the value itself.
     */
    public String decrypt(String stored, String location) {
        if (stored == null) {
            return null;
        }
        if (!isEncrypted(stored)) {
            throw new IllegalStateException(
                    "Plaintext secret in "
                            + location
                            + " — this value was never encrypted. Run the migration runbook in"
                            + " docs/operations/secrets-encryption.md");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (combined.length < IV_LENGTH_BYTES) {
                throw new IllegalStateException("Corrupt or truncated secret in " + location);
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);

            Cipher jce = Cipher.getInstance(TRANSFORMATION);
            jce.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext =
                    jce.doFinal(combined, IV_LENGTH_BYTES, combined.length - IV_LENGTH_BYTES);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to decrypt secret in " + location, e);
        }
    }
}
