package io.emcip.audit.service.service;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.common.pagination.PageResponse;
import io.emcip.common.tenant.ReactorTenantContext;
import io.r2dbc.postgresql.codec.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;
    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactionalOperator;

    /** Stable advisory-lock key for the audit chain: ASCII "emcipaud". */
    private static final long AUDIT_CHAIN_LOCK_KEY = 0x656D636970617564L;

    public Mono<AuditEventEntity> save(AuditEventEntity entity) {
        return repository
                .save(entity)
                .doOnSuccess(
                        saved ->
                                log.debug(
                                        "Saved audit event: id={}, type={}",
                                        saved.getId(),
                                        saved.getEventType()))
                .doOnError(
                        e ->
                                log.error(
                                        "Failed to save audit event: eventId={}",
                                        entity.getEventId(),
                                        e));
    }

    public Flux<AuditEventEntity> findByEventType(String eventType) {
        return Flux.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    if (tenantId != null) {
                        return repository.findByEventTypeAndTenantId(
                                eventType, UUID.fromString(tenantId));
                    }
                    return repository.findByEventType(eventType);
                });
    }

    public Flux<AuditEventEntity> findByDateRange(Instant from, Instant to) {
        return Flux.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    if (tenantId != null) {
                        return repository.findByCreatedAtBetweenAndTenantId(
                                from, to, UUID.fromString(tenantId));
                    }
                    return repository.findByCreatedAtBetween(from, to);
                });
    }

    public Flux<AuditEventEntity> findByEventTypeAndDateRange(
            String eventType, Instant from, Instant to) {
        return Flux.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    if (tenantId != null) {
                        return repository.findByEventTypeAndCreatedAtBetweenAndTenantId(
                                eventType, from, to, UUID.fromString(tenantId));
                    }
                    return repository.findByEventTypeAndCreatedAtBetween(eventType, from, to);
                });
    }

    public Flux<AuditEventEntity> findByCorrelationId(String correlationId) {
        return repository.findByCorrelationId(correlationId);
    }

    public Mono<AuditEventEntity> findByEventId(String eventId) {
        return Mono.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    if (tenantId != null) {
                        return repository.findByEventIdAndTenantId(
                                eventId, UUID.fromString(tenantId));
                    }
                    return repository.findByEventId(eventId);
                });
    }

    public Mono<PageResponse<AuditEventEntity>> findPage(
            Instant from, Instant to, int page, int size, String eventType) {
        Pageable pageable = PageRequest.of(page, size);
        return Mono.deferContextual(
                ctx -> {
                    String tenantId = ReactorTenantContext.getTenantId(ctx);
                    if (tenantId != null) {
                        UUID tid = UUID.fromString(tenantId);
                        if (eventType != null && !eventType.isBlank()) {
                            Flux<AuditEventEntity> items =
                                    repository
                                            .findByEventTypeAndCreatedAtBetweenAndTenantIdOrderByCreatedAtDesc(
                                                    eventType, from, to, tid, pageable);
                            Mono<Long> count =
                                    repository.countByEventTypeAndCreatedAtBetweenAndTenantId(
                                            eventType, from, to, tid);
                            return Mono.zip(items.collectList(), count)
                                    .map(t -> new PageResponse<>(t.getT1(), t.getT2(), page, size));
                        }
                        Flux<AuditEventEntity> items =
                                repository.findByCreatedAtBetweenAndTenantIdOrderByCreatedAtDesc(
                                        from, to, tid, pageable);
                        Mono<Long> count =
                                repository.countByCreatedAtBetweenAndTenantId(from, to, tid);
                        return Mono.zip(items.collectList(), count)
                                .map(t -> new PageResponse<>(t.getT1(), t.getT2(), page, size));
                    }
                    if (eventType != null && !eventType.isBlank()) {
                        Flux<AuditEventEntity> items =
                                repository.findByEventTypeAndCreatedAtBetweenOrderByCreatedAtDesc(
                                        eventType, from, to, pageable);
                        Mono<Long> count =
                                repository.countByEventTypeAndCreatedAtBetween(eventType, from, to);
                        return Mono.zip(items.collectList(), count)
                                .map(t -> new PageResponse<>(t.getT1(), t.getT2(), page, size));
                    }
                    Flux<AuditEventEntity> items =
                            repository.findByCreatedAtBetweenOrderByCreatedAtDesc(
                                    from, to, pageable);
                    Mono<Long> count = repository.countByCreatedAtBetween(from, to);
                    return Mono.zip(items.collectList(), count)
                            .map(t -> new PageResponse<>(t.getT1(), t.getT2(), page, size));
                });
    }

    /**
     * Serialize a map of event fields to a {@link Json} value for the JSONB details column.
     *
     * @param fields key/value pairs to serialize
     * @return Json wrapping the serialized JSON, or null if serialization fails
     */
    public Json serializeDetails(Map<String, Object> fields) {
        try {
            return Json.of(objectMapper.writeValueAsString(fields));
        } catch (JacksonException e) {
            log.warn("Failed to serialize details map: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Save an audit event with hash chaining. Fetches the last record's integrity_hash to use as
     * prev_hash, computes the new record's integrity_hash, then persists.
     *
     * <p>The read-tail -> compute -> insert sequence is serialized across concurrent callers by a
     * Postgres transaction-scoped advisory lock ({@code pg_advisory_xact_lock}), so parallel
     * callers cannot read the same tail and fork the chain.
     */
    public Mono<AuditEventEntity> saveWithChain(AuditEventEntity entity) {
        Mono<AuditEventEntity> op =
                databaseClient
                        .sql("SELECT pg_advisory_xact_lock(:key)")
                        .bind("key", AUDIT_CHAIN_LOCK_KEY)
                        .fetch()
                        .first() // acquire the lock before reading the tail
                        .then(
                                repository
                                        .findTopByOrderByIdDesc()
                                        .map(AuditEventEntity::getIntegrityHash)
                                        .defaultIfEmpty(""))
                        .flatMap(
                                prevHash -> {
                                    entity.setPrevHash(prevHash.isEmpty() ? null : prevHash);
                                    entity.setIntegrityHash(computeIntegrityHash(entity));
                                    return repository.save(entity);
                                });
        // Lock is transaction-scoped: it releases automatically on commit/rollback.
        return transactionalOperator.transactional(op);
    }

    /**
     * Delete audit records older than the given cutoff. Logs the anchor hash of the oldest deleted
     * record before purging.
     */
    public Mono<Long> deleteRecordsOlderThan(Instant cutoff) {
        Mono<Long> op =
                databaseClient
                        .sql("SET LOCAL emcip.audit_purge = 'on'")
                        .fetch()
                        .rowsUpdated()
                        .then(
                                repository
                                        .findOldestBeforeCutoff(cutoff)
                                        .flatMap(
                                                oldest -> {
                                                    String anchorHash = oldest.getIntegrityHash();
                                                    return repository
                                                            .deleteByCreatedAtBefore(cutoff)
                                                            .doOnSuccess(
                                                                    count -> {
                                                                        if (count > 0) {
                                                                            log.info(
                                                                                    "Purged {}"
                                                                                        + " audit"
                                                                                        + " records,"
                                                                                        + " anchor"
                                                                                        + " hash:"
                                                                                        + " {}",
                                                                                    count,
                                                                                    anchorHash);
                                                                        }
                                                                    });
                                                })
                                        .defaultIfEmpty(0L));
        return transactionalOperator.transactional(op);
    }

    /**
     * Verify the hash chain integrity for the last {@code batchSize} records. Walks from newest to
     * oldest, comparing each record's prevHash to the next-newer record's integrityHash.
     */
    public Mono<ChainVerificationResult> verifyChain(int batchSize) {
        return repository
                .findTopNByOrderByIdDesc(batchSize)
                .collectList()
                .map(
                        records -> {
                            if (records.isEmpty()) {
                                return ChainVerificationResult.ok(0);
                            }
                            // records[0] = newest, records[n-1] = oldest
                            for (int i = 0; i < records.size() - 1; i++) {
                                AuditEventEntity newer = records.get(i);
                                AuditEventEntity older = records.get(i + 1);

                                // Skip pre-activation rows (chain was never populated for them).
                                if (newer.getIntegrityHash() == null) {
                                    continue;
                                }

                                // (1) Content tamper check: recompute from stored content + stored
                                // prev_hash.
                                String recomputed = computeIntegrityHash(newer);
                                if (!recomputed.equals(newer.getIntegrityHash())) {
                                    return ChainVerificationResult.broken(
                                            i + 1,
                                            newer.getId(),
                                            recomputed,
                                            newer.getIntegrityHash(),
                                            ChainFailureReason.CONTENT_TAMPERED);
                                }

                                // (2) Linkage check: newer.prev_hash must equal
                                // older.integrity_hash.
                                String expectedPrevHash = older.getIntegrityHash();
                                String actualPrevHash = newer.getPrevHash();
                                if (expectedPrevHash == null && actualPrevHash == null) {
                                    continue;
                                }
                                if (expectedPrevHash == null
                                        || !expectedPrevHash.equals(actualPrevHash)) {
                                    return ChainVerificationResult.broken(
                                            i + 1,
                                            newer.getId(),
                                            expectedPrevHash,
                                            actualPrevHash,
                                            ChainFailureReason.BROKEN_LINKAGE);
                                }
                            }
                            return ChainVerificationResult.ok(records.size());
                        });
    }

    /**
     * Content hash of a row folded with its predecessor's hash, so altering any earlier row
     * cascades into every later integrity_hash. Digests ALL immutable content columns (not just
     * identity fields) so tampering with any of them is detectable. Save and verify MUST use this
     * identical formula.
     */
    static String computeIntegrityHash(AuditEventEntity entity) {
        String input =
                entity.getEventId()
                        + "|"
                        + entity.getCreatedAt()
                        + "|"
                        + entity.getEventType()
                        + "|"
                        + entity.getActorType()
                        + "|"
                        + entity.getActorId()
                        + "|"
                        + entity.getResourceType()
                        + "|"
                        + entity.getResourceId()
                        + "|"
                        + entity.getAction()
                        + "|"
                        + entity.getSourceService()
                        + "|"
                        + entity.getCorrelationId()
                        + "|"
                        + (entity.getTenantId() == null ? "" : entity.getTenantId())
                        + "|"
                        + entity.getOutcome()
                        + "|"
                        + (entity.getDetails() == null ? "" : entity.getDetails().asString())
                        + "|"
                        + (entity.getPrevHash() == null ? "" : entity.getPrevHash());
        return sha256Hex(input);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** Distinguishes a self-inconsistent row from a broken predecessor link. */
    public enum ChainFailureReason {
        CONTENT_TAMPERED,
        BROKEN_LINKAGE
    }

    /** Result of a chain integrity verification run. */
    public record ChainVerificationResult(
            boolean valid,
            int recordsChecked,
            Long brokenAtId,
            String expectedHash,
            String actualHash,
            ChainFailureReason reason) {

        public static ChainVerificationResult ok(int count) {
            return new ChainVerificationResult(true, count, null, null, null, null);
        }

        public static ChainVerificationResult broken(
                int count, Long id, String expected, String actual, ChainFailureReason reason) {
            return new ChainVerificationResult(false, count, id, expected, actual, reason);
        }
    }
}
