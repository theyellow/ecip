package io.emcip.llm.orchestrator.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emcip.common.crypto.ColumnResult;
import io.emcip.common.crypto.ColumnResult.Outcome;
import io.emcip.common.crypto.SecretCipher;
import io.emcip.common.crypto.SecretColumn;
import io.emcip.common.crypto.SecretColumnScanner;
import io.emcip.common.crypto.SecretsSelfCheck;
import io.emcip.common.crypto.SecretsSelfCheckProperties;
import io.emcip.common.crypto.SecretsSelfCheckProperties.SelfCheckMode;
import io.emcip.llm.orchestrator.TestcontainersInitializer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * Proves the self-check against a real PostgreSQL, which is the only place the SQL is exercised —
 * the unit tests all mock the DataSource, and {@code count(*) FILTER (WHERE ...)} is
 * Postgres-specific syntax that no mock can validate.
 */
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersInitializer.class)
class SecretsSelfCheckIT {

    /** Matches TestcontainersInitializer's emcip.secret-key, so it decrypts what the app wrote. */
    private static final byte[] KEY =
            Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

    private static final byte[] OTHER_KEY =
            Base64.getDecoder().decode("ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=");

    private static final SecretColumn COLUMN =
            new SecretColumn("llm_provider_configs", "api_key", "id");

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from llm_provider_configs");
    }

    /**
     * Columns verified against {@code LlmProviderConfig}: {@code id, name, base_url, api_key,
     * active, created_at, updated_at, version_lock}. There is no {@code provider_name} column — the
     * entity field is {@code name} — and {@code base_url} is {@code nullable = false}, so a bare
     * INSERT omitting it would fail the schema's NOT NULL constraint. {@code version_lock} is the
     * {@code @Version} column; it is set explicitly since this is a raw JDBC insert bypassing JPA's
     * own initialization of it.
     */
    private void insert(UUID id, String apiKeyAsStored) {
        // Timestamp.from(...), not the bare Instant: plain JdbcTemplate.update(String,
        // Object...) has no PreparedStatementSetter to hint the SQL type, and the driver cannot
        // infer one for java.time.Instant on its own.
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "insert into llm_provider_configs (id, name, base_url, api_key, active,"
                        + " created_at, updated_at, version_lock) values (?, ?, ?, ?, true, ?, ?,"
                        + " 0)",
                id,
                "provider-" + id,
                "http://litellm:4000",
                apiKeyAsStored,
                now,
                now);
    }

    private SecretColumnScanner scannerWith(byte[] key) {
        return new SecretColumnScanner(dataSource, new SecretCipher(key));
    }

    private SecretsSelfCheck selfCheck(byte[] key, SelfCheckMode mode) {
        return new SecretsSelfCheck(
                scannerWith(key), List.of(COLUMN), new SecretsSelfCheckProperties(mode));
    }

    @Test
    void reportsOkWhenEveryRowIsEncryptedWithTheMountedKey() {
        insert(UUID.randomUUID(), new SecretCipher(KEY).encrypt("sk-live-aaa"));
        insert(UUID.randomUUID(), new SecretCipher(KEY).encrypt("sk-live-bbb"));

        ColumnResult result = scannerWith(KEY).scan(COLUMN);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.plaintextCount()).isZero();
        assertThat(result.encryptedRows()).isEqualTo(2);
    }

    @Test
    void findsAPlaintextRowAndReportsItsPrimaryKey() {
        UUID legacyId = UUID.randomUUID();
        // A straight INSERT bypassing JPA: this is what a row written before encryption
        // existed actually looks like on disk - a bare key with no v1: prefix.
        insert(legacyId, "sk-live-legacy-plaintext");
        insert(UUID.randomUUID(), new SecretCipher(KEY).encrypt("sk-live-ok"));

        ColumnResult result = scannerWith(KEY).scan(COLUMN);

        assertThat(result.outcome()).isEqualTo(Outcome.PLAINTEXT);
        assertThat(result.plaintextCount()).isEqualTo(1);
        assertThat(result.encryptedRows()).isEqualTo(1);
        assertThat(result.plaintextIds()).containsExactly(legacyId.toString());
    }

    @Test
    void reportsKeyMismatchWhenTheMountedKeyCannotDecryptStoredData() {
        insert(UUID.randomUUID(), new SecretCipher(KEY).encrypt("sk-live-aaa"));

        ColumnResult result = scannerWith(OTHER_KEY).scan(COLUMN);

        assertThat(result.outcome()).isEqualTo(Outcome.KEY_MISMATCH);
        // The whole point of the key proof: a prefix-only scan would have said "clean" here.
        assertThat(result.plaintextCount()).isZero();
    }

    @Test
    void reportsUnverifiedWhenThereIsNothingToProveTheKeyAgainst() {
        ColumnResult result = scannerWith(KEY).scan(COLUMN);

        assertThat(result.outcome()).isEqualTo(Outcome.UNVERIFIED);
        assertThat(result.isProblem()).isTrue();
    }

    @Test
    void nullApiKeysAreNotCountedAsPlaintext() {
        insert(UUID.randomUUID(), null);
        insert(UUID.randomUUID(), new SecretCipher(KEY).encrypt("sk-live-aaa"));

        ColumnResult result = scannerWith(KEY).scan(COLUMN);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.plaintextCount()).isZero();
    }

    @Test
    void failModeRefusesToStartOnPlaintextButWarnModeDoesNot() {
        insert(UUID.randomUUID(), "sk-live-legacy-plaintext");

        assertThatThrownBy(() -> selfCheck(KEY, SelfCheckMode.FAIL).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm_provider_configs.api_key");

        assertThatCode(() -> selfCheck(KEY, SelfCheckMode.WARN).run(null))
                .doesNotThrowAnyException();
    }

    /** The gauge must be able to go green again after an operator repairs the row. */
    @Test
    void repairingTheRowClearsTheFindingOnRescan() {
        UUID legacyId = UUID.randomUUID();
        insert(legacyId, "sk-live-legacy-plaintext");

        SecretsSelfCheck check = selfCheck(KEY, SelfCheckMode.WARN);
        check.run(null);
        assertThat(check.lastResults().get(0).outcome()).isEqualTo(Outcome.PLAINTEXT);

        jdbc.update(
                "update llm_provider_configs set api_key = ? where id = ?",
                new SecretCipher(KEY).encrypt("sk-live-repaired"),
                legacyId);

        check.rescan();
        assertThat(check.lastResults().get(0).outcome()).isEqualTo(Outcome.OK);
        assertThat(check.lastResults().get(0).plaintextCount()).isZero();
    }
}
