# Audit Integrity Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Activate the `audit_events` hash chain safely under concurrency and make it genuinely tamper-evident, replace the blocking Kafka listener with a reactive consumer that cannot silently lose records, and add a DELETE-prevention trigger that still permits the sanctioned retention purge — all in one PR.

**Architecture:** `AuditService.saveWithChain` serialises read-tail→compute→insert with a Postgres advisory transaction lock inside a `TransactionalOperator`. The five `@KafkaListener` methods become one `ReactiveKafkaConsumerTemplate` pipeline (`concatMap` for in-instance ordering, advisory lock for cross-replica) with retry→DLQ via the existing `DeadLetterTopicHandler`. `integrity_hash` folds in `prev_hash`; `verifyChain` recomputes it. A `BEFORE DELETE` trigger blocks deletes unless a `SET LOCAL emcip.audit_purge='on'` flag is set by the retention path.

**Tech Stack:** Java 21, Spring Boot 4.0.5, Spring WebFlux + Spring Data R2DBC (reactive), spring-kafka + reactor-kafka (`ReactiveKafkaConsumerTemplate`), Liquibase, PostgreSQL 16, Testcontainers (Kafka + Postgres), JUnit 5 + Mockito + reactor-test + AssertJ + Awaitility.

## Global Constraints

- **Module:** all changes are in `emcip-audit-service`, except reuse of `io.emcip.common.kafka.DeadLetterTopicHandler` / `KafkaMetricsConfig` (already on the classpath via the `emcip-core` dependency).
- **Rule 5 (R2DBC):** reactive services use `Mono`/`Flux`; **never `@Transactional`** — use `TransactionalOperator`.
- **Rule 2 (Spotless):** run `mvn -pl emcip-audit-service spotless:apply` before every commit; the passing indicator is `0 were changed to be clean`.
- **Rule 1 (Liquibase only):** schema changes via Liquibase changesets, never Flyway.
- **Rule 6 (cron offsets):** never schedule at exact round times — existing audit jobs already comply; do not change their crons.
- **Rule 3 (Lombok):** `@Slf4j`, `@RequiredArgsConstructor`; no manual getters.
- **Tenant fail-closed:** every Kafka record must carry a `tenant_id` header; `TenantAwareKafkaSupport.validateTenantHeader` throws on a missing/invalid header. Test producers **must** set the header (real UUID or `TenantAwareKafkaSupport.GLOBAL_TENANT_SENTINEL`). Header key constant: `TenantContext.KAFKA_HEADER` = `"tenant_id"`.
- **Do not split into multiple PRs.** Multiple commits on one branch (`feat/p2-audit-integrity`) is the intended shape.
- **Advisory lock key:** `private static final long AUDIT_CHAIN_LOCK_KEY = 0x656D636970617564L;` (ASCII `"emcipaud"`, a stable, documented constant).
- **Test class naming:** unit tests `*Test` (Surefire), integration tests `*IT` (Failsafe, `mvn verify`). Integration tests extend `AbstractAuditIntegrationTest`.

---

### Task 1: Add the reactor-kafka dependency

`ReactiveKafkaConsumerTemplate` ships in spring-kafka but wraps `reactor.kafka.receiver.KafkaReceiver`, which comes from `reactor-kafka`. reactor-kafka is **not** managed by the Spring Boot 4.0.5 BOM, so an explicit version is required.

**Files:**
- Modify: `emcip-audit-service/pom.xml`

**Interfaces:**
- Produces: `io.projectreactor.kafka:reactor-kafka` on the audit-service classpath (enables `org.springframework.kafka.core.reactive.ReactiveKafkaConsumerTemplate` and `reactor.kafka.receiver.*`).

- [ ] **Step 1: Add the dependency**

In `emcip-audit-service/pom.xml`, inside `<dependencies>`, next to the existing `spring-kafka` dependency, add:

```xml
<dependency>
  <groupId>io.projectreactor.kafka</groupId>
  <artifactId>reactor-kafka</artifactId>
  <version>1.3.23</version>
</dependency>
```

- [ ] **Step 2: Verify it resolves and compiles**

Run: `mvn -q -pl emcip-audit-service -am compile`
Expected: `BUILD SUCCESS`. If reactor-kafka `1.3.23` fails to resolve, check Maven Central for the latest `1.3.x` and use that (the `1.3` line targets Reactor 3.x, which Spring Boot 4 uses).

- [ ] **Step 3: Confirm the reactive template type is now importable**

Run: `mvn -q -pl emcip-audit-service dependency:tree -Dincludes=io.projectreactor.kafka:reactor-kafka`
Expected: the tree shows `io.projectreactor.kafka:reactor-kafka:jar:1.3.23`.

- [ ] **Step 4: Commit**

```bash
mvn -pl emcip-audit-service spotless:apply
git add emcip-audit-service/pom.xml
git commit -m "build(audit): add reactor-kafka for reactive Kafka consumer"
```

---

### Task 2: Tamper-evident hash — fold prev_hash + recompute on verify

Pure logic in `AuditService`, unit-tested with a mocked repository. `AuditServiceTest` lives in the **same package** (`io.emcip.audit.service.service`), so it can call a package-private hashing method to build valid and tampered chains.

**Files:**
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java`
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditChainVerificationJob.java`
- Test: `emcip-audit-service/src/test/java/io/emcip/audit/service/service/AuditServiceTest.java`
- Test: `emcip-audit-service/src/test/java/io/emcip/audit/service/service/AuditChainVerificationJobTest.java`

**Interfaces:**
- Produces: `static String AuditService.computeIntegrityHash(AuditEventEntity)` (package-private, folds `prev_hash`); `AuditService.ChainVerificationResult` gains `ChainFailureReason reason()` and enum `ChainFailureReason { CONTENT_TAMPERED, BROKEN_LINKAGE }`; `ChainVerificationResult.broken(int, Long, String, String, ChainFailureReason)`.
- Consumes: `AuditEventEntity` builder (existing).

- [ ] **Step 1: Write failing tests for the strengthened hash + verification**

Add to `AuditServiceTest`:

```java
@Test
void computeIntegrityHash_foldsPrevHash_soContentTamperingIsDetectable() {
    AuditEventEntity a = row("evt-1", null);
    a.setIntegrityHash(AuditService.computeIntegrityHash(a));
    AuditEventEntity b = row("evt-2", a.getIntegrityHash());
    b.setIntegrityHash(AuditService.computeIntegrityHash(b));

    when(repository.findTopNByOrderByIdDesc(10))
            .thenReturn(Flux.just(withId(b, 2L), withId(a, 1L)));

    StepVerifier.create(auditService.verifyChain(10))
            .assertNext(r -> assertThat(r.valid()).isTrue())
            .verifyComplete();
}

@Test
void verifyChain_contentTampered_reportsContentTamperedReason() {
    AuditEventEntity a = row("evt-1", null);
    a.setIntegrityHash(AuditService.computeIntegrityHash(a));
    AuditEventEntity b = row("evt-2", a.getIntegrityHash());
    b.setIntegrityHash(AuditService.computeIntegrityHash(b));
    // Tamper b's content AFTER its hash was stored -> recompute won't match stored hash.
    b.setResourceId("tampered-resource");

    when(repository.findTopNByOrderByIdDesc(10))
            .thenReturn(Flux.just(withId(b, 2L), withId(a, 1L)));

    StepVerifier.create(auditService.verifyChain(10))
            .assertNext(r -> {
                assertThat(r.valid()).isFalse();
                assertThat(r.reason())
                        .isEqualTo(AuditService.ChainFailureReason.CONTENT_TAMPERED);
                assertThat(r.brokenAtId()).isEqualTo(2L);
            })
            .verifyComplete();
}

@Test
void verifyChain_brokenLinkage_reportsBrokenLinkageReason() {
    AuditEventEntity a = row("evt-1", null);
    a.setIntegrityHash(AuditService.computeIntegrityHash(a));
    // b points at the wrong predecessor hash but its own content hash is self-consistent.
    AuditEventEntity b = row("evt-2", "0000000000000000000000000000000000000000000000000000000000000000");
    b.setIntegrityHash(AuditService.computeIntegrityHash(b));

    when(repository.findTopNByOrderByIdDesc(10))
            .thenReturn(Flux.just(withId(b, 2L), withId(a, 1L)));

    StepVerifier.create(auditService.verifyChain(10))
            .assertNext(r -> {
                assertThat(r.valid()).isFalse();
                assertThat(r.reason())
                        .isEqualTo(AuditService.ChainFailureReason.BROKEN_LINKAGE);
            })
            .verifyComplete();
}

private static AuditEventEntity row(String eventId, String prevHash) {
    AuditEventEntity e =
            AuditEventEntity.builder()
                    .eventId(eventId)
                    .eventType("TelegramMessage")
                    .sourceService("emcip-tdlib-adapter")
                    .action("TelegramMessage")
                    .actorType("SYSTEM")
                    .actorId("actor-1")
                    .resourceType("TelegramMessage")
                    .resourceId("res-1")
                    .outcome("PROCESSED")
                    .createdAt(Instant.parse("2026-07-25T10:00:00Z"))
                    .build();
    e.setPrevHash(prevHash);
    return e;
}

private static AuditEventEntity withId(AuditEventEntity e, long id) {
    e.setId(id);
    return e;
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -pl emcip-audit-service test -Dtest=AuditServiceTest`
Expected: FAIL — `computeIntegrityHash` is not `static`/package-visible, `ChainFailureReason` and `reason()` do not exist.

- [ ] **Step 3: Implement the strengthened hash and verification**

In `AuditService`, replace `computeIntegrityHash` (make it package-private static and fold `prev_hash`):

```java
/**
 * Content hash of a row folded with its predecessor's hash, so altering any earlier row
 * cascades into every later integrity_hash. Save and verify MUST use this identical formula.
 */
static String computeIntegrityHash(AuditEventEntity entity) {
    String input =
            entity.getEventId()
                    + "|"
                    + entity.getCreatedAt()
                    + "|"
                    + entity.getEventType()
                    + "|"
                    + entity.getActorId()
                    + "|"
                    + entity.getResourceType()
                    + "|"
                    + entity.getResourceId()
                    + "|"
                    + (entity.getPrevHash() == null ? "" : entity.getPrevHash());
    return sha256Hex(input);
}
```

Make `sha256Hex` `static`. Replace `verifyChain`'s loop body so it recomputes the content hash first, then checks linkage:

```java
for (int i = 0; i < records.size() - 1; i++) {
    AuditEventEntity newer = records.get(i);
    AuditEventEntity older = records.get(i + 1);

    // Skip pre-activation rows (chain was never populated for them).
    if (newer.getIntegrityHash() == null) {
        continue;
    }

    // (1) Content tamper check: recompute from stored content + stored prev_hash.
    String recomputed = computeIntegrityHash(newer);
    if (!recomputed.equals(newer.getIntegrityHash())) {
        return ChainVerificationResult.broken(
                i + 1, newer.getId(), recomputed, newer.getIntegrityHash(),
                ChainFailureReason.CONTENT_TAMPERED);
    }

    // (2) Linkage check: newer.prev_hash must equal older.integrity_hash.
    String expectedPrevHash = older.getIntegrityHash();
    String actualPrevHash = newer.getPrevHash();
    if (expectedPrevHash == null && actualPrevHash == null) {
        continue;
    }
    if (expectedPrevHash == null || !expectedPrevHash.equals(actualPrevHash)) {
        return ChainVerificationResult.broken(
                i + 1, newer.getId(), expectedPrevHash, actualPrevHash,
                ChainFailureReason.BROKEN_LINKAGE);
    }
}
return ChainVerificationResult.ok(records.size());
```

Replace `ChainVerificationResult` with the reason-carrying version:

```java
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
```

In `AuditChainVerificationJob`, add `reason` to the CRITICAL log:

```java
log.error(
        "CRITICAL: Audit chain integrity violation ({}) at record {}! expected={}, found={}",
        result.reason(),
        result.brokenAtId(),
        result.expectedHash(),
        result.actualHash());
```

- [ ] **Step 4: Fix the existing `AuditChainVerificationJobTest` broken() call**

In `AuditChainVerificationJobTest`, update the `broken(...)` call to the new 5-arg signature:

```java
ChainVerificationResult.broken(
        50, 42L, "expectedHash", "actualHash",
        AuditService.ChainFailureReason.BROKEN_LINKAGE)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q -pl emcip-audit-service test -Dtest=AuditServiceTest,AuditChainVerificationJobTest`
Expected: `BUILD SUCCESS`, all green.

- [ ] **Step 6: Commit**

```bash
mvn -pl emcip-audit-service spotless:apply
git add emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java \
        emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditChainVerificationJob.java \
        emcip-audit-service/src/test/java/io/emcip/audit/service/service/AuditServiceTest.java \
        emcip-audit-service/src/test/java/io/emcip/audit/service/service/AuditChainVerificationJobTest.java
git commit -m "feat(audit): fold prev_hash into integrity_hash; verify recomputes content (RT-027)"
```

---

### Task 3: Serialise + activate saveWithChain via advisory lock

`saveWithChain` becomes a single transaction that takes `pg_advisory_xact_lock`, reads the tail, computes, and inserts. Verified by a concurrency integration test that publishes many rows in parallel and asserts a linear chain.

**Files:**
- Create: `emcip-audit-service/src/main/java/io/emcip/audit/service/config/AuditPersistenceConfig.java`
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java`
- Modify: `emcip-audit-service/src/test/java/io/emcip/audit/service/service/AuditServiceTest.java` (constructor now takes 4 args)
- Test: `emcip-audit-service/src/test/java/io/emcip/audit/service/AuditChainConcurrencyIT.java`

**Interfaces:**
- Produces: `@Bean TransactionalOperator transactionalOperator(ReactiveTransactionManager)`; `AuditService(AuditEventRepository, ObjectMapper, DatabaseClient, TransactionalOperator)` (Lombok `@RequiredArgsConstructor` over the four final fields).
- Consumes: `computeIntegrityHash` (Task 2).

- [ ] **Step 1: Add the TransactionalOperator bean**

Create `AuditPersistenceConfig.java`:

```java
package io.emcip.audit.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
public class AuditPersistenceConfig {

    @Bean
    public TransactionalOperator transactionalOperator(ReactiveTransactionManager txManager) {
        return TransactionalOperator.create(txManager);
    }
}
```

(`ReactiveTransactionManager` / `R2dbcTransactionManager` is auto-configured by `spring-boot-starter-data-r2dbc`.)

- [ ] **Step 2: Write the failing concurrency integration test**

Create `AuditChainConcurrencyIT.java`:

```java
package io.emcip.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.audit.service.service.AuditService;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

class AuditChainConcurrencyIT extends AbstractAuditIntegrationTest {

    @Autowired private AuditService auditService;
    @Autowired private AuditEventRepository repository;
    @Autowired private DatabaseClient databaseClient;

    @BeforeEach
    void clean() {
        // TRUNCATE bypasses the row-level DELETE trigger (added in Task 4).
        databaseClient.sql("TRUNCATE audit_events").fetch().rowsUpdated().block();
    }

    @Test
    void concurrentSaveWithChain_producesLinearChain_noForks() {
        int n = 200;
        Flux.range(0, n)
                .parallel(8)
                .runOn(Schedulers.boundedElastic())
                .flatMap(i -> auditService.saveWithChain(newEntity("evt-" + i)))
                .sequential()
                .blockLast();

        List<AuditEventEntity> rows =
                repository.findAll().sort((a, b) -> Long.compare(a.getId(), b.getId()))
                        .collectList().block();

        assertThat(rows).hasSize(n);
        // Genesis row has no predecessor.
        assertThat(rows.get(0).getPrevHash()).isNull();
        Set<String> seenPrev = new HashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            String prev = rows.get(i).getPrevHash();
            // Linear: each row points at exactly its immediate predecessor's hash.
            assertThat(prev).isEqualTo(rows.get(i - 1).getIntegrityHash());
            // No fork: no two rows share a predecessor hash.
            assertThat(seenPrev.add(prev)).as("duplicate prev_hash = fork").isTrue();
        }
    }

    private static AuditEventEntity newEntity(String eventId) {
        return AuditEventEntity.builder()
                .eventId(eventId)
                .eventType("TelegramMessage")
                .sourceService("emcip-tdlib-adapter")
                .action("TelegramMessage")
                .actorType("SYSTEM")
                .actorId("actor")
                .resourceType("TelegramMessage")
                .resourceId("res")
                .outcome("PROCESSED")
                .createdAt(Instant.now())
                .build();
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `mvn -q -pl emcip-audit-service verify -Dit.test=AuditChainConcurrencyIT -DfailIfNoTests=false`
Expected: FAIL — either compilation fails (new `AuditService` deps not yet injected) or the chain forks/contains duplicate `prev_hash`.

- [ ] **Step 4: Implement the advisory-locked saveWithChain**

In `AuditService`, add the two constructor fields (via `@RequiredArgsConstructor`) and the lock key, and rewrite `saveWithChain`:

```java
private final AuditEventRepository repository;
private final ObjectMapper objectMapper;
private final DatabaseClient databaseClient;
private final TransactionalOperator transactionalOperator;

/** Stable advisory-lock key for the audit chain: ASCII "emcipaud". */
private static final long AUDIT_CHAIN_LOCK_KEY = 0x656D636970617564L;

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
```

Add imports: `org.springframework.r2dbc.core.DatabaseClient`, `org.springframework.transaction.reactive.TransactionalOperator`.

- [ ] **Step 5: Fix the AuditServiceTest constructor (unit tests don't exercise saveWithChain)**

In `AuditServiceTest.setUp()`, pass mocks for the two new dependencies:

```java
@Mock private AuditEventRepository repository;
@Mock private DatabaseClient databaseClient;
@Mock private TransactionalOperator transactionalOperator;

@BeforeEach
void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    auditService = new AuditService(repository, objectMapper, databaseClient, transactionalOperator);
}
```

- [ ] **Step 6: Run unit + the new IT to verify green**

Run: `mvn -q -pl emcip-audit-service test -Dtest=AuditServiceTest`
Expected: PASS.
Run: `mvn -q -pl emcip-audit-service verify -Dit.test=AuditChainConcurrencyIT -DfailIfNoTests=false`
Expected: PASS — 200 rows, linear chain, no duplicate `prev_hash`.

- [ ] **Step 7: Commit**

```bash
mvn -pl emcip-audit-service spotless:apply
git add emcip-audit-service/src/main/java/io/emcip/audit/service/config/AuditPersistenceConfig.java \
        emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java \
        emcip-audit-service/src/test/java/io/emcip/audit/service/service/AuditServiceTest.java \
        emcip-audit-service/src/test/java/io/emcip/audit/service/AuditChainConcurrencyIT.java
git commit -m "feat(audit): serialize saveWithChain with pg advisory lock (RT2-002)"
```

---

### Task 4: DELETE-prevention trigger + guarded retention + test-cleanup fix

Coupled by necessity: the `BEFORE DELETE` trigger blocks **all** deletes — including `repository.deleteAll()` used in existing test cleanup and the retention job — until the purge flag is set. So the migration, the retention change, and the test-cleanup switch to `TRUNCATE` land together.

**Files:**
- Create: `emcip-audit-service/src/main/resources/db/changelog/004-audit-delete-prevention.xml`
- Modify: `emcip-audit-service/src/main/resources/db/changelog/db.changelog-master.xml`
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java` (`deleteRecordsOlderThan`)
- Modify: `emcip-audit-service/src/test/java/io/emcip/audit/service/AuditEventPersistenceIT.java` (cleanup → TRUNCATE)
- Test: `emcip-audit-service/src/test/java/io/emcip/audit/service/AuditDeletePreventionIT.java`

**Interfaces:**
- Produces: DB trigger `audit_no_delete` + function `prevent_audit_delete()`; `AuditService.deleteRecordsOlderThan` now runs inside a transaction that sets `emcip.audit_purge='on'`.
- Consumes: `TransactionalOperator`, `DatabaseClient` (Task 3).

- [ ] **Step 1: Write the migration**

Create `004-audit-delete-prevention.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="004-audit-prevent-delete-trigger" author="p2.1-audit-integrity">
        <comment>RT2-016: Prevent DELETE on audit_events unless the sanctioned purge flag is set</comment>
        <sql splitStatements="false">
            CREATE OR REPLACE FUNCTION prevent_audit_delete() RETURNS trigger AS $$
            BEGIN
                IF current_setting('emcip.audit_purge', true) IS DISTINCT FROM 'on' THEN
                    RAISE EXCEPTION 'audit_events rows cannot be deleted';
                END IF;
                RETURN OLD;
            END;
            $$ LANGUAGE plpgsql;

            CREATE TRIGGER audit_no_delete
                BEFORE DELETE ON audit_events
                FOR EACH ROW
                EXECUTE FUNCTION prevent_audit_delete();
        </sql>
        <rollback>
            DROP TRIGGER IF EXISTS audit_no_delete ON audit_events;
            DROP FUNCTION IF EXISTS prevent_audit_delete();
        </rollback>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 2: Include it in the master changelog**

In `db.changelog-master.xml`, after the `003-audit-tamper-resistance.xml` include, add:

```xml
<include file="004-audit-delete-prevention.xml" relativeToChangelogFile="true"/>
```

- [ ] **Step 3: Write the failing integration test**

Create `AuditDeletePreventionIT.java`:

```java
package io.emcip.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.audit.service.service.AuditService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

class AuditDeletePreventionIT extends AbstractAuditIntegrationTest {

    @Autowired private AuditService auditService;
    @Autowired private AuditEventRepository repository;
    @Autowired private DatabaseClient databaseClient;

    @BeforeEach
    void clean() {
        databaseClient.sql("TRUNCATE audit_events").fetch().rowsUpdated().block();
    }

    @Test
    void directDelete_withoutPurgeFlag_isBlockedByTrigger() {
        AuditEventEntity saved = auditService.saveWithChain(row("evt-del-1", Instant.now())).block();
        assertThat(saved.getId()).isNotNull();

        assertThatThrownBy(() -> repository.deleteById(saved.getId()).block())
                .hasMessageContaining("audit_events rows cannot be deleted");

        assertThat(repository.count().block()).isEqualTo(1L);
    }

    @Test
    void retention_withPurgeFlag_deletesExpiredRows() {
        Instant old = Instant.now().minus(400, ChronoUnit.DAYS);
        Instant recent = Instant.now();
        auditService.saveWithChain(row("evt-old", old)).block();
        auditService.saveWithChain(row("evt-recent", recent)).block();

        Long deleted =
                auditService
                        .deleteRecordsOlderThan(Instant.now().minus(365, ChronoUnit.DAYS))
                        .block();

        assertThat(deleted).isEqualTo(1L);
        assertThat(repository.count().block()).isEqualTo(1L);
        assertThat(repository.findByEventId("evt-recent").block()).isNotNull();
    }

    private static AuditEventEntity row(String eventId, Instant createdAt) {
        return AuditEventEntity.builder()
                .eventId(eventId)
                .eventType("TelegramMessage")
                .sourceService("emcip-tdlib-adapter")
                .action("TelegramMessage")
                .actorType("SYSTEM")
                .actorId("actor")
                .resourceType("TelegramMessage")
                .resourceId("res")
                .outcome("PROCESSED")
                .createdAt(createdAt)
                .build();
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `mvn -q -pl emcip-audit-service verify -Dit.test=AuditDeletePreventionIT -DfailIfNoTests=false`
Expected: FAIL — `retention_withPurgeFlag_deletesExpiredRows` fails because `deleteRecordsOlderThan` does not yet set the purge flag, so the trigger blocks it.

- [ ] **Step 5: Guard the retention delete with the purge flag**

In `AuditService`, rewrite `deleteRecordsOlderThan` to run inside a transaction that sets `SET LOCAL emcip.audit_purge='on'` first (same connection, so the trigger sees it):

```java
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
                                                                                "Purged {} audit"
                                                                                    + " records, anchor"
                                                                                    + " hash: {}",
                                                                                count,
                                                                                anchorHash);
                                                                    }
                                                                });
                                            })
                                    .defaultIfEmpty(0L));
    return transactionalOperator.transactional(op);
}
```

- [ ] **Step 6: Switch existing IT cleanup off deleteAll()**

In `AuditEventPersistenceIT`, replace the `@BeforeEach` body. Add `@Autowired private DatabaseClient databaseClient;` and:

```java
@BeforeEach
void cleanUp() {
    databaseClient.sql("TRUNCATE audit_events").fetch().rowsUpdated().block();
}
```

Remove the now-unused `deleteAll()` call. (Import `org.springframework.r2dbc.core.DatabaseClient`.)

- [ ] **Step 7: Run the delete-prevention IT and the persistence IT to verify green**

Run: `mvn -q -pl emcip-audit-service verify -Dit.test=AuditDeletePreventionIT,AuditEventPersistenceIT,AuditChainConcurrencyIT -DfailIfNoTests=false`
Expected: PASS — direct delete blocked, retention purges with the flag, cleanup works.

- [ ] **Step 8: Commit**

```bash
mvn -pl emcip-audit-service spotless:apply
git add emcip-audit-service/src/main/resources/db/changelog/004-audit-delete-prevention.xml \
        emcip-audit-service/src/main/resources/db/changelog/db.changelog-master.xml \
        emcip-audit-service/src/main/java/io/emcip/audit/service/service/AuditService.java \
        emcip-audit-service/src/test/java/io/emcip/audit/service/AuditEventPersistenceIT.java \
        emcip-audit-service/src/test/java/io/emcip/audit/service/AuditDeletePreventionIT.java
git commit -m "feat(audit): DELETE-prevention trigger guarded by purge flag (RT2-016)"
```

---

### Task 5: Reactive Kafka infrastructure (producer + DLQ handler + reactive template)

audit-service is consumer-only today with no `KafkaTemplate`, and `DeadLetterTopicHandler` is not scanned. This task adds the producer/DLQ beans and the reactive consumer template **without** subscribing yet (the old `@KafkaListener` consumer stays active), so behaviour is unchanged and tests stay green. It also removes the duplicate test `KafkaTemplate` so the main one is used everywhere.

**Files:**
- Create: `emcip-audit-service/src/main/java/io/emcip/audit/service/config/AuditKafkaConfig.java`
- Delete: `emcip-audit-service/src/test/java/io/emcip/audit/service/TestKafkaProducerConfig.java`
- Modify: `emcip-audit-service/src/test/java/io/emcip/audit/service/AbstractAuditIntegrationTest.java` (drop the `@Import`)

**Interfaces:**
- Produces: `@Bean KafkaTemplate<String,String> kafkaTemplate`; `@Bean DeadLetterTopicHandler deadLetterTopicHandler`; `@Bean ReactiveKafkaConsumerTemplate<String,String> reactiveAuditConsumerTemplate` (subscribed to the five audit topics); `@Bean KafkaMetricsConfig` (imported).
- Consumes: `io.emcip.common.kafka.DeadLetterTopicHandler`, `io.emcip.common.kafka.KafkaMetricsConfig` (from emcip-core), the audit `ObjectMapper` bean.

- [ ] **Step 1: Create the Kafka config**

Create `AuditKafkaConfig.java`:

```java
package io.emcip.audit.service.config;

import io.emcip.common.kafka.DeadLetterTopicHandler;
import io.emcip.common.kafka.KafkaMetricsConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.reactive.ReactiveKafkaConsumerTemplate;
import reactor.kafka.receiver.ReceiverOptions;
import tools.jackson.databind.ObjectMapper;

/** Reactive Kafka consumer + producer (for DLQ) wiring for the audit service. */
@Configuration
@Import(KafkaMetricsConfig.class)
public class AuditKafkaConfig {

    static final List<String> AUDIT_TOPICS =
            List.of(
                    "telegram.raw.messages",
                    "messages.classified",
                    "policies.decisions",
                    "responses.generated",
                    "moderation.flags");

    static final String GROUP_ID = "emcip-audit-service";

    @Value("${spring.kafka.bootstrap-servers:localhost:14003}")
    private String bootstrapServers;

    // --- Producer side: needed by DeadLetterTopicHandler to publish to <topic>.dlq ---

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public DeadLetterTopicHandler deadLetterTopicHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            KafkaMetricsConfig metricsConfig) {
        return new DeadLetterTopicHandler(kafkaTemplate, objectMapper, metricsConfig);
    }

    // --- Reactive consumer side ---

    @Bean
    public ReactiveKafkaConsumerTemplate<String, String> reactiveAuditConsumerTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);

        ReceiverOptions<String, String> receiverOptions =
                ReceiverOptions.<String, String>create(props).subscription(AUDIT_TOPICS);
        return new ReactiveKafkaConsumerTemplate<>(receiverOptions);
    }
}
```

- [ ] **Step 2: Remove the duplicate test producer config**

The main `kafkaTemplate` bean now serves tests too. Delete `TestKafkaProducerConfig.java` and drop its import from the base test class.

In `AbstractAuditIntegrationTest`, remove the `@Import(TestKafkaProducerConfig.class)` annotation and its import line.

```bash
git rm emcip-audit-service/src/test/java/io/emcip/audit/service/TestKafkaProducerConfig.java
```

- [ ] **Step 3: Verify the context still loads and existing ITs pass**

Run: `mvn -q -pl emcip-audit-service verify -Dit.test=AuditEventPersistenceIT,AuditChainConcurrencyIT,AuditDeletePreventionIT -DfailIfNoTests=false`
Expected: PASS — the old `@KafkaListener` consumer still handles `AuditEventPersistenceIT`; the new beans load; tests autowire the main `KafkaTemplate`.

> If the context fails with "no qualifying bean of type MeterRegistry" for `KafkaMetricsConfig`, confirm actuator/micrometer are on the classpath (they are). If `KafkaMetricsConfig` needs a `MeterRegistry` not present in the slice, it is provided by `spring-boot-starter-actuator` in the full `@SpringBootTest` context — no action needed.

- [ ] **Step 4: Commit**

```bash
mvn -pl emcip-audit-service spotless:apply
git add emcip-audit-service/src/main/java/io/emcip/audit/service/config/AuditKafkaConfig.java \
        emcip-audit-service/src/test/java/io/emcip/audit/service/AbstractAuditIntegrationTest.java
git commit -m "feat(audit): reactive Kafka + DLQ producer wiring (inert until Task 6)"
```

---

### Task 6: Topic→mapper registry + reactive consumer; activate the chain

Replace the five `@KafkaListener` methods and `KafkaConsumerConfig` with the reactive pipeline that calls `saveWithChain` (activating the chain), commits per record after persist, and routes failures to the DLQ. The per-topic mapping data moves into a registry.

**Files:**
- Create: `emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditTopicMapping.java`
- Create: `emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditTopicMappings.java`
- Create: `emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/ReactiveAuditEventConsumer.java`
- Delete: `emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java`
- Delete: `emcip-audit-service/src/main/java/io/emcip/audit/service/config/KafkaConsumerConfig.java`
- Replace: `emcip-audit-service/src/test/java/io/emcip/audit/service/kafka/AuditEventConsumerTest.java` → `ReactiveAuditEventConsumerTest.java`
- Modify: `emcip-audit-service/src/test/java/io/emcip/audit/service/AuditEventPersistenceIT.java` (send tenant header; assert `integrity_hash` populated)

**Interfaces:**
- Produces: `AuditTopicMapping<T extends EventSchemas.Event>` (record); `AuditTopicMappings.forTopic(String) : AuditTopicMapping<?>`; `ReactiveAuditEventConsumer.buildEntity(String json, AuditTopicMapping<T>, UUID tenant) : AuditEventEntity` (package-private, unit-testable).
- Consumes: `ReactiveKafkaConsumerTemplate` + `DeadLetterTopicHandler` (Task 5), `AuditService.saveWithChain` + `serializeDetails` (Tasks 2–3), `TenantAwareKafkaSupport.validateTenantHeader`.

- [ ] **Step 1: Create the mapping record**

Create `AuditTopicMapping.java`:

```java
package io.emcip.audit.service.kafka;

import io.emcip.common.events.EventSchemas;
import java.util.Map;
import java.util.function.Function;

/** Immutable per-topic description of how to turn an event into an audit record. */
public record AuditTopicMapping<T extends EventSchemas.Event>(
        String topic,
        Class<T> eventClass,
        String sourceService,
        String resourceType,
        Function<T, String> resourceIdFn,
        Function<T, String> actorIdFn,
        Function<T, String> correlationIdFn,
        Function<T, Map<String, Object>> detailsFn) {}
```

- [ ] **Step 2: Create the registry (the five current listener mappings as data)**

Create `AuditTopicMappings.java`:

```java
package io.emcip.audit.service.kafka;

import io.emcip.common.events.EventSchemas;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Registry of the five audit topic mappings, keyed by topic name. */
@Component
public class AuditTopicMappings {

    private final Map<String, AuditTopicMapping<?>> byTopic = new LinkedHashMap<>();

    public AuditTopicMappings() {
        register(
                new AuditTopicMapping<>(
                        "telegram.raw.messages",
                        EventSchemas.TelegramMessageEvent.class,
                        "emcip-tdlib-adapter",
                        "TelegramMessage",
                        e -> e.telegramMessageId() != null ? e.telegramMessageId().toString() : null,
                        EventSchemas.TelegramMessageEvent::senderId,
                        EventSchemas.TelegramMessageEvent::eventId,
                        e -> {
                            Map<String, Object> d = new LinkedHashMap<>();
                            d.put("telegramMessageId", e.telegramMessageId());
                            d.put("chatId", e.chatId());
                            d.put("senderId", e.senderId());
                            d.put("senderType", e.senderType());
                            return d;
                        }));
        register(
                new AuditTopicMapping<>(
                        "messages.classified",
                        EventSchemas.IntentClassifiedEvent.class,
                        "emcip-intent-classifier",
                        "Intent",
                        EventSchemas.IntentClassifiedEvent::sourceEventId,
                        e -> null,
                        EventSchemas.IntentClassifiedEvent::sourceEventId,
                        e -> {
                            Map<String, Object> d = new LinkedHashMap<>();
                            d.put("sourceEventId", e.sourceEventId());
                            d.put("intent", e.intent());
                            d.put("confidence", e.confidence());
                            d.put("matchedRules", e.matchedRules());
                            return d;
                        }));
        register(
                new AuditTopicMapping<>(
                        "policies.decisions",
                        EventSchemas.PolicyDecisionEvent.class,
                        "emcip-policy-engine",
                        "Policy",
                        EventSchemas.PolicyDecisionEvent::policyId,
                        e -> null,
                        EventSchemas.PolicyDecisionEvent::sourceEventId,
                        e -> {
                            Map<String, Object> d = new LinkedHashMap<>();
                            d.put("sourceEventId", e.sourceEventId());
                            d.put("policyId", e.policyId());
                            d.put("decision", e.decision());
                            d.put("reason", e.reason());
                            d.put("actions", e.actions());
                            return d;
                        }));
        register(
                new AuditTopicMapping<>(
                        "responses.generated",
                        EventSchemas.ResponseGeneratedEvent.class,
                        "emcip-llm-orchestrator",
                        "LlmResponse",
                        EventSchemas.ResponseGeneratedEvent::sourceEventId,
                        e -> null,
                        EventSchemas.ResponseGeneratedEvent::sourceEventId,
                        e -> {
                            Map<String, Object> d = new LinkedHashMap<>();
                            d.put("sourceEventId", e.sourceEventId());
                            d.put("modelUsed", e.modelUsed());
                            d.put("tokenCount", e.tokenCount());
                            return d;
                        }));
        register(
                new AuditTopicMapping<>(
                        "moderation.flags",
                        EventSchemas.ModerationFlagEvent.class,
                        "emcip-moderation-service",
                        "ModerationFlag",
                        EventSchemas.ModerationFlagEvent::sourceEventId,
                        e -> null,
                        EventSchemas.ModerationFlagEvent::sourceEventId,
                        e -> {
                            Map<String, Object> d = new LinkedHashMap<>();
                            d.put("sourceEventId", e.sourceEventId());
                            d.put("flagType", e.flagType());
                            d.put("severity", e.severity());
                            d.put("reason", e.reason());
                            return d;
                        }));
    }

    private void register(AuditTopicMapping<?> mapping) {
        byTopic.put(mapping.topic(), mapping);
    }

    public AuditTopicMapping<?> forTopic(String topic) {
        return byTopic.get(topic);
    }
}
```

- [ ] **Step 3: Write the failing unit test for entity building + tenant handling**

Create `ReactiveAuditEventConsumerTest.java` (same package, so it can call the package-private `buildEntity`):

```java
package io.emcip.audit.service.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emcip.audit.service.service.AuditService;
import io.emcip.common.events.EventSchemas.TelegramMessageEvent;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class ReactiveAuditEventConsumerTest {

    private ReactiveAuditEventConsumer consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuditTopicMappings mappings = new AuditTopicMappings();

    @BeforeEach
    void setUp() {
        AuditService auditService = Mockito.mock(AuditService.class);
        consumer =
                new ReactiveAuditEventConsumer(
                        Mockito.mock(
                                org.springframework.kafka.core.reactive.ReactiveKafkaConsumerTemplate
                                        .class),
                        auditService,
                        objectMapper,
                        mappings,
                        Mockito.mock(io.emcip.common.kafka.DeadLetterTopicHandler.class));
    }

    @Test
    void buildEntity_mapsTelegramMessageEvent_withTenantAndFields() throws Exception {
        TelegramMessageEvent event =
                new TelegramMessageEvent(
                        "evt-1", "2026-07-25T10:00:00Z", null, null, 300L, 400L,
                        "user-1", "USER", "hello", 0, null, false, null, null,
                        Map.of(), null, null, null, null);
        String json = objectMapper.writeValueAsString(event);
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000001");

        var entity = consumer.buildEntity(json, mappings.forTopic("telegram.raw.messages"), tenant);

        assertThat(entity.getEventId()).isEqualTo("evt-1");
        assertThat(entity.getEventType()).isEqualTo("TelegramMessage");
        assertThat(entity.getSourceService()).isEqualTo("emcip-tdlib-adapter");
        assertThat(entity.getResourceType()).isEqualTo("TelegramMessage");
        assertThat(entity.getTenantId()).isEqualTo(tenant);
        assertThat(entity.getOutcome()).isEqualTo("PROCESSED");
    }

    @Test
    void buildEntity_malformedJson_throws() {
        assertThatThrownBy(
                        () ->
                                consumer.buildEntity(
                                        "{ not valid json",
                                        mappings.forTopic("telegram.raw.messages"),
                                        UUID.randomUUID()))
                .isInstanceOf(Exception.class);
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `mvn -q -pl emcip-audit-service test -Dtest=ReactiveAuditEventConsumerTest`
Expected: FAIL — `ReactiveAuditEventConsumer` does not exist yet.

- [ ] **Step 5: Implement the reactive consumer**

Create `ReactiveAuditEventConsumer.java`:

```java
package io.emcip.audit.service.kafka;

import io.emcip.audit.service.config.AuditKafkaConfig;
import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.service.AuditService;
import io.emcip.common.events.EventSchemas;
import io.emcip.common.kafka.DeadLetterTopicHandler;
import io.emcip.common.tenant.TenantAwareKafkaSupport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.reactive.ReactiveKafkaConsumerTemplate;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.retry.Retry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Reactive consumer for all audit source topics. `concatMap` serialises processing within this
 * instance; {@code AuditService.saveWithChain}'s advisory lock serialises across replicas. Offsets
 * are committed only after a successful persist or after the record is durably placed on the DLQ, so
 * no record is silently lost.
 */
@Component
@Slf4j
public class ReactiveAuditEventConsumer {

    private final ReactiveKafkaConsumerTemplate<String, String> template;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final AuditTopicMappings mappings;
    private final DeadLetterTopicHandler dlqHandler;

    private Disposable subscription;

    public ReactiveAuditEventConsumer(
            ReactiveKafkaConsumerTemplate<String, String> template,
            AuditService auditService,
            ObjectMapper objectMapper,
            AuditTopicMappings mappings,
            DeadLetterTopicHandler dlqHandler) {
        this.template = template;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.mappings = mappings;
        this.dlqHandler = dlqHandler;
    }

    @PostConstruct
    void start() {
        subscription =
                template.receive()
                        .concatMap(this::handleRecord) // one record at a time
                        .subscribe(
                                v -> {},
                                err -> log.error("Audit consumer stream terminated", err));
    }

    @PreDestroy
    void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    Mono<Void> handleRecord(ReceiverRecord<String, String> record) {
        ReceiverOffset offset = record.receiverOffset();

        UUID tenant;
        try {
            tenant = TenantAwareKafkaSupport.validateTenantHeader(record);
        } catch (IllegalStateException e) {
            log.error("Rejecting record: {}", e.getMessage());
            return offset.commit();
        }

        AuditTopicMapping<?> mapping = mappings.forTopic(record.topic());
        if (mapping == null) {
            log.error("No audit mapping for topic {}, skipping offset {}", record.topic(),
                    record.offset());
            return offset.commit();
        }

        return persist(record, mapping, tenant)
                .retryWhen(
                        Retry.backoff(3, Duration.ofSeconds(1))
                                .filter(this::isTransient)) // malformed JSON is not retried
                .then(offset.commit())
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Routing {} offset {} to DLQ after failure: {}",
                                    record.topic(),
                                    record.offset(),
                                    e.getMessage());
                            // String-message overload: onErrorResume yields Throwable, not Exception.
                            dlqHandler.sendToDeadLetterQueue(
                                    record, e.getMessage(), 3, AuditKafkaConfig.GROUP_ID);
                            return offset.commit();
                        });
    }

    private <T extends EventSchemas.Event> Mono<AuditEventEntity> persist(
            ReceiverRecord<String, String> record, AuditTopicMapping<T> mapping, UUID tenant) {
        return Mono.fromCallable(() -> buildEntity(record.value(), mapping, tenant))
                .flatMap(auditService::saveWithChain);
    }

    /** Parse the JSON payload and map it to an audit entity. Package-private for unit tests. */
    <T extends EventSchemas.Event> AuditEventEntity buildEntity(
            String json, AuditTopicMapping<T> mapping, UUID tenant) {
        T event = objectMapper.readValue(json, mapping.eventClass());
        return AuditEventEntity.builder()
                .eventId(event.eventId())
                .eventType(event.eventType())
                .correlationId(mapping.correlationIdFn().apply(event))
                .sourceService(mapping.sourceService())
                .action(event.eventType())
                .actorType("SYSTEM")
                .actorId(mapping.actorIdFn().apply(event))
                .resourceType(mapping.resourceType())
                .resourceId(mapping.resourceIdFn().apply(event))
                .outcome("PROCESSED")
                .details(auditService.serializeDetails(mapping.detailsFn().apply(event)))
                .tenantId(tenant)
                .createdAt(Instant.now())
                .build();
    }

    /** Malformed payloads are permanent — do not retry them, send straight to the DLQ. */
    private boolean isTransient(Throwable t) {
        return !(t instanceof JacksonException);
    }
}
```

- [ ] **Step 6: Delete the old consumer and its config**

```bash
git rm emcip-audit-service/src/main/java/io/emcip/audit/service/kafka/AuditEventConsumer.java
git rm emcip-audit-service/src/main/java/io/emcip/audit/service/config/KafkaConsumerConfig.java
git rm emcip-audit-service/src/test/java/io/emcip/audit/service/kafka/AuditEventConsumerTest.java
```

- [ ] **Step 7: Update the end-to-end IT to send a tenant header and assert the chain is active**

In `AuditEventPersistenceIT`, change the send to include the `tenant_id` header and assert `integrity_hash` is now populated. Replace the send + await block:

```java
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import io.emcip.common.tenant.TenantAwareKafkaSupport;
import java.nio.charset.StandardCharsets;
// ...

String tenant = "00000000-0000-0000-0000-000000000001";
ProducerRecord<String, String> pr =
        new ProducerRecord<>("telegram.raw.messages", "audit-persist-001", json);
pr.headers().add(new RecordHeader("tenant_id", tenant.getBytes(StandardCharsets.UTF_8)));
kafkaTemplate.send(pr).get();

await().atMost(Duration.ofSeconds(20))
        .untilAsserted(
                () -> {
                    AuditEventEntity saved =
                            auditEventRepository.findByEventId("audit-persist-001").block();
                    assertThat(saved).isNotNull();
                    assertThat(saved.getEventType()).isEqualTo("TelegramMessage");
                    assertThat(saved.getSourceService()).isEqualTo("emcip-tdlib-adapter");
                    assertThat(saved.getOutcome()).isEqualTo("PROCESSED");
                    // Chain is now active: genesis row has a hash and a null predecessor.
                    assertThat(saved.getIntegrityHash()).isNotBlank();
                    assertThat(saved.getPrevHash()).isNull();
                });
```

- [ ] **Step 8: Run unit + integration to verify green**

Run: `mvn -q -pl emcip-audit-service test -Dtest=ReactiveAuditEventConsumerTest`
Expected: PASS.
Run: `mvn -q -pl emcip-audit-service verify -Dit.test=AuditEventPersistenceIT,AuditChainConcurrencyIT,AuditDeletePreventionIT -DfailIfNoTests=false`
Expected: PASS — record persisted end-to-end with `integrity_hash` populated by the reactive consumer.

- [ ] **Step 9: Commit**

```bash
mvn -pl emcip-audit-service spotless:apply
git add -A emcip-audit-service/src/main/java/io/emcip/audit/service/kafka \
        emcip-audit-service/src/main/java/io/emcip/audit/service/config \
        emcip-audit-service/src/test/java/io/emcip/audit/service
git commit -m "feat(audit): reactive consumer replaces @KafkaListener; activates hash chain (B1/RT2-002)"
```

---

### Task 7: No-silent-loss — malformed record lands on the DLQ

Prove the failure path: a malformed payload (with a valid tenant header) is not persisted, is published to `<topic>.dlq`, the offset advances, and a subsequent good record is still processed.

**Files:**
- Test: `emcip-audit-service/src/test/java/io/emcip/audit/service/AuditDlqIT.java`

**Interfaces:**
- Consumes: main `KafkaTemplate` (Task 5), `ReactiveAuditEventConsumer` (Task 6).

- [ ] **Step 1: Write the failing DLQ integration test**

Create `AuditDlqIT.java`:

```java
package io.emcip.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.common.events.EventSchemas.TelegramMessageEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import tools.jackson.databind.ObjectMapper;

class AuditDlqIT extends AbstractAuditIntegrationTest {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private AuditEventRepository repository;
    @Autowired private DatabaseClient databaseClient;

    private static final String TOPIC = "telegram.raw.messages";
    private static final String TENANT = "00000000-0000-0000-0000-000000000001";

    @BeforeEach
    void clean() {
        databaseClient.sql("TRUNCATE audit_events").fetch().rowsUpdated().block();
    }

    @Test
    void malformedRecord_goesToDlq_andGoodRecordStillProcessed() throws Exception {
        // Malformed JSON with a valid tenant header -> permanent failure -> DLQ.
        send(TOPIC, "bad-1", "{ not json", TENANT);

        // A good record after it must still be processed (offset advanced past the bad one).
        TelegramMessageEvent good =
                new TelegramMessageEvent(
                        "good-1", Instant.now().toString(), null, null, 1L, 2L, "u", "USER",
                        "hi", 0, null, false, null, null, Map.of(), null, null, null, null);
        send(TOPIC, "good-1", new ObjectMapper().writeValueAsString(good), TENANT);

        await().atMost(Duration.ofSeconds(25))
                .untilAsserted(
                        () -> {
                            AuditEventEntity saved = repository.findByEventId("good-1").block();
                            assertThat(saved).isNotNull();
                        });

        // The bad record was never persisted...
        assertThat(repository.count().block()).isEqualTo(1L);
        // ...and it landed on the DLQ.
        assertThat(dlqHasRecord(TOPIC + ".dlq")).isTrue();
    }

    private void send(String topic, String key, String value, String tenant) throws Exception {
        ProducerRecord<String, String> pr = new ProducerRecord<>(topic, key, value);
        pr.headers().add(new RecordHeader("tenant_id", tenant.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(pr).get();
    }

    private boolean dlqHasRecord(String dlqTopic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-verify-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            c.subscribe(Collections.singletonList(dlqTopic));
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> recs = c.poll(Duration.ofMillis(500));
                if (!recs.isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }
}
```

- [ ] **Step 2: Run it**

Run: `mvn -q -pl emcip-audit-service verify -Dit.test=AuditDlqIT -DfailIfNoTests=false`
Expected: PASS — bad record on DLQ, good record persisted, count == 1.

> If the good record is not persisted, the offset did not advance past the bad one — verify `handleRecord` commits in the `onErrorResume` branch.

- [ ] **Step 3: Commit**

```bash
mvn -pl emcip-audit-service spotless:apply
git add emcip-audit-service/src/test/java/io/emcip/audit/service/AuditDlqIT.java
git commit -m "test(audit): malformed record routed to DLQ, no silent loss (B1)"
```

---

### Task 8: Documentation + backlog/roadmap

Per the `documentation-checklist` skill (which now also covers `BACKLOG.md` and `ROADMAP.md`), update the docs and tracking files in the same PR. This task also commits the already-staged `documentation-checklist.md` edit.

**Files:**
- Modify: `documentation/diagrams/dataflow-audit-trail.puml`
- Modify: `documentation/diagrams/sequence-error-handling.puml`
- Modify: `documentation/architecture-guide.adoc`
- Modify: `documentation/operations-guide.adoc`
- Modify: `docs/superpowers/BACKLOG.md`
- Modify: `documentation/ROADMAP.md`
- Include: `.claude/skills/documentation-checklist.md` (already edited, uncommitted)

- [ ] **Step 1: Update the audit dataflow + error-handling diagrams**

In `dataflow-audit-trail.puml`, add: the reactive consumer, `saveWithChain` with the advisory lock, and the `<topic>.dlq` branch on failure. In `sequence-error-handling.puml`, add the audit consumer's retry(3, backoff)→DLQ path and per-record commit-after-persist.

- [ ] **Step 2: Update architecture + operations guides**

In `architecture-guide.adoc`, add an audit-integrity subsection: append-only hash chain (folds `prev_hash`), advisory-lock serialisation, DELETE trigger + `emcip.audit_purge` flag, reactive consumer with DLQ. In `operations-guide.adoc`, add an operator note: what `AuditChainVerificationJob`'s CRITICAL log means (with `reason`), that retention purge uses the flag, and that manual DB deletes are blocked by design.

- [ ] **Step 3: Flip backlog + roadmap status**

In `docs/superpowers/BACKLOG.md` §0, change the Status of RT2-002, RT2-016, and B1 from `⏳ deferred` to `✅` (branch `feat/p2-audit-integrity`), and remove the "deferred" note lines at the top of §0 that describe them as unresolved. In `documentation/ROADMAP.md`, add a P2.1 delivery note under the P2 section mirroring the P2.0 note style (branch, date `2026-07-25`, scope: advisory-lock chain activation + strengthened hash + reactive consumer/DLQ + DELETE trigger; note the new `reactor-kafka` dependency), and set "Next: P2.2 — SSRF protection".

- [ ] **Step 4: Verify the whole module builds and all tests pass together**

Run: `mvn -q -pl emcip-audit-service -am verify`
Expected: `BUILD SUCCESS` — all unit + integration tests green in one combined run (the P1 lesson: never merge parallel batches without a combined build).

- [ ] **Step 5: Commit**

```bash
git add documentation/diagrams/dataflow-audit-trail.puml \
        documentation/diagrams/sequence-error-handling.puml \
        documentation/architecture-guide.adoc \
        documentation/operations-guide.adoc \
        docs/superpowers/BACKLOG.md \
        documentation/ROADMAP.md \
        .claude/skills/documentation-checklist.md
git commit -m "docs(audit): P2.1 delivery — audit integrity redesign; backlog/roadmap; checklist"
```

---

## Notes for the implementer

- **reactor-kafka version:** `1.3.23` is the assumed compatible `1.3.x` release for Spring Boot 4 / Reactor 3.x. If it fails to resolve, use the newest `1.3.x` on Maven Central.
- **`ReactiveKafkaConsumerTemplate` offset control:** `enable.auto.commit=false` plus explicit `receiverOffset().commit()` after persist/DLQ gives at-least-once with no silent loss. `concatMap` guarantees ordered, one-at-a-time processing; the advisory lock covers the multi-replica case.
- **TRUNCATE vs DELETE in tests:** the DELETE trigger (Task 4) blocks row deletes, so test cleanup uses `TRUNCATE audit_events`, which does not fire `FOR EACH ROW` DELETE triggers. Production retention uses selective DELETE with the purge flag.
- **Do not change the job crons** (Rule 6 — they already carry offset seconds).
```
