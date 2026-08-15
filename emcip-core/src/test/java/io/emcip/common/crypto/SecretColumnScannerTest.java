package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.emcip.common.crypto.ColumnResult.Outcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SecretColumnScannerTest {

    private static final SecretColumn COLUMN =
            new SecretColumn("llm_provider_configs", "api_key", "id");

    private static final byte[] KEY_A = new byte[32];
    private static final byte[] KEY_B;

    static {
        KEY_B = new byte[32];
        KEY_B[0] = 1;
    }

    private DataSource dataSource;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
    }

    /**
     * Wires the three queries the scanner issues, in the order it issues them: plaintext count,
     * offending primary keys, then one encrypted sample.
     */
    private void stubQueries(long plaintextCount, String sampleEncrypted, long encryptedRows)
            throws Exception {
        PreparedStatement countStmt = mock(PreparedStatement.class);
        ResultSet countRs = mock(ResultSet.class);
        when(countRs.next()).thenReturn(true);
        when(countRs.getLong(1)).thenReturn(plaintextCount);
        when(countRs.getLong(2)).thenReturn(encryptedRows);
        when(countStmt.executeQuery()).thenReturn(countRs);

        PreparedStatement idStmt = mock(PreparedStatement.class);
        ResultSet idRs = mock(ResultSet.class);
        when(idRs.next()).thenReturn(plaintextCount > 0, false);
        when(idRs.getString(1)).thenReturn("11111111-1111-1111-1111-111111111111");
        when(idStmt.executeQuery()).thenReturn(idRs);

        PreparedStatement sampleStmt = mock(PreparedStatement.class);
        ResultSet sampleRs = mock(ResultSet.class);
        when(sampleRs.next()).thenReturn(sampleEncrypted != null);
        when(sampleRs.getString(1)).thenReturn(sampleEncrypted);
        when(sampleStmt.executeQuery()).thenReturn(sampleRs);

        when(connection.prepareStatement(anyString())).thenReturn(countStmt, idStmt, sampleStmt);
    }

    @Test
    void reportsOkWhenNoPlaintextAndKeyDecryptsTheSample() throws Exception {
        String encrypted = new SecretCipher(KEY_A).encrypt("some-api-key");
        stubQueries(0, encrypted, 3);

        ColumnResult result =
                new SecretColumnScanner(dataSource, new SecretCipher(KEY_A)).scan(COLUMN);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.plaintextCount()).isZero();
        assertThat(result.isProblem()).isFalse();
    }

    @Test
    void reportsPlaintextWithOffendingPrimaryKeys() throws Exception {
        String encrypted = new SecretCipher(KEY_A).encrypt("some-api-key");
        stubQueries(1, encrypted, 2);

        ColumnResult result =
                new SecretColumnScanner(dataSource, new SecretCipher(KEY_A)).scan(COLUMN);

        assertThat(result.outcome()).isEqualTo(Outcome.PLAINTEXT);
        assertThat(result.plaintextCount()).isEqualTo(1);
        assertThat(result.plaintextIds()).containsExactly("11111111-1111-1111-1111-111111111111");
    }

    /**
     * The finding a prefix-only scan is structurally blind to: with the wrong key mounted every row
     * still starts with v1:, so counting prefixes alone reports a clean bill of health while every
     * secret in the service is unreadable.
     */
    @Test
    void reportsKeyMismatchWhenTheSampleWillNotDecrypt() throws Exception {
        String encryptedWithA = new SecretCipher(KEY_A).encrypt("some-api-key");
        stubQueries(0, encryptedWithA, 3);

        ColumnResult result =
                new SecretColumnScanner(dataSource, new SecretCipher(KEY_B)).scan(COLUMN);

        assertThat(result.outcome()).isEqualTo(Outcome.KEY_MISMATCH);
    }

    @Test
    void keyMismatchOutranksPlaintextButKeepsTheTrueCount() throws Exception {
        String encryptedWithA = new SecretCipher(KEY_A).encrypt("some-api-key");
        stubQueries(2, encryptedWithA, 1);

        ColumnResult result =
                new SecretColumnScanner(dataSource, new SecretCipher(KEY_B)).scan(COLUMN);

        assertThat(result.outcome()).isEqualTo(Outcome.KEY_MISMATCH);
        assertThat(result.plaintextCount()).isEqualTo(2);
    }

    /** An empty column must not be able to masquerade as a passing check. */
    @Test
    void reportsUnverifiedWhenThereIsNoEncryptedRowToProveTheKeyAgainst() throws Exception {
        stubQueries(0, null, 0);

        ColumnResult result =
                new SecretColumnScanner(dataSource, new SecretCipher(KEY_A)).scan(COLUMN);

        assertThat(result.outcome()).isEqualTo(Outcome.UNVERIFIED);
        assertThat(result.isProblem()).isTrue();
    }

    /**
     * A bug unrelated to key mismatch (e.g. an NPE from a future refactor of {@code decrypt}) must
     * propagate as a startup failure, not be silently reclassified as KEY_MISMATCH — that would
     * send an operator hunting for a wrong encryption key that is actually fine.
     */
    @Test
    void propagatesUnrelatedRuntimeExceptionsFromDecryptRatherThanReportingKeyMismatch()
            throws Exception {
        String encrypted = new SecretCipher(KEY_A).encrypt("some-api-key");
        stubQueries(0, encrypted, 3);

        SecretCipher brokenCipher = mock(SecretCipher.class);
        when(brokenCipher.decrypt(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("boom"));

        SecretColumnScanner scanner = new SecretColumnScanner(dataSource, brokenCipher);

        assertThatThrownBy(() -> scanner.scan(COLUMN)).isInstanceOf(IllegalArgumentException.class);
    }
}
