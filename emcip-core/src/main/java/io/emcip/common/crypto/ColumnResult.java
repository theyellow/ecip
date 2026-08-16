package io.emcip.common.crypto;

import java.util.List;

/**
 * What the self-check found in one column.
 *
 * <p>{@code outcome} is single-valued but the counts are always preserved, so a column that is both
 * key-mismatched and holding plaintext still reports the true {@code plaintextCount}.
 *
 * @param column the column scanned
 * @param outcome single most severe finding, per the precedence in {@link Outcome}
 * @param encryptedRows rows carrying the {@code v1:} prefix
 * @param plaintextCount non-null rows lacking the prefix
 * @param plaintextIds primary keys of offending rows, capped; never contains a secret value
 * @param keyProven whether the mounted key was actually tested against a sample: {@code true} if a
 *     sample decrypted, {@code false} if a sample would not decrypt, {@code null} if there was no
 *     encrypted row to try it against. Independent of {@code outcome} — a column can be {@code
 *     PLAINTEXT} (rows exist without the prefix) while {@code keyProven} is still {@code null},
 *     because {@code outcome}'s precedence reports the worse finding without claiming the key was
 *     verified.
 */
public record ColumnResult(
        SecretColumn column,
        Outcome outcome,
        long encryptedRows,
        long plaintextCount,
        List<String> plaintextIds,
        Boolean keyProven) {

    /** Maximum primary keys reported per column, so a mass finding cannot flood the log. */
    public static final int MAX_REPORTED_IDS = 20;

    public ColumnResult {
        plaintextIds = List.copyOf(plaintextIds);
    }

    public boolean isProblem() {
        return outcome != Outcome.OK;
    }

    /**
     * Findings, most severe first. {@code KEY_MISMATCH} outranks {@code PLAINTEXT} because an
     * unreadable key makes every secret in the service unusable, whereas plaintext rows are
     * individually repairable through the Admin UI.
     */
    public enum Outcome {
        /** A {@code v1:} row exists and the mounted key cannot decrypt it. */
        KEY_MISMATCH,
        /** Rows lack the {@code v1:} prefix — never encrypted. */
        PLAINTEXT,
        /** No encrypted rows exist, so the key could not be proven against real data. */
        UNVERIFIED,
        /** Zero plaintext, and one encrypted row decrypted successfully. */
        OK
    }
}
