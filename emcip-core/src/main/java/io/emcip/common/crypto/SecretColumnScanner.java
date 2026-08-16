package io.emcip.common.crypto;

import io.emcip.common.crypto.ColumnResult.Outcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;

/**
 * The only class here that talks SQL.
 *
 * <p>Runs three read-only queries per column: how many rows lack the {@code v1:} prefix, which rows
 * those are (primary keys only), and one encrypted sample used to prove the mounted key can
 * actually decrypt stored data. The sample's plaintext is discarded immediately and never leaves
 * this method.
 *
 * <p>Uses raw JDBC rather than {@code JdbcTemplate} because {@code emcip-core} deliberately does
 * not depend on {@code spring-jdbc}. This mirrors the existing {@code DatabaseHealthIndicator}s.
 */
@RequiredArgsConstructor
public class SecretColumnScanner {

    private final DataSource dataSource;
    private final SecretCipher cipher;

    /** Scans one column. Never throws for data problems — those are outcomes, not errors. */
    public ColumnResult scan(SecretColumn column) {
        String prefixPattern = SecretCipher.PREFIX + "%";
        try (Connection connection = dataSource.getConnection()) {
            long plaintextCount;
            long encryptedRows;
            // Identifiers are validated lower-snake-case by SecretColumn's constructor; the
            // prefix is bound as a parameter.
            String countSql =
                    "SELECT count(*) FILTER (WHERE "
                            + column.column()
                            + " NOT LIKE ?), count(*) FILTER (WHERE "
                            + column.column()
                            + " LIKE ?) FROM "
                            + column.table()
                            + " WHERE "
                            + column.column()
                            + " IS NOT NULL";
            try (PreparedStatement statement = connection.prepareStatement(countSql)) {
                statement.setString(1, prefixPattern);
                statement.setString(2, prefixPattern);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    plaintextCount = rs.getLong(1);
                    encryptedRows = rs.getLong(2);
                }
            }

            // Always issued (even when plaintextCount is 0): the scanner's three-query shape is
            // fixed, and an empty result set is a harmless no-op for a clean column.
            List<String> plaintextIds = new ArrayList<>();
            String idSql =
                    "SELECT "
                            + column.pkColumn()
                            + " FROM "
                            + column.table()
                            + " WHERE "
                            + column.column()
                            + " IS NOT NULL AND "
                            + column.column()
                            + " NOT LIKE ? LIMIT "
                            + ColumnResult.MAX_REPORTED_IDS;
            try (PreparedStatement statement = connection.prepareStatement(idSql)) {
                statement.setString(1, prefixPattern);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        plaintextIds.add(rs.getString(1));
                    }
                }
            }

            Boolean keyWorks = proveKey(connection, column, prefixPattern);

            Outcome outcome;
            if (Boolean.FALSE.equals(keyWorks)) {
                outcome = Outcome.KEY_MISMATCH;
            } else if (plaintextCount > 0) {
                outcome = Outcome.PLAINTEXT;
            } else if (keyWorks == null) {
                outcome = Outcome.UNVERIFIED;
            } else {
                outcome = Outcome.OK;
            }

            return new ColumnResult(
                    column, outcome, encryptedRows, plaintextCount, plaintextIds, keyWorks);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Secret self-check could not read " + column.location(), e);
        }
    }

    /**
     * @return {@code true} if a sample decrypted, {@code false} if it would not, {@code null} if
     *     there was no encrypted row to try.
     */
    private Boolean proveKey(Connection connection, SecretColumn column, String prefixPattern)
            throws SQLException {
        String sampleSql =
                "SELECT "
                        + column.column()
                        + " FROM "
                        + column.table()
                        + " WHERE "
                        + column.column()
                        + " LIKE ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sampleSql)) {
            statement.setString(1, prefixPattern);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String sample = rs.getString(1);
                try {
                    // Result deliberately discarded: we need only the fact that it decrypted.
                    cipher.decrypt(sample, column.location());
                    return true;
                } catch (IllegalStateException e) {
                    // SecretCipher.decrypt's only documented failure modes:
                    // PlaintextSecretException
                    // (a subtype) for a missing v1: prefix, or IllegalStateException for a corrupt
                    // or
                    // undecryptable value under the mounted key. Anything else (e.g. an NPE from a
                    // future refactor) is a real defect and must propagate as a startup failure,
                    // not
                    // be silently reclassified as KEY_MISMATCH.
                    return false;
                }
            }
        }
    }
}
