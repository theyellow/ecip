package io.emcip.common.crypto;

/**
 * Thrown when a stored secret has no {@code v1:} prefix — it was written before secrets encryption
 * was enabled and never migrated.
 *
 * <p>Distinct from a decryption *failure*, which means the value is encrypted but unreadable with
 * the configured key. The difference matters operationally and must not be blurred: this condition
 * is fixed by re-entering the value, whereas re-entering after a decrypt failure would overwrite
 * data that is merely unreadable with the current key and might still be recoverable with the right
 * one.
 *
 * <p>Extends {@link IllegalStateException} so existing fail-closed behaviour and any caller
 * catching that type is unaffected; the subtype exists so callers can react to *this* case by type
 * instead of matching on message text.
 *
 * <p>{@link #getLocation()} is {@code table.column} and is for logs only. It must never be echoed
 * to an HTTP client — see {@code GlobalExceptionHandler}, which maps this to a safe message.
 */
public class PlaintextSecretException extends IllegalStateException {

    private final String location;

    public PlaintextSecretException(String location) {
        super(
                "Plaintext secret in "
                        + location
                        + " — this value was never encrypted. Run the migration runbook in"
                        + " docs/operations/secrets-encryption.md");
        this.location = location;
    }

    /**
     * {@code table.column} of the offending value. Log-only: never include in a client response.
     */
    public String getLocation() {
        return location;
    }
}
