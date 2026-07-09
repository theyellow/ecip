# Chunk Deduplication & Ingestion Job Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent duplicate document ingestion, add job lifecycle management (detail view, delete with cascade, re-ingest), and link chunks to jobs via a foreign key.

**Architecture:** Add `job_id` FK on `ke_knowledge_documents` and `content_hash` on `ke_ingestion_jobs`. Two-layer dedup: sourceRef pre-extraction (409 Conflict), content hash post-extraction (marks job as duplicate). Delete cascades job → chunks → graph edges (via Apache AGE Cypher, keyed on `source_message_id`). Detail view joins job → chunks → edges → nodes.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate, PostgreSQL + pgvector + Apache AGE, Liquibase, React (Vite), CSS Modules.

## Global Constraints

- Liquibase only for schema changes (never Flyway)
- Spotless formatting before every commit: `mvn spotless:apply -pl emcip-knowledge-engine`
- Lombok `@Slf4j`, `@RequiredArgsConstructor` — no manual getters
- UUID primary keys, `@Column(nullable = false)` where applicable
- Admin-UI: semantic tokens only, no emoji, no rounded corners on data surfaces, Cinzel display font for headings/labels
- Admin-API proxies all knowledge-engine endpoints through circuit breaker with appropriate permissions (KNOWLEDGE_READ / KNOWLEDGE_WRITE)
- Graph edges live in Apache AGE (`knowledge_graph`), not a relational table. Edge property `source_message_id` links to `ke_knowledge_documents.id`.

## File Structure

| File | Responsibility |
|------|---------------|
| `emcip-knowledge-engine/src/main/resources/db/changelog/changes/020-job-dedup-columns.xml` | Create: Liquibase migration — `content_hash` on jobs, `job_id` FK on documents, backfill |
| `emcip-knowledge-engine/src/main/java/.../entity/IngestionJob.java` | Modify: add `contentHash` field |
| `emcip-knowledge-engine/src/main/java/.../entity/KnowledgeDocument.java` | Modify: add `jobId` field |
| `emcip-knowledge-engine/src/main/java/.../model/IngestionJobDto.java` | Modify: add `contentHash` field |
| `emcip-knowledge-engine/src/main/java/.../model/IngestionJobDetailDto.java` | Create: detail response record |
| `emcip-knowledge-engine/src/main/java/.../model/ChunkSummaryDto.java` | Create: chunk summary record |
| `emcip-knowledge-engine/src/main/java/.../model/EntitySummaryDto.java` | Create: entity summary record |
| `emcip-knowledge-engine/src/main/java/.../model/DuplicateSourceException.java` | Create: exception for 409 |
| `emcip-knowledge-engine/src/main/java/.../repository/IngestionJobRepository.java` | Modify: add dedup query methods |
| `emcip-knowledge-engine/src/main/java/.../repository/KnowledgeDocumentRepository.java` | Modify: add `findAllByJobId`, `deleteAllByJobId` |
| `emcip-knowledge-engine/src/main/java/.../repository/GraphRepository.java` | Modify: add `deleteEdgesBySourceMessageIds` |
| `emcip-knowledge-engine/src/main/java/.../repository/AgeGraphRepository.java` | Modify: implement edge deletion by source_message_id |
| `emcip-knowledge-engine/src/main/java/.../service/DocumentIngestionService.java` | Modify: dedup checks, delete, reingest, detail, content hash |
| `emcip-knowledge-engine/src/main/java/.../service/KnowledgeExtractionService.java` | Modify: add `jobId` parameter to `processDocument()` |
| `emcip-knowledge-engine/src/main/java/.../controller/DocumentIngestionController.java` | Modify: add DELETE, details, reingest endpoints; 409 handling |
| `emcip-admin-api/src/main/java/.../controller/DocumentIngestionProxyController.java` | Modify: add DELETE, details, reingest proxies |
| `emcip-admin-ui/src/main/frontend/src/api/knowledge.js` | Modify: add deleteJob, jobDetails, reingest methods |
| `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.jsx` | Modify: add action column, detail modal trigger, delete flow |
| `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/JobDetailModal.jsx` | Create: job detail modal component |
| `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/JobDetailModal.module.css` | Create: styles for detail modal |
| `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/IngestionModal.jsx` | Modify: support re-upload mode with `replaceJobId` |

---

### Task 1: Schema Migration & Entity Updates

**Files:**
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/020-job-dedup-columns.xml`
- Modify: `emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/IngestionJob.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/KnowledgeDocument.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/IngestionJobDto.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/IngestionJobDetailDto.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ChunkSummaryDto.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/EntitySummaryDto.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/DuplicateSourceException.java`
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: `IngestionJob.contentHash` field, `KnowledgeDocument.jobId` field, `IngestionJobDto.contentHash()`, `IngestionJobDetailDto`, `ChunkSummaryDto`, `EntitySummaryDto`, `DuplicateSourceException`

- [ ] **Step 1: Create Liquibase migration**

Create `emcip-knowledge-engine/src/main/resources/db/changelog/changes/020-job-dedup-columns.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <!-- Add content_hash to ingestion jobs for dedup detection -->
    <changeSet id="ke-20a" author="knowledge-engine">
        <addColumn tableName="ke_ingestion_jobs">
            <column name="content_hash" type="VARCHAR(64)">
                <constraints nullable="true"/>
            </column>
        </addColumn>
        <createIndex indexName="idx_ke_jobs_content_hash" tableName="ke_ingestion_jobs">
            <column name="content_hash"/>
        </createIndex>
    </changeSet>

    <!-- Add job_id FK to knowledge documents -->
    <changeSet id="ke-20b" author="knowledge-engine">
        <addColumn tableName="ke_knowledge_documents">
            <column name="job_id" type="UUID">
                <constraints nullable="true"
                             foreignKeyName="fk_ke_docs_job_id"
                             referencedTableName="ke_ingestion_jobs"
                             referencedColumnNames="id"/>
            </column>
        </addColumn>
        <createIndex indexName="idx_ke_docs_job_id" tableName="ke_knowledge_documents">
            <column name="job_id"/>
        </createIndex>
    </changeSet>

    <!-- Backfill job_id on existing documents by matching source_ref + tenant_id -->
    <changeSet id="ke-20c" author="knowledge-engine">
        <sql>
            UPDATE ke_knowledge_documents d
            SET job_id = sub.job_id
            FROM (
                SELECT DISTINCT ON (d2.id) d2.id AS doc_id, j.id AS job_id
                FROM ke_knowledge_documents d2
                JOIN ke_ingestion_jobs j
                  ON d2.source_ref = j.source_ref
                 AND (d2.tenant_id = j.tenant_id OR (d2.tenant_id IS NULL AND j.tenant_id IS NULL))
                WHERE j.status = 'COMPLETED'
                  AND d2.job_id IS NULL
                ORDER BY d2.id, j.created_at DESC
            ) sub
            WHERE d.id = sub.doc_id;
        </sql>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Register migration in changelog master**

In `emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml`, add before the closing `</databaseChangeLog>`:

```xml
    <include file="changes/020-job-dedup-columns.xml" relativeToChangelogFile="true"/>
```

- [ ] **Step 3: Add `contentHash` to IngestionJob entity**

In `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/IngestionJob.java`, add after the `createdAt` field (line 50):

```java
    @Column(name = "content_hash", length = 64)
    private String contentHash;
```

- [ ] **Step 4: Add `jobId` to KnowledgeDocument entity**

In `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/KnowledgeDocument.java`, add after the `tenantId` field (line 32):

```java
    @Column(name = "job_id")
    private UUID jobId;
```

- [ ] **Step 5: Add `contentHash` to IngestionJobDto**

Replace `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/IngestionJobDto.java`:

```java
package io.emcip.knowledge.engine.model;

import io.emcip.knowledge.engine.entity.IngestionJob;

public record IngestionJobDto(
        String jobId,
        String sourceType,
        String sourceRef,
        String tenantId,
        String status,
        Integer chunkCount,
        String errorMessage,
        String createdAt,
        String contentHash) {

    public static IngestionJobDto from(IngestionJob job) {
        return new IngestionJobDto(
                job.getId().toString(),
                job.getSourceType().name(),
                job.getSourceRef(),
                job.getTenantId() != null ? job.getTenantId().toString() : null,
                job.getStatus().name(),
                job.getChunkCount(),
                job.getErrorMessage(),
                job.getCreatedAt() != null ? job.getCreatedAt().toString() : null,
                job.getContentHash());
    }
}
```

- [ ] **Step 6: Create new DTOs**

Create `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ChunkSummaryDto.java`:

```java
package io.emcip.knowledge.engine.model;

import java.util.UUID;

public record ChunkSummaryDto(
        UUID id, int chunkIndex, String contentPreview, int entityCount, int relationshipCount) {}
```

Create `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/EntitySummaryDto.java`:

```java
package io.emcip.knowledge.engine.model;

import java.util.UUID;

public record EntitySummaryDto(String label, String conceptType, UUID nodeId) {}
```

Create `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/IngestionJobDetailDto.java`:

```java
package io.emcip.knowledge.engine.model;

import java.util.List;

public record IngestionJobDetailDto(
        IngestionJobDto job,
        List<ChunkSummaryDto> chunks,
        List<EntitySummaryDto> entities,
        int totalChunks,
        int totalEntities,
        int totalRelationships) {}
```

- [ ] **Step 7: Create DuplicateSourceException**

Create `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/DuplicateSourceException.java`:

```java
package io.emcip.knowledge.engine.model;

import java.util.UUID;
import lombok.Getter;

@Getter
public class DuplicateSourceException extends RuntimeException {

    private final UUID existingJobId;

    public DuplicateSourceException(String sourceRef, UUID existingJobId) {
        super("Already ingested: " + sourceRef + ". Use re-ingest to update.");
        this.existingJobId = existingJobId;
    }
}
```

- [ ] **Step 8: Run Spotless and verify compilation**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
mvn compile -pl emcip-knowledge-engine -q
```

- [ ] **Step 9: Run existing tests to verify nothing broke**

```bash
mvn test -pl emcip-knowledge-engine -q
```

Expected: all existing tests pass (DTO constructor change is additive — tests that call `IngestionJobDto.from()` will get the new field).

- [ ] **Step 10: Commit**

```bash
git add emcip-knowledge-engine/src/main/resources/db/changelog/changes/020-job-dedup-columns.xml \
        emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/IngestionJob.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/KnowledgeDocument.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/IngestionJobDto.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/IngestionJobDetailDto.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ChunkSummaryDto.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/EntitySummaryDto.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/DuplicateSourceException.java
git commit -m "feat(knowledge-engine): add content_hash, job_id FK, and dedup DTOs

Liquibase migration 020: content_hash on ke_ingestion_jobs, job_id FK
on ke_knowledge_documents with backfill. New DTOs for job detail view.
DuplicateSourceException for 409 dedup responses."
```

---

### Task 2: Dedup Checks & Content Hash in Service Layer

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/IngestionJobRepository.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/DocumentIngestionService.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java`
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java`

**Interfaces:**
- Consumes: `IngestionJob.contentHash`, `KnowledgeDocument.jobId`, `DuplicateSourceException` from Task 1
- Produces: `DocumentIngestionService.submitUrlIngestion()` now throws `DuplicateSourceException` on duplicate sourceRef. `KnowledgeExtractionService.processDocument()` now takes 6 args (added `UUID jobId`). Content hash stored on job after extraction.

- [ ] **Step 1: Add repository query methods**

In `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/IngestionJobRepository.java`, add:

```java
    import io.emcip.knowledge.engine.entity.IngestionJob.IngestionStatus;
    import java.util.Optional;

    @Query("""
        SELECT j FROM IngestionJob j
        WHERE j.sourceRef = :sourceRef
          AND j.status = :status
          AND (j.tenantId = :tenantId OR (j.tenantId IS NULL AND :tenantId IS NULL))
        ORDER BY j.createdAt DESC
        LIMIT 1
        """)
    Optional<IngestionJob> findCompletedBySourceRefAndTenant(
            @Param("sourceRef") String sourceRef,
            @Param("tenantId") UUID tenantId,
            @Param("status") IngestionStatus status);

    @Query("""
        SELECT j FROM IngestionJob j
        WHERE j.contentHash = :contentHash
          AND j.status = :status
          AND j.id <> :excludeJobId
          AND (j.tenantId = :tenantId OR (j.tenantId IS NULL AND :tenantId IS NULL))
        ORDER BY j.createdAt DESC
        LIMIT 1
        """)
    Optional<IngestionJob> findCompletedByContentHashAndTenant(
            @Param("contentHash") String contentHash,
            @Param("tenantId") UUID tenantId,
            @Param("status") IngestionStatus status,
            @Param("excludeJobId") UUID excludeJobId);
```

Add the required imports: `org.springframework.data.jpa.repository.Query` and `org.springframework.data.repository.query.Param`.

- [ ] **Step 2: Write failing test for sourceRef dedup**

In `DocumentIngestionServiceTest.java`, add:

```java
    @Test
    void submitUrlIngestion_throwsDuplicateSourceExceptionWhenAlreadyIngested() {
        UUID existingJobId = UUID.randomUUID();
        IngestionJob existingJob = new IngestionJob();
        existingJob.setId(existingJobId);
        existingJob.setStatus(IngestionJob.IngestionStatus.COMPLETED);
        existingJob.setSourceRef("https://example.com/doc.pdf");

        when(jobRepository.findCompletedBySourceRefAndTenant(
                eq("https://example.com/doc.pdf"), isNull(), eq(IngestionJob.IngestionStatus.COMPLETED)))
                .thenReturn(Optional.of(existingJob));

        org.junit.jupiter.api.Assertions.assertThrows(
                io.emcip.knowledge.engine.model.DuplicateSourceException.class,
                () -> service.submitUrlIngestion("https://example.com/doc.pdf", null));
    }
```

- [ ] **Step 3: Run test to verify it fails**

```bash
mvn test -pl emcip-knowledge-engine -Dtest="DocumentIngestionServiceTest#submitUrlIngestion_throwsDuplicateSourceExceptionWhenAlreadyIngested" -q
```

Expected: FAIL — `submitUrlIngestion` doesn't check for duplicates yet.

- [ ] **Step 4: Add sourceRef dedup to submit methods**

In `DocumentIngestionService.java`, add this private method:

```java
    private void checkSourceRefDuplicate(String sourceRef, UUID tenantId) {
        jobRepository
                .findCompletedBySourceRefAndTenant(sourceRef, tenantId, IngestionStatus.COMPLETED)
                .ifPresent(
                        existing -> {
                            throw new DuplicateSourceException(sourceRef, existing.getId());
                        });
    }
```

Add import: `import io.emcip.knowledge.engine.model.DuplicateSourceException;`

Call it at the start of `submitUrlIngestion` (before `createAndSaveJob`):

```java
    public String submitUrlIngestion(String url, UUID tenantId) {
        checkSourceRefDuplicate(url, tenantId);
        IngestionJob job = createAndSaveJob(SourceType.URL, url, tenantId);
        UUID jobId = job.getId();
        INGESTION_EXECUTOR.submit(() -> processUrlAsync(jobId, url, tenantId));
        return jobId.toString();
    }
```

Same for `submitFileIngestion`:

```java
    public String submitFileIngestion(InputStream inputStream, String filename, UUID tenantId)
            throws IOException {
        checkSourceRefDuplicate(filename, tenantId);
        byte[] bytes = inputStream.readAllBytes();
        IngestionJob job = createAndSaveJob(SourceType.FILE_UPLOAD, filename, tenantId);
        UUID jobId = job.getId();
        INGESTION_EXECUTOR.submit(() -> processFileAsync(jobId, bytes, filename, tenantId));
        return jobId.toString();
    }
```

Also add an overload that accepts a `replaceJobId` to bypass dedup for re-ingestion:

```java
    public String submitUrlIngestion(String url, UUID tenantId, UUID replaceJobId) {
        if (replaceJobId == null) {
            checkSourceRefDuplicate(url, tenantId);
        }
        IngestionJob job = createAndSaveJob(SourceType.URL, url, tenantId);
        UUID jobId = job.getId();
        INGESTION_EXECUTOR.submit(() -> processUrlAsync(jobId, url, tenantId));
        return jobId.toString();
    }

    public String submitFileIngestion(
            InputStream inputStream, String filename, UUID tenantId, UUID replaceJobId)
            throws IOException {
        if (replaceJobId == null) {
            checkSourceRefDuplicate(filename, tenantId);
        }
        byte[] bytes = inputStream.readAllBytes();
        IngestionJob job = createAndSaveJob(SourceType.FILE_UPLOAD, filename, tenantId);
        UUID jobId = job.getId();
        INGESTION_EXECUTOR.submit(() -> processFileAsync(jobId, bytes, filename, tenantId));
        return jobId.toString();
    }
```

Update the original 2-arg methods to delegate:

```java
    public String submitUrlIngestion(String url, UUID tenantId) {
        return submitUrlIngestion(url, tenantId, null);
    }

    public String submitFileIngestion(InputStream inputStream, String filename, UUID tenantId)
            throws IOException {
        return submitFileIngestion(inputStream, filename, tenantId, null);
    }
```

- [ ] **Step 5: Add content hash computation and Layer 2 dedup**

Add to `DocumentIngestionService.java`:

```java
    import java.nio.charset.StandardCharsets;
    import java.security.MessageDigest;
    import java.util.HexFormat;
```

New private method:

```java
    private String computeContentHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("Failed to compute content hash: {}", e.getMessage());
            return null;
        }
    }
```

In `processUrlAsync`, after `containsInjectionPatterns` check and before `processChunks`, add:

```java
            String contentHash = computeContentHash(extracted.text());
            if (contentHash != null) {
                updateJobContentHash(jobId, contentHash);
                Optional<IngestionJob> hashDuplicate =
                        jobRepository.findCompletedByContentHashAndTenant(
                                contentHash, tenantId, IngestionStatus.COMPLETED, jobId);
                if (hashDuplicate.isPresent()) {
                    IngestionJob dup = hashDuplicate.get();
                    updateJobStatus(
                            jobId,
                            IngestionStatus.COMPLETED,
                            0,
                            "Duplicate content (matches job "
                                    + dup.getId()
                                    + ", source: "
                                    + dup.getSourceRef()
                                    + ")");
                    log.info(
                            "Content hash duplicate detected: jobId={}, matchesJob={}",
                            jobId,
                            dup.getId());
                    return;
                }
            }
```

Same block in `processFileAsync`, after injection check.

Add the helper method:

```java
    @Transactional
    void updateJobContentHash(UUID jobId, String contentHash) {
        jobRepository
                .findById(jobId)
                .ifPresent(
                        job -> {
                            job.setContentHash(contentHash);
                            jobRepository.save(job);
                        });
    }
```

- [ ] **Step 6: Pass jobId through processChunks to KnowledgeExtractionService**

Change `processChunks` signature to include `UUID jobId`:

```java
    private int processChunks(
            ExtractedContent extracted, String sourceRef, UUID tenantId, UUID jobId) {
```

In the lambda inside `processChunks`, change the `extractionService.processDocument` call to pass `jobId`:

```java
                                        extractionService.processDocument(
                                                chunk,
                                                sourceRef,
                                                tenantId,
                                                chunkIndex,
                                                immutableMetadata,
                                                jobId);
```

Update the two callers (`processUrlAsync` and `processFileAsync`) to pass `jobId`:

```java
            int chunkCount = processChunks(extracted, url, tenantId, jobId);
```

```java
            int chunkCount = processChunks(extracted, filename, tenantId, jobId);
```

- [ ] **Step 7: Update KnowledgeExtractionService.processDocument to accept jobId**

In `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java`, change the `processDocument` method signature at line 170:

```java
    @Transactional
    public void processDocument(
            String chunk,
            String sourceRef,
            UUID tenantId,
            int chunkIndex,
            Map<String, String> documentMetadata,
            UUID jobId) {
```

After the line `doc.setMetadata(metadata);` (around line 193), add:

```java
        doc.setJobId(jobId);
```

- [ ] **Step 8: Write failing test for content hash dedup**

In `DocumentIngestionServiceTest.java`, add:

```java
    @Test
    void processUrlAsync_marksJobAsDuplicateWhenContentHashMatches() throws Exception {
        String content = "some unique document content";
        httpServer.createContext(
                "/hashdup",
                exchange -> {
                    byte[] body = content.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
        String testUrl = "http://localhost:" + httpServer.getAddress().getPort() + "/hashdup";

        UUID jobId = UUID.randomUUID();
        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setStatus(IngestionJob.IngestionStatus.QUEUED);

        UUID existingJobId = UUID.randomUUID();
        IngestionJob existingJob = new IngestionJob();
        existingJob.setId(existingJobId);
        existingJob.setSourceRef("other-file.pdf");

        when(jobRepository.save(any()))
                .thenAnswer(inv -> {
                    IngestionJob j = inv.getArgument(0);
                    if (j.getId() == null) j.setId(jobId);
                    return j;
                });
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(tikaExtractionService.extract(any(byte[].class)))
                .thenReturn(new ExtractedContent(content, Map.of()));
        when(jobRepository.findCompletedByContentHashAndTenant(
                anyString(), isNull(), eq(IngestionJob.IngestionStatus.COMPLETED), eq(jobId)))
                .thenReturn(Optional.of(existingJob));

        service.submitUrlIngestion(testUrl, null);

        await().atMost(5, SECONDS)
                .untilAsserted(() -> {
                    ArgumentCaptor<IngestionJob> captor = ArgumentCaptor.forClass(IngestionJob.class);
                    verify(jobRepository, atLeastOnce()).save(captor.capture());
                    assertThat(captor.getAllValues())
                            .anyMatch(j ->
                                    j.getStatus() == IngestionJob.IngestionStatus.COMPLETED
                                            && j.getChunkCount() != null
                                            && j.getChunkCount() == 0
                                            && j.getErrorMessage() != null
                                            && j.getErrorMessage().contains("Duplicate content"));
                });
        verifyNoInteractions(extractionService);
    }
```

- [ ] **Step 9: Run all tests**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
mvn test -pl emcip-knowledge-engine -q
```

Expected: all tests pass.

- [ ] **Step 10: Commit**

```bash
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/IngestionJobRepository.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/DocumentIngestionService.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java
git commit -m "feat(knowledge-engine): two-layer dedup and content hash

Layer 1: sourceRef check before job creation, throws DuplicateSourceException (409).
Layer 2: SHA-256 content hash after extraction, marks job as duplicate if match found.
processDocument now receives jobId and sets it on KnowledgeDocument."
```

---

### Task 3: Job Delete with Cascade & Graph Edge Cleanup

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/KnowledgeDocumentRepository.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/GraphRepository.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/AgeGraphRepository.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/DocumentIngestionService.java`
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java`

**Interfaces:**
- Consumes: `KnowledgeDocument.jobId` from Task 1, `GraphRepository` from existing code
- Produces: `DocumentIngestionService.deleteJob(UUID jobId)` — transactional cascade delete. `GraphRepository.deleteEdgesBySourceMessageIds(List<UUID> documentIds)` — Cypher-based edge deletion.

- [ ] **Step 1: Add repository methods**

In `KnowledgeDocumentRepository.java`, add:

```java
    List<KnowledgeDocument> findAllByJobId(UUID jobId);

    void deleteAllByJobId(UUID jobId);
```

In `GraphRepository.java`, add:

```java
    /** Delete all edges whose source_message_id is in the given list of document IDs. */
    void deleteEdgesBySourceMessageIds(List<UUID> documentIds);
```

- [ ] **Step 2: Implement edge deletion in AgeGraphRepository**

In `AgeGraphRepository.java`, add:

```java
    @Override
    public void deleteEdgesBySourceMessageIds(List<UUID> documentIds) {
        if (documentIds.isEmpty()) return;
        for (UUID docId : documentIds) {
            String cypher =
                    String.format(
                            "MATCH ()-[r {source_message_id: '%s'}]->() DELETE r", docId);
            try {
                executeCypher(cypher);
            } catch (Exception e) {
                log.warn(
                        "Failed to delete edges for document {}: {}",
                        docId,
                        e.getMessage());
            }
        }
    }
```

- [ ] **Step 3: Write failing test for deleteJob**

In `DocumentIngestionServiceTest.java`, add:

```java
    @Test
    void deleteJob_cascadeDeletesChunksAndEdgesButNotNodes() {
        UUID jobId = UUID.randomUUID();
        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setStatus(IngestionJob.IngestionStatus.COMPLETED);
        job.setChunkCount(2);

        UUID doc1Id = UUID.randomUUID();
        UUID doc2Id = UUID.randomUUID();
        KnowledgeDocument doc1 = new KnowledgeDocument();
        doc1.setId(doc1Id);
        doc1.setJobId(jobId);
        KnowledgeDocument doc2 = new KnowledgeDocument();
        doc2.setId(doc2Id);
        doc2.setJobId(jobId);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findAllByJobId(jobId)).thenReturn(List.of(doc1, doc2));

        service.deleteJob(jobId);

        verify(graphRepository).deleteEdgesBySourceMessageIds(List.of(doc1Id, doc2Id));
        verify(documentRepository).deleteAllByJobId(jobId);
        verify(jobRepository).deleteById(jobId);
    }
```

Also add `@Mock GraphRepository graphRepository;` to the mock fields if not already present — check: the existing test file already has it at line 50.

- [ ] **Step 4: Run test to verify it fails**

```bash
mvn test -pl emcip-knowledge-engine -Dtest="DocumentIngestionServiceTest#deleteJob_cascadeDeletesChunksAndEdgesButNotNodes" -q
```

Expected: FAIL — `deleteJob` method doesn't exist yet.

- [ ] **Step 5: Implement deleteJob**

In `DocumentIngestionService.java`, add `graphRepository` as a dependency. Change the constructor dependencies by adding:

```java
    private final GraphRepository graphRepository;
    private final KnowledgeDocumentRepository documentRepository;
```

Add the new imports:

```java
    import io.emcip.knowledge.engine.repository.GraphRepository;
    import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
    import io.emcip.knowledge.engine.entity.KnowledgeDocument;
```

Add the method:

```java
    @Transactional
    public void deleteJob(UUID jobId) {
        IngestionJob job =
                jobRepository
                        .findById(jobId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Ingestion job not found: " + jobId));

        List<KnowledgeDocument> chunks = documentRepository.findAllByJobId(jobId);
        List<UUID> chunkIds = chunks.stream().map(KnowledgeDocument::getId).toList();

        if (!chunkIds.isEmpty()) {
            graphRepository.deleteEdgesBySourceMessageIds(chunkIds);
            documentRepository.deleteAllByJobId(jobId);
        }

        jobRepository.deleteById(jobId);
        log.info(
                "Deleted ingestion job {}: {} chunks and their edges removed",
                jobId,
                chunkIds.size());
    }
```

- [ ] **Step 6: Update DocumentIngestionService constructor in test setUp**

In `DocumentIngestionServiceTest.java`, the `setUp` method creates `DocumentIngestionService` with 5 args. Add the new dependencies:

```java
        service =
                new DocumentIngestionService(
                        jobRepository,
                        extractionService,
                        tikaExtractionService,
                        chunker,
                        new IngestionProperties(3),
                        graphRepository,
                        documentRepository);
```

Note: `@RequiredArgsConstructor` generates the constructor from all `final` fields in declaration order. Make sure the field order in `DocumentIngestionService` matches: `jobRepository`, `extractionService`, `tikaExtractionService`, `chunker`, `ingestionProperties`, `graphRepository`, `documentRepository`.

- [ ] **Step 7: Run all tests**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
mvn test -pl emcip-knowledge-engine -q
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/KnowledgeDocumentRepository.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/GraphRepository.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/AgeGraphRepository.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/DocumentIngestionService.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java
git commit -m "feat(knowledge-engine): cascade delete job with chunks and graph edges

deleteJob() deletes chunks by job_id, removes AGE graph edges by
source_message_id, then deletes the job record. Graph nodes preserved."
```

---

### Task 4: Job Detail & Re-ingest Endpoints

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/DocumentIngestionService.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/GraphRepository.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/AgeGraphRepository.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/DocumentIngestionController.java`
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java`

**Interfaces:**
- Consumes: `deleteJob()` from Task 3, `IngestionJobDetailDto`, `ChunkSummaryDto`, `EntitySummaryDto` from Task 1, `submitUrlIngestion(url, tenantId, replaceJobId)` from Task 2
- Produces: `GET /api/knowledge/ingest/{jobId}/details` returns `IngestionJobDetailDto`. `DELETE /api/knowledge/ingest/{jobId}` returns 204. `POST /api/knowledge/ingest/{jobId}/reingest` returns new job ID or 400 for files.

- [ ] **Step 1: Add graph query for edges by document IDs**

In `GraphRepository.java`, add:

```java
    /** Find all edges originating from given document IDs, returning source/target node info. */
    List<GraphEdge> findEdgesBySourceMessageIds(List<UUID> documentIds);
```

In `AgeGraphRepository.java`, add:

```java
    @Override
    public List<GraphEdge> findEdgesBySourceMessageIds(List<UUID> documentIds) {
        if (documentIds.isEmpty()) return List.of();
        List<GraphEdge> allEdges = new ArrayList<>();
        for (UUID docId : documentIds) {
            String cypher =
                    String.format(
                            """
                            MATCH (a)-[r {source_message_id: '%s'}]->(b)
                            RETURN {edge_id: r.edge_id,
                                    relationship_type: type(r),
                                    source_node_id: a.node_id,
                                    target_node_id: b.node_id,
                                    source_message_id: r.source_message_id}
                            """,
                            docId);
            allEdges.addAll(queryEdges(cypher));
        }
        return allEdges;
    }
```

- [ ] **Step 2: Implement getJobDetails**

In `DocumentIngestionService.java`, add:

```java
    public IngestionJobDetailDto getJobDetails(UUID jobId) {
        IngestionJob job = getJob(jobId);
        List<KnowledgeDocument> chunks = documentRepository.findAllByJobId(jobId);
        List<UUID> chunkIds = chunks.stream().map(KnowledgeDocument::getId).toList();

        List<GraphEdge> edges = graphRepository.findEdgesBySourceMessageIds(chunkIds);

        // Build chunk summaries with per-chunk entity/relationship counts
        Map<UUID, Long> entityCountByDoc = new HashMap<>();
        Map<UUID, Long> relCountByDoc = new HashMap<>();
        for (GraphEdge edge : edges) {
            UUID docId = edge.sourceMessageId();
            relCountByDoc.merge(docId, 1L, Long::sum);
            // Each edge has a target node — count unique target nodes per doc
            entityCountByDoc.merge(docId, 1L, Long::sum);
        }

        List<ChunkSummaryDto> chunkSummaries =
                chunks.stream()
                        .sorted(java.util.Comparator.comparingInt(KnowledgeDocument::getChunkIndex))
                        .map(
                                doc -> {
                                    String preview =
                                            doc.getContent().length() > 200
                                                    ? doc.getContent().substring(0, 200)
                                                    : doc.getContent();
                                    return new ChunkSummaryDto(
                                            doc.getId(),
                                            doc.getChunkIndex(),
                                            preview,
                                            entityCountByDoc.getOrDefault(doc.getId(), 0L).intValue(),
                                            relCountByDoc.getOrDefault(doc.getId(), 0L).intValue());
                                })
                        .toList();

        // Deduplicated entities from edge target nodes
        Set<UUID> seenNodeIds = new java.util.HashSet<>();
        List<EntitySummaryDto> entities = new ArrayList<>();
        for (GraphEdge edge : edges) {
            if (edge.targetNodeId() != null && seenNodeIds.add(edge.targetNodeId())) {
                // We need node label + conceptType — query from graph
                graphRepository
                        .findNodeById(edge.targetNodeId())
                        .ifPresent(
                                node ->
                                        entities.add(
                                                new EntitySummaryDto(
                                                        node.label(),
                                                        node.conceptType(),
                                                        node.id())));
            }
        }

        return new IngestionJobDetailDto(
                IngestionJobDto.from(job),
                chunkSummaries,
                entities,
                chunks.size(),
                entities.size(),
                edges.size());
    }
```

Add import: `import io.emcip.knowledge.engine.model.*;`

Add `findNodeById` to `GraphRepository.java`:

```java
    Optional<GraphNode> findNodeById(UUID nodeId);
```

Implement in `AgeGraphRepository.java`:

```java
    @Override
    public Optional<GraphNode> findNodeById(UUID nodeId) {
        String cypher = String.format("MATCH (n {node_id: '%s'}) RETURN n", nodeId);
        List<GraphNode> results = queryNodes(cypher);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }
```

- [ ] **Step 3: Implement reingestJob**

In `DocumentIngestionService.java`, add:

```java
    public String reingestJob(UUID jobId) {
        IngestionJob oldJob = getJob(jobId);

        if (oldJob.getSourceType() == SourceType.FILE_UPLOAD) {
            throw new IllegalArgumentException("REUPLOAD_REQUIRED");
        }

        // Delete old data
        List<KnowledgeDocument> oldChunks = documentRepository.findAllByJobId(jobId);
        List<UUID> oldChunkIds = oldChunks.stream().map(KnowledgeDocument::getId).toList();
        if (!oldChunkIds.isEmpty()) {
            graphRepository.deleteEdgesBySourceMessageIds(oldChunkIds);
            documentRepository.deleteAllByJobId(jobId);
        }

        // Create new job and process
        return submitUrlIngestion(oldJob.getSourceRef(), oldJob.getTenantId(), jobId);
    }
```

- [ ] **Step 4: Add controller endpoints**

In `DocumentIngestionController.java`, add these imports:

```java
    import io.emcip.knowledge.engine.model.DuplicateSourceException;
    import io.emcip.knowledge.engine.model.IngestionJobDetailDto;
    import org.springframework.web.bind.annotation.DeleteMapping;
    import org.springframework.http.HttpStatus;
    import org.springframework.web.bind.annotation.ExceptionHandler;
    import org.springframework.web.bind.annotation.ResponseStatus;
```

Add these endpoints:

```java
    @Operation(summary = "Get ingestion job details with chunks and entities")
    @GetMapping("/{jobId}/details")
    public IngestionJobDetailDto getJobDetails(@PathVariable UUID jobId) {
        return ingestionService.getJobDetails(jobId);
    }

    @Operation(summary = "Delete an ingestion job and its chunks")
    @DeleteMapping("/{jobId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteJob(@PathVariable UUID jobId) {
        ingestionService.deleteJob(jobId);
    }

    @Operation(summary = "Re-ingest a URL job (re-fetches content, replaces old chunks)")
    @PostMapping("/{jobId}/reingest")
    public ResponseEntity<Map<String, Object>> reingestJob(@PathVariable UUID jobId) {
        try {
            String newJobId = ingestionService.reingestJob(jobId);
            return ResponseEntity.accepted().body(Map.of("jobId", newJobId));
        } catch (IllegalArgumentException e) {
            if ("REUPLOAD_REQUIRED".equals(e.getMessage())) {
                IngestionJob oldJob = ingestionService.getJob(jobId);
                return ResponseEntity.badRequest()
                        .body(
                                Map.of(
                                        "error",
                                        "REUPLOAD_REQUIRED",
                                        "sourceRef",
                                        oldJob.getSourceRef()));
            }
            throw e;
        }
    }

    @ExceptionHandler(DuplicateSourceException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateSourceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        Map.of(
                                "error",
                                "DUPLICATE_SOURCE",
                                "existingJobId",
                                ex.getExistingJobId().toString(),
                                "message",
                                ex.getMessage()));
    }
```

- [ ] **Step 5: Write test for detail endpoint**

In `DocumentIngestionServiceTest.java`, add:

```java
    @Test
    void getJobDetails_returnsChunksAndCounts() {
        UUID jobId = UUID.randomUUID();
        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setSourceType(IngestionJob.SourceType.URL);
        job.setSourceRef("https://example.com");
        job.setStatus(IngestionJob.IngestionStatus.COMPLETED);
        job.setChunkCount(1);
        job.setCreatedAt(java.time.OffsetDateTime.now());

        UUID docId = UUID.randomUUID();
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(docId);
        doc.setJobId(jobId);
        doc.setContent("Test chunk content here");
        doc.setChunkIndex(0);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findAllByJobId(jobId)).thenReturn(List.of(doc));
        when(graphRepository.findEdgesBySourceMessageIds(List.of(docId))).thenReturn(List.of());

        IngestionJobDetailDto detail = service.getJobDetails(jobId);

        assertThat(detail.totalChunks()).isEqualTo(1);
        assertThat(detail.chunks()).hasSize(1);
        assertThat(detail.chunks().getFirst().contentPreview()).isEqualTo("Test chunk content here");
        assertThat(detail.entities()).isEmpty();
    }
```

Add import: `import io.emcip.knowledge.engine.model.IngestionJobDetailDto;`

- [ ] **Step 6: Run all tests**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
mvn test -pl emcip-knowledge-engine -q
```

- [ ] **Step 7: Commit**

```bash
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/GraphRepository.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/AgeGraphRepository.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/DocumentIngestionService.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/DocumentIngestionController.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java
git commit -m "feat(knowledge-engine): job detail, delete, and re-ingest endpoints

GET /{jobId}/details returns chunks with previews, entities, counts.
DELETE /{jobId} cascades to chunks and graph edges.
POST /{jobId}/reingest re-fetches URLs; returns REUPLOAD_REQUIRED for files.
409 Conflict handler for DuplicateSourceException."
```

---

### Task 5: Admin-API Proxy Endpoints

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/DocumentIngestionProxyController.java`
- Test: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/DocumentIngestionProxyControllerTest.java` (create if needed)

**Interfaces:**
- Consumes: knowledge-engine endpoints from Task 4
- Produces: `DELETE /api/admin/knowledge/ingest/{jobId}`, `GET /api/admin/knowledge/ingest/{jobId}/details`, `POST /api/admin/knowledge/ingest/{jobId}/reingest` — all proxied with circuit breaker

- [ ] **Step 1: Add proxy methods**

In `DocumentIngestionProxyController.java`, add import:

```java
    import org.springframework.web.bind.annotation.DeleteMapping;
```

Add these methods:

```java
    @Operation(summary = "Get ingestion job details")
    @GetMapping("/{jobId}/details")
    @PreAuthorize("hasAuthority('KNOWLEDGE_READ')")
    public Mono<ResponseEntity<String>> getJobDetails(@PathVariable String jobId) {
        return knowledgeWebClient
                .get()
                .uri("/api/knowledge/ingest/{jobId}/details", jobId)
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Job details proxy error jobId={}: {}",
                                    jobId,
                                    e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Delete an ingestion job and its chunks")
    @DeleteMapping("/{jobId}")
    @PreAuthorize("hasAuthority('KNOWLEDGE_WRITE')")
    public Mono<ResponseEntity<Void>> deleteJob(@PathVariable String jobId) {
        return knowledgeWebClient
                .delete()
                .uri("/api/knowledge/ingest/{jobId}", jobId)
                .retrieve()
                .toBodilessEntity()
                .map(r -> ResponseEntity.noContent().<Void>build())
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Job delete proxy error jobId={}: {}",
                                    jobId,
                                    e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<Void>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Re-ingest a job (re-fetch URL or request file re-upload)")
    @PostMapping("/{jobId}/reingest")
    @PreAuthorize("hasAuthority('KNOWLEDGE_WRITE')")
    public Mono<ResponseEntity<String>> reingestJob(@PathVariable String jobId) {
        return knowledgeWebClient
                .post()
                .uri("/api/knowledge/ingest/{jobId}/reingest", jobId)
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> ResponseEntity.accepted().<String>body(body))
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Reingest proxy error jobId={}: {}",
                                    jobId,
                                    e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
```

Also need to forward 409 from knowledge-engine to the frontend. Update the `ingestUrl` method to not swallow the 409. Replace the `onErrorResume` block in `ingestUrl` with:

```java
                .onErrorResume(
                        org.springframework.web.reactive.function.client.WebClientResponseException.class,
                        e -> {
                            if (e.getStatusCode().value() == 409) {
                                return Mono.just(
                                        ResponseEntity.status(HttpStatus.CONFLICT)
                                                .<String>body(e.getResponseBodyAsString()));
                            }
                            log.error("Ingest URL proxy error: {}", e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
```

- [ ] **Step 2: Run Spotless and compile**

```bash
mvn spotless:apply -pl emcip-admin-api
mvn compile -pl emcip-admin-api -q
```

- [ ] **Step 3: Commit**

```bash
git add emcip-admin-api/src/main/java/io/emcip/admin/api/controller/DocumentIngestionProxyController.java
git commit -m "feat(admin-api): proxy delete, detail, and reingest ingestion endpoints

DELETE, GET /details, POST /reingest proxied with circuit breaker.
409 Conflict forwarded from knowledge-engine for dedup responses."
```

---

### Task 6: Frontend API & Job Detail Modal

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/api/knowledge.js`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/JobDetailModal.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/JobDetailModal.module.css`
- Test: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/JobDetailModal.test.jsx`

**Interfaces:**
- Consumes: `GET /api/admin/knowledge/ingest/{jobId}/details` from Task 5
- Produces: `<JobDetailModal jobId tenants onClose />` component, `knowledgeApi.jobDetails()`, `knowledgeApi.deleteJob()`, `knowledgeApi.reingest()` methods

- [ ] **Step 1: Add API methods**

In `emcip-admin-ui/src/main/frontend/src/api/knowledge.js`, add before the closing `}`:

```javascript
    /** GET /api/admin/knowledge/ingest/{jobId}/details — returns IngestionJobDetailDto */
    jobDetails: jobId =>
      request(`/api/admin/knowledge/ingest/${encodeURIComponent(jobId)}/details`),

    /** DELETE /api/admin/knowledge/ingest/{jobId} — returns 204 */
    deleteJob: jobId =>
      request(`/api/admin/knowledge/ingest/${encodeURIComponent(jobId)}`, {
        method: 'DELETE',
      }),

    /** POST /api/admin/knowledge/ingest/{jobId}/reingest — returns { jobId } or 400 */
    reingest: jobId =>
      request(`/api/admin/knowledge/ingest/${encodeURIComponent(jobId)}/reingest`, {
        method: 'POST',
      }),
```

- [ ] **Step 2: Create JobDetailModal component**

Create `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/JobDetailModal.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { Modal } from '../../components/Modal/Modal'
import { Badge } from '../../components/Badge/Badge'
import styles from './JobDetailModal.module.css'

const STATUS_VARIANT = {
  COMPLETED: 'green',
  RUNNING: 'blue',
  QUEUED: 'gray',
  FAILED: 'red',
  FLAGGED_INJECTION_RISK: 'yellow',
}

export function JobDetailModal({ api, jobId, tenants, onClose, onSearchEntity }) {
  const [detail, setDetail] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    setLoading(true)
    api
      .jobDetails(jobId)
      .then(setDetail)
      .catch(e => setError(e.message || 'Failed to load details'))
      .finally(() => setLoading(false))
  }, [api, jobId])

  const tenantName = detail?.job?.tenantId
    ? (tenants.find(t => t.id === detail.job.tenantId)?.name ?? detail.job.tenantId)
    : 'Global'

  const title = detail?.job?.sourceRef
    ? (detail.job.sourceRef.length > 60
        ? detail.job.sourceRef.substring(0, 60) + '\u2026'
        : detail.job.sourceRef)
    : 'Job Details'

  return (
    <Modal title={title} onClose={onClose}>
      {loading && <p className={styles.loading}>Loading details\u2026</p>}
      {error && <p className={styles.error}>{error}</p>}
      {detail && (
        <div className={styles.content}>
          {/* Job Info */}
          <div className={styles.sectionLabel}>
            <span>\u2014 JOB INFO \u2014</span>
          </div>
          <div className={styles.infoGrid}>
            <div className={styles.infoRow}>
              <span className={styles.infoLabel}>Source</span>
              <span className={styles.infoValue}>{detail.job.sourceRef}</span>
            </div>
            <div className={styles.infoRow}>
              <span className={styles.infoLabel}>Type</span>
              <span className={styles.infoValue}>{detail.job.sourceType}</span>
            </div>
            <div className={styles.infoRow}>
              <span className={styles.infoLabel}>Tenant</span>
              <span className={styles.infoValue}>{tenantName}</span>
            </div>
            <div className={styles.infoRow}>
              <span className={styles.infoLabel}>Status</span>
              <Badge variant={STATUS_VARIANT[detail.job.status] ?? 'gray'}>
                {detail.job.status}
              </Badge>
            </div>
            {detail.job.contentHash && (
              <div className={styles.infoRow}>
                <span className={styles.infoLabel}>Content Hash</span>
                <span className={styles.mono}>{detail.job.contentHash}</span>
              </div>
            )}
            <div className={styles.infoRow}>
              <span className={styles.infoLabel}>Created</span>
              <span className={styles.mono}>
                {detail.job.createdAt
                  ? new Date(detail.job.createdAt).toLocaleString()
                  : '\u2014'}
              </span>
            </div>
          </div>

          {/* Error message for FAILED / FLAGGED */}
          {detail.job.errorMessage && (
            <div
              className={
                detail.job.status === 'FLAGGED_INJECTION_RISK'
                  ? styles.warningBlock
                  : styles.errorBlock
              }
            >
              {detail.job.errorMessage}
            </div>
          )}

          {/* Chunks */}
          <div className={styles.sectionLabel}>
            <span>\u2014 CHUNKS ({detail.totalChunks}) \u2014</span>
          </div>
          {detail.chunks.length === 0 ? (
            <p className={styles.empty}>No chunks.</p>
          ) : (
            <div className={styles.chunkList}>
              {detail.chunks.map(c => (
                <div key={c.id} className={styles.chunkCard}>
                  <div className={styles.chunkMeta}>
                    <span className={styles.mono}>#{c.chunkIndex}</span>
                    <span className={styles.mono}>
                      {c.entityCount} entities \u00b7 {c.relationshipCount} rels
                    </span>
                  </div>
                  <div className={styles.chunkPreview}>{c.contentPreview}</div>
                </div>
              ))}
            </div>
          )}

          {/* Entities */}
          <div className={styles.sectionLabel}>
            <span>\u2014 ENTITIES ({detail.totalEntities}) \u2014</span>
          </div>
          {detail.entities.length === 0 ? (
            <p className={styles.empty}>No entities extracted.</p>
          ) : (
            <div className={styles.entityChips}>
              {detail.entities.map(e => (
                <button
                  key={e.nodeId}
                  type="button"
                  className={styles.entityChip}
                  onClick={() => {
                    onSearchEntity?.(e.label)
                    onClose()
                  }}
                >
                  {e.label}{' '}
                  <Badge variant="gray">{e.conceptType}</Badge>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </Modal>
  )
}
```

- [ ] **Step 3: Create JobDetailModal styles**

Create `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/JobDetailModal.module.css`:

```css
.content {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}

.sectionLabel {
  font-family: var(--font-display);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.18em;
  color: var(--accent);
  text-align: center;
  margin: var(--sp-2) 0 var(--sp-1);
}

.infoGrid {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
}

.infoRow {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
}

.infoLabel {
  font-family: var(--font-display);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.10em;
  color: var(--fg-3);
  min-width: 100px;
  flex-shrink: 0;
}

.infoValue {
  color: var(--fg-1);
  font-size: 13px;
  word-break: break-all;
}

.mono {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}

.errorBlock {
  background: var(--signal-stop-bg);
  color: var(--signal-stop-fg);
  padding: var(--sp-2) var(--sp-3);
  font-size: 13px;
  border: 1px solid var(--border);
}

.warningBlock {
  background: var(--signal-warn-bg);
  color: var(--signal-warn-fg);
  padding: var(--sp-2) var(--sp-3);
  font-size: 13px;
  border: 1px solid var(--border);
}

.loading,
.error,
.empty {
  color: var(--fg-3);
  font-style: italic;
  text-align: center;
  padding: var(--sp-4) 0;
}

.error {
  color: var(--signal-stop-fg);
}

.chunkList {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  max-height: 300px;
  overflow-y: auto;
}

.chunkCard {
  background: var(--bg-input);
  border: 1px solid var(--border);
  padding: var(--sp-2) var(--sp-3);
}

.chunkMeta {
  display: flex;
  justify-content: space-between;
  margin-bottom: var(--sp-1);
}

.chunkPreview {
  font-size: 13px;
  color: var(--fg-2);
  line-height: 1.5;
}

.entityChips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-1);
}

.entityChip {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-1);
  background: transparent;
  border: 1px solid var(--border);
  padding: 2px 8px;
  font-family: var(--font-body);
  font-size: 12px;
  color: var(--fg-1);
  cursor: pointer;
}

.entityChip:hover {
  border-color: var(--accent);
  color: var(--accent);
}
```

- [ ] **Step 4: Write test**

Create `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/JobDetailModal.test.jsx`:

```jsx
import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { JobDetailModal } from './JobDetailModal'

const mockDetail = {
  job: {
    jobId: '123',
    sourceType: 'URL',
    sourceRef: 'https://example.com/doc.pdf',
    tenantId: null,
    status: 'COMPLETED',
    chunkCount: 2,
    errorMessage: null,
    createdAt: '2026-07-09T10:00:00Z',
    contentHash: 'abc123',
  },
  chunks: [
    { id: 'c1', chunkIndex: 0, contentPreview: 'First chunk text...', entityCount: 2, relationshipCount: 1 },
    { id: 'c2', chunkIndex: 1, contentPreview: 'Second chunk text...', entityCount: 1, relationshipCount: 0 },
  ],
  entities: [
    { label: 'Angela Merkel', conceptType: 'PERSON', nodeId: 'n1' },
  ],
  totalChunks: 2,
  totalEntities: 1,
  totalRelationships: 1,
}

describe('JobDetailModal', () => {
  it('renders job info, chunks, and entities', async () => {
    const api = { jobDetails: vi.fn().mockResolvedValue(mockDetail) }
    render(
      <JobDetailModal
        api={api}
        jobId="123"
        tenants={[]}
        onClose={() => {}}
      />
    )

    await waitFor(() => {
      expect(screen.getByText('COMPLETED')).toBeInTheDocument()
    })
    expect(screen.getByText('abc123')).toBeInTheDocument()
    expect(screen.getByText('First chunk text...')).toBeInTheDocument()
    expect(screen.getByText('Second chunk text...')).toBeInTheDocument()
    expect(screen.getByText('Angela Merkel')).toBeInTheDocument()
  })

  it('shows error block for failed jobs', async () => {
    const failedDetail = {
      ...mockDetail,
      job: { ...mockDetail.job, status: 'FAILED', errorMessage: 'Something went wrong' },
    }
    const api = { jobDetails: vi.fn().mockResolvedValue(failedDetail) }
    render(
      <JobDetailModal api={api} jobId="123" tenants={[]} onClose={() => {}} />
    )

    await waitFor(() => {
      expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    })
  })
})
```

- [ ] **Step 5: Run tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run src/pages/Knowledge/JobDetailModal.test.jsx --reporter=verbose
```

- [ ] **Step 6: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/api/knowledge.js \
        emcip-admin-ui/src/main/frontend/src/pages/Knowledge/JobDetailModal.jsx \
        emcip-admin-ui/src/main/frontend/src/pages/Knowledge/JobDetailModal.module.css \
        emcip-admin-ui/src/main/frontend/src/pages/Knowledge/JobDetailModal.test.jsx
git commit -m "feat(admin-ui): job detail modal and API methods for delete/reingest

JobDetailModal shows job info, chunk previews with entity counts,
and clickable entity chips. API methods for jobDetails, deleteJob, reingest."
```

---

### Task 7: KnowledgePage Job Actions (View, Delete, Re-ingest)

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/IngestionModal.jsx`

**Interfaces:**
- Consumes: `<JobDetailModal>` from Task 6, `api.deleteJob()`, `api.reingest()` from Task 6, `<ConfirmDialog>` from existing components
- Produces: Action column in DataTable with View/Delete/Re-ingest buttons. IngestionModal supports `replaceJobId` prop for re-upload mode.

- [ ] **Step 1: Add job actions to KnowledgePage**

In `KnowledgePage.jsx`, add imports at the top:

```javascript
import { ConfirmDialog } from '../../components/ConfirmDialog/ConfirmDialog'
import { JobDetailModal } from './JobDetailModal'
```

Add state variables after existing state declarations:

```javascript
  const [detailJobId, setDetailJobId] = useState(null)
  const [confirmDelete, setConfirmDelete] = useState(null)
  const [reingestJob, setReingestJob] = useState(null)
```

Update `STATUS_VARIANT` to include the injection risk status:

```javascript
const STATUS_VARIANT = {
  COMPLETED: 'green',
  RUNNING: 'blue',
  QUEUED: 'gray',
  FAILED: 'red',
  FLAGGED_INJECTION_RISK: 'yellow',
}
```

Add an actions column to `JOB_COLUMNS`. Replace the existing `JOB_COLUMNS` definition with a function that receives handlers — or simpler: add the render column inside the component. After the `JOB_COLUMNS` definition, within the `Knowledge` component, create the columns with the actions column appended:

```javascript
  const jobColumns = [
    ...JOB_COLUMNS,
    {
      key: '_actions',
      label: '',
      width: '120px',
      render: (_, row) => (
        <span className={styles.actionBtns} onClick={e => e.stopPropagation()}>
          <button
            type="button"
            className={styles.actionBtn}
            title="View details"
            onClick={() => setDetailJobId(row.id)}
          >
            \u25b8
          </button>
          <button
            type="button"
            className={styles.actionBtn}
            title="Delete"
            onClick={() => setConfirmDelete(row)}
          >
            \u2715
          </button>
          {(row.rawStatus === 'COMPLETED' || row.rawStatus === 'FAILED') && (
            <button
              type="button"
              className={styles.actionBtn}
              title="Re-ingest"
              onClick={() => setReingestJob(row)}
            >
              \u21bb
            </button>
          )}
        </span>
      ),
    },
  ]
```

Add handler functions:

```javascript
  async function handleDeleteJob(row) {
    try {
      await api.deleteJob(row.id)
      addToast('info', `Job deleted: ${row.sourceRef}`)
      loadJobs()
    } catch (e) {
      addToast('error', `Delete failed: ${e.message || 'Unknown error'}`)
    }
    setConfirmDelete(null)
  }

  async function handleReingest(row) {
    try {
      const result = await api.reingest(row.id)
      if (result.error === 'REUPLOAD_REQUIRED') {
        setReingestJob(null)
        setShowModal({ replaceJobId: row.id, sourceRef: result.sourceRef, tenantId: row.rawTenantId })
        return
      }
      addToast('info', `Re-ingestion started: ${row.sourceRef}`)
      loadJobs()
    } catch (e) {
      addToast('error', `Re-ingest failed: ${e.message || 'Unknown error'}`)
    }
    setReingestJob(null)
  }

  function handleSearchEntity(label) {
    setActiveTab('search')
    setQuery(label)
  }
```

Update the `setJobs` mapping to include `rawTenantId` for re-upload:

```javascript
      const mapped = (data?.content ?? []).map(j => ({
        ...j,
        rawStatus: j.status,
        rawTenantId: j.tenantId,
        tenantId: j.tenantId
          ? (tenants.find(t => t.id === j.tenantId)?.name ?? j.tenantId)
          : 'Global',
        createdAt: j.createdAt ? new Date(j.createdAt).toLocaleString() : '\u2014',
      }))
```

In the DataTable, replace `columns={JOB_COLUMNS}` with `columns={jobColumns}`.

Add the modals before the closing `</>` of the jobs tab section:

```jsx
          {detailJobId && (
            <JobDetailModal
              api={api}
              jobId={detailJobId}
              tenants={tenants}
              onClose={() => setDetailJobId(null)}
              onSearchEntity={handleSearchEntity}
            />
          )}

          {confirmDelete && (
            <ConfirmDialog
              title="Delete ingestion job"
              message={`Delete job and its ${confirmDelete.chunkCount ?? 0} chunks? Graph nodes will be preserved.`}
              onConfirm={() => handleDeleteJob(confirmDelete)}
              onClose={() => setConfirmDelete(null)}
            />
          )}

          {reingestJob && (
            <ConfirmDialog
              title="Re-ingest document"
              message={
                reingestJob.sourceType === 'URL'
                  ? `Re-ingest from ${reingestJob.sourceRef}? Old chunks will be replaced.`
                  : `File re-upload required for ${reingestJob.sourceRef}. Continue?`
              }
              onConfirm={() => handleReingest(reingestJob)}
              onClose={() => setReingestJob(null)}
            />
          )}
```

Update `setShowModal` to accept an object or boolean. Change the existing `setShowModal(true)` button and modal usage to handle both modes. Replace:

```javascript
  const [showModal, setShowModal] = useState(false)
```

with:

```javascript
  const [showModal, setShowModal] = useState(null) // null | true | { replaceJobId, sourceRef, tenantId }
```

Update the "Add Document" button: `onClick={() => setShowModal(true)}`.

Update the `IngestionModal` rendering:

```jsx
          {showModal && (
            <IngestionModal
              api={api}
              tenants={tenants}
              onClose={() => setShowModal(null)}
              onJobCreated={loadJobs}
              replaceJobId={showModal?.replaceJobId ?? null}
              initialSourceRef={showModal?.sourceRef ?? null}
              initialTenantId={showModal?.tenantId ?? null}
            />
          )}
```

- [ ] **Step 2: Add action button styles**

In `KnowledgePage.module.css`, add:

```css
.actionBtns {
  display: inline-flex;
  gap: var(--sp-1);
}

.actionBtn {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--fg-2);
  width: 28px;
  height: 28px;
  font-size: 14px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}

.actionBtn:hover {
  border-color: var(--accent);
  color: var(--accent);
}
```

- [ ] **Step 3: Update IngestionModal for re-upload mode**

In `IngestionModal.jsx`, update the component signature:

```javascript
export function IngestionModal({
  api,
  tenants,
  onClose,
  onJobCreated,
  replaceJobId = null,
  initialSourceRef = null,
  initialTenantId = null,
}) {
```

Update initial state:

```javascript
  const [url, setUrl] = useState(initialSourceRef ?? '')
  const [tenantId, setTenantId] = useState(initialTenantId ?? '')
```

Update the submit handler to bypass dedup when re-uploading. The `ingestUrl` and `ingestUpload` calls already go to the backend — we need to pass `replaceJobId` through the API. Update the `handleSubmit`:

```javascript
  const handleSubmit = useCallback(async () => {
    setSubmitting(true)
    try {
      const effectiveTenantId = tenantId || null
      if (replaceJobId) {
        // Re-upload: delete old data first, then ingest normally
        try {
          await api.deleteJob(replaceJobId)
        } catch {
          // Old job may already be deleted
        }
      }
      if (mode === 'url') {
        await api.ingestUrl(url, effectiveTenantId)
      } else {
        await api.ingestUpload(file, effectiveTenantId)
      }
      const sourceRef = mode === 'url' ? url : file.name
      addToast('info', replaceJobId ? `Re-ingestion started: ${sourceRef}` : `Document submitted: ${sourceRef}`)
      onJobCreated()
      onClose()
    } catch (err) {
      // Handle 409 dedup
      if (err.message?.includes('409') || err.status === 409) {
        addToast('info', `Already ingested: ${mode === 'url' ? url : file?.name}. Use re-ingest to update.`)
        onClose()
        return
      }
      addToast('error', `Submission failed: ${err.message || 'Unknown error'}`)
      setSubmitting(false)
    }
  }, [mode, url, file, tenantId, api, addToast, onJobCreated, onClose, replaceJobId])
```

Update the submit button label:

```jsx
        <Button
          variant="primary"
          onClick={handleSubmit}
          disabled={!canSubmit}
        >
          {replaceJobId ? 'Re-ingest' : 'Submit'}
        </Button>
```

- [ ] **Step 4: Run frontend tests**

```bash
cd emcip-admin-ui/src/main/frontend && npx vitest run --reporter=verbose 2>&1 | tail -20
```

- [ ] **Step 5: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.jsx \
        emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.module.css \
        emcip-admin-ui/src/main/frontend/src/pages/Knowledge/IngestionModal.jsx
git commit -m "feat(admin-ui): job actions — view detail, delete, re-ingest

Action column in DataTable with View/Delete/Re-ingest buttons.
Delete shows ConfirmDialog with chunk count.
Re-ingest: URLs re-fetch directly; files open IngestionModal in re-upload mode.
409 dedup handled with info toast. FLAGGED_INJECTION_RISK badge mapped to yellow."
```
