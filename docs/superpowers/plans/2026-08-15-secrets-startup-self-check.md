# Secrets Startup Self-Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Discover plaintext secrets and a mismatched `EMCIP_SECRET_KEY` at service boot instead of on the first operator action that touches an affected row.

**Architecture:** A `SecretsSelfCheck` `ApplicationRunner` in `emcip-core` scans a per-service list of `SecretColumn` descriptors over plain JDBC. It counts unprefixed rows and decrypts exactly one encrypted row per column to prove the mounted key. Results feed a Micrometer gauge (the alertable surface) and an always-`UP` health indicator (the operator-facing surface). Default mode `warn` boots normally so the #241/#243 in-product repair paths stay reachable; `fail` is opt-in per environment.

**Tech Stack:** Java 21, Spring Boot 4.0.5, plain JDBC (`javax.sql.DataSource`), Micrometer 1.16.4, `spring-boot-health` 4.0.5, JUnit 5 + AssertJ + Mockito, Testcontainers (llm-orchestrator harness), Maven, Spotless.

**Spec:** `docs/superpowers/specs/2026-08-15-secrets-startup-self-check-design.md`

## Global Constraints

- **Java 21, Spring Boot 4.** Health API is `org.springframework.boot.health.contributor.{Health,HealthIndicator}` (Boot 4 moved these out of `spring-boot-actuator` into `spring-boot-health`).
- **`mvn spotless:apply` before every commit.** Success indicator is `0 were changed to be clean`.
- **Lombok**: use `@Slf4j` and `@RequiredArgsConstructor`; never write manual getters.
- **emcip-core must stay dependency-light.** It has `spring-boot-starter`, `micrometer-core`, `spring-context`. It has **no** `spring-jdbc` (so no `JdbcTemplate` — use raw `java.sql` from the JDK) and **no** actuator (so `spring-boot-health` must be added as `provided` + `optional`, matching the existing `jakarta.persistence-api` / `spring-web` pattern in `emcip-core/pom.xml`).
- **`io.emcip.common.crypto` is outside every service's component-scan base package.** Nothing in it is picked up automatically; every service opts in via `@Import`. Preserve this — it is what lets services storing no secrets skip the key entirely.
- **Never log, expose, or assert on a secret value, a ciphertext, or any prefix of either.** `PlaintextSecretException` sets the discipline: `table.column` and primary keys only.
- **The encrypted-value marker is `SecretCipher.PREFIX` (`"v1:"`).** Reference the constant; never hardcode the literal in production code.
- **Cron timing rule (CLAUDE.md #6):** never schedule at a round time. The re-scan uses `"23 17 * * * *"` (17m 23s past the hour).
- **Identifiers are concatenated into SQL**, so `SecretColumn` must validate them against a strict allowlist regex in its canonical constructor. This is the mitigation CodeQL's `java/sql-injection` expects; do not skip it.
- **Every check must be observed failing at least once** (P3.4 lesson). A test that has never been seen red is not evidence.

## Outcome precedence (referenced by several tasks)

A column yields exactly one `Outcome`, but the underlying counts are always preserved separately so nothing is lost:

`KEY_MISMATCH` > `PLAINTEXT` > `UNVERIFIED` > `OK`

`KEY_MISMATCH` outranks `PLAINTEXT` because an unreadable key makes every secret in the service unusable, whereas plaintext rows are individually repairable. When both conditions hold, `outcome` is `KEY_MISMATCH` and `plaintextCount` is still the true count.

---

## File Structure

**Create — `emcip-core/src/main/java/io/emcip/common/crypto/`:**

| File | Responsibility |
|------|----------------|
| `SecretColumn.java` | Validated `(table, column, pkColumn)` descriptor. Owns identifier safety. |
| `SecretsSelfCheckProperties.java` | `emcip.secrets.self-check` → `WARN\|FAIL\|OFF`. |
| `ColumnResult.java` | Per-column scan result: outcome + counts + offending PKs. |
| `SecretColumnScanner.java` | The only class that talks SQL. Produces a `ColumnResult`. |
| `SecretsSelfCheck.java` | Orchestration: runs at boot + hourly, applies mode, logs, holds latest state. |
| `SecretsMetrics.java` | Registers the two gauges against `SecretsSelfCheck` state. |
| `SecretsHealthIndicator.java` | Always-`UP` reporting surface reading the same state. |

**Modify:**

| File | Change |
|------|--------|
| `emcip-core/pom.xml` | Add `spring-boot-health` as `provided` + `optional`. |
| `emcip-admin-api/.../config/CryptoConfig.java` | Contribute 2 columns; import self-check config. |
| `emcip-knowledge-engine/.../config/CryptoConfig.java` | Contribute 1 column. |
| `emcip-llm-orchestrator/.../config/CryptoConfig.java` | Contribute 1 column. |
| `emcip-llm-orchestrator/.../LlmOrchestratorApplication.java` | Add `@EnableScheduling` — **it is missing**, so the re-scan would silently never run. |
| 3 × `application.yml` | `emcip.secrets.self-check: ${EMCIP_SECRETS_SELF_CHECK:warn}` |
| `docs/operations/secrets-encryption.md` | Self-check section. |
| `docs/superpowers/BACKLOG.md`, `documentation/ROADMAP.md` | Status + premise correction. |

**Test:**

| File | Covers |
|------|--------|
| `emcip-core/src/test/.../SecretColumnTest.java` | Identifier validation. |
| `emcip-core/src/test/.../SecretColumnScannerTest.java` | Scan logic + outcome classification, mocked `DataSource`. |
| `emcip-core/src/test/.../SecretsSelfCheckTest.java` | Modes, log redaction, state. |
| `emcip-core/src/test/.../SecretsHealthIndicatorTest.java` | Always-`UP`, details shape. |
| `emcip-llm-orchestrator/src/test/.../SecretsSelfCheckIT.java` | **Real Postgres.** Proves the SQL, plaintext detection, key mismatch, `fail` mode, gauge clearing. |

### Deviation from spec §6 — read this

Spec §6 asks for a Testcontainers IT "one per persistence style — JPA and the admin-api/R2DBC service." **This plan builds only the JPA-side IT (llm-orchestrator).**

Reason: approach A runs the *identical* JDBC code path in all three services, so there is no persistence-style difference left in the thing under test. admin-api has **no** Testcontainers harness today (llm-orchestrator and knowledge-engine do), and standing one up is a larger piece of work than this feature. The admin-api-specific risk — that its Liquibase-credentialed `spring.datasource` can read `telegram_accounts` — is a live-data question that spec §7's cluster verification answers directly and a container cannot.

If you want the admin-api harness built anyway, say so and it becomes Task 5b.

---

## Task 1: `SecretColumn` — validated descriptor

**Files:**
- Create: `emcip-core/src/main/java/io/emcip/common/crypto/SecretColumn.java`
- Test: `emcip-core/src/test/java/io/emcip/common/crypto/SecretColumnTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `record SecretColumn(String table, String column, String pkColumn)` with `String location()` returning `"table.column"`. Canonical constructor throws `IllegalArgumentException` on any identifier failing `^[a-z_][a-z0-9_]{0,62}$`.

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretColumnTest {

    @Test
    void acceptsPlainLowerSnakeCaseIdentifiers() {
        SecretColumn c = new SecretColumn("telegram_accounts", "api_hash", "id");
        assertThat(c.location()).isEqualTo("telegram_accounts.api_hash");
    }

    // These identifiers are concatenated into SQL. The constructor is the only thing
    // standing between a descriptor and an injection, so it must reject anything that
    // is not a bare lower-snake-case identifier.
    @Test
    void rejectsIdentifiersThatCouldBreakOutOfTheQuery() {
        assertThatThrownBy(() -> new SecretColumn("users; drop table users", "api_key", "id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretColumn("users", "api_key\" , 1 --", "id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretColumn("Users", "api_key", "id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretColumn("users", "", "id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretColumn(null, "api_key", "id"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectionMessageNamesTheOffendingFieldWithoutEchoingUnboundedInput() {
        assertThatThrownBy(() -> new SecretColumn("users", "bad name", "id"))
                .hasMessageContaining("column");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl emcip-core test -Dtest=SecretColumnTest`
Expected: FAIL — compilation error, `SecretColumn` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl emcip-core test -Dtest=SecretColumnTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
mvn -q -pl emcip-core spotless:apply
git add emcip-core/src/main/java/io/emcip/common/crypto/SecretColumn.java \
        emcip-core/src/test/java/io/emcip/common/crypto/SecretColumnTest.java
git commit -m "feat(core): add SecretColumn descriptor with SQL-identifier validation"
```

---

## Task 2: Properties and result types

**Files:**
- Create: `emcip-core/src/main/java/io/emcip/common/crypto/SecretsSelfCheckProperties.java`
- Create: `emcip-core/src/main/java/io/emcip/common/crypto/ColumnResult.java`
- Test: `emcip-core/src/test/java/io/emcip/common/crypto/SecretsSelfCheckPropertiesTest.java`

**Interfaces:**
- Consumes: `SecretColumn` (Task 1).
- Produces:
  - `SecretsSelfCheckProperties(SelfCheckMode selfCheck)` with nested `enum SelfCheckMode { WARN, FAIL, OFF }`, defaulting to `WARN` when unset.
  - `ColumnResult(SecretColumn column, Outcome outcome, long encryptedRows, long plaintextCount, List<String> plaintextIds)` with nested `enum Outcome { OK, PLAINTEXT, KEY_MISMATCH, UNVERIFIED }` and `boolean isProblem()` (true unless `OK`).

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.crypto.SecretsSelfCheckProperties.SelfCheckMode;
import org.junit.jupiter.api.Test;

class SecretsSelfCheckPropertiesTest {

    @Test
    void defaultsToWarnWhenUnset() {
        assertThat(new SecretsSelfCheckProperties(null).selfCheck()).isEqualTo(SelfCheckMode.WARN);
    }

    @Test
    void keepsAnExplicitMode() {
        assertThat(new SecretsSelfCheckProperties(SelfCheckMode.FAIL).selfCheck())
                .isEqualTo(SelfCheckMode.FAIL);
        assertThat(new SecretsSelfCheckProperties(SelfCheckMode.OFF).selfCheck())
                .isEqualTo(SelfCheckMode.OFF);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl emcip-core test -Dtest=SecretsSelfCheckPropertiesTest`
Expected: FAIL — compilation error, type does not exist.

- [ ] **Step 3: Write minimal implementation**

`SecretsSelfCheckProperties.java`:

```java
package io.emcip.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls what the startup self-check does when it finds a problem.
 *
 * <p>The default is {@link SelfCheckMode#WARN} rather than {@code FAIL} on purpose. PRs #241 and
 * #243 built in-product repair paths for plaintext secrets — a 409 that opens a Credentials dialog
 * — and those paths need the service running. Refusing to start would deadlock exactly the case
 * they were built to fix, leaving direct database access as the only recovery.
 *
 * @param selfCheck what to do on a finding; null binds to {@code WARN}
 */
@ConfigurationProperties("emcip.secrets")
public record SecretsSelfCheckProperties(SelfCheckMode selfCheck) {

    public SecretsSelfCheckProperties {
        if (selfCheck == null) {
            selfCheck = SelfCheckMode.WARN;
        }
    }

    public enum SelfCheckMode {
        /** Log, record metrics, start normally. The shipping default. */
        WARN,
        /** Log, then refuse to start. Opt-in, per environment, after it has reported clean. */
        FAIL,
        /** Skip entirely. Local dev and tests that do not exercise this. */
        OFF
    }
}
```

`ColumnResult.java`:

```java
package io.emcip.common.crypto;

import java.util.List;

/**
 * What the self-check found in one column.
 *
 * <p>{@code outcome} is single-valued but the counts are always preserved, so a column that is
 * both key-mismatched and holding plaintext still reports the true {@code plaintextCount}.
 *
 * @param column the column scanned
 * @param outcome single most severe finding, per the precedence in {@link Outcome}
 * @param encryptedRows rows carrying the {@code v1:} prefix
 * @param plaintextCount non-null rows lacking the prefix
 * @param plaintextIds primary keys of offending rows, capped; never contains a secret value
 */
public record ColumnResult(
        SecretColumn column,
        Outcome outcome,
        long encryptedRows,
        long plaintextCount,
        List<String> plaintextIds) {

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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl emcip-core test -Dtest=SecretsSelfCheckPropertiesTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
mvn -q -pl emcip-core spotless:apply
git add emcip-core/src/main/java/io/emcip/common/crypto/SecretsSelfCheckProperties.java \
        emcip-core/src/main/java/io/emcip/common/crypto/ColumnResult.java \
        emcip-core/src/test/java/io/emcip/common/crypto/SecretsSelfCheckPropertiesTest.java
git commit -m "feat(core): add self-check mode properties and per-column result type"
```

---

## Task 3: `SecretColumnScanner` — the SQL

**Files:**
- Create: `emcip-core/src/main/java/io/emcip/common/crypto/SecretColumnScanner.java`
- Test: `emcip-core/src/test/java/io/emcip/common/crypto/SecretColumnScannerTest.java`

**Interfaces:**
- Consumes: `SecretColumn`, `ColumnResult`, `ColumnResult.Outcome` (Tasks 1–2); `SecretCipher` (existing, `decrypt(String stored, String location)`).
- Produces: `SecretColumnScanner(DataSource dataSource, SecretCipher cipher)` with `ColumnResult scan(SecretColumn column)`.

- [ ] **Step 1: Write the failing test**

Mocked `DataSource` — the real SQL is proven against Postgres in Task 6. This test pins the *classification* logic, which is where the bugs live.

```java
package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.emcip.common.crypto.ColumnResult.Outcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
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

        when(connection.prepareStatement(anyString()))
                .thenReturn(countStmt, idStmt, sampleStmt);
    }

    @Test
    void reportsOkWhenNoPlaintextAndKeyDecryptsTheSample() throws Exception {
        String encrypted = new SecretCipher(KEY_A).encrypt("some-api-key");
        stubQueries(0, encrypted, 3);

        ColumnResult result = new SecretColumnScanner(dataSource, new SecretCipher(KEY_A)).scan(COLUMN);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.plaintextCount()).isZero();
        assertThat(result.isProblem()).isFalse();
    }

    @Test
    void reportsPlaintextWithOffendingPrimaryKeys() throws Exception {
        String encrypted = new SecretCipher(KEY_A).encrypt("some-api-key");
        stubQueries(1, encrypted, 2);

        ColumnResult result = new SecretColumnScanner(dataSource, new SecretCipher(KEY_A)).scan(COLUMN);

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

        ColumnResult result = new SecretColumnScanner(dataSource, new SecretCipher(KEY_B)).scan(COLUMN);

        assertThat(result.outcome()).isEqualTo(Outcome.KEY_MISMATCH);
    }

    @Test
    void keyMismatchOutranksPlaintextButKeepsTheTrueCount() throws Exception {
        String encryptedWithA = new SecretCipher(KEY_A).encrypt("some-api-key");
        stubQueries(2, encryptedWithA, 1);

        ColumnResult result = new SecretColumnScanner(dataSource, new SecretCipher(KEY_B)).scan(COLUMN);

        assertThat(result.outcome()).isEqualTo(Outcome.KEY_MISMATCH);
        assertThat(result.plaintextCount()).isEqualTo(2);
    }

    /** An empty column must not be able to masquerade as a passing check. */
    @Test
    void reportsUnverifiedWhenThereIsNoEncryptedRowToProveTheKeyAgainst() throws Exception {
        stubQueries(0, null, 0);

        ColumnResult result = new SecretColumnScanner(dataSource, new SecretCipher(KEY_A)).scan(COLUMN);

        assertThat(result.outcome()).isEqualTo(Outcome.UNVERIFIED);
        assertThat(result.isProblem()).isTrue();
    }

    @Test
    void capsReportedPrimaryKeys() {
        assertThat(ColumnResult.MAX_REPORTED_IDS).isEqualTo(20);
    }

    @Test
    void base64KeysAreDistinct() {
        assertThat(Base64.getEncoder().encodeToString(KEY_A))
                .isNotEqualTo(Base64.getEncoder().encodeToString(KEY_B));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl emcip-core test -Dtest=SecretColumnScannerTest`
Expected: FAIL — compilation error, `SecretColumnScanner` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
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
 * <p>Runs three read-only queries per column: how many rows lack the {@code v1:} prefix, which
 * rows those are (primary keys only), and one encrypted sample used to prove the mounted key can
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

            List<String> plaintextIds = new ArrayList<>();
            if (plaintextCount > 0) {
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

            return new ColumnResult(column, outcome, encryptedRows, plaintextCount, plaintextIds);
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
                } catch (RuntimeException e) {
                    return false;
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl emcip-core test -Dtest=SecretColumnScannerTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
mvn -q -pl emcip-core spotless:apply
git add emcip-core/src/main/java/io/emcip/common/crypto/SecretColumnScanner.java \
        emcip-core/src/test/java/io/emcip/common/crypto/SecretColumnScannerTest.java
git commit -m "feat(core): scan a secret column for plaintext rows and prove the mounted key"
```

---

## Task 4: `SecretsSelfCheck` — orchestration, modes, logging

**Files:**
- Create: `emcip-core/src/main/java/io/emcip/common/crypto/SecretsSelfCheck.java`
- Test: `emcip-core/src/test/java/io/emcip/common/crypto/SecretsSelfCheckTest.java`

**Interfaces:**
- Consumes: `SecretColumnScanner.scan(SecretColumn)`, `SecretsSelfCheckProperties`, `ColumnResult` (Tasks 1–3).
- Produces: `SecretsSelfCheck(SecretColumnScanner scanner, List<SecretColumn> columns, SecretsSelfCheckProperties properties)` implementing `ApplicationRunner`, plus:
  - `List<ColumnResult> run()` — scans, logs, updates state, returns results. Never throws on findings.
  - `List<ColumnResult> lastResults()` — latest state, empty before the first scan.
  - `void rescan()` — `@Scheduled` entry point; never fails the application.
  - `SelfCheckMode mode()`.

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.emcip.common.crypto.ColumnResult.Outcome;
import io.emcip.common.crypto.SecretsSelfCheckProperties.SelfCheckMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretsSelfCheckTest {

    private static final SecretColumn COLUMN =
            new SecretColumn("llm_provider_configs", "api_key", "id");

    private SecretColumnScanner scannerReturning(Outcome outcome, long plaintextCount) {
        SecretColumnScanner scanner = mock(SecretColumnScanner.class);
        when(scanner.scan(any()))
                .thenReturn(
                        new ColumnResult(
                                COLUMN, outcome, 1, plaintextCount, plaintextCount > 0
                                        ? List.of("11111111-1111-1111-1111-111111111111")
                                        : List.of()));
        return scanner;
    }

    private SecretsSelfCheck selfCheck(SelfCheckMode mode, Outcome outcome, long plaintextCount) {
        return new SecretsSelfCheck(
                scannerReturning(outcome, plaintextCount),
                List.of(COLUMN),
                new SecretsSelfCheckProperties(mode));
    }

    @Test
    void warnModeBootsDespiteAFinding() {
        SecretsSelfCheck check = selfCheck(SelfCheckMode.WARN, Outcome.PLAINTEXT, 1);

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
        assertThat(check.lastResults()).singleElement().satisfies(
                r -> assertThat(r.outcome()).isEqualTo(Outcome.PLAINTEXT));
    }

    @Test
    void failModeRefusesToStartOnAFinding() {
        SecretsSelfCheck check = selfCheck(SelfCheckMode.FAIL, Outcome.PLAINTEXT, 1);

        assertThatThrownBy(() -> check.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm_provider_configs.api_key");
    }

    @Test
    void failModeStartsNormallyWhenEverythingIsOk() {
        SecretsSelfCheck check = selfCheck(SelfCheckMode.FAIL, Outcome.OK, 0);

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    }

    @Test
    void failModeAlsoRefusesOnKeyMismatchAndOnUnverified() {
        assertThatThrownBy(() -> selfCheck(SelfCheckMode.FAIL, Outcome.KEY_MISMATCH, 0).run(null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> selfCheck(SelfCheckMode.FAIL, Outcome.UNVERIFIED, 0).run(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void offModeScansNothing() {
        SecretColumnScanner scanner = scannerReturning(Outcome.PLAINTEXT, 1);
        SecretsSelfCheck check =
                new SecretsSelfCheck(
                        scanner, List.of(COLUMN), new SecretsSelfCheckProperties(SelfCheckMode.OFF));

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
        assertThat(check.lastResults()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(scanner);
    }

    /**
     * fail governs startup only. Killing a healthy running pod because a new plaintext row appeared
     * would be a self-inflicted outage; the metric and log already carry the signal.
     */
    @Test
    void scheduledRescanNeverThrowsEvenInFailMode() {
        SecretsSelfCheck check = selfCheck(SelfCheckMode.FAIL, Outcome.PLAINTEXT, 1);

        assertThatCode(check::rescan).doesNotThrowAnyException();
        assertThat(check.lastResults()).hasSize(1);
    }

    @Test
    void rescanSurvivesAScannerFailureAndKeepsTheApplicationAlive() {
        SecretColumnScanner scanner = mock(SecretColumnScanner.class);
        when(scanner.scan(any())).thenThrow(new IllegalStateException("database is down"));
        SecretsSelfCheck check =
                new SecretsSelfCheck(
                        scanner,
                        List.of(COLUMN),
                        new SecretsSelfCheckProperties(SelfCheckMode.WARN));

        assertThatCode(check::rescan).doesNotThrowAnyException();
    }

    @Test
    void lastResultsIsEmptyBeforeTheFirstScan() {
        assertThat(selfCheck(SelfCheckMode.WARN, Outcome.OK, 0).lastResults()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl emcip-core test -Dtest=SecretsSelfCheckTest`
Expected: FAIL — compilation error, `SecretsSelfCheck` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.emcip.common.crypto;

import io.emcip.common.crypto.SecretsSelfCheckProperties.SelfCheckMode;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Verifies at boot that every registered secret column is fully encrypted and that the mounted
 * {@code EMCIP_SECRET_KEY} actually decrypts stored data.
 *
 * <p>An {@link ApplicationRunner} rather than an {@code ApplicationReadyEvent} listener for two
 * reasons: an exception thrown from a runner fails {@code SpringApplication.run()} cleanly, which
 * is what {@link SelfCheckMode#FAIL} needs, and runners execute before the application reports
 * ready, so a failing check never briefly serves traffic.
 *
 * <p>The scheduled re-scan exists so the metric can *clear*. A gauge pinned at 1 until the next pod
 * restart, long after an operator repaired the row, is an alert that cannot go green — nearly as
 * harmful as a check that never fires.
 */
@Slf4j
public class SecretsSelfCheck implements ApplicationRunner {

    private final SecretColumnScanner scanner;
    private final List<SecretColumn> columns;
    private final SecretsSelfCheckProperties properties;

    private volatile List<ColumnResult> lastResults = List.of();

    public SecretsSelfCheck(
            SecretColumnScanner scanner,
            List<SecretColumn> columns,
            SecretsSelfCheckProperties properties) {
        this.scanner = scanner;
        this.columns = List.copyOf(columns);
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ColumnResult> results = scanAndLog();
        if (properties.selfCheck() == SelfCheckMode.FAIL) {
            List<ColumnResult> problems = results.stream().filter(ColumnResult::isProblem).toList();
            if (!problems.isEmpty()) {
                throw new IllegalStateException(
                        "Secret self-check failed in "
                                + problems.size()
                                + " column(s): "
                                + problems.stream()
                                        .map(r -> r.column().location() + " [" + r.outcome() + "]")
                                        .toList()
                                + ". Set emcip.secrets.self-check=warn to start anyway and repair"
                                + " via the Admin UI. See docs/operations/secrets-encryption.md");
            }
        }
    }

    /** Scheduled re-scan. Never throws: {@code FAIL} governs startup only. */
    @Scheduled(cron = "23 17 * * * *")
    public void rescan() {
        try {
            scanAndLog();
        } catch (RuntimeException e) {
            log.warn("Secret self-check re-scan failed; keeping previous results", e);
        }
    }

    public List<ColumnResult> lastResults() {
        return lastResults;
    }

    public SelfCheckMode mode() {
        return properties.selfCheck();
    }

    private List<ColumnResult> scanAndLog() {
        if (properties.selfCheck() == SelfCheckMode.OFF) {
            return List.of();
        }
        List<ColumnResult> results = new ArrayList<>();
        for (SecretColumn column : columns) {
            results.add(scanner.scan(column));
        }
        lastResults = List.copyOf(results);
        logResults(results);
        return lastResults;
    }

    private void logResults(List<ColumnResult> results) {
        long problems = results.stream().filter(ColumnResult::isProblem).count();
        StringBuilder report = new StringBuilder("SECRET SELF-CHECK  mode=")
                .append(properties.selfCheck().name().toLowerCase(java.util.Locale.ROOT));
        for (ColumnResult r : results) {
            report.append(String.format(
                    "%n  %-34s %4d encrypted, %4d plaintext  [%s]",
                    r.column().location(), r.encryptedRows(), r.plaintextCount(), r.outcome()));
            // Primary keys only. A secret value must never reach a log.
            if (!r.plaintextIds().isEmpty()) {
                report.append(String.format("%n    offending "))
                        .append(r.column().pkColumn())
                        .append(": ")
                        .append(r.plaintextIds());
            }
        }
        if (problems == 0) {
            log.info("{}", report);
        } else {
            report.append(
                    "\n  Repair plaintext values via Admin UI -> Credentials."
                            + " See docs/operations/secrets-encryption.md");
            log.error("{}", report);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl emcip-core test -Dtest=SecretsSelfCheckTest`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
mvn -q -pl emcip-core spotless:apply
git add emcip-core/src/main/java/io/emcip/common/crypto/SecretsSelfCheck.java \
        emcip-core/src/test/java/io/emcip/common/crypto/SecretsSelfCheckTest.java
git commit -m "feat(core): run the secrets self-check at boot with warn/fail/off modes"
```

---

## Task 5: Metrics and health indicator

**Files:**
- Modify: `emcip-core/pom.xml`
- Create: `emcip-core/src/main/java/io/emcip/common/crypto/SecretsMetrics.java`
- Create: `emcip-core/src/main/java/io/emcip/common/crypto/SecretsHealthIndicator.java`
- Test: `emcip-core/src/test/java/io/emcip/common/crypto/SecretsHealthIndicatorTest.java`
- Test: `emcip-core/src/test/java/io/emcip/common/crypto/SecretsMetricsTest.java`

**Interfaces:**
- Consumes: `SecretsSelfCheck.lastResults()`, `SecretsSelfCheck.mode()`, `ColumnResult` (Tasks 2, 4).
- Produces:
  - `SecretsMetrics(MeterRegistry registry, SecretsSelfCheck selfCheck, List<SecretColumn> columns)` registering gauges `emcip.secrets.plaintext_count{column}` and `emcip.secrets.key_status{column}` (`0` OK, `1` mismatch, `2` unverified).
  - `SecretsHealthIndicator(SecretsSelfCheck selfCheck)` implementing `HealthIndicator`.

- [ ] **Step 1: Add the health dependency**

`emcip-core` has no actuator on its classpath. Boot 4 moved `HealthIndicator` into `spring-boot-health`. Add to `emcip-core/pom.xml` inside `<dependencies>`, following the existing `provided` + `optional` pattern used for `jakarta.persistence-api` and `spring-web`:

```xml
<!-- Spring Boot health contributor API (optional - for SecretsHealthIndicator).
     provided+optional so services that do not run the secrets self-check are unaffected
     and no actuator dependency leaks onto their classpath. -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-health</artifactId>
    <scope>provided</scope>
    <optional>true</optional>
</dependency>
```

- [ ] **Step 2: Write the failing tests**

`SecretsHealthIndicatorTest.java`:

```java
package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.emcip.common.crypto.ColumnResult.Outcome;
import io.emcip.common.crypto.SecretsSelfCheckProperties.SelfCheckMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class SecretsHealthIndicatorTest {

    private static final SecretColumn COLUMN =
            new SecretColumn("telegram_accounts", "api_hash", "id");

    private SecretsSelfCheck selfCheckWith(ColumnResult result) {
        SecretsSelfCheck check = mock(SecretsSelfCheck.class);
        when(check.lastResults()).thenReturn(List.of(result));
        when(check.mode()).thenReturn(SelfCheckMode.WARN);
        return check;
    }

    /**
     * Health indicators in this project feed the Kubernetes readiness probe. Reporting DOWN here
     * would pull the pod out of rotation and make the Admin UI repair path unreachable - the exact
     * deadlock the warn default exists to prevent. This indicator reports, it does not gate.
     */
    @Test
    void staysUpEvenWhenPlaintextIsFound() {
        Health health =
                new SecretsHealthIndicator(
                                selfCheckWith(
                                        new ColumnResult(
                                                COLUMN,
                                                Outcome.PLAINTEXT,
                                                11,
                                                1,
                                                List.of("11111111-1111-1111-1111-111111111111"))))
                        .health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void staysUpOnKeyMismatchToo() {
        Health health =
                new SecretsHealthIndicator(
                                selfCheckWith(
                                        new ColumnResult(COLUMN, Outcome.KEY_MISMATCH, 3, 0, List.of())))
                        .health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsModeCountsAndPerColumnOutcome() {
        Health health =
                new SecretsHealthIndicator(
                                selfCheckWith(
                                        new ColumnResult(
                                                COLUMN,
                                                Outcome.PLAINTEXT,
                                                11,
                                                1,
                                                List.of("11111111-1111-1111-1111-111111111111"))))
                        .health();

        assertThat(health.getDetails()).containsEntry("mode", "warn");
        assertThat(health.getDetails()).containsEntry("plaintextCount", 1L);

        @SuppressWarnings("unchecked")
        Map<String, Object> columns = (Map<String, Object>) health.getDetails().get("columns");
        assertThat(columns).containsKey("telegram_accounts.api_hash");
    }

    /** Primary keys are operational breadcrumbs; a secret value must never reach an endpoint. */
    @Test
    void detailsNeverCarrySecretValues() {
        Health health =
                new SecretsHealthIndicator(
                                selfCheckWith(
                                        new ColumnResult(COLUMN, Outcome.OK, 11, 0, List.of())))
                        .health();

        assertThat(health.getDetails().toString()).doesNotContain("v1:");
    }
}
```

`SecretsMetricsTest.java`:

```java
package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.emcip.common.crypto.ColumnResult.Outcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretsMetricsTest {

    private static final SecretColumn COLUMN =
            new SecretColumn("llm_provider_configs", "api_key", "id");

    @Test
    void gaugesReflectTheLatestScanAndCanClear() {
        SecretsSelfCheck check = mock(SecretsSelfCheck.class);
        when(check.lastResults())
                .thenReturn(List.of(new ColumnResult(COLUMN, Outcome.PLAINTEXT, 2, 1, List.of("a"))));

        MeterRegistry registry = new SimpleMeterRegistry();
        new SecretsMetrics(registry, check, List.of(COLUMN));

        assertThat(
                        registry.get("emcip.secrets.plaintext_count")
                                .tag("column", "llm_provider_configs.api_key")
                                .gauge()
                                .value())
                .isEqualTo(1.0);

        // The repair case: the gauge must be able to go back to zero without a restart.
        when(check.lastResults())
                .thenReturn(List.of(new ColumnResult(COLUMN, Outcome.OK, 3, 0, List.of())));

        assertThat(
                        registry.get("emcip.secrets.plaintext_count")
                                .tag("column", "llm_provider_configs.api_key")
                                .gauge()
                                .value())
                .isZero();
    }

    @Test
    void keyStatusEncodesOkMismatchAndUnverifiedDistinctly() {
        SecretsSelfCheck check = mock(SecretsSelfCheck.class);
        MeterRegistry registry = new SimpleMeterRegistry();
        new SecretsMetrics(registry, check, List.of(COLUMN));

        when(check.lastResults())
                .thenReturn(List.of(new ColumnResult(COLUMN, Outcome.OK, 3, 0, List.of())));
        assertThat(keyStatus(registry)).isZero();

        when(check.lastResults())
                .thenReturn(List.of(new ColumnResult(COLUMN, Outcome.KEY_MISMATCH, 3, 0, List.of())));
        assertThat(keyStatus(registry)).isEqualTo(1.0);

        when(check.lastResults())
                .thenReturn(List.of(new ColumnResult(COLUMN, Outcome.UNVERIFIED, 0, 0, List.of())));
        assertThat(keyStatus(registry)).isEqualTo(2.0);
    }

    private double keyStatus(MeterRegistry registry) {
        return registry.get("emcip.secrets.key_status")
                .tag("column", "llm_provider_configs.api_key")
                .gauge()
                .value();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -q -pl emcip-core test -Dtest='SecretsHealthIndicatorTest,SecretsMetricsTest'`
Expected: FAIL — compilation errors, neither class exists.

- [ ] **Step 4: Write minimal implementation**

`SecretsMetrics.java`:

```java
package io.emcip.common.crypto;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;

/**
 * Publishes the self-check result as gauges. This is the surface that alerts (P3.22) — the health
 * indicator only reports, and a log line cannot be alerted on.
 *
 * <p>Gauges read {@link SecretsSelfCheck#lastResults()} on scrape rather than caching a value, so a
 * repaired row clears the metric at the next scheduled re-scan without a pod restart.
 */
public class SecretsMetrics {

    private static final double KEY_OK = 0;
    private static final double KEY_MISMATCH = 1;
    private static final double KEY_UNVERIFIED = 2;

    public SecretsMetrics(
            MeterRegistry registry, SecretsSelfCheck selfCheck, List<SecretColumn> columns) {
        for (SecretColumn column : columns) {
            Gauge.builder(
                            "emcip.secrets.plaintext_count",
                            selfCheck,
                            check -> value(check, column, r -> (double) r.plaintextCount()))
                    .description("Rows in this column stored without the v1: encryption prefix")
                    .tag("column", column.location())
                    .register(registry);

            Gauge.builder(
                            "emcip.secrets.key_status",
                            selfCheck,
                            check -> value(check, column, SecretsMetrics::keyStatus))
                    .description("0 = key decrypts stored data, 1 = mismatch, 2 = unverified")
                    .tag("column", column.location())
                    .register(registry);
        }
    }

    private static double value(
            SecretsSelfCheck selfCheck,
            SecretColumn column,
            java.util.function.ToDoubleFunction<ColumnResult> extractor) {
        return selfCheck.lastResults().stream()
                .filter(r -> r.column().equals(column))
                .findFirst()
                .map(extractor::applyAsDouble)
                .orElse(0.0);
    }

    private static double keyStatus(ColumnResult result) {
        return switch (result.outcome()) {
            case KEY_MISMATCH -> KEY_MISMATCH;
            case UNVERIFIED -> KEY_UNVERIFIED;
            case OK, PLAINTEXT -> KEY_OK;
        };
    }
}
```

`SecretsHealthIndicator.java`:

```java
package io.emcip.common.crypto;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports the self-check result at {@code /actuator/health}, for an operator who is looking.
 *
 * <p><strong>Always UP.</strong> Health indicators in this project feed the Kubernetes readiness
 * probe, so reporting DOWN would pull the pod out of rotation and make the Admin UI repair path
 * unreachable — the deadlock the {@code warn} default exists to prevent. Alerting belongs on
 * {@link SecretsMetrics}; this is a reporting surface, not a gate.
 */
@RequiredArgsConstructor
public class SecretsHealthIndicator implements HealthIndicator {

    private final SecretsSelfCheck selfCheck;

    @Override
    public Health health() {
        Map<String, Object> columns = new LinkedHashMap<>();
        long plaintextTotal = 0;
        for (ColumnResult result : selfCheck.lastResults()) {
            plaintextTotal += result.plaintextCount();
            // Primary keys, counts and outcomes only. Never a value or a ciphertext.
            columns.put(
                    result.column().location(),
                    Map.of(
                            "outcome", result.outcome().name(),
                            "plaintext", result.plaintextCount(),
                            "encrypted", result.encryptedRows()));
        }
        return Health.up()
                .withDetail("mode", selfCheck.mode().name().toLowerCase(Locale.ROOT))
                .withDetail("plaintextCount", plaintextTotal)
                .withDetail("columns", columns)
                .build();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q -pl emcip-core test -Dtest='SecretsHealthIndicatorTest,SecretsMetricsTest'`
Expected: PASS, 6 tests.

- [ ] **Step 6: Commit**

```bash
mvn -q -pl emcip-core spotless:apply
git add emcip-core/pom.xml \
        emcip-core/src/main/java/io/emcip/common/crypto/SecretsMetrics.java \
        emcip-core/src/main/java/io/emcip/common/crypto/SecretsHealthIndicator.java \
        emcip-core/src/test/java/io/emcip/common/crypto/SecretsHealthIndicatorTest.java \
        emcip-core/src/test/java/io/emcip/common/crypto/SecretsMetricsTest.java
git commit -m "feat(core): expose secrets self-check as gauges and an always-UP health indicator"
```

---

## Task 6: Real-Postgres integration test

**Files:**
- Create: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/crypto/SecretsSelfCheckIT.java`

**Interfaces:**
- Consumes: everything from Tasks 1–5, plus the existing `io.emcip.llm.orchestrator.TestcontainersInitializer`.
- Produces: nothing consumed by later tasks.

This is the task that proves the SQL. Tasks 3–5 mock the `DataSource`, so nothing so far has executed a single statement against Postgres. `count(*) FILTER (WHERE ...)` is Postgres-specific and completely unverified until here.

Named `*IT.java` so failsafe runs it (surefire excludes both `*IT` and `*IntegrationTest` — see INF-CI-IT).

- [ ] **Step 1: Write the failing test**

```java
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

    private void insert(UUID id, String apiKeyAsStored) {
        jdbc.update(
                "insert into llm_provider_configs (id, provider_name, api_key, active, created_at,"
                        + " updated_at) values (?, ?, ?, true, ?, ?)",
                id,
                "provider-" + id,
                apiKeyAsStored,
                Instant.now(),
                Instant.now());
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl emcip-llm-orchestrator -am verify -Dit.test=SecretsSelfCheckIT -DfailIfNoTests=false`
Expected: FAIL — compilation error initially. If the classes compile but the SQL is wrong, expect a `PSQLException` from `count(*) FILTER`. **This is the point of the task** — do not proceed until you have seen a genuine red here.

Note: `insert(...)` assumes `llm_provider_configs` columns `id, provider_name, api_key, active, created_at, updated_at`. Verify against the entity `LlmProviderConfig` and adjust the INSERT if the real NOT NULL set differs — `TestcontainersInitializer` builds the schema with `ddl-auto=create-drop` from the entity mappings.

- [ ] **Step 3: Fix whatever the real database rejects**

Adjust `SecretColumnScanner`'s SQL until all 7 tests pass. Do not change the tests to match a broken query.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q -pl emcip-llm-orchestrator -am verify -Dit.test=SecretsSelfCheckIT -DfailIfNoTests=false`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
mvn -q spotless:apply
git add emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/crypto/SecretsSelfCheckIT.java
git commit -m "test(llm-orchestrator): prove the secrets self-check against real PostgreSQL"
```

---

## Task 7: Wire the three services

**Files:**
- Create: `emcip-core/src/main/java/io/emcip/common/crypto/SecretsSelfCheckConfig.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/config/CryptoConfig.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/CryptoConfig.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/CryptoConfig.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/LlmOrchestratorApplication.java`
- Modify: 3 × `src/main/resources/application.yml`

**Interfaces:**
- Consumes: all core types from Tasks 1–5.
- Produces: `SecretsSelfCheckConfig`, a `@Configuration` importable alongside `SecretCipherConfig`, building `SecretColumnScanner`, `SecretsSelfCheck`, `SecretsMetrics` and `SecretsHealthIndicator` from an injected `List<SecretColumn>`.

- [ ] **Step 1: Create the wiring configuration**

```java
package io.emcip.common.crypto;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the startup self-check. Imported explicitly by the three services that store secrets,
 * alongside {@link SecretCipherConfig}.
 *
 * <p>Like the rest of {@code io.emcip.common.crypto} this sits outside every service's
 * component-scan base package, so a service that stores no secrets picks up nothing — the same
 * isolation property that lets those services run without an {@code EMCIP_SECRET_KEY}.
 *
 * <p>Each service supplies its own {@code List<SecretColumn>} bean.
 */
@Configuration
@EnableConfigurationProperties(SecretsSelfCheckProperties.class)
public class SecretsSelfCheckConfig {

    @Bean
    public SecretColumnScanner secretColumnScanner(DataSource dataSource, SecretCipher cipher) {
        return new SecretColumnScanner(dataSource, cipher);
    }

    @Bean
    public SecretsSelfCheck secretsSelfCheck(
            SecretColumnScanner scanner,
            List<SecretColumn> columns,
            SecretsSelfCheckProperties properties) {
        return new SecretsSelfCheck(scanner, columns, properties);
    }

    @Bean
    public SecretsMetrics secretsMetrics(
            MeterRegistry registry, SecretsSelfCheck selfCheck, List<SecretColumn> columns) {
        return new SecretsMetrics(registry, selfCheck, columns);
    }

    @Bean
    public SecretsHealthIndicator secretsHealthIndicator(SecretsSelfCheck selfCheck) {
        return new SecretsHealthIndicator(selfCheck);
    }
}
```

- [ ] **Step 2: Contribute columns from each service**

`emcip-llm-orchestrator/.../config/CryptoConfig.java` — add the import and bean, keeping the existing `@Import(SecretCipherConfig.class)`:

```java
@Configuration
@Import({SecretCipherConfig.class, SecretsSelfCheckConfig.class})
public class CryptoConfig {

    /** The one column this service encrypts. See LlmProviderApiKeyCipherConverter. */
    @Bean
    public SecretColumn llmProviderApiKeyColumn() {
        return new SecretColumn("llm_provider_configs", "api_key", "id");
    }
}
```

`emcip-knowledge-engine/.../config/CryptoConfig.java`:

```java
@Bean
public SecretColumn vendorApiKeyColumn() {
    return new SecretColumn("ke_vendor_api_keys", "api_key", "id");
}
```

`emcip-admin-api/.../config/CryptoConfig.java` — two columns:

```java
@Bean
public SecretColumn telegramApiHashColumn() {
    return new SecretColumn("telegram_accounts", "api_hash", "id");
}

@Bean
public SecretColumn telegramSessionStringColumn() {
    return new SecretColumn("telegram_accounts", "session_string", "id");
}
```

In each case add `SecretsSelfCheckConfig.class` to the existing `@Import` and import `io.emcip.common.crypto.SecretColumn` / `SecretsSelfCheckConfig`.

**Verify the physical column names before writing them.** Check `telegram_accounts` and `ke_vendor_api_keys` against their Liquibase changelogs and entities — a wrong name here fails at runtime with a Postgres "column does not exist" error, not at compile time.

- [ ] **Step 3: Add `@EnableScheduling` to llm-orchestrator**

`emcip-llm-orchestrator/.../LlmOrchestratorApplication.java` does **not** have `@EnableScheduling` (admin-api and knowledge-engine both do). Without it the hourly re-scan silently never runs — a check that cannot fire, exactly the P3.4 shape.

```java
@SpringBootApplication
@EnableScheduling
public class LlmOrchestratorApplication {
```

Add the import `org.springframework.scheduling.annotation.EnableScheduling`.

- [ ] **Step 4: Add the property to all three `application.yml`**

```yaml
emcip:
  secrets:
    # warn | fail | off. Ships as warn everywhere: a hard failure would make the
    # Admin UI credentials-repair path unreachable. Promote an environment to fail
    # only after it has reported clean - see docs/operations/secrets-encryption.md
    self-check: ${EMCIP_SECRETS_SELF_CHECK:warn}
```

Merge into the existing `emcip:` block in each file rather than adding a second one.

- [ ] **Step 5: Verify each service still starts**

Run: `mvn -q -pl emcip-core,emcip-admin-api,emcip-knowledge-engine,emcip-llm-orchestrator -am verify`
Expected: BUILD SUCCESS, all module coverage floors held (see INF-CI-COV — floors are `measured − 2`; new uncovered code in emcip-core can push it below `0.78`, so if `jacoco:check` fails, add the missing unit coverage rather than lowering the floor).

- [ ] **Step 6: Commit**

```bash
mvn -q spotless:apply
git add emcip-core/src/main/java/io/emcip/common/crypto/SecretsSelfCheckConfig.java \
        emcip-admin-api emcip-knowledge-engine emcip-llm-orchestrator
git commit -m "feat(admin-api,knowledge-engine,llm-orchestrator): register secret columns for the self-check

Also adds @EnableScheduling to LlmOrchestratorApplication, which was missing -
without it the hourly re-scan would have silently never run in that service."
```

---

## Task 8: Documentation and status

**Files:**
- Modify: `docs/operations/secrets-encryption.md`
- Modify: `docs/superpowers/specs/2026-07-23-secrets-encryption-at-rest-design.md`
- Modify: `docs/superpowers/BACKLOG.md`
- Modify: `documentation/ROADMAP.md`

- [ ] **Step 1: Add the self-check section to the runbook**

In `docs/operations/secrets-encryption.md`, add a section covering:

- What each outcome means: `OK`, `PLAINTEXT` (repair via Admin UI → Credentials, per PR #241/#243), `KEY_MISMATCH` (wrong `EMCIP_SECRET_KEY` mounted — **do not** re-enter secrets, that would overwrite recoverable data; fix the Secret instead), `UNVERIFIED` (no encrypted rows exist yet — expected on a fresh install).
- The two metrics and their meaning: `emcip.secrets.plaintext_count{column}`, `emcip.secrets.key_status{column}` (`0` OK / `1` mismatch / `2` unverified).
- How to read the state: `curl -s localhost:PORT/actuator/health | jq .components.secrets`.
- That the indicator is **always UP by design**, and why (readiness probe / repair-path deadlock).
- The procedure for promoting an environment to `fail`: confirm `plaintext_count == 0` and `key_status == 0` for every column over at least one full re-scan cycle, then set `EMCIP_SECRETS_SELF_CHECK=fail` for that environment only.
- That the re-scan runs hourly at 17m23s past, so a repair is reflected within the hour.

- [ ] **Step 2: Cross-reference from the P2.0 spec**

In `docs/superpowers/specs/2026-07-23-secrets-encryption-at-rest-design.md`, note that the planned hardening is delivered by `2026-08-15-secrets-startup-self-check-design.md` (P3.7).

- [ ] **Step 3: Update BACKLOG and ROADMAP**

`BACKLOG.md` §0b — mark `P2.0-F1` ✅ with a delivered-note recording:
- the premise correction (reads were already strict; the gap was discovery timing, not strictness);
- that `warn` is the shipping default because a hard-fail would deadlock the #241/#243 repair paths;
- that `KEY_MISMATCH` was added because a prefix-only scan reports clean under a wrong key;
- that `@EnableScheduling` was missing from llm-orchestrator.

Add two new follow-up rows:
- **SELFCHECK-F1** (LOW, P4, XS) — admin-api scans with the Liquibase `spring.datasource` credential, not its runtime R2DBC user, so the check does not prove the runtime user can read `telegram_accounts`. Fix is an R2DBC scanner adapter. Ref: spec §2.
- **SELFCHECK-F2** (LOW, P4, XS) — no Testcontainers IT for the admin-api wiring; it has no harness. Ref: plan "Deviation from spec §6".

`ROADMAP.md` — mark 3.7 ✅ with a delivered-note including the premise correction.

- [ ] **Step 4: Commit**

```bash
git add docs documentation
git commit -m "docs(p3.7): document the secrets self-check and close P2.0-F1"
```

---

## Task 9: Live verification (with the user)

**Not automatable — this is spec §7, and it is the task most likely to produce a real finding.**

P3.6's transferable lesson was that the value no test could check was the one that was wrong (`trusted-proxy-hops` was 2 while the correct value was 1, failing silently). This feature is a claim about **live data**, which no test can observe.

- [ ] **Step 1: Deploy the three services in `warn` mode**

Follow `docs/operations/rollout.md`. Note tdlib-adapter is a StatefulSet and is not affected here.

- [ ] **Step 2: Read the actual state off the cluster**

```bash
microk8s.kubectl logs deploy/emcip-admin-api | grep -A8 "SECRET SELF-CHECK" | cat
microk8s.kubectl logs deploy/emcip-knowledge-engine | grep -A8 "SECRET SELF-CHECK" | cat
microk8s.kubectl logs deploy/emcip-llm-orchestrator | grep -A8 "SECRET SELF-CHECK" | cat
```

- [ ] **Step 3: Confirm the check can fail in the real environment**

A green result on the first run proves nothing on its own (P3.4's rule: a check never observed failing is not evidence). Confirm the live wiring genuinely reports by checking that each service logged a `SECRET SELF-CHECK` block naming its expected columns with non-zero `encrypted` counts. A column reporting `UNVERIFIED` in production means it holds no encrypted rows — investigate rather than accept.

- [ ] **Step 4: Record the result**

Write the actual per-column state into `docs/operations/secrets-encryption.md` with the date. **Do not assume it is clean** — PR #243 fixed a legacy plaintext `api_hash` lockout on 2026-08-10. Whatever it reports is the finding, and if plaintext exists, repair it through the Admin UI Credentials dialog and re-check.

- [ ] **Step 5: Do NOT promote anything to `fail`**

Out of scope, per spec §9. Promotion is a separate later decision taken on this evidence.

- [ ] **Step 6: Commit the recorded result**

```bash
git add docs/operations/secrets-encryption.md
git commit -m "docs(p3.7): record live secret self-check state"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|--------------|------|
| §0 premise correction | 8 (docs) |
| §1 warn default / repair-path constraint | 2 (properties), 4 (modes) |
| §2 component, `SecretColumn`, JDBC, per-service lists | 1, 3, 7 |
| §3 three queries, four outcomes, precedence | 3, 6 |
| §4 warn/fail/off | 2, 4, 7 |
| §5 log, metrics, always-UP health, re-scan | 4, 5 |
| §6 testing incl. wrong-key, empty column, leak assertions | 1, 3, 4, 5, 6 |
| §7 live verification | 9 |
| §8 documentation | 8 |
| §9 exclusions | respected — no re-encryption, no UI inventory, no promotion to `fail` |

**Gap found and closed during review:** spec §6 asks for an IT per persistence style; only the JPA one is built. Documented under "Deviation from spec §6" with the reasoning, and filed as follow-up SELFCHECK-F2 in Task 8 rather than left silent.

**Placeholder scan:** none. Every code step carries real code; Task 9's steps are commands, and Task 8's are enumerated content rather than "update the docs".

**Type consistency:** `SecretColumn.location()`, `ColumnResult.isProblem()`, `ColumnResult.MAX_REPORTED_IDS`, `SecretsSelfCheck.lastResults()/mode()/rescan()`, `SecretColumnScanner.scan()`, `SelfCheckMode.{WARN,FAIL,OFF}` and `Outcome.{OK,PLAINTEXT,KEY_MISMATCH,UNVERIFIED}` are used identically across Tasks 1–7. `ColumnResult`'s parameter order `(column, outcome, encryptedRows, plaintextCount, plaintextIds)` is consistent in every construction site in Tasks 3–6.

**Known risk carried into execution:** Task 6 Step 2 flags that the `llm_provider_configs` INSERT column list is written from the entity and must be checked against `LlmProviderConfig`; Task 7 Step 2 flags the same for the `telegram_accounts` and `ke_vendor_api_keys` physical column names. Both fail loudly at runtime rather than silently, but both are worth verifying before the first run.
