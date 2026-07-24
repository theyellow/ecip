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

        // Spring Data JPA wraps converter exceptions (JpaSystemException -> PersistenceException
        // -> our IllegalStateException), so the fail-closed guarantee is verified on the root
        // cause rather than the exception thrown directly at the call site.
        assertThatThrownBy(() -> apiKeyResolver.resolve("core", null))
                .rootCause()
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
