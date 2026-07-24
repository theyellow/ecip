package io.emcip.common.crypto;

/**
 * Operator tool for the hand-run secrets migration.
 *
 * <p>AES-GCM ciphertext cannot be produced with standard shell tooling — {@code openssl enc} does
 * not support AEAD modes — so this exists to generate values for the UPDATE statements in {@code
 * docs/operations/secrets-encryption.md}.
 *
 * <pre>
 * EMCIP_SECRET_KEY=... java -cp emcip-core.jar \
 *     io.emcip.common.crypto.SecretCipherCli encrypt 'plaintext-secret'
 * </pre>
 *
 * <p>Note the plaintext appears in the shell command and therefore in shell history. Clear it
 * afterwards.
 */
public final class SecretCipherCli {

    private static final String USAGE =
            "Usage: SecretCipherCli <encrypt|isEncrypted> <value>\n"
                    + "  Requires the EMCIP_SECRET_KEY environment variable (base64, 32 bytes).";

    private SecretCipherCli() {}

    public static void main(String[] args) {
        String base64Key = System.getenv("EMCIP_SECRET_KEY");
        try {
            System.out.println(run(args, base64Key));
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        }
    }

    /**
     * Runs one command and returns the line to print.
     *
     * @param args {@code [command, value]}
     * @param base64Key base64-encoded 32-byte key
     */
    public static String run(String[] args, String base64Key) {
        if (args == null || args.length != 2) {
            throw new IllegalArgumentException(USAGE);
        }
        String command = args[0];
        String value = args[1];

        if ("isEncrypted".equals(command)) {
            return Boolean.toString(SecretCipher.isEncrypted(value));
        }
        if ("encrypt".equals(command)) {
            return new SecretCipherConfig().secretCipher(base64Key).encrypt(value);
        }
        throw new IllegalArgumentException("Unknown command '" + command + "'.\n" + USAGE);
    }
}
