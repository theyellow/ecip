package io.emcip.common.crypto;

import java.util.regex.Pattern;

/**
 * Identifies one encrypted secret column for the startup self-check.
 *
 * <p>These identifiers are concatenated into SQL — there is no bind-parameter form for a table or
 * column name — so the canonical constructor restricts them to bare lower-snake-case identifiers.
 * That validation is the injection mitigation; it is not cosmetic. Every value used in practice is
 * a compile-time constant contributed by a service's {@code CryptoConfig}, so the restriction costs
 * nothing.
 *
 * @param table physical table name, e.g. {@code telegram_accounts}
 * @param column encrypted column name, e.g. {@code api_hash}
 * @param pkColumn primary-key column, reported so an operator can find an offending row
 */
public record SecretColumn(String table, String column, String pkColumn) {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    public SecretColumn {
        validate(table, "table");
        validate(column, "column");
        validate(pkColumn, "pkColumn");
    }

    private static void validate(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            // Deliberately does not echo the value: it is attacker-influenced in principle.
            throw new IllegalArgumentException(
                    "SecretColumn " + field + " must be a lower-snake-case SQL identifier");
        }
    }

    /** {@code table.column}, the form used in logs, metrics tags and health details. */
    public String location() {
        return table + "." + column;
    }
}
