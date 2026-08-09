package io.emcip.llm.orchestrator.repository;

import io.emcip.llm.orchestrator.entity.LlmProviderConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for LlmProviderConfig entity.
 *
 * <p>Note the split between entity reads and the projection/bulk-update methods below. Loading the
 * entity decrypts {@code api_key} eagerly through its {@code AttributeConverter}, so any read of a
 * row whose key predates secrets encryption throws and takes the whole query with it - including
 * queries that never wanted the key. That is why administration paths (listing configs, renaming
 * one, storing a replacement key) go through the methods here that never touch the decrypted value,
 * and only the paths that genuinely need the key load the entity.
 *
 * <p>Without that split an operator cannot repair a legacy row at all: listing the configs and
 * saving a new key would both fail on the very value they are meant to fix.
 */
@Repository
public interface LlmProviderConfigRepository extends JpaRepository<LlmProviderConfig, UUID> {

    /**
     * Everything the admin UI shows about a provider config. Deliberately has no accessor for the
     * key itself - only whether one is set - so that selecting it cannot decrypt.
     */
    interface Summary {
        UUID getId();

        String getName();

        String getBaseUrl();

        Boolean getActive();

        Boolean getHasApiKey();
    }

    /** Returns the most-recently-updated active provider config. Decrypts the key. */
    Optional<LlmProviderConfig> findFirstByActiveTrueOrderByUpdatedAtDesc();

    /**
     * Lists configs without decrypting any key.
     *
     * <p>{@code c.apiKey is not null} is a predicate on the column, not a selection of the
     * attribute, so the converter is never invoked. The key is never compared against a literal
     * here for the same reason - Hibernate would convert (encrypt) the literal, comparing
     * ciphertexts that can never match.
     */
    @Query(
            """
            select c.id as id, c.name as name, c.baseUrl as baseUrl, c.active as active,
                   case when c.apiKey is not null then true else false end as hasApiKey
            from LlmProviderConfig c
            order by c.name
            """)
    List<Summary> findAllSummaries();

    /** Single-row form of {@link #findAllSummaries()}, for read-modify-write without decrypting. */
    @Query(
            """
            select c.id as id, c.name as name, c.baseUrl as baseUrl, c.active as active,
                   case when c.apiKey is not null then true else false end as hasApiKey
            from LlmProviderConfig c
            where c.id = :id
            """)
    Optional<Summary> findSummaryById(@Param("id") UUID id);

    /**
     * Clears the active flag on every config except the given one.
     *
     * <p>Bulk updates bypass {@code @Version} and {@code @UpdateTimestamp}, so both are maintained
     * explicitly. Passing the id of the row being activated avoids a pointless write to it.
     */
    @Modifying
    @Query(
            """
            update LlmProviderConfig c
               set c.active = false,
                   c.versionLock = c.versionLock + 1,
                   c.updatedAt = :now
             where c.active = true
               and (:keepId is null or c.id <> :keepId)
            """)
    int deactivateAllExcept(@Param("keepId") UUID keepId, @Param("now") Instant now);

    /** Updates the non-secret fields of one config. */
    @Modifying
    @Query(
            """
            update LlmProviderConfig c
               set c.name = :name,
                   c.baseUrl = :baseUrl,
                   c.active = :active,
                   c.versionLock = c.versionLock + 1,
                   c.updatedAt = :now
             where c.id = :id
            """)
    int updateDetails(
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("baseUrl") String baseUrl,
            @Param("active") Boolean active,
            @Param("now") Instant now);

    /**
     * Stores a replacement API key.
     *
     * <p>The converter <em>does</em> apply to the bind parameter, so the value written here is
     * encrypted exactly as an entity save would encrypt it. That is the whole point of this method
     * and it is asserted directly against the raw column in {@code LlmProviderConfigRepositoryIT} -
     * a silent regression to plaintext would otherwise look like a working feature.
     */
    @Modifying
    @Query(
            """
            update LlmProviderConfig c
               set c.apiKey = :apiKey,
                   c.versionLock = c.versionLock + 1,
                   c.updatedAt = :now
             where c.id = :id
            """)
    int updateApiKey(
            @Param("id") UUID id, @Param("apiKey") String apiKey, @Param("now") Instant now);
}
