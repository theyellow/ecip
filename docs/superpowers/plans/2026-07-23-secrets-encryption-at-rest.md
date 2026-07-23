# Secrets Encryption at Rest — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Encrypt four plaintext secret columns at rest with AES-256-GCM, so that a database dump or a
read-only DB compromise no longer yields usable Telegram credentials or vendor API keys.

**Architecture:** A single `SecretCipher` in `emcip-core` owns all crypto and the `v1:` wire format.
JPA services (knowledge-engine, llm-orchestrator) apply it transparently through per-column
`AttributeConverter` subclasses; admin-api's R2DBC path calls it explicitly in the service layer. Reads
are **fail-closed** — a value without the `v1:` prefix throws — and the existing rows are migrated by
hand with a small CLI, so no backfill code ships.

**Tech Stack:** Java 21, Spring Boot 4, Maven multi-module, JCE (`AES/GCM/NoPadding`), Hibernate 7
`AttributeConverter`, Spring Data R2DBC, Liquibase, JUnit 5 + AssertJ + Mockito + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-07-23-secrets-encryption-at-rest-design.md`

## Global Constraints

- **Liquibase only** — never Flyway. Every schema change is a changeset plus a `<include>` in that
  service's master changelog.
- **Each service uses a different `<include>` path convention.** Copy the one already in that file:
  - admin-api: `<include file="db/changelog/017-....xml"/>`
  - knowledge-engine: `<include file="changes/021-....xml" relativeToChangelogFile="true"/>`
  - llm-orchestrator: `<include file="classpath:db/changelog/changes/016-....xml"/>`
- **Lombok** — `@Slf4j`, `@RequiredArgsConstructor`; never hand-written getters. Note
  `TelegramAccountService` has an **explicit constructor**, so it is edited by hand.
- **Jackson 3** — `tools.jackson`, never `com.fasterxml.jackson`, in any new code.
- **`mvn spotless:apply` before every commit.** Success is the line
  `... 0 were changed to be clean, N were already clean`. PMD is blocking.
- **Never log, print, or put in an exception message**: a secret value, its plaintext, or any prefix
  of either. Exception messages name the *column*, never the content.
- **No secret is ever committed.** `EMCIP_SECRET_KEY` lives in a K8s Secret; `application.yml` carries
  **no default value** for it.
- Tests are package-private classes, JUnit 5 + AssertJ (`assertThat`), Mockito
  (`@ExtendWith(MockitoExtension.class)`), `StepVerifier` for reactive types — match the style in
  `emcip-admin-api/src/test/java/io/emcip/admin/api/service/AuthServiceTest.java`.

## Finding verified during planning — read before Task 7

**Nothing in the repository ever writes `telegram_accounts.session_string`.** `grep -rn
"setSessionString"` across all modules, JSX and XML returns **zero** results. `TelegramAccountService.create()`
never sets it, and tdlib-adapter only *receives* it (`AuthRequest.sessionString`) — it never sends one
back. The column is read at `TelegramAccountService.java:182` and `:412` and passed to tdlib-adapter,
but in practice it is always `NULL`.

Consequences, which Task 7 depends on:
- Only the **decrypt** path matters for `session_string` today, and `decrypt(null)` returns `null`
  without throwing, so strict mode cannot break anything through this column.
- The hand-run migration for this column is a **no-op** — there is nothing to encrypt.
- The spec's §4 fallback caveat about re-authenticating Telegram accounts is therefore **moot**. Do not
  spend effort preserving session strings that do not exist.
- The encrypt-on-write path is still implemented, so the column is correct the day a writer is added.

---

## File Structure

**Created:**

| File | Responsibility |
|------|----------------|
| `emcip-core/src/main/java/io/emcip/common/crypto/SecretCipher.java` | All crypto + the `v1:` wire format. The only class that touches JCE. |
| `emcip-core/src/main/java/io/emcip/common/crypto/SecretCipherConfig.java` | Reads and validates the key, exposes the `SecretCipher` bean. Imported explicitly — never component-scanned. |
| `emcip-core/src/main/java/io/emcip/common/crypto/SecretCipherCli.java` | Operator tool: prints ciphertext for the hand-run migration. |
| `emcip-core/src/main/java/io/emcip/common/crypto/EncryptedStringConverter.java` | Abstract JPA `AttributeConverter` base; subclasses supply the column name for error messages. |
| `emcip-knowledge-engine/.../config/CryptoConfig.java` | Imports `SecretCipherConfig` into knowledge-engine. |
| `emcip-knowledge-engine/.../entity/VendorApiKeyCipherConverter.java` | Binds the converter to `ke_vendor_api_keys.api_key`. |
| `emcip-llm-orchestrator/.../config/CryptoConfig.java` | Imports `SecretCipherConfig` into llm-orchestrator. |
| `emcip-llm-orchestrator/.../entity/LlmProviderApiKeyCipherConverter.java` | Binds the converter to `llm_provider_configs.api_key`. |
| `emcip-admin-api/.../config/CryptoConfig.java` | Imports `SecretCipherConfig` into admin-api. |
| 3 Liquibase changesets | Column widening + dropping `telegram_config`. |
| `docs/operations/secrets-encryption.md` | Key generation, the migration runbook, key-loss recovery. |

**Modified:** `emcip-core/pom.xml` (add `jakarta.persistence-api`), `VendorApiKey.java`,
`LlmProviderConfig.java`, `VendorApiKeyService.java`, `VendorApiKeyResponse.java`,
`TelegramAccountService.java`, 3 master changelogs, `helm/emcip/values.yaml`, `docker-compose.yml`,
`emcip-knowledge-engine/src/test/resources/application-test.yml`.

---

## Task 1: `SecretCipher` — crypto core

**Files:**
- Create: `emcip-core/src/main/java/io/emcip/common/crypto/SecretCipher.java`
- Create: `emcip-core/src/main/java/io/emcip/common/crypto/SecretCipherConfig.java`
- Test: `emcip-core/src/test/java/io/emcip/common/crypto/SecretCipherTest.java`
- Test: `emcip-core/src/test/java/io/emcip/common/crypto/SecretCipherConfigTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `SecretCipher(byte[] keyBytes)` — throws `IllegalArgumentException` unless exactly 32 bytes
  - `String SecretCipher.encrypt(String plaintext)` — `null` → `null`
  - `String SecretCipher.decrypt(String stored, String location)` — `null` → `null`; throws
    `IllegalStateException` if `stored` lacks the prefix
  - `static boolean SecretCipher.isEncrypted(String value)`
  - `static final String SecretCipher.PREFIX = "v1:"`
  - `SecretCipherConfig.secretCipher(String base64Key)` → `SecretCipher` bean

- [ ] **Step 1: Write the failing test**

Create `emcip-core/src/test/java/io/emcip/common/crypto/SecretCipherTest.java`:

```java
package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String LOCATION = "ke_vendor_api_keys.api_key";

    private final SecretCipher cipher = new SecretCipher(KEY);

    @Test
    void encryptThenDecrypt_returnsOriginalPlaintext() {
        String encrypted = cipher.encrypt("sk-super-secret-value");

        assertThat(encrypted).startsWith("v1:");
        assertThat(encrypted).doesNotContain("sk-super-secret-value");
        assertThat(cipher.decrypt(encrypted, LOCATION)).isEqualTo("sk-super-secret-value");
    }

    @Test
    void encrypt_sameInputTwice_producesDifferentCiphertext() {
        String first = cipher.encrypt("same-input");
        String second = cipher.encrypt("same-input");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first, LOCATION)).isEqualTo(cipher.decrypt(second, LOCATION));
    }

    @Test
    void decrypt_plaintextValue_throwsNamingTheColumnButNotTheValue() {
        assertThatThrownBy(() -> cipher.decrypt("sk-legacy-plaintext", LOCATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ke_vendor_api_keys.api_key")
                .hasMessageNotContaining("sk-legacy-plaintext")
                .hasMessageNotContaining("sk-legacy");
    }

    @Test
    void decrypt_tamperedCiphertext_failsWithAeadBadTag() {
        String encrypted = cipher.encrypt("tamper-me");
        byte[] raw = Base64.getDecoder().decode(encrypted.substring(3));
        raw[raw.length - 1] ^= 0x01;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered, LOCATION))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    void decrypt_wrongKey_failsAndDoesNotReturnGarbage() {
        String encrypted = cipher.encrypt("secret");
        SecretCipher other =
                new SecretCipher("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> other.decrypt(encrypted, LOCATION))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullValues_passThroughBothDirections() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null, LOCATION)).isNull();
    }

    @Test
    void isEncrypted_detectsPrefix() {
        assertThat(SecretCipher.isEncrypted(cipher.encrypt("x"))).isTrue();
        assertThat(SecretCipher.isEncrypted("plaintext")).isFalse();
        assertThat(SecretCipher.isEncrypted(null)).isFalse();
    }

    @Test
    void constructor_rejectsKeyThatIsNot32Bytes() {
        assertThatThrownBy(() -> new SecretCipher("too-short".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");

        assertThatThrownBy(() -> new SecretCipher(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl emcip-core -am test -Dtest=SecretCipherTest`
Expected: FAIL — compilation error, `cannot find symbol: class SecretCipher`

- [ ] **Step 3: Write the implementation**

Create `emcip-core/src/main/java/io/emcip/common/crypto/SecretCipher.java`:

```java
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
 * tag}. The prefix is a version marker: it lets a future key-rotation or KMS-backed implementation be
 * introduced without touching already-stored data.
 *
 * <p>Reads are fail-closed. A stored value without the prefix is legacy plaintext and throws, rather
 * than being returned silently — see the migration runbook in {@code
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl emcip-core -am test -Dtest=SecretCipherTest`
Expected: PASS — 8 tests, 0 failures

- [ ] **Step 5: Write the failing config test**

Create `emcip-core/src/test/java/io/emcip/common/crypto/SecretCipherConfigTest.java`:

```java
package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherConfigTest {

    private final SecretCipherConfig config = new SecretCipherConfig();

    private static String validKey() {
        return Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validKey_producesWorkingCipher() {
        SecretCipher cipher = config.secretCipher(validKey());

        assertThat(cipher.decrypt(cipher.encrypt("round-trip"), "test.column"))
                .isEqualTo("round-trip");
    }

    @Test
    void validKey_toleratesSurroundingWhitespace() {
        SecretCipher cipher = config.secretCipher("  " + validKey() + "\n");

        assertThat(cipher.encrypt("x")).startsWith("v1:");
    }

    @Test
    void missingKey_failsFastWithActionableMessage() {
        assertThatThrownBy(() -> config.secretCipher(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMCIP_SECRET_KEY");

        assertThatThrownBy(() -> config.secretCipher(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMCIP_SECRET_KEY");
    }

    @Test
    void nonBase64Key_failsWithoutEchoingTheValue() {
        assertThatThrownBy(() -> config.secretCipher("not!valid!base64!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not!valid!base64!");
    }

    @Test
    void wrongLengthKey_failsFast() {
        String sixteenBytes =
                Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> config.secretCipher(sixteenBytes))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `mvn -pl emcip-core -am test -Dtest=SecretCipherConfigTest`
Expected: FAIL — `cannot find symbol: class SecretCipherConfig`

- [ ] **Step 7: Write the config implementation**

Create `emcip-core/src/main/java/io/emcip/common/crypto/SecretCipherConfig.java`:

```java
package io.emcip.common.crypto;

import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the {@link SecretCipher} bean from the {@code EMCIP_SECRET_KEY} environment variable.
 *
 * <p>This class lives outside every service's component-scan base package, so it is only active in
 * services that {@code @Import} it explicitly. That is deliberate: it means startup can fail fast on a
 * missing key without forcing a key on the services that store no secrets.
 *
 * <p>Spring's relaxed binding maps the {@code EMCIP_SECRET_KEY} environment variable onto the {@code
 * emcip.secret-key} property. There is intentionally no default — a default here would eventually
 * become somebody's production key.
 */
@Configuration
public class SecretCipherConfig {

    @Bean
    public SecretCipher secretCipher(@Value("${emcip.secret-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "EMCIP_SECRET_KEY is not set. Generate one with 'openssl rand -base64 32' and"
                            + " supply it via the emcip-secret-key Kubernetes Secret. See"
                            + " docs/operations/secrets-encryption.md");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            // Deliberately does not echo the value.
            throw new IllegalStateException(
                    "EMCIP_SECRET_KEY is not valid base64. Generate one with 'openssl rand -base64"
                            + " 32'.");
        }
        try {
            return new SecretCipher(keyBytes);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("EMCIP_SECRET_KEY is invalid: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 8: Run both tests to verify they pass**

Run: `mvn -pl emcip-core -am test -Dtest='SecretCipher*Test'`
Expected: PASS — 13 tests, 0 failures

- [ ] **Step 9: Format and commit**

```bash
mvn spotless:apply
git add emcip-core/src/main/java/io/emcip/common/crypto/ emcip-core/src/test/java/io/emcip/common/crypto/
git commit -m "feat(core): AES-256-GCM SecretCipher for secrets at rest

Fail-closed by design: a stored value without the v1: prefix throws
rather than being returned as plaintext. The v1: prefix is a version
marker so a future rotation or KMS backend needs no data migration.

Exception messages name the column, never the value.

Part of P2.0 (S5 / S-OPEN-1 / RT-013 / S-NEW-2)."
```

---

## Task 2: `SecretCipherCli` — the migration tool

AES-256-GCM ciphertext cannot be produced with shell tooling (`openssl enc` has no AEAD support), so
the hand-run migration needs this. Without it the chosen strategy has no execution path.

**Files:**
- Create: `emcip-core/src/main/java/io/emcip/common/crypto/SecretCipherCli.java`
- Test: `emcip-core/src/test/java/io/emcip/common/crypto/SecretCipherCliTest.java`

**Interfaces:**
- Consumes: `SecretCipher`, `SecretCipherConfig` from Task 1.
- Produces: `static String SecretCipherCli.run(String[] args, String base64Key)` — returns the line to
  print; throws `IllegalArgumentException` on bad usage. `main` delegates to it.

- [ ] **Step 1: Write the failing test**

Create `emcip-core/src/test/java/io/emcip/common/crypto/SecretCipherCliTest.java`:

```java
package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherCliTest {

    private static final String KEY =
            Base64.getEncoder()
                    .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    void encrypt_outputIsReadableByTheCipherTheServicesUse() {
        String output = SecretCipherCli.run(new String[] {"encrypt", "sk-vendor-key"}, KEY);

        assertThat(output).startsWith("v1:");

        SecretCipher serviceSideCipher = new SecretCipherConfig().secretCipher(KEY);
        assertThat(serviceSideCipher.decrypt(output, "test.column")).isEqualTo("sk-vendor-key");
    }

    @Test
    void isEncrypted_reportsTrueForCiphertextAndFalseForPlaintext() {
        String encrypted = SecretCipherCli.run(new String[] {"encrypt", "value"}, KEY);

        assertThat(SecretCipherCli.run(new String[] {"isEncrypted", encrypted}, KEY)).isEqualTo("true");
        assertThat(SecretCipherCli.run(new String[] {"isEncrypted", "plain"}, KEY)).isEqualTo("false");
    }

    @Test
    void unknownCommand_reportsUsage() {
        assertThatThrownBy(() -> SecretCipherCli.run(new String[] {"decrypt", "x"}, KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usage");
    }

    @Test
    void wrongArgumentCount_reportsUsage() {
        assertThatThrownBy(() -> SecretCipherCli.run(new String[] {"encrypt"}, KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usage");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl emcip-core -am test -Dtest=SecretCipherCliTest`
Expected: FAIL — `cannot find symbol: class SecretCipherCli`

- [ ] **Step 3: Write the implementation**

Create `emcip-core/src/main/java/io/emcip/common/crypto/SecretCipherCli.java`:

```java
package io.emcip.common.crypto;

/**
 * Operator tool for the hand-run secrets migration.
 *
 * <p>AES-GCM ciphertext cannot be produced with standard shell tooling — {@code openssl enc} does not
 * support AEAD modes — so this exists to generate values for the UPDATE statements in {@code
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl emcip-core -am test -Dtest=SecretCipherCliTest`
Expected: PASS — 4 tests, 0 failures

- [ ] **Step 5: Format and commit**

```bash
mvn spotless:apply
git add emcip-core/src/main/java/io/emcip/common/crypto/SecretCipherCli.java emcip-core/src/test/java/io/emcip/common/crypto/SecretCipherCliTest.java
git commit -m "feat(core): SecretCipherCli for the hand-run secrets migration

AES-GCM ciphertext cannot be produced with shell tooling (openssl enc
has no AEAD support), so the migration runbook needs a generator. The
logic lives in a testable run() method; main() only handles I/O and
exit codes.

Part of P2.0."
```

---

## Task 3: `EncryptedStringConverter` — JPA integration point

**Files:**
- Modify: `emcip-core/pom.xml` (add `jakarta.persistence-api`)
- Create: `emcip-core/src/main/java/io/emcip/common/crypto/EncryptedStringConverter.java`
- Test: `emcip-core/src/test/java/io/emcip/common/crypto/EncryptedStringConverterTest.java`

**Interfaces:**
- Consumes: `SecretCipher` from Task 1.
- Produces: `abstract class EncryptedStringConverter implements AttributeConverter<String, String>`
  with protected constructor `EncryptedStringConverter(SecretCipher cipher, String location)`.
  Subclasses in Tasks 4 and 5 supply the column name.

**Why abstract + per-column subclasses:** the spec requires the strict-mode exception to name the exact
column, and a shared converter instance cannot know which column it was invoked for. One tiny subclass
per column solves this without duplicating any crypto.

- [ ] **Step 1: Add the JPA API dependency to emcip-core**

`emcip-core` has no `jakarta.persistence` dependency today. Add it as `provided` + `optional`, exactly
matching the existing precedent in that file for `jakarta.servlet-api` and `spring-web` — helpers used
only by the services that already have the real implementation on their classpath.

In `emcip-core/pom.xml`, insert after the `spring-web` dependency block and before the Reactor Core
block:

```xml
    <!-- Jakarta Persistence API (optional - for EncryptedStringConverter in JPA services).
         Provided: JPA services already bring Hibernate; R2DBC services must not gain a JPA
         dependency from emcip-core. -->
    <dependency>
      <groupId>jakarta.persistence</groupId>
      <artifactId>jakarta.persistence-api</artifactId>
      <scope>provided</scope>
      <optional>true</optional>
    </dependency>
```

- [ ] **Step 2: Write the failing test**

Create `emcip-core/src/test/java/io/emcip/common/crypto/EncryptedStringConverterTest.java`:

```java
package io.emcip.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EncryptedStringConverterTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    /** Stand-in for the per-column subclasses that live in the JPA services. */
    private static class TestConverter extends EncryptedStringConverter {
        TestConverter(SecretCipher cipher) {
            super(cipher, "some_table.some_column");
        }
    }

    private final TestConverter converter = new TestConverter(new SecretCipher(KEY));

    @Test
    void writeThenRead_roundTripsThroughTheColumn() {
        String columnValue = converter.convertToDatabaseColumn("sk-secret");

        assertThat(columnValue).startsWith("v1:");
        assertThat(columnValue).doesNotContain("sk-secret");
        assertThat(converter.convertToEntityAttribute(columnValue)).isEqualTo("sk-secret");
    }

    @Test
    void readingLegacyPlaintext_throwsNamingTheColumn() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("sk-legacy"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("some_table.some_column")
                .hasMessageNotContaining("sk-legacy");
    }

    @Test
    void nullValues_passThroughBothDirections() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -pl emcip-core -am test -Dtest=EncryptedStringConverterTest`
Expected: FAIL — `cannot find symbol: class EncryptedStringConverter`

- [ ] **Step 4: Write the implementation**

Create `emcip-core/src/main/java/io/emcip/common/crypto/EncryptedStringConverter.java`:

```java
package io.emcip.common.crypto;

import jakarta.persistence.AttributeConverter;

/**
 * Base for JPA attribute converters that transparently encrypt a String column.
 *
 * <p>Subclass once per encrypted column and annotate the subclass with {@code @Converter}, so that
 * the strict-mode error message can name the exact column:
 *
 * <pre>
 * &#64;Converter
 * public class VendorApiKeyCipherConverter extends EncryptedStringConverter {
 *     public VendorApiKeyCipherConverter(SecretCipher cipher) {
 *         super(cipher, "ke_vendor_api_keys.api_key");
 *     }
 * }
 * </pre>
 *
 * <p>Hibernate instantiates converters through Spring's bean container, which Spring Boot configures
 * automatically, so constructor injection of {@link SecretCipher} works.
 */
public abstract class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final SecretCipher cipher;
    private final String location;

    /**
     * @param cipher shared cipher bean
     * @param location {@code table.column}, used only in error messages
     */
    protected EncryptedStringConverter(SecretCipher cipher, String location) {
        this.cipher = cipher;
        this.location = location;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData, location);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl emcip-core -am test -Dtest=EncryptedStringConverterTest`
Expected: PASS — 3 tests, 0 failures

- [ ] **Step 6: Run the whole core module**

Run: `mvn -pl emcip-core -am test`
Expected: PASS — all existing core tests still green

- [ ] **Step 7: Format and commit**

```bash
mvn spotless:apply
git add emcip-core/pom.xml emcip-core/src/main/java/io/emcip/common/crypto/EncryptedStringConverter.java emcip-core/src/test/java/io/emcip/common/crypto/EncryptedStringConverterTest.java
git commit -m "feat(core): abstract EncryptedStringConverter for JPA columns

One subclass per encrypted column, so the strict-mode exception can name
the exact table and column - a shared converter instance cannot know
which column it was invoked for.

jakarta.persistence-api added as provided+optional, matching the
existing precedent for jakarta.servlet-api and spring-web: R2DBC
services must not inherit a JPA dependency from emcip-core.

Part of P2.0."
```

---

## Task 4: knowledge-engine — encrypt `ke_vendor_api_keys.api_key`

**Files:**
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/021-widen-vendor-api-key.xml`
- Modify: `emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml` (add include)
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/CryptoConfig.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/VendorApiKeyCipherConverter.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/VendorApiKey.java`
- Modify: `emcip-knowledge-engine/src/test/resources/application-test.yml`
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/crypto/VendorApiKeyEncryptionIT.java`

**Interfaces:**
- Consumes: `SecretCipher`, `SecretCipherConfig`, `EncryptedStringConverter` from Tasks 1 and 3.
- Produces: `VendorApiKeyCipherConverter` bound to `VendorApiKey.apiKey`. After this task,
  `ApiKeyResolver.resolve(...)` and `WebSearchService` return **plaintext** with no changes to their
  own code.

- [ ] **Step 1: Add the Liquibase changeset**

Create `emcip-knowledge-engine/src/main/resources/db/changelog/changes/021-widen-vendor-api-key.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-21" author="secrets-encryption">
        <comment>
            P2.0 (RT-013 / S-NEW-2): widen api_key for AES-256-GCM ciphertext.
            A 512-char key encrypts to roughly 723 chars of v1:-prefixed base64,
            which does not fit VARCHAR(512).
        </comment>
        <modifyDataType tableName="ke_vendor_api_keys" columnName="api_key" newDataType="TEXT"/>
        <addNotNullConstraint tableName="ke_vendor_api_keys" columnName="api_key" columnDataType="TEXT"/>
        <rollback>
            <modifyDataType tableName="ke_vendor_api_keys" columnName="api_key" newDataType="VARCHAR(512)"/>
            <addNotNullConstraint tableName="ke_vendor_api_keys" columnName="api_key" columnDataType="VARCHAR(512)"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

The explicit `addNotNullConstraint` is defensive: Liquibase's `modifyDataType` is documented to drop
column constraints on some databases. Re-asserting it is a no-op if it survived.

- [ ] **Step 2: Register the changeset**

In `emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml`, add after the
`020-job-dedup-columns.xml` line (line 27):

```xml
    <include file="changes/021-widen-vendor-api-key.xml" relativeToChangelogFile="true"/>
```

- [ ] **Step 3: Write the failing integration test**

Create `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/crypto/VendorApiKeyEncryptionIT.java`:

```java
package io.emcip.knowledge.engine.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emcip.common.crypto.SecretCipher;
import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.VendorApiKey;
import io.emcip.knowledge.engine.repository.VendorApiKeyRepository;
import io.emcip.knowledge.engine.service.ApiKeyResolver;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class VendorApiKeyEncryptionIT {

    @Autowired private VendorApiKeyRepository repository;
    @Autowired private ApiKeyResolver apiKeyResolver;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SecretCipher cipher;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM ke_vendor_api_keys");
    }

    private VendorApiKey newKey(String vendorId, String apiKey) {
        VendorApiKey entity = new VendorApiKey();
        entity.setVendorId(vendorId);
        entity.setApiKey(apiKey);
        entity.setEnabled(true);
        return entity;
    }

    @Test
    void savedKey_isCiphertextInTheColumnButPlaintextThroughJpa() {
        repository.saveAndFlush(newKey("brave", "sk-brave-plaintext"));

        String raw =
                jdbcTemplate.queryForObject(
                        "SELECT api_key FROM ke_vendor_api_keys WHERE vendor_id = 'brave'",
                        String.class);

        assertThat(raw).startsWith("v1:");
        assertThat(raw).doesNotContain("sk-brave-plaintext");

        assertThat(apiKeyResolver.resolve("brave", null)).contains("sk-brave-plaintext");
    }

    @Test
    void ciphertextWrittenByAnotherService_isReadableHere() {
        // Simulates admin-api's R2DBC write path: same SecretCipher, same v1: format,
        // different persistence stack. This is the cross-stack contract the shared
        // ke_vendor_api_keys table depends on.
        jdbcTemplate.update(
                "INSERT INTO ke_vendor_api_keys (id, vendor_id, api_key, enabled, created_at,"
                        + " updated_at) VALUES (?, 'exa', ?, true, now(), now())",
                UUID.randomUUID(),
                cipher.encrypt("sk-written-by-admin-api"));

        assertThat(apiKeyResolver.resolve("exa", null)).contains("sk-written-by-admin-api");
    }

    @Test
    void legacyPlaintextRow_failsLoudlyNamingTheColumn() {
        // The safety net that replaces an automated backfill: an unmigrated row must never
        // be read as a usable secret.
        jdbcTemplate.update(
                "INSERT INTO ke_vendor_api_keys (id, vendor_id, api_key, enabled, created_at,"
                        + " updated_at) VALUES (?, 'core', 'sk-never-migrated', true, now(), now())",
                UUID.randomUUID());

        assertThatThrownBy(() -> apiKeyResolver.resolve("core", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ke_vendor_api_keys.api_key")
                .hasMessageNotContaining("sk-never-migrated");
    }

    @Test
    void converterReceivesTheInjectedCipherBean() {
        // Proves Hibernate resolves the converter through Spring's bean container. If this
        // fails, fall back to service-layer encrypt/decrypt as the spec describes.
        repository.saveAndFlush(newKey("pubmed", "sk-injection-proof"));

        assertThat(apiKeyResolver.resolve("pubmed", null)).contains("sk-injection-proof");
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn -pl emcip-knowledge-engine -am test -Dtest=VendorApiKeyEncryptionIT`
Expected: FAIL — no `SecretCipher` bean, and the raw column still holds plaintext

- [ ] **Step 5: Import the cipher config**

Create `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/CryptoConfig.java`:

```java
package io.emcip.knowledge.engine.config;

import io.emcip.common.crypto.SecretCipherConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Activates the shared {@code SecretCipher} in knowledge-engine.
 *
 * <p>emcip-core sits outside this service's component-scan base package, so the cipher must be
 * imported explicitly. Importing it makes {@code EMCIP_SECRET_KEY} mandatory for this service.
 */
@Configuration
@Import(SecretCipherConfig.class)
public class CryptoConfig {}
```

- [ ] **Step 6: Add the per-column converter**

Create `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/VendorApiKeyCipherConverter.java`:

```java
package io.emcip.knowledge.engine.entity;

import io.emcip.common.crypto.EncryptedStringConverter;
import io.emcip.common.crypto.SecretCipher;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** Encrypts {@code ke_vendor_api_keys.api_key} at rest. */
@Component
@Converter
public class VendorApiKeyCipherConverter extends EncryptedStringConverter {

    public VendorApiKeyCipherConverter(SecretCipher cipher) {
        super(cipher, "ke_vendor_api_keys.api_key");
    }
}
```

- [ ] **Step 7: Apply the converter to the entity**

In `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/VendorApiKey.java`, add the
imports and annotate the field. Replace:

```java
    @Column(name = "api_key", nullable = false, length = 512)
```

with:

```java
    @Convert(converter = VendorApiKeyCipherConverter.class)
    @Column(name = "api_key", nullable = false)
```

and add `import jakarta.persistence.Convert;` to the import block. The `length = 512` is dropped
because the column is now `TEXT`.

- [ ] **Step 8: Supply a test key**

In `emcip-knowledge-engine/src/test/resources/application-test.yml`, add at the root level:

```yaml
emcip:
  # Test-only key. Base64 of "0123456789abcdef0123456789abcdef" (32 bytes).
  secret-key: MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
```

- [ ] **Step 9: Run test to verify it passes**

Run: `mvn -pl emcip-knowledge-engine -am test -Dtest=VendorApiKeyEncryptionIT`
Expected: PASS — 4 tests, 0 failures

If `converterReceivesTheInjectedCipherBean` fails with a converter instantiation error, Hibernate is
not resolving the converter through Spring. Stop and apply the spec's documented fallback: drop the
converter, and encrypt/decrypt in `ApiKeyResolver` and `WebSearchService` instead.

- [ ] **Step 10: Run the full module**

Run: `mvn -pl emcip-knowledge-engine -am test`
Expected: PASS — no existing knowledge-engine test regressed

- [ ] **Step 11: Format and commit**

```bash
mvn spotless:apply
git add emcip-knowledge-engine/
git commit -m "feat(knowledge-engine): encrypt ke_vendor_api_keys.api_key at rest

@Convert makes every existing read site correct with no changes to
ApiKeyResolver or WebSearchService, and leaves no read path that can
bypass decryption.

Column widened VARCHAR(512) -> TEXT: a 512-char key encrypts to ~723
chars, so the first encrypted write would otherwise fail at the DB.

Includes the cross-stack test that ciphertext written the way admin-api
writes it is readable through JPA here, and the strict-mode test that an
unmigrated plaintext row fails loudly instead of being used.

Part of P2.0 (RT-013 / S-NEW-2)."
```

---

## Task 5: llm-orchestrator — encrypt `llm_provider_configs.api_key`

**Files:**
- Create: `emcip-llm-orchestrator/src/main/resources/db/changelog/changes/016-widen-llm-provider-api-key.xml`
- Modify: `emcip-llm-orchestrator/src/main/resources/db/changelog/db.changelog-master.xml` (add include)
- Create: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/CryptoConfig.java`
- Create: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/LlmProviderApiKeyCipherConverter.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/LlmProviderConfig.java`
- Test: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/entity/LlmProviderApiKeyCipherConverterTest.java`

**Interfaces:**
- Consumes: `SecretCipher`, `EncryptedStringConverter`, `SecretCipherConfig` from Tasks 1 and 3.
- Produces: `LlmProviderApiKeyCipherConverter` bound to `LlmProviderConfig.apiKey`. The 10 existing
  `getApiKey()` read sites in `OrchestratorController` and `OpenAiCompatibleLlmClient` need **no
  changes**.

llm-orchestrator has no Testcontainers harness, so this task is unit-tested; the cross-stack behaviour
is already covered by Task 4 against the same shared converter base.

- [ ] **Step 1: Add the Liquibase changeset**

Create `emcip-llm-orchestrator/src/main/resources/db/changelog/changes/016-widen-llm-provider-api-key.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="llm-16" author="secrets-encryption">
        <comment>
            P2.0 (S-NEW-2): widen api_key for AES-256-GCM ciphertext.
            A 512-char key encrypts to roughly 723 chars of v1:-prefixed base64.
        </comment>
        <modifyDataType tableName="llm_provider_configs" columnName="api_key" newDataType="TEXT"/>
        <rollback>
            <modifyDataType tableName="llm_provider_configs" columnName="api_key" newDataType="VARCHAR(512)"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

`llm_provider_configs.api_key` is nullable, so no NOT NULL constraint to re-assert.

- [ ] **Step 2: Register the changeset**

In `emcip-llm-orchestrator/src/main/resources/db/changelog/db.changelog-master.xml`, add after the
`015-improve-knowledge-extraction-prompt.xml` line (line 22):

```xml
    <include file="classpath:db/changelog/changes/016-widen-llm-provider-api-key.xml"/>
```

- [ ] **Step 3: Write the failing test**

Create `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/entity/LlmProviderApiKeyCipherConverterTest.java`:

```java
package io.emcip.llm.orchestrator.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emcip.common.crypto.SecretCipher;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LlmProviderApiKeyCipherConverterTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private final LlmProviderApiKeyCipherConverter converter =
            new LlmProviderApiKeyCipherConverter(new SecretCipher(KEY));

    @Test
    void writeThenRead_roundTrips() {
        String stored = converter.convertToDatabaseColumn("sk-litellm-key");

        assertThat(stored).startsWith("v1:");
        assertThat(stored).doesNotContain("sk-litellm-key");
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("sk-litellm-key");
    }

    @Test
    void legacyPlaintext_throwsNamingTheColumn() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("sk-unmigrated"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llm_provider_configs.api_key")
                .hasMessageNotContaining("sk-unmigrated");
    }

    @Test
    void nullApiKey_passesThrough() {
        // api_key is nullable on this table.
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn -pl emcip-llm-orchestrator -am test -Dtest=LlmProviderApiKeyCipherConverterTest`
Expected: FAIL — `cannot find symbol: class LlmProviderApiKeyCipherConverter`

- [ ] **Step 5: Write the converter**

Create `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/LlmProviderApiKeyCipherConverter.java`:

```java
package io.emcip.llm.orchestrator.entity;

import io.emcip.common.crypto.EncryptedStringConverter;
import io.emcip.common.crypto.SecretCipher;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** Encrypts {@code llm_provider_configs.api_key} at rest. */
@Component
@Converter
public class LlmProviderApiKeyCipherConverter extends EncryptedStringConverter {

    public LlmProviderApiKeyCipherConverter(SecretCipher cipher) {
        super(cipher, "llm_provider_configs.api_key");
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -pl emcip-llm-orchestrator -am test -Dtest=LlmProviderApiKeyCipherConverterTest`
Expected: PASS — 3 tests, 0 failures

- [ ] **Step 7: Import the cipher config**

Create `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/CryptoConfig.java`:

```java
package io.emcip.llm.orchestrator.config;

import io.emcip.common.crypto.SecretCipherConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Activates the shared {@code SecretCipher} in llm-orchestrator.
 *
 * <p>emcip-core sits outside this service's component-scan base package, so the cipher must be
 * imported explicitly. Importing it makes {@code EMCIP_SECRET_KEY} mandatory for this service.
 */
@Configuration
@Import(SecretCipherConfig.class)
public class CryptoConfig {}
```

- [ ] **Step 8: Apply the converter to the entity**

In `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/entity/LlmProviderConfig.java`,
replace:

```java
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 512)
    private String apiKey;
```

with:

```java
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Convert(converter = LlmProviderApiKeyCipherConverter.class)
    @Column
    private String apiKey;
```

and add `import jakarta.persistence.Convert;` to the import block. Leave the `@Schema` and
`@JsonProperty` annotations above it untouched — the key must stay write-only in API responses.

- [ ] **Step 9: Run the full module**

Run: `mvn -pl emcip-llm-orchestrator -am test`
Expected: PASS — no existing llm-orchestrator test regressed

- [ ] **Step 10: Format and commit**

```bash
mvn spotless:apply
git add emcip-llm-orchestrator/
git commit -m "feat(llm-orchestrator): encrypt llm_provider_configs.api_key at rest

@Convert keeps all 10 existing getApiKey() read sites in
OrchestratorController and OpenAiCompatibleLlmClient working unchanged.

Column widened VARCHAR(512) -> TEXT for ciphertext expansion.

Part of P2.0 (S-NEW-2)."
```

---

## Task 6: admin-api — encrypt vendor keys on the R2DBC path

Spring Data R2DBC has no `AttributeConverter` equivalent, so encryption is explicit here.

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/config/CryptoConfig.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/VendorApiKeyService.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/dto/VendorApiKeyResponse.java`
- Test: `emcip-admin-api/src/test/java/io/emcip/admin/api/integration/VendorApiKeyServiceTest.java`

**Interfaces:**
- Consumes: `SecretCipher`, `SecretCipherConfig` from Task 1.
- Produces: `VendorApiKeyResponse.from(VendorApiKeyRow row, String plaintextKey)` — **signature
  change**, the second argument is the already-decrypted key. Rows written here are readable by
  knowledge-engine's converter from Task 4.

**Masking bug this prevents:** `VendorApiKeyResponse.maskKey()` renders `"••••••••" + last 4 chars`.
Users identify a key by its tail. Fed ciphertext it would show four characters of base64 — meaningless,
and it looks like data corruption. The DTO must be handed the decrypted value.

- [ ] **Step 1: Write the failing test**

Create `emcip-admin-api/src/test/java/io/emcip/admin/api/integration/VendorApiKeyServiceTest.java`:

```java
package io.emcip.admin.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.admin.api.integration.dto.VendorApiKeyRequest;
import io.emcip.common.crypto.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class VendorApiKeyServiceTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Mock private VendorApiKeyRowRepository repo;

    private final SecretCipher cipher = new SecretCipher(KEY);

    private VendorApiKeyService service() {
        return new VendorApiKeyService(repo, cipher);
    }

    @Test
    void createGlobal_encryptsBeforePersisting() {
        when(repo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service().createGlobal(new VendorApiKeyRequest("brave", "sk-plaintext", true)))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<VendorApiKeyRow> captor = ArgumentCaptor.forClass(VendorApiKeyRow.class);
        org.mockito.Mockito.verify(repo).save(captor.capture());

        String persisted = captor.getValue().getApiKey();
        assertThat(persisted).startsWith("v1:");
        assertThat(persisted).doesNotContain("sk-plaintext");
        assertThat(cipher.decrypt(persisted, "test")).isEqualTo("sk-plaintext");
    }

    @Test
    void listGlobal_masksTheDecryptedKeyNotTheCiphertext() {
        VendorApiKeyRow row =
                VendorApiKeyRow.builder()
                        .id(UUID.randomUUID())
                        .vendorId("brave")
                        .apiKey(cipher.encrypt("sk-abcdefgh-TAIL"))
                        .enabled(true)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        when(repo.findAllByTenantIdIsNull()).thenReturn(reactor.core.publisher.Flux.just(row));

        StepVerifier.create(service().listGlobal())
                .assertNext(
                        response -> {
                            // Last 4 chars of the PLAINTEXT, not of the base64 ciphertext.
                            assertThat(response.maskedKey()).endsWith("TAIL");
                            assertThat(response.maskedKey()).doesNotContain("v1:");
                        })
                .verifyComplete();
    }

    @Test
    void update_reEncryptsTheReplacementKey() {
        VendorApiKeyRow existing =
                VendorApiKeyRow.builder()
                        .id(UUID.randomUUID())
                        .vendorId("exa")
                        .apiKey(cipher.encrypt("sk-old"))
                        .enabled(true)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        when(repo.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(
                        service().update(existing.getId(), new VendorApiKeyRequest("exa", "sk-new", true)))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(existing.getApiKey()).startsWith("v1:");
        assertThat(cipher.decrypt(existing.getApiKey(), "test")).isEqualTo("sk-new");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl emcip-admin-api -am test -Dtest=VendorApiKeyServiceTest`
Expected: FAIL — `VendorApiKeyService` has no two-argument constructor

- [ ] **Step 3: Change the DTO to take a decrypted key**

In `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/dto/VendorApiKeyResponse.java`,
replace the `from` method:

```java
    public static VendorApiKeyResponse from(io.emcip.admin.api.integration.VendorApiKeyRow row) {
        return new VendorApiKeyResponse(
                row.getId(),
                row.getVendorId(),
                row.getTenantId(),
                maskKey(row.getApiKey()),
                row.isEnabled(),
                row.getUpdatedAt());
    }
```

with:

```java
    /**
     * @param row the stored row; its api_key is ciphertext and must not be masked directly
     * @param plaintextKey the decrypted key — masking shows its last 4 characters, which is how
     *     users identify a key
     */
    public static VendorApiKeyResponse from(
            io.emcip.admin.api.integration.VendorApiKeyRow row, String plaintextKey) {
        return new VendorApiKeyResponse(
                row.getId(),
                row.getVendorId(),
                row.getTenantId(),
                maskKey(plaintextKey),
                row.isEnabled(),
                row.getUpdatedAt());
    }
```

- [ ] **Step 4: Encrypt and decrypt in the service**

In `emcip-admin-api/src/main/java/io/emcip/admin/api/integration/VendorApiKeyService.java`:

Add imports:

```java
import io.emcip.common.crypto.SecretCipher;
```

Add the field and helper, immediately after `private final VendorApiKeyRowRepository repo;`:

```java
    private final SecretCipher cipher;

    private static final String API_KEY_LOCATION = "ke_vendor_api_keys.api_key";

    private VendorApiKeyResponse toResponse(VendorApiKeyRow row) {
        return VendorApiKeyResponse.from(row, cipher.decrypt(row.getApiKey(), API_KEY_LOCATION));
    }
```

`@RequiredArgsConstructor` picks up the new final field, giving the two-argument constructor the test
uses.

Replace **all five** occurrences of `.map(VendorApiKeyResponse::from)` with `.map(this::toResponse)`
— in `listGlobal`, `listByTenant`, `createGlobal`, `upsertForTenant` and `update`.

Replace **all four** write sites so the key is encrypted before it reaches the row:

- in `createGlobal`, `.apiKey(req.apiKey())` → `.apiKey(cipher.encrypt(req.apiKey()))`
- in `upsertForTenant`, `existing.setApiKey(req.apiKey())` → `existing.setApiKey(cipher.encrypt(req.apiKey()))`
- in `upsertForTenant`'s `switchIfEmpty` branch, `.apiKey(req.apiKey())` → `.apiKey(cipher.encrypt(req.apiKey()))`
- in `update`, `row.setApiKey(req.apiKey())` → `row.setApiKey(cipher.encrypt(req.apiKey()))`

- [ ] **Step 5: Import the cipher config**

Create `emcip-admin-api/src/main/java/io/emcip/admin/api/config/CryptoConfig.java`:

```java
package io.emcip.admin.api.config;

import io.emcip.common.crypto.SecretCipherConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Activates the shared {@code SecretCipher} in admin-api.
 *
 * <p>emcip-core sits outside this service's component-scan base package, so the cipher must be
 * imported explicitly. Importing it makes {@code EMCIP_SECRET_KEY} mandatory for this service.
 */
@Configuration
@Import(SecretCipherConfig.class)
public class CryptoConfig {}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -pl emcip-admin-api -am test -Dtest=VendorApiKeyServiceTest`
Expected: PASS — 3 tests, 0 failures

Leave `VendorApiKeyRequest`'s `@Size(max = 512)` on `apiKey` **unchanged** — it validates the plaintext
the operator submits, which is still bounded at 512. Only the stored ciphertext grows, and `TEXT`
absorbs it.

- [ ] **Step 7: Check for other callers of the changed DTO method**

Run: `grep -rn "VendorApiKeyResponse::from\|VendorApiKeyResponse.from" --include=*.java emcip-admin-api/src`
Expected: only the occurrences inside `VendorApiKeyService` and `VendorApiKeyResponse` itself. If
`AIProxyController` or any other class calls it directly, update that call to pass the decrypted key
the same way.

- [ ] **Step 8: Format and commit**

```bash
mvn spotless:apply
git add emcip-admin-api/src/main/java/io/emcip/admin/api/integration/ emcip-admin-api/src/main/java/io/emcip/admin/api/config/CryptoConfig.java emcip-admin-api/src/test/java/io/emcip/admin/api/integration/VendorApiKeyServiceTest.java
git commit -m "feat(admin-api): encrypt vendor API keys on the R2DBC write path

R2DBC has no AttributeConverter equivalent, so encryption is explicit in
the service layer. Rows written here are readable by knowledge-engine's
JPA converter - same cipher, same v1: format.

VendorApiKeyResponse.from() now takes the decrypted key. Masking shows
the last 4 characters, which is how users identify a key; masking the
ciphertext instead would have shown 4 chars of base64 and looked like
corruption.

Part of P2.0 (RT-013)."
```

---

## Task 7: admin-api — encrypt Telegram credentials, drop the orphan table

**Read the "Finding verified during planning" section above before starting.** Nothing writes
`session_string`; only `api_hash` has a live writer.

**Files:**
- Create: `emcip-admin-api/src/main/resources/db/changelog/017-secrets-encryption.xml`
- Modify: `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml` (add include)
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/TelegramAccountService.java`
- Test: `emcip-admin-api/src/test/java/io/emcip/admin/api/service/TelegramAccountCryptoTest.java`

**Interfaces:**
- Consumes: `SecretCipher` from Task 1, `CryptoConfig` from Task 6.
- Produces: `TelegramAccountService` constructor gains a trailing `SecretCipher cipher` parameter.

- [ ] **Step 1: Add the Liquibase changeset**

Create `emcip-admin-api/src/main/resources/db/changelog/017-secrets-encryption.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="017-widen-telegram-api-hash" author="secrets-encryption">
        <comment>
            P2.0 (S5 / S-OPEN-1): widen api_hash for AES-256-GCM ciphertext.
            session_string is already TEXT and needs no change.
        </comment>
        <modifyDataType tableName="telegram_accounts" columnName="api_hash" newDataType="TEXT"/>
        <rollback>
            <modifyDataType tableName="telegram_accounts" columnName="api_hash" newDataType="VARCHAR(255)"/>
        </rollback>
    </changeSet>

    <changeSet id="017-drop-orphan-telegram-config" author="secrets-encryption">
        <comment>
            P2.0: drop the orphaned telegram_config table. Created in changelog 006, superseded by
            telegram_accounts in 007, and referenced by zero Java files. It still holds plaintext
            api_hash and session_string in any environment that populated it. Deleting beats
            encrypting a table nothing reads.
        </comment>
        <dropTable tableName="telegram_config"/>
        <rollback>
            <createTable tableName="telegram_config">
                <column name="id" type="INTEGER">
                    <constraints primaryKey="true" nullable="false"/>
                </column>
                <column name="api_id" type="INTEGER"/>
                <column name="api_hash" type="VARCHAR(255)"/>
                <column name="session_string" type="TEXT"/>
            </createTable>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Register the changeset**

In `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml`, add after the
`016-admin-users-add-current-jti.xml` line (line 82):

```xml
    <include file="db/changelog/017-secrets-encryption.xml"/>
```

Note this service's convention: root-level path, **no** `relativeToChangelogFile`.

- [ ] **Step 3: Write the failing test**

Create `emcip-admin-api/src/test/java/io/emcip/admin/api/service/TelegramAccountCryptoTest.java`:

```java
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

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

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
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn -pl emcip-admin-api -am test -Dtest=TelegramAccountCryptoTest`
Expected: FAIL — compilation error, `SecretCipher` not on the admin-api test classpath until Task 6's
changes are in place. If Task 6 is already committed this compiles; the test then passes trivially,
which is fine — it is a regression guard for Steps 5–7.

- [ ] **Step 5: Inject the cipher**

In `emcip-admin-api/src/main/java/io/emcip/admin/api/service/TelegramAccountService.java`:

Add the import:

```java
import io.emcip.common.crypto.SecretCipher;
```

Add the field after `private final CircuitBreaker tdlibCircuitBreaker;`:

```java
    private final SecretCipher cipher;

    private static final String API_HASH_LOCATION = "telegram_accounts.api_hash";
    private static final String SESSION_LOCATION = "telegram_accounts.session_string";
```

This class has an **explicit constructor** — add a trailing parameter and assignment:

```java
    public TelegramAccountService(
            TelegramAccountRepository repository,
            AccountWatchedGroupRepository watchedGroupRepository,
            GroupProfileRepository groupProfileRepository,
            R2dbcEntityTemplate r2dbcEntityTemplate,
            @Qualifier("tdlibWebClient") WebClient tdlibClient,
            CircuitBreakerRegistry registry,
            SecretCipher cipher) {
        // ... existing assignments unchanged ...
        this.cipher = cipher;
    }
```

- [ ] **Step 6: Encrypt on write**

In `create(...)` (around line 100), replace:

```java
                                    .apiHash(apiHash != null ? apiHash : telegramApiHash)
```

with:

```java
                                    .apiHash(
                                            cipher.encrypt(
                                                    apiHash != null ? apiHash : telegramApiHash))
```

- [ ] **Step 7: Decrypt on read**

There are exactly two payload sites. In `reconnect(...)` (around lines 181–182), replace:

```java
                            payload.put("apiHash", account.getApiHash());
                            payload.put("sessionString", account.getSessionString());
```

with:

```java
                            payload.put(
                                    "apiHash",
                                    cipher.decrypt(account.getApiHash(), API_HASH_LOCATION));
                            payload.put(
                                    "sessionString",
                                    cipher.decrypt(account.getSessionString(), SESSION_LOCATION));
```

In `initializeAccount(...)` (around lines 411–412), make the identical replacement.

Do **not** change `TelegramAccountController.toSafeMap` line 205 — it only tests
`getSessionString() != null && !isEmpty()`, which is correct against ciphertext and must not decrypt.

- [ ] **Step 8: Run test to verify it passes**

Run: `mvn -pl emcip-admin-api -am test -Dtest=TelegramAccountCryptoTest`
Expected: PASS — 3 tests, 0 failures

- [ ] **Step 9: Run the full module**

Run: `mvn -pl emcip-admin-api -am test`
Expected: PASS. If an existing `TelegramAccountService` test fails to compile, add a `SecretCipher` to
its constructor call — `new SecretCipher("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))`.

- [ ] **Step 10: Format and commit**

```bash
mvn spotless:apply
git add emcip-admin-api/
git commit -m "feat(admin-api): encrypt Telegram credentials, drop orphan telegram_config

api_hash is encrypted on write and decrypted at the two tdlib payload
sites. session_string gets the same treatment, though note that no code
path in the repo writes it today (grep for setSessionString returns
nothing) - so its migration is a no-op and only the null-safe decrypt
path is live. Implementing the write path keeps the column correct the
day a writer is added.

telegram_config is dropped rather than encrypted: created in changelog
006, superseded by telegram_accounts in 007, referenced by zero Java
files, and still holding plaintext credentials in any environment that
populated it.

toSafeMap's sessionStringSet check is deliberately left undecrypted - it
only tests presence.

Part of P2.0 (S5 / S-OPEN-1)."
```

---

## Task 8: Deployment config and the operator runbook

**Files:**
- Modify: `helm/emcip/values.yaml`
- Modify: `docker-compose.yml`
- Create: `docs/operations/secrets-encryption.md`

- [ ] **Step 1: Add the key to Helm**

All three services already have a `secrets:` block — `llmOrchestrator` at line 155, `adminApi` at line
254, `knowledgeEngine` at line 288. Add this one line to each of the three:

```yaml
      EMCIP_SECRET_KEY: emcip-secret-key
```

For reference, `adminApi`'s block already holds `ADMIN_JWT_SECRET`, `ADMIN_SERVICE_TOKEN`,
`TELEGRAM_API_ID` and `TELEGRAM_API_HASH`; match that indentation exactly.

- [ ] **Step 2: Add a local-dev key to docker-compose**

In `docker-compose.yml`, add to the `environment:` list of `ecip-admin-api`, `ecip-knowledge-engine`
and `ecip-llm-orchestrator`:

```yaml
      - EMCIP_SECRET_KEY=${EMCIP_SECRET_KEY:-MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=}
```

This mirrors how `ADMIN_JWT_SECRET` is handled at line 263. **Do not** put a default in any
`application.yml` — a default there would eventually become somebody's production key. compose is
local-only and overridable.

- [ ] **Step 3: Write the runbook**

Create `docs/operations/secrets-encryption.md`:

````markdown
# Secrets Encryption at Rest — Operations

Four columns are encrypted with AES-256-GCM (`v1:`-prefixed base64 of `iv || ciphertext || tag`):

| Column | Table | Written by |
|--------|-------|------------|
| `session_string` | `telegram_accounts` | nothing today — always NULL |
| `api_hash` | `telegram_accounts` | admin-api |
| `api_key` | `ke_vendor_api_keys` | admin-api |
| `api_key` | `llm_provider_configs` | llm-orchestrator |

Reads are **fail-closed**: a value without the `v1:` prefix raises an error naming the column. There is
no automatic backfill.

## Generating the key

```bash
openssl rand -base64 32
```

Store it as the `emcip-secret-key` Kubernetes Secret. **Never commit it.** admin-api,
knowledge-engine and llm-orchestrator all read it as `EMCIP_SECRET_KEY` and will not start without it.
No other service needs it.

## Key loss

There is no recovery. Every encrypted value becomes unreadable:

- vendor API keys and the LLM provider key must be re-entered through the Admin UI
- `telegram_accounts.api_hash` must be re-entered
- Telegram sessions would need re-authentication — currently moot, since nothing writes
  `session_string`

Back the key up separately from the database. A backup of both together defeats the encryption.

## Migration runbook (one-time, per environment)

Liquibase runs at service startup, so the columns cannot be widened while the services are scaled to
zero. Deploy first, migrate second, and accept a short window where secret-reading features fail
loudly.

1. Create the `emcip-secret-key` Secret.
2. Deploy the new images. Pods start normally — decryption happens on demand, not at boot — and
   Liquibase widens the columns. **From here until step 4, any feature reading one of the four
   secrets fails with the strict-mode error.** This is the maintenance window.
3. For each column, read the current plaintext, generate ciphertext, and UPDATE by primary key.
   There is no `psql` on the workstation, so work through the Postgres pod:

   ```bash
   microk8s.kubectl exec -it <postgres-pod> -- psql -U emcip -d emcip \
     -c "SELECT id, vendor_id, api_key FROM ke_vendor_api_keys;"
   ```

   Generate the ciphertext (the plaintext lands in shell history — clear it afterwards):

   ```bash
   EMCIP_SECRET_KEY='<the key>' java -cp emcip-core.jar \
     io.emcip.common.crypto.SecretCipherCli encrypt 'sk-the-plaintext-key'
   ```

   Apply it:

   ```bash
   microk8s.kubectl exec -it <postgres-pod> -- psql -U emcip -d emcip \
     -c "UPDATE ke_vendor_api_keys SET api_key = 'v1:...' WHERE id = '<uuid>';"
   ```

   Repeat for `llm_provider_configs.api_key` and `telegram_accounts.api_hash`.
   `telegram_accounts.session_string` is NULL everywhere and needs nothing.

4. Verify — each of these must return zero rows:

   ```sql
   SELECT id FROM ke_vendor_api_keys    WHERE api_key       NOT LIKE 'v1:%';
   SELECT id FROM llm_provider_configs  WHERE api_key       NOT LIKE 'v1:%' AND api_key IS NOT NULL;
   SELECT id FROM telegram_accounts     WHERE api_hash      NOT LIKE 'v1:%' AND api_hash IS NOT NULL;
   SELECT id FROM telegram_accounts     WHERE session_string NOT LIKE 'v1:%' AND session_string IS NOT NULL;
   ```

5. Restart the three services so nothing holds a stale decrypted value.

**Zero-window alternative:** run the widening changesets ahead of the deploy with the Liquibase Maven
plugin pointed at the cluster database, migrate the values, then deploy — at the cost of applying
schema changes outside the normal service-startup path.

**Fallback without the CLI:** set the columns to NULL and re-enter the secrets through the Admin UI
after deploy, which writes them encrypted.

## Key rotation

Not supported yet. The `v1:` prefix is the version marker that will let a second key be introduced
without touching stored data. Tracked for P6 alongside the secrets-management ADR.
````

- [ ] **Step 4: Verify the compose stack still starts**

Run: `docker compose config --quiet && echo "compose file valid"`
Expected: `compose file valid`

- [ ] **Step 5: Commit**

```bash
git add helm/emcip/values.yaml docker-compose.yml docs/operations/secrets-encryption.md
git commit -m "docs(operations): secrets encryption runbook, Helm and compose wiring

EMCIP_SECRET_KEY added to the existing secrets: map for the three
services that need it. No default in application.yml - a default there
would eventually become somebody's production key; compose supplies an
overridable one for local dev only.

The runbook's ordering reflects that Liquibase runs at service startup,
so the columns cannot be widened while the services are scaled to zero:
deploy first, migrate second, with a bounded window where
secret-reading features fail loudly. UPDATEs go through the Postgres
pod, since there is no psql on the workstation.

Part of P2.0."
```

---

## Task 9: Full verification and status update

**Files:**
- Modify: `docs/superpowers/BACKLOG.md`
- Modify: `documentation/ROADMAP.md`

- [ ] **Step 1: Full build**

Run: `mvn clean install -DskipTests=false`
Expected: `BUILD SUCCESS`, all modules green.

- [ ] **Step 2: Verify formatting**

Run: `mvn spotless:check`
Expected: no violations. If it fails, run `mvn spotless:apply` and commit the result.

- [ ] **Step 3: Verify no plaintext leaked into logs or messages**

Run: `grep -rn "getApiKey()\|getSessionString()\|getApiHash()" --include=*.java emcip-*/src/main | grep -i "log\.\|System.out\|println"`
Expected: no output.

- [ ] **Step 4: Verify no secret default was committed**

Run: `grep -rn "EMCIP_SECRET_KEY\|emcip.secret-key\|secret-key" --include=*.yml emcip-*/src/main/resources`
Expected: **no output at all** — no `application.yml` may carry this key or a default for it. The only
occurrences in the repo should be `docker-compose.yml`, `helm/emcip/values.yaml`, the knowledge-engine
**test** resources, and documentation.

- [ ] **Step 5: Update architecture docs that describe these tables**

The documentation checklist requires any diagram or guide depicting the changed tables or the
secret-handling flow to be updated. Three files reference them:

Run: `grep -rn "vendor_api_keys\|llm_provider_configs\|session_string\|telegram_config" documentation/architecture-guide.adoc documentation/diagrams/c3-llm-orchestrator.puml documentation/diagrams/sequence-llm-orchestration.puml`

For each hit, update it if it states or implies the column is plaintext, and remove any reference to
`telegram_config` (that table no longer exists). If a hit is only a neutral mention of the table name,
leave it alone — do not churn diagrams that are still accurate.

- [ ] **Step 6: Update the backlog**

In `docs/superpowers/BACKLOG.md`, change the P2.0 row's status from `⏳` to `✅ PR #<number>` once the
PR is open. Keep the row where it is.

- [ ] **Step 7: Update the roadmap**

In `documentation/ROADMAP.md`, mark P2.0 as delivered in the P2 table, following how P1 records
`**P1 delivered (2026-07-22):** PR #206 ...`.

- [ ] **Step 8: Commit and open the PR**

```bash
mvn spotless:apply
git add docs/superpowers/BACKLOG.md documentation/ROADMAP.md
git commit -m "docs: mark P2.0 secrets encryption delivered"
git push -u origin feat/p2-secrets-encryption-at-rest
```

Open the PR with a body covering: the four columns, the strict-mode decision, and a prominent pointer
to `docs/operations/secrets-encryption.md` — **the migration must be run by hand before or right after
deploying this**, or secret-reading features will fail.

---

## Acceptance criteria (from the spec)

| # | Criterion | Verified by |
|---|-----------|-------------|
| 1 | No plaintext remains in the four columns | Task 8 runbook step 4 (raw SQL, zero rows) |
| 2 | `telegram_config` no longer exists | Task 7 changeset `017-drop-orphan-telegram-config` |
| 3 | A key written by admin-api is readable by knowledge-engine | Task 4 `ciphertextWrittenByAnotherService_isReadableHere` |
| 4 | The Admin UI still shows the last 4 characters of the real key | Task 6 `listGlobal_masksTheDecryptedKeyNotTheCiphertext` |
| 5 | The three services fail to start without a valid key; no other service is affected | Task 1 `SecretCipherConfigTest`; only 3 services `@Import` `SecretCipherConfig` |
| 6 | A plaintext value causes a loud exception naming the column | Task 4 `legacyPlaintextRow_failsLoudlyNamingTheColumn`; Task 5 `legacyPlaintext_throwsNamingTheColumn` |
| 7 | CLI output is readable by the running service | Task 2 `encrypt_outputIsReadableByTheCipherTheServicesUse` |
| 8 | Spotless clean, PMD passes, full build green | Task 9 steps 1–2 |
