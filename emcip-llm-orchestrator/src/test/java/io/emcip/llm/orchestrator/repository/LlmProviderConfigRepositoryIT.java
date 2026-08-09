package io.emcip.llm.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emcip.common.crypto.PlaintextSecretException;
import io.emcip.llm.orchestrator.TestcontainersInitializer;
import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

/**
 * Covers the paths that must keep working when a stored {@code api_key} predates secrets
 * encryption.
 *
 * <p>Such a row is poison to any query that materialises the entity: the attribute converter
 * decrypts eagerly and throws, failing the whole result set. That took out both the provider list
 * and the provider edit, which is precisely the pair of screens an operator needs in order to
 * replace the offending key - the feature that repairs the row was unreachable because of the row
 * it was meant to repair.
 */
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersInitializer.class)
@Transactional
class LlmProviderConfigRepositoryIT {

    @Autowired LlmProviderConfigRepository repository;
    @Autowired JdbcTemplate jdbc;

    private UUID legacyId;

    @BeforeEach
    void insertLegacyPlaintextRow() {
        jdbc.update("delete from llm_provider_configs");
        legacyId = UUID.randomUUID();
        // Straight INSERT, bypassing JPA: this is what a row written before encryption existed
        // actually looks like on disk - a bare key with no v1: prefix.
        jdbc.update(
                """
                insert into llm_provider_configs
                    (id, name, base_url, api_key, active, created_at, updated_at, version_lock)
                values (?, ?, ?, ?, ?, now(), now(), 0)
                """,
                legacyId,
                "legacy-provider",
                "http://litellm:4000",
                "sk-plaintext-legacy-key",
                true);
    }

    /**
     * The reproduction. If this ever stops throwing, the rest of this class proves nothing, because
     * the row it is built around would no longer be the hazard it is meant to represent.
     */
    @Test
    void loadingTheEntityStillFailsOnALegacyPlaintextKey() {
        assertThatThrownBy(() -> repository.findById(legacyId).map(LlmProviderConfig::getApiKey))
                .rootCause()
                .isInstanceOf(PlaintextSecretException.class);
    }

    @Test
    void listingSummariesSucceedsDespiteTheLegacyRow() {
        List<LlmProviderConfigRepository.Summary> all = repository.findAllSummaries();

        assertThat(all).hasSize(1);
        assertThat(all.get(0).getId()).isEqualTo(legacyId);
        assertThat(all.get(0).getName()).isEqualTo("legacy-provider");
        assertThat(all.get(0).getHasApiKey()).isTrue();
    }

    @Test
    void summaryReportsAbsentKeyAsSuch() {
        jdbc.update("update llm_provider_configs set api_key = null where id = ?", legacyId);

        assertThat(repository.findSummaryById(legacyId))
                .get()
                .extracting("hasApiKey")
                .isEqualTo(false);
    }

    @Test
    void replacingTheKeyStoresItEncrypted() {
        repository.updateApiKey(legacyId, "sk-the-replacement", Instant.now());

        String stored =
                jdbc.queryForObject(
                        "select api_key from llm_provider_configs where id = ?",
                        String.class,
                        legacyId);

        assertThat(stored).startsWith("v1:");
        assertThat(stored).doesNotContain("sk-the-replacement");
    }

    /** After the repair the row is readable again through the ordinary entity path. */
    @Test
    void entityLoadWorksAgainOnceTheKeyIsReplaced() {
        repository.updateApiKey(legacyId, "sk-the-replacement", Instant.now());

        assertThat(repository.findById(legacyId))
                .get()
                .extracting(LlmProviderConfig::getApiKey)
                .isEqualTo("sk-the-replacement");
    }

    @Test
    void deactivatingOthersDoesNotReadTheLegacyKey() {
        UUID other = UUID.randomUUID();
        jdbc.update(
                """
                insert into llm_provider_configs
                    (id, name, base_url, api_key, active, created_at, updated_at, version_lock)
                values (?, ?, ?, null, ?, now(), now(), 0)
                """,
                other,
                "new-provider",
                "http://other:4000",
                true);

        int changed = repository.deactivateAllExcept(other, Instant.now());

        assertThat(changed).isEqualTo(1);
        assertThat(activeFlag(legacyId)).isFalse();
        assertThat(activeFlag(other)).isTrue();
    }

    @Test
    void deactivationBumpsTheVersionSoOptimisticLockingStaysHonest() {
        repository.deactivateAllExcept(UUID.randomUUID(), Instant.now());

        Long version =
                jdbc.queryForObject(
                        "select version_lock from llm_provider_configs where id = ?",
                        Long.class,
                        legacyId);
        assertThat(version).isEqualTo(1L);
    }

    private Boolean activeFlag(UUID id) {
        return jdbc.queryForObject(
                "select active from llm_provider_configs where id = ?", Boolean.class, id);
    }
}
