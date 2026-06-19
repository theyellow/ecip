# Document Ingestion (US-26.8) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow admins to submit URLs or upload documents (plain text, HTML, PDF, DOCX) into the knowledge base via async job, with full entity extraction and graph population.

**Architecture:** DocumentIngestionService creates a persisted `ke_ingestion_jobs` row (QUEUED), submits a VirtualThread task, and returns the job ID immediately. The task uses Apache Tika 3.3.1 to parse content, chunks it, then calls `KnowledgeExtractionService.processDocument()` per chunk for embedding + entity extraction + graph writes. AdminUI polls job status from a new Knowledge page via an Admin-API proxy controller.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate, Apache Tika 3.3.1, Liquibase, WebClient (WebFlux in admin-api), React + CSS Modules (admin-ui)

---

## File Map

| Action | Path |
|--------|------|
| MODIFY | `emcip-knowledge-engine/pom.xml` |
| CREATE | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/TikaConfig.java` |
| CREATE | `emcip-knowledge-engine/src/main/resources/db/changelog/changes/010-ingestion-jobs.xml` |
| MODIFY | `emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml` |
| CREATE | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/IngestionJob.java` |
| CREATE | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/IngestionJobRepository.java` |
| CREATE | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/IngestionJobDto.java` |
| MODIFY | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java` |
| REWRITE | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/DocumentIngestionService.java` |
| REWRITE | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/DocumentIngestionController.java` |
| CREATE | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java` |
| CREATE | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/DocumentIngestionControllerTest.java` |
| CREATE | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/DocumentIngestionProxyController.java` |
| CREATE | `emcip-admin-ui/src/main/frontend/src/api/knowledge.js` |
| MODIFY | `emcip-admin-ui/src/main/frontend/src/App.jsx` |
| MODIFY | `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx` |
| CREATE | `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.jsx` |
| CREATE | `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.module.css` |
| CREATE | `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/IngestionModal.jsx` |
| CREATE | `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/IngestionModal.module.css` |

---

## Task 1: Tika dependency + TikaConfig bean

**Files:**
- Modify: `emcip-knowledge-engine/pom.xml`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/TikaConfig.java`

- [ ] **Step 1: Add Tika to pom.xml**

In `emcip-knowledge-engine/pom.xml`, add inside `<dependencies>` after the Lombok block:

```xml
<!-- Apache Tika: HTML, plain text, PDF, DOCX parsing -->
<dependency>
  <groupId>org.apache.tika</groupId>
  <artifactId>tika-core</artifactId>
  <version>3.3.1</version>
</dependency>
<!-- type=pom required in Tika 3.x — artifact is a BOM, not a JAR -->
<dependency>
  <groupId>org.apache.tika</groupId>
  <artifactId>tika-parsers-standard-package</artifactId>
  <version>3.3.1</version>
  <type>pom</type>
</dependency>
```

- [ ] **Step 2: Create TikaConfig.java**

```java
package io.emcip.knowledge.engine.config;

import org.apache.tika.Tika;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TikaConfig {

    @Bean
    public Tika tika() {
        Tika tika = new Tika();
        tika.setMaxStringLength(-1); // disable 100k-char default truncation
        return tika;
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
mvn compile -pl emcip-knowledge-engine -q
```

Expected: `BUILD SUCCESS` with no errors.

- [ ] **Step 4: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/pom.xml \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/config/TikaConfig.java
git commit -m "feat(knowledge-engine): add Apache Tika 3.3.1 dependency and TikaConfig bean (#26.8)"
```

---

## Task 2: Liquibase migration + IngestionJob entity + repository + DTO

**Files:**
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/010-ingestion-jobs.xml`
- Modify: `emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/IngestionJob.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/IngestionJobRepository.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/IngestionJobDto.java`

- [ ] **Step 1: Create 010-ingestion-jobs.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-10" author="knowledge-engine">
        <createTable tableName="ke_ingestion_jobs"
                     remarks="Tracks async document ingestion jobs">
            <column name="id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="tenant_id" type="UUID">
                <constraints nullable="true"/>
            </column>
            <column name="source_type" type="VARCHAR(20)">
                <constraints nullable="false"/>
            </column>
            <column name="source_ref" type="TEXT">
                <constraints nullable="false"/>
            </column>
            <column name="status" type="VARCHAR(20)" defaultValue="QUEUED">
                <constraints nullable="false"/>
            </column>
            <column name="chunk_count" type="INT">
                <constraints nullable="true"/>
            </column>
            <column name="error_message" type="TEXT">
                <constraints nullable="true"/>
            </column>
            <column name="created_at" type="TIMESTAMP WITH TIME ZONE">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex indexName="idx_ke_ingestion_jobs_tenant_created"
                     tableName="ke_ingestion_jobs">
            <column name="tenant_id"/>
            <column name="created_at" descending="true"/>
        </createIndex>

        <createIndex indexName="idx_ke_ingestion_jobs_status"
                     tableName="ke_ingestion_jobs">
            <column name="status"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Register in db.changelog-master.xml**

Add the following line inside `<databaseChangeLog>` after the 009 include:

```xml
<include file="changes/010-ingestion-jobs.xml" relativeToChangelogFile="true"/>
```

- [ ] **Step 3: Create IngestionJob.java**

```java
package io.emcip.knowledge.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "ke_ingestion_jobs")
@Getter
@Setter
@NoArgsConstructor
public class IngestionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = true)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SourceType sourceType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String sourceRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IngestionStatus status;

    @Column(nullable = true)
    private Integer chunkCount;

    @Column(nullable = true, columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(nullable = false)
    private OffsetDateTime createdAt;

    public enum IngestionStatus {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED
    }

    public enum SourceType {
        URL,
        FILE_UPLOAD
    }
}
```

- [ ] **Step 4: Create IngestionJobRepository.java**

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.IngestionJob;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {

    Page<IngestionJob> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Page<IngestionJob> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
```

- [ ] **Step 5: Create IngestionJobDto.java**

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
        String createdAt) {

    public static IngestionJobDto from(IngestionJob job) {
        return new IngestionJobDto(
                job.getId().toString(),
                job.getSourceType().name(),
                job.getSourceRef(),
                job.getTenantId() != null ? job.getTenantId().toString() : null,
                job.getStatus().name(),
                job.getChunkCount(),
                job.getErrorMessage(),
                job.getCreatedAt() != null ? job.getCreatedAt().toString() : null);
    }
}
```

- [ ] **Step 6: Verify compilation**

```bash
mvn compile -pl emcip-knowledge-engine -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/main/resources/db/changelog/ \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/IngestionJob.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/IngestionJobRepository.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/IngestionJobDto.java
git commit -m "feat(knowledge-engine): add IngestionJob entity, repository, DTO + Liquibase migration 010 (#26.8)"
```

---

## Task 3: KnowledgeExtractionService — add processDocument() overload

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java` (bootstrap — full test in Task 4)

- [ ] **Step 1: Write the failing test (just the processDocument assertion)**

Create `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java`:

```java
package io.emcip.knowledge.engine.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.RelationshipType;
import io.emcip.knowledge.engine.model.ExtractionResult;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceTest {

    @Mock KnowledgeDocumentRepository documentRepository;
    @Mock VectorSearchRepository vectorSearchRepository;
    @Mock GraphRepository graphRepository;
    @Mock EntityResolutionService entityResolutionService;
    @Mock LlmOrchestratorClient llmClient;
    @Mock OntologyService ontologyService;

    KnowledgeExtractionService extractionService;

    @BeforeEach
    void setUp() {
        extractionService =
                new KnowledgeExtractionService(
                        documentRepository,
                        vectorSearchRepository,
                        graphRepository,
                        entityResolutionService,
                        llmClient,
                        ontologyService);
    }

    @Test
    void processDocument_callsLlmExtractForChunk() {
        String chunk = "Alice met Bob in Berlin to discuss the treaty.";
        UUID tenantId = UUID.randomUUID();

        when(ontologyService.getAllConceptTypes()).thenReturn(List.of());
        when(ontologyService.getAllRelationshipTypes()).thenReturn(List.of());
        when(llmClient.embed(any())).thenReturn(new float[] {0.1f, 0.2f});
        when(documentRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            var doc = inv.getArgument(0,
                                    io.emcip.knowledge.engine.entity.KnowledgeDocument.class);
                            doc.setId(UUID.randomUUID());
                            return doc;
                        });
        when(llmClient.extract(eq(chunk), anyList(), anyList()))
                .thenReturn(new ExtractionResult(List.of(), List.of()));

        extractionService.processDocument(chunk, "https://example.com/doc", tenantId);

        verify(llmClient).extract(eq(chunk), anyList(), anyList());
    }

    @Test
    void processDocument_skipsBlankChunk() {
        extractionService.processDocument("   ", "https://example.com/doc", null);
        // No interactions expected
        org.mockito.Mockito.verifyNoInteractions(llmClient, documentRepository);
    }
}
```

- [ ] **Step 2: Run the test — expect compilation failure (processDocument doesn't exist yet)**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=DocumentIngestionServiceTest -q 2>&1 | tail -20
```

Expected: compilation error `cannot find symbol: method processDocument`.

- [ ] **Step 3: Add processDocument() to KnowledgeExtractionService**

Open `KnowledgeExtractionService.java`. Add the following method after the existing `processMessage()` method. The logic mirrors `processMessage()` but uses `sourceType = "DOCUMENT"` and omits all chat-specific metadata:

```java
@Transactional
public void processDocument(String chunk, String sourceRef, UUID tenantId) {
    if (chunk == null || chunk.isBlank()) {
        log.debug("Skipping empty chunk for: {}", sourceRef);
        return;
    }

    // Step 1: Store chunk as KnowledgeDocument (no chat metadata)
    KnowledgeDocument doc = new KnowledgeDocument();
    doc.setTenantId(tenantId);
    doc.setSourceType("DOCUMENT");
    doc.setSourceRef(sourceRef);
    doc.setContent(chunk);
    doc.setChunkIndex(0);
    doc.setMetadata(Map.of("sourceRef", sourceRef != null ? sourceRef : ""));
    KnowledgeDocument saved = documentRepository.save(doc);

    // Step 2: Generate and store embedding
    float[] embedding = llmClient.embed(chunk);
    if (embedding.length > 0) {
        vectorSearchRepository.storeEmbedding(saved.getId(), embedding);
    }

    // Step 3: LLM entity/relationship extraction
    List<ConceptType> conceptTypes = ontologyService.getAllConceptTypes();
    List<RelationshipType> relTypes = ontologyService.getAllRelationshipTypes();
    ExtractionResult result = llmClient.extract(chunk, conceptTypes, relTypes);

    // Step 4: Filter invalid entries (same logic as processMessage)
    Set<String> knownConceptNames =
            conceptTypes.stream().map(ConceptType::getName).collect(Collectors.toSet());
    Set<String> knownRelNames =
            relTypes.stream().map(RelationshipType::getName).collect(Collectors.toSet());

    List<ExtractedEntity> validEntities =
            result.entities().stream()
                    .filter(
                            e ->
                                    e.type() != null
                                            && !e.type().isBlank()
                                            && e.label() != null
                                            && !e.label().isBlank()
                                            && knownConceptNames.contains(e.type()))
                    .toList();

    List<ExtractedRelationship> validRelationships =
            result.relationships().stream()
                    .filter(
                            r ->
                                    r.type() != null
                                            && !r.type().isBlank()
                                            && r.source() != null
                                            && !r.source().isBlank()
                                            && r.target() != null
                                            && !r.target().isBlank()
                                            && knownRelNames.contains(r.type()))
                    .toList();

    // Step 5: Entity resolution + graph storage
    for (ExtractedEntity entity : validEntities) {
        entityResolutionService.resolve(entity.label(), entity.type(), tenantId);
    }

    for (ExtractedRelationship rel : validRelationships) {
        UUID sourceId =
                entityResolutionService.resolve(rel.source(), inferType(rel, true), tenantId);
        UUID targetId =
                entityResolutionService.resolve(rel.target(), inferType(rel, false), tenantId);
        graphRepository.createRelationship(
                rel.type(), sourceId, targetId, rel.properties(), saved.getId());
    }

    log.info(
            "processDocument complete: sourceRef={}, entities={}, relationships={}",
            sourceRef,
            validEntities.size(),
            validRelationships.size());
}
```

- [ ] **Step 4: Run the test — expect PASS**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=DocumentIngestionServiceTest -q
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeExtractionService.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java
git commit -m "feat(knowledge-engine): add KnowledgeExtractionService.processDocument() for document chunks (#26.8)"
```

---

## Task 4: Rewrite DocumentIngestionService (async, Tika, job tracking)

**Files:**
- Rewrite: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/DocumentIngestionService.java`
- Modify: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java`

- [ ] **Step 1: Add async tests to DocumentIngestionServiceTest**

Replace the contents of `DocumentIngestionServiceTest.java` with the full test class (the two tests from Task 3 plus the new service tests):

```java
package io.emcip.knowledge.engine.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.IngestionJob;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.ExtractionResult;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.IngestionJobRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.tika.Tika;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceTest {

    @Mock KnowledgeDocumentRepository documentRepository;
    @Mock VectorSearchRepository vectorSearchRepository;
    @Mock GraphRepository graphRepository;
    @Mock EntityResolutionService entityResolutionService;
    @Mock LlmOrchestratorClient llmClient;
    @Mock OntologyService ontologyService;
    @Mock IngestionJobRepository jobRepository;
    @Mock KnowledgeExtractionService extractionService;
    @Mock Tika tika;

    DocumentIngestionService service;
    KnowledgeExtractionService realExtractionService;

    @BeforeEach
    void setUp() {
        realExtractionService =
                new KnowledgeExtractionService(
                        documentRepository,
                        vectorSearchRepository,
                        graphRepository,
                        entityResolutionService,
                        llmClient,
                        ontologyService);

        service = new DocumentIngestionService(jobRepository, extractionService, tika);
    }

    // ── KnowledgeExtractionService.processDocument tests (from Task 3) ──────

    @Test
    void processDocument_callsLlmExtractForChunk() {
        String chunk = "Alice met Bob in Berlin to discuss the treaty.";
        UUID tenantId = UUID.randomUUID();

        when(ontologyService.getAllConceptTypes()).thenReturn(List.of());
        when(ontologyService.getAllRelationshipTypes()).thenReturn(List.of());
        when(llmClient.embed(any())).thenReturn(new float[] {0.1f, 0.2f});
        when(documentRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            KnowledgeDocument doc = inv.getArgument(0);
                            doc.setId(UUID.randomUUID());
                            return doc;
                        });
        when(llmClient.extract(eq(chunk), anyList(), anyList()))
                .thenReturn(new ExtractionResult(List.of(), List.of()));

        realExtractionService.processDocument(chunk, "https://example.com/doc", tenantId);

        verify(llmClient).extract(eq(chunk), anyList(), anyList());
    }

    @Test
    void processDocument_skipsBlankChunk() {
        realExtractionService.processDocument("   ", "https://example.com/doc", null);
        verifyNoInteractions(llmClient, documentRepository);
    }

    // ── DocumentIngestionService tests ───────────────────────────────────────

    @Test
    void submitUrlIngestion_createsQueuedJobAndReturnsId() {
        when(jobRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            IngestionJob j = inv.getArgument(0);
                            j.setId(UUID.randomUUID());
                            return j;
                        });

        String jobId = service.submitUrlIngestion("https://example.com", null);

        assertThat(jobId).isNotNull();
        ArgumentCaptor<IngestionJob> captor = ArgumentCaptor.forClass(IngestionJob.class);
        verify(jobRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus())
                .isEqualTo(IngestionJob.IngestionStatus.QUEUED);
        assertThat(captor.getValue().getSourceType())
                .isEqualTo(IngestionJob.SourceType.URL);
        assertThat(captor.getValue().getSourceRef()).isEqualTo("https://example.com");
    }

    @Test
    void submitUrlIngestion_asyncProcessingCallsProcessDocumentPerChunk() throws Exception {
        UUID jobId = UUID.randomUUID();
        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setStatus(IngestionJob.IngestionStatus.QUEUED);

        when(jobRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            IngestionJob j = inv.getArgument(0);
                            if (j.getId() == null) j.setId(jobId);
                            return j;
                        });
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        // Tika returns 3 words → 1 chunk at default 500-word size
        when(tika.parseToString(any(java.net.URL.class))).thenReturn("word1 word2 word3");

        service.submitUrlIngestion("https://example.com", null);

        await().atMost(5, SECONDS)
                .untilAsserted(
                        () ->
                                verify(extractionService, atLeastOnce())
                                        .processDocument(
                                                eq("word1 word2 word3"),
                                                eq("https://example.com"),
                                                isNull()));
    }

    @Test
    void submitUrlIngestion_setsJobToFailedOnParseError() throws Exception {
        UUID jobId = UUID.randomUUID();
        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setStatus(IngestionJob.IngestionStatus.QUEUED);

        when(jobRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            IngestionJob j = inv.getArgument(0);
                            if (j.getId() == null) j.setId(jobId);
                            return j;
                        });
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(tika.parseToString(any(java.net.URL.class)))
                .thenThrow(new java.io.IOException("connection refused"));

        service.submitUrlIngestion("https://unreachable.invalid", null);

        await().atMost(5, SECONDS)
                .untilAsserted(
                        () -> {
                            ArgumentCaptor<IngestionJob> captor =
                                    ArgumentCaptor.forClass(IngestionJob.class);
                            verify(jobRepository, atLeastOnce()).save(captor.capture());
                            assertThat(captor.getAllValues())
                                    .anyMatch(
                                            j ->
                                                    j.getStatus()
                                                            == IngestionJob.IngestionStatus.FAILED
                                                            && j.getErrorMessage() != null);
                        });
    }
}
```

- [ ] **Step 2: Run the test — expect compilation failure (DocumentIngestionService doesn't have right signature)**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=DocumentIngestionServiceTest -q 2>&1 | tail -20
```

Expected: compilation errors about missing constructor / method.

- [ ] **Step 3: Rewrite DocumentIngestionService.java**

Replace the entire file:

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.entity.IngestionJob;
import io.emcip.knowledge.engine.entity.IngestionJob.IngestionStatus;
import io.emcip.knowledge.engine.entity.IngestionJob.SourceType;
import io.emcip.knowledge.engine.repository.IngestionJobRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentIngestionService {

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;
    private static final ExecutorService INGESTION_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final IngestionJobRepository jobRepository;
    private final KnowledgeExtractionService extractionService;
    private final Tika tika;

    /** Submit a URL for async ingestion. Returns the job ID immediately. */
    public String submitUrlIngestion(String url, UUID tenantId) {
        IngestionJob job = createAndSaveJob(SourceType.URL, url, tenantId);
        UUID jobId = job.getId();
        INGESTION_EXECUTOR.submit(() -> processUrlAsync(jobId, url, tenantId));
        return jobId.toString();
    }

    /**
     * Submit a file for async ingestion. Reads all bytes immediately (before HTTP request ends),
     * then processes asynchronously. Returns the job ID immediately.
     */
    public String submitFileIngestion(InputStream inputStream, String filename, UUID tenantId)
            throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        IngestionJob job = createAndSaveJob(SourceType.FILE_UPLOAD, filename, tenantId);
        UUID jobId = job.getId();
        INGESTION_EXECUTOR.submit(() -> processFileAsync(jobId, bytes, filename, tenantId));
        return jobId.toString();
    }

    public IngestionJob getJob(UUID jobId) {
        return jobRepository
                .findById(jobId)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Ingestion job not found: " + jobId));
    }

    public Page<IngestionJob> listJobs(UUID tenantId, Pageable pageable) {
        if (tenantId != null) {
            return jobRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
        }
        return jobRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    // ── private async workers ────────────────────────────────────────────────

    private void processUrlAsync(UUID jobId, String url, UUID tenantId) {
        updateJobStatus(jobId, IngestionStatus.RUNNING, null, null);
        try {
            String text = tika.parseToString(new URL(url));
            int chunkCount = processChunks(text, url, tenantId);
            updateJobStatus(jobId, IngestionStatus.COMPLETED, chunkCount, null);
            log.info("URL ingestion COMPLETED: jobId={}, url={}, chunks={}", jobId, url, chunkCount);
        } catch (Exception e) {
            log.error("URL ingestion FAILED: jobId={}, url={}: {}", jobId, url, e.getMessage(), e);
            updateJobStatus(jobId, IngestionStatus.FAILED, null, e.getMessage());
        }
    }

    private void processFileAsync(
            UUID jobId, byte[] fileBytes, String filename, UUID tenantId) {
        updateJobStatus(jobId, IngestionStatus.RUNNING, null, null);
        try {
            String text = tika.parseToString(new ByteArrayInputStream(fileBytes));
            int chunkCount = processChunks(text, filename, tenantId);
            updateJobStatus(jobId, IngestionStatus.COMPLETED, chunkCount, null);
            log.info(
                    "File ingestion COMPLETED: jobId={}, file={}, chunks={}",
                    jobId,
                    filename,
                    chunkCount);
        } catch (Exception e) {
            log.error(
                    "File ingestion FAILED: jobId={}, file={}: {}",
                    jobId,
                    filename,
                    e.getMessage(),
                    e);
            updateJobStatus(jobId, IngestionStatus.FAILED, null, e.getMessage());
        }
    }

    private int processChunks(String text, String sourceRef, UUID tenantId) {
        List<String> chunks = chunkText(text, CHUNK_SIZE, CHUNK_OVERLAP);
        for (String chunk : chunks) {
            extractionService.processDocument(chunk, sourceRef, tenantId);
        }
        return chunks.size();
    }

    @Transactional
    IngestionJob createAndSaveJob(SourceType sourceType, String sourceRef, UUID tenantId) {
        IngestionJob job = new IngestionJob();
        job.setSourceType(sourceType);
        job.setSourceRef(sourceRef);
        job.setTenantId(tenantId);
        job.setStatus(IngestionStatus.QUEUED);
        job.setCreatedAt(OffsetDateTime.now());
        return jobRepository.save(job);
    }

    private void updateJobStatus(
            UUID jobId, IngestionStatus status, Integer chunkCount, String errorMessage) {
        Optional<IngestionJob> opt = jobRepository.findById(jobId);
        if (opt.isEmpty()) {
            log.warn("updateJobStatus: job not found: {}", jobId);
            return;
        }
        IngestionJob job = opt.get();
        job.setStatus(status);
        if (chunkCount != null) job.setChunkCount(chunkCount);
        if (errorMessage != null) job.setErrorMessage(errorMessage);
        jobRepository.save(job);
    }

    List<String> chunkText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        String[] words = text.split("\\s+");
        if (words.length == 0 || (words.length == 1 && words[0].isBlank())) return chunks;
        int start = 0;
        while (start < words.length) {
            int end = Math.min(start + chunkSize, words.length);
            chunks.add(String.join(" ", Arrays.copyOfRange(words, start, end)));
            start += chunkSize - overlap;
        }
        return chunks;
    }
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=DocumentIngestionServiceTest -q
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/DocumentIngestionService.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/DocumentIngestionServiceTest.java
git commit -m "feat(knowledge-engine): rewrite DocumentIngestionService — async jobs, Tika parsing, extraction pipeline (#26.8)"
```

---

## Task 5: Rewrite DocumentIngestionController (async job endpoints)

**Files:**
- Rewrite: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/DocumentIngestionController.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/DocumentIngestionControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.knowledge.engine.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.emcip.knowledge.engine.entity.IngestionJob;
import io.emcip.knowledge.engine.entity.IngestionJob.IngestionStatus;
import io.emcip.knowledge.engine.entity.IngestionJob.SourceType;
import io.emcip.knowledge.engine.service.DocumentIngestionService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionControllerTest {

    @Mock DocumentIngestionService ingestionService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DocumentIngestionController(ingestionService))
                .build();
    }

    @Test
    void ingestUrl_returns202WithJobId() throws Exception {
        when(ingestionService.submitUrlIngestion(eq("https://example.com/article"), isNull()))
                .thenReturn("job-abc-123");

        mvc.perform(
                        post("/api/knowledge/ingest/url")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"url": "https://example.com/article"}
                                        """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-abc-123"));
    }

    @Test
    void ingestUpload_returns202WithJobId() throws Exception {
        when(ingestionService.submitFileIngestion(any(), eq("report.pdf"), isNull()))
                .thenReturn("job-def-456");

        MockMultipartFile file =
                new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/knowledge/ingest/upload").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-def-456"));
    }

    @Test
    void getJob_returnsJobDto() throws Exception {
        UUID jobId = UUID.randomUUID();
        IngestionJob job = new IngestionJob();
        job.setId(jobId);
        job.setSourceType(SourceType.URL);
        job.setSourceRef("https://example.com/article");
        job.setStatus(IngestionStatus.COMPLETED);
        job.setChunkCount(7);
        job.setCreatedAt(OffsetDateTime.now());

        when(ingestionService.getJob(jobId)).thenReturn(job);

        mvc.perform(get("/api/knowledge/ingest/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.chunkCount").value(7))
                .andExpect(jsonPath("$.sourceRef").value("https://example.com/article"));
    }

    @Test
    void listJobs_returnsPage() throws Exception {
        IngestionJob job = new IngestionJob();
        job.setId(UUID.randomUUID());
        job.setSourceType(SourceType.FILE_UPLOAD);
        job.setSourceRef("report.pdf");
        job.setStatus(IngestionStatus.RUNNING);
        job.setCreatedAt(OffsetDateTime.now());

        when(ingestionService.listJobs(isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(job)));

        mvc.perform(get("/api/knowledge/ingest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.content[0].sourceRef").value("report.pdf"));
    }
}
```

- [ ] **Step 2: Run the test — expect compilation failure**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=DocumentIngestionControllerTest -q 2>&1 | tail -20
```

Expected: compilation errors — existing controller has the wrong constructor/methods.

- [ ] **Step 3: Rewrite DocumentIngestionController.java**

Replace the entire file:

```java
package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.model.IngestionJobDto;
import io.emcip.knowledge.engine.service.DocumentIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Document Ingestion", description = "Ingest documents into the knowledge base")
@RestController
@RequestMapping("/api/knowledge/ingest")
@RequiredArgsConstructor
public class DocumentIngestionController {

    private final DocumentIngestionService ingestionService;

    @Operation(summary = "Submit a URL for async ingestion")
    @PostMapping("/url")
    public ResponseEntity<Map<String, Object>> ingestUrl(@RequestBody UrlRequest request) {
        String jobId = ingestionService.submitUrlIngestion(request.url(), request.tenantId());
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    @Operation(summary = "Upload a document for async ingestion")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> ingestUpload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) UUID tenantId)
            throws IOException {
        String jobId =
                ingestionService.submitFileIngestion(
                        file.getInputStream(), file.getOriginalFilename(), tenantId);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    @Operation(summary = "Get ingestion job status")
    @GetMapping("/{jobId}")
    public IngestionJobDto getJob(@PathVariable UUID jobId) {
        return IngestionJobDto.from(ingestionService.getJob(jobId));
    }

    @Operation(summary = "List ingestion jobs")
    @GetMapping
    public Page<IngestionJobDto> listJobs(
            @RequestParam(required = false) UUID tenantId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return ingestionService.listJobs(tenantId, pageable).map(IngestionJobDto::from);
    }

    public record UrlRequest(String url, UUID tenantId) {}
}
```

- [ ] **Step 4: Run both controller and service tests**

```bash
mvn test -pl emcip-knowledge-engine \
    -Dtest="DocumentIngestionControllerTest,DocumentIngestionServiceTest" -q
```

Expected: `Tests run: 9, Failures: 0, Errors: 0` (4 controller + 5 service).

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/DocumentIngestionController.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/DocumentIngestionControllerTest.java
git commit -m "feat(knowledge-engine): rewrite DocumentIngestionController — async job endpoints (#26.8)"
```

---

## Task 6: Admin-API — DocumentIngestionProxyController

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/DocumentIngestionProxyController.java`

- [ ] **Step 1: Create DocumentIngestionProxyController.java**

This mirrors `BackfillProxyController` exactly. The admin-api module uses Spring WebFlux (reactive); multipart is received as `FilePart` and forwarded to knowledge-engine.

```java
package io.emcip.admin.api.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.domain.Pageable;

/**
 * Proxies document ingestion requests to the knowledge-engine service. Admin-UI → admin-api →
 * knowledge-engine (API Gateway pattern). Mirrors BackfillProxyController.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/knowledge/ingest")
@Tag(name = "Document Ingestion", description = "Submit and monitor document ingestion jobs")
public class DocumentIngestionProxyController {

    private final WebClient knowledgeWebClient;
    private final CircuitBreaker circuitBreaker;

    public DocumentIngestionProxyController(
            @Qualifier("knowledgeWebClient") WebClient knowledgeWebClient,
            CircuitBreakerRegistry registry) {
        this.knowledgeWebClient = knowledgeWebClient;
        this.circuitBreaker = registry.circuitBreaker("knowledge");
    }

    @Operation(summary = "Submit a URL for ingestion")
    @PostMapping("/url")
    public Mono<ResponseEntity<String>> ingestUrl(@RequestBody String body) {
        return knowledgeWebClient
                .post()
                .uri("/api/knowledge/ingest/url")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error("Ingest URL proxy error: {}", e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Upload a document for ingestion")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<String>> ingestUpload(
            @RequestPart("file") FilePart file,
            @RequestParam(required = false) UUID tenantId) {
        return DataBufferUtils.join(file.content())
                .flatMap(
                        dataBuffer -> {
                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);
                            DataBufferUtils.release(dataBuffer);

                            String filename = file.filename();
                            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
                            parts.add(
                                    "file",
                                    new ByteArrayResource(bytes) {
                                        @Override
                                        public String getFilename() {
                                            return filename;
                                        }
                                    });
                            if (tenantId != null) {
                                parts.add("tenantId", tenantId.toString());
                            }

                            return knowledgeWebClient
                                    .post()
                                    .uri("/api/knowledge/ingest/upload")
                                    .contentType(MediaType.MULTIPART_FORM_DATA)
                                    .body(BodyInserters.fromMultipartData(parts))
                                    .retrieve()
                                    .bodyToMono(String.class)
                                    .map(ResponseEntity::ok)
                                    .onErrorResume(
                                            e -> {
                                                log.error(
                                                        "Ingest upload proxy error: {}",
                                                        e.getMessage());
                                                return Mono.just(
                                                        ResponseEntity.status(
                                                                        HttpStatus.SERVICE_UNAVAILABLE)
                                                                .<String>build());
                                            });
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Get ingestion job status")
    @GetMapping("/{jobId}")
    public Mono<ResponseEntity<String>> getJobStatus(@PathVariable String jobId) {
        return knowledgeWebClient
                .get()
                .uri("/api/knowledge/ingest/{jobId}", jobId)
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error("Ingest status proxy error jobId={}: {}", jobId, e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "List ingestion jobs")
    @GetMapping
    public Mono<ResponseEntity<String>> listJobs(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String uri =
                tenantId != null
                        ? String.format(
                                "/api/knowledge/ingest?tenantId=%s&page=%d&size=%d",
                                tenantId, page, size)
                        : String.format("/api/knowledge/ingest?page=%d&size=%d", page, size);

        return knowledgeWebClient
                .get()
                .uri(uri)
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error("Ingest list proxy error: {}", e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
```

- [ ] **Step 2: Verify admin-api compiles**

```bash
mvn compile -pl emcip-admin-api -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/src/main/java/io/emcip/admin/api/controller/DocumentIngestionProxyController.java
git commit -m "feat(admin-api): add DocumentIngestionProxyController proxying to knowledge-engine (#26.8)"
```

---

## Task 7: Admin-UI — knowledge.js API module + App.jsx route + Sidebar entry

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/api/knowledge.js`
- Modify: `emcip-admin-ui/src/main/frontend/src/App.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx`

- [ ] **Step 1: Create knowledge.js**

```javascript
export function knowledgeApi(request, rawFetch) {
  return {
    /** POST /api/admin/knowledge/ingest/url — returns { jobId } */
    ingestUrl: (url, tenantId) =>
      request('/api/admin/knowledge/ingest/url', {
        method: 'POST',
        body: JSON.stringify({ url, tenantId: tenantId ?? null }),
      }),

    /**
     * POST /api/admin/knowledge/ingest/upload — multipart form data.
     * Uses rawFetch to avoid the JSON Content-Type header collision.
     * rawFetch signature: (path, options) => Promise<any>
     */
    ingestUpload: (file, tenantId) => {
      const form = new FormData()
      form.append('file', file)
      if (tenantId) form.append('tenantId', tenantId)
      return rawFetch('/api/admin/knowledge/ingest/upload', {
        method: 'POST',
        body: form,
      })
    },

    /** GET /api/admin/knowledge/ingest/{jobId} — returns IngestionJobDto */
    status: jobId =>
      request(`/api/admin/knowledge/ingest/${encodeURIComponent(jobId)}`),

    /** GET /api/admin/knowledge/ingest — returns Spring Page<IngestionJobDto> */
    jobs: (page = 0, size = 20, tenantId) => {
      const params = new URLSearchParams({ page, size })
      if (tenantId) params.append('tenantId', tenantId)
      return request(`/api/admin/knowledge/ingest?${params}`)
    },
  }
}
```

> **Note on `rawFetch`:** The multipart upload cannot go through the standard `request()` helper because `client.js` always sets `Content-Type: application/json`, which breaks multipart boundaries. `rawFetch` is a bare `fetch` call that only adds the `Authorization` header. When wiring `knowledgeApi` into the API context, pass a `rawFetch` function that accepts `(path, options)` and adds the token header but not `Content-Type`. Example:
> ```javascript
> const rawFetch = (path, options = {}) =>
>   fetch(`${API_BASE}${path}`, {
>     ...options,
>     headers: { Authorization: `Bearer ${token}`, ...options.headers },
>   }).then(res => res.ok ? res.json() : Promise.reject(res))
> ```

- [ ] **Step 2: Add the Knowledge import and route to App.jsx**

Add the import at the top (with other page imports):
```javascript
import { Knowledge } from './pages/Knowledge/KnowledgePage'
```

Add the route inside `<Route element={<AppShell />}>` after the `costs` route:
```jsx
<Route path="knowledge" element={<Knowledge />} />
```

- [ ] **Step 3: Add Knowledge to the Sidebar NAV array**

In `Sidebar.jsx`, add after the `groups` entry:
```javascript
{ to: '/knowledge', label: 'Knowledge', icon: '◆', permission: 'KNOWLEDGE_READ' },
```

> **Permission note:** `KNOWLEDGE_READ` must exist in `src/auth/permissions.js`. Add it following the same pattern as the other `*_READ` constants there. The sidebar will hide the entry for users without this permission.

- [ ] **Step 4: Verify the frontend builds**

```bash
cd emcip-admin-ui/src/main/frontend && npm run build 2>&1 | tail -20
```

Expected: build completes without errors (the page component does not exist yet, but the import will fail — create a stub if needed):

If the build fails because `KnowledgePage.jsx` doesn't exist yet, create a minimal stub:

```javascript
// emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.jsx
export function Knowledge() {
  return <div>Knowledge — coming in next task</div>
}
```

- [ ] **Step 5: Commit**

```bash
cd emcip-admin-ui/src/main/frontend
git add src/api/knowledge.js src/App.jsx src/layout/Sidebar/Sidebar.jsx \
        src/pages/Knowledge/KnowledgePage.jsx   # stub only if created
```

```bash
git commit -m "feat(admin-ui): add knowledge.js API, /knowledge route, and Sidebar entry (#26.8)"
```

---

## Task 8: Admin-UI — KnowledgePage + IngestionModal

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.module.css`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/IngestionModal.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/IngestionModal.module.css`

**Design rules** (from `emcip-admin-ui/CLAUDE.md`):
- Page title: ALL CAPS Cinzel, `var(--font-display)`, `letter-spacing: 0.18em`
- Buttons: Title Case, `var(--font-display)`, no border-radius
- Badges: ALL CAPS mono, `border-radius: var(--r-pill)` (badges are the exception)
- No emoji. No icon libraries. Unicode glyphs only.
- Status badge colors: COMPLETED → `--signal-ok-*`, RUNNING → `--signal-info-*`, FAILED → `--signal-stop-*`, QUEUED → `--signal-mute-*`

- [ ] **Step 1: Create IngestionModal.jsx**

```jsx
import { useEffect, useRef, useState } from 'react'
import { Modal } from '../../components/Modal/Modal'
import { Button } from '../../components/Button/Button'
import styles from './IngestionModal.module.css'

export function IngestionModal({ api, tenants, onClose, onJobCreated }) {
  const [mode, setMode] = useState('url') // 'url' | 'file'
  const [url, setUrl] = useState('')
  const [file, setFile] = useState(null)
  const [tenantId, setTenantId] = useState('')
  const [phase, setPhase] = useState('config') // 'config' | 'polling' | 'done' | 'error'
  const [jobId, setJobId] = useState(null)
  const [jobStatus, setJobStatus] = useState(null)
  const [errorMsg, setErrorMsg] = useState('')
  const pollRef = useRef(null)

  useEffect(() => {
    if (phase !== 'polling' || !jobId) return

    pollRef.current = setInterval(async () => {
      try {
        const s = await api.status(jobId)
        setJobStatus(s)
        if (s.status === 'COMPLETED') {
          setPhase('done')
        } else if (s.status === 'FAILED') {
          setErrorMsg(s.errorMessage || 'Ingestion failed.')
          setPhase('error')
        }
      } catch (e) {
        setErrorMsg(e.message || 'Failed to fetch status.')
        setPhase('error')
      }
    }, 2000)

    return () => clearInterval(pollRef.current)
  }, [phase, jobId])

  async function handleSubmit() {
    try {
      const tid = tenantId || null
      let result
      if (mode === 'url') {
        result = await api.ingestUrl(url, tid)
      } else {
        result = await api.ingestUpload(file, tid)
      }
      setJobId(result.jobId)
      if (onJobCreated) onJobCreated()
      setPhase('polling')
    } catch (e) {
      setErrorMsg(e.message || 'Failed to submit.')
      setPhase('error')
    }
  }

  const canSubmit =
    phase === 'config' &&
    (mode === 'url' ? url.trim().startsWith('http') : file != null)

  const handleClose = phase === 'polling' ? () => {} : onClose

  return (
    <Modal title="ADD DOCUMENT" onClose={handleClose}>
      {phase === 'config' && (
        <>
          <div className={styles.segRow}>
            <button
              type="button"
              className={`${styles.seg}${mode === 'url' ? ` ${styles.segActive}` : ''}`}
              onClick={() => setMode('url')}
            >
              URL
            </button>
            <button
              type="button"
              className={`${styles.seg}${mode === 'file' ? ` ${styles.segActive}` : ''}`}
              onClick={() => setMode('file')}
            >
              File Upload
            </button>
          </div>

          {mode === 'url' ? (
            <div className={styles.field}>
              <label className={styles.label}>URL</label>
              <input
                className={styles.input}
                type="url"
                placeholder="https://example.com/article"
                value={url}
                onChange={e => setUrl(e.target.value)}
              />
            </div>
          ) : (
            <div className={styles.field}>
              <label className={styles.label}>File</label>
              <input
                className={styles.input}
                type="file"
                accept=".txt,.html,.pdf,.docx"
                onChange={e => setFile(e.target.files[0] ?? null)}
              />
              {file && (
                <span className={styles.filename}>{file.name}</span>
              )}
            </div>
          )}

          <div className={styles.field}>
            <label className={styles.label}>Tenant</label>
            <select
              className={styles.select}
              value={tenantId}
              onChange={e => setTenantId(e.target.value)}
            >
              <option value="">Global (shared)</option>
              {(tenants ?? []).map(t => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
          </div>

          <div className={styles.footer}>
            <Button variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button variant="primary" disabled={!canSubmit} onClick={handleSubmit}>
              Submit
            </Button>
          </div>
        </>
      )}

      {phase === 'polling' && (
        <div className={styles.status}>
          <div className={styles.spinner} aria-hidden="true" />
          <span>Processing\u2026</span>
        </div>
      )}

      {phase === 'done' && (
        <>
          <p className={styles.done}>
            Done \u2014 {jobStatus?.chunkCount ?? 0} chunks extracted.
          </p>
          <div className={styles.footer}>
            <Button variant="secondary" onClick={onClose}>
              Close
            </Button>
          </div>
        </>
      )}

      {phase === 'error' && (
        <>
          <p className={styles.error}>{errorMsg}</p>
          <div className={styles.footer}>
            <Button
              variant="secondary"
              onClick={() => {
                setPhase('config')
                setErrorMsg('')
              }}
            >
              Retry
            </Button>
            <Button variant="secondary" onClick={onClose}>
              Close
            </Button>
          </div>
        </>
      )}
    </Modal>
  )
}
```

- [ ] **Step 2: Create IngestionModal.module.css**

```css
.segRow {
  display: flex;
  gap: 0;
  margin-bottom: var(--sp-4);
  border: 1px solid var(--border);
}

.seg {
  flex: 1;
  padding: 7px 0;
  background: transparent;
  border: none;
  color: var(--fg-2);
  font-family: var(--font-display);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  cursor: pointer;
  transition: background 150ms;
}

.seg:not(:last-child) {
  border-right: 1px solid var(--border);
}

.seg:hover {
  background: var(--accent-soft);
  color: var(--accent);
}

.segActive {
  background: var(--accent-soft);
  color: var(--accent);
  box-shadow: inset 0 -2px 0 var(--accent);
}

.field {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  margin-bottom: var(--sp-4);
}

.label {
  font-family: var(--font-mono);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: var(--fg-3);
}

.input,
.select {
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 0;
  color: var(--fg-1);
  font-family: var(--font-body);
  font-size: 13px;
  padding: 8px 10px;
  width: 100%;
  box-sizing: border-box;
}

.input:focus,
.select:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--orb-glow);
}

.filename {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-2);
}

.footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-3);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--rule);
  margin-top: var(--sp-2);
}

.status {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-5) 0;
  color: var(--fg-2);
  font-family: var(--font-body);
  font-size: 13px;
}

.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  flex-shrink: 0;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.done {
  color: var(--signal-ok-fg);
  font-family: var(--font-body);
  font-size: 13px;
  margin: var(--sp-4) 0;
}

.error {
  color: var(--signal-stop-fg);
  font-family: var(--font-mono);
  font-size: 12px;
  margin: var(--sp-4) 0;
  word-break: break-all;
}
```

- [ ] **Step 3: Create KnowledgePage.jsx**

```jsx
import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { DataTable } from '../../components/DataTable/DataTable'
import { knowledgeApi } from '../../api/knowledge'
import { makeRefreshableRequest } from '../../api/client'
import { IngestionModal } from './IngestionModal'
import styles from './KnowledgePage.module.css'

const STATUS_VARIANT = {
  COMPLETED: 'green',
  RUNNING: 'blue',
  QUEUED: 'gray',
  FAILED: 'red',
}

const COLUMNS = [
  { key: 'sourceType', label: 'Type', width: '80px' },
  { key: 'sourceRef', label: 'Source' },
  { key: 'tenantId', label: 'Tenant', width: '160px', mono: true },
  {
    key: 'status',
    label: 'Status',
    width: '110px',
    render: row => (
      <Badge variant={STATUS_VARIANT[row.status] ?? 'gray'}>{row.status}</Badge>
    ),
  },
  { key: 'chunkCount', label: 'Chunks', width: '80px', mono: true },
  { key: 'createdAt', label: 'Created', width: '180px', mono: true },
]

export function Knowledge() {
  const { token, role, currentTenant, onRefresh, onLogout } = useAuth()
  const [jobs, setJobs] = useState([])
  const [totalPages, setTotalPages] = useState(0)
  const [page, setPage] = useState(0)
  const [showModal, setShowModal] = useState(false)
  const [loading, setLoading] = useState(true)

  const request = makeRefreshableRequest(token, role, currentTenant, onRefresh, onLogout)

  // rawFetch for multipart (no Content-Type: application/json)
  const rawFetch = useCallback(
    (path, options = {}) => {
      const API_BASE = import.meta.env.VITE_API_BASE ?? ''
      return fetch(`${API_BASE}${path}`, {
        ...options,
        headers: { Authorization: `Bearer ${token}`, ...options.headers },
      }).then(res => {
        if (!res.ok) return Promise.reject(new Error(`${res.status} ${res.statusText}`))
        return res.json()
      })
    },
    [token]
  )

  const api = knowledgeApi(request, rawFetch)

  const loadJobs = useCallback(async () => {
    setLoading(true)
    try {
      const data = await api.jobs(page, 20)
      setJobs(
        (data?.content ?? []).map(j => ({
          ...j,
          tenantId: j.tenantId ?? 'Global',
          createdAt: j.createdAt ? new Date(j.createdAt).toLocaleString() : '—',
        }))
      )
      setTotalPages(data?.totalPages ?? 0)
    } catch {
      setJobs([])
    } finally {
      setLoading(false)
    }
  }, [page])

  useEffect(() => {
    loadJobs()
  }, [loadJobs])

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h2 className={styles.title}>KNOWLEDGE BASE</h2>
          <div className={styles.subtitle}>◆ knowledge-engine · port 9088</div>
        </div>
        <Button variant="primary" onClick={() => setShowModal(true)}>
          Add Document
        </Button>
      </div>

      <DataTable
        columns={COLUMNS}
        rows={jobs}
        emptyText={loading ? 'Loading\u2026' : 'No ingestion jobs yet. Submit a URL or file.'}
      />

      {totalPages > 1 && (
        <div className={styles.pagination}>
          <button
            className={styles.pageBtn}
            disabled={page === 0}
            onClick={() => setPage(p => p - 1)}
          >
            ◂ Prev
          </button>
          <span className={styles.pageInfo}>
            {page + 1} / {totalPages}
          </span>
          <button
            className={styles.pageBtn}
            disabled={page >= totalPages - 1}
            onClick={() => setPage(p => p + 1)}
          >
            Next ▸
          </button>
        </div>
      )}

      {showModal && (
        <IngestionModal
          api={api}
          tenants={[]} // pass tenants list if available from context
          onClose={() => setShowModal(false)}
          onJobCreated={loadJobs}
        />
      )}
    </div>
  )
}
```

- [ ] **Step 4: Create KnowledgePage.module.css**

```css
.page {
  padding: var(--sp-5);
  max-width: 1200px;
}

.header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--sp-5);
}

.title {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--accent);
  margin: 0 0 var(--sp-1) 0;
}

.subtitle {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
  letter-spacing: 0.06em;
}

.pagination {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-top: var(--sp-4);
}

.pageBtn {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--fg-2);
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 5px 12px;
  cursor: pointer;
  border-radius: 0;
  transition: border-color 150ms, color 150ms;
}

.pageBtn:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.pageBtn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.pageInfo {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}
```

- [ ] **Step 5: Update App.jsx — replace stub with real import**

Update the import (replacing the stub if added in Task 7):
```javascript
import { Knowledge } from './pages/Knowledge/KnowledgePage'
```

The route added in Task 7 (`<Route path="knowledge" element={<Knowledge />} />`) is already correct.

- [ ] **Step 6: Build frontend**

```bash
cd emcip-admin-ui/src/main/frontend && npm run build 2>&1 | tail -20
```

Expected: build completes without errors.

- [ ] **Step 7: Apply Spotless to all modified Java modules and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-knowledge-engine,emcip-admin-api
git add emcip-admin-ui/src/main/frontend/src/pages/Knowledge/ \
        emcip-admin-ui/src/main/frontend/src/App.jsx
git commit -m "feat(admin-ui): add Knowledge page and IngestionModal for document ingestion (#26.8)"
```

---

## Self-Review

### Spec coverage check

| Spec requirement | Task |
|------------------|------|
| POST /ingest/url → 202 + jobId | Task 5 |
| POST /ingest/upload → 202 + jobId | Task 5 |
| GET /ingest/{jobId} → status | Task 5 |
| GET /ingest → paginated job list | Task 5 |
| Apache Tika 3.3.1 for HTML/text/PDF/DOCX | Task 1, 4 |
| Async VirtualThread processing | Task 4 |
| ke_ingestion_jobs table | Task 2 |
| Entity extraction per chunk | Task 3, 4 |
| tenant_id = null for global | Task 2, 4 |
| Admin-API proxy | Task 6 |
| Admin-UI Knowledge page | Task 8 |
| Admin-UI IngestionModal (4 phases) | Task 8 |
| Admin-UI API hooks | Task 7 |
| Sidebar nav entry | Task 7 |
| Unit tests (service + controller) | Task 3, 4, 5 |

All spec requirements are covered.

### Type consistency

- `IngestionJob.IngestionStatus` used consistently across Tasks 2, 4, 5
- `IngestionJob.SourceType` used consistently across Tasks 2, 4, 5
- `IngestionJobDto.from(IngestionJob)` defined in Task 2, used in Task 5
- `DocumentIngestionService.submitUrlIngestion(String, UUID)` defined in Task 4, tested in Task 5
- `DocumentIngestionService.submitFileIngestion(InputStream, String, UUID)` defined in Task 4, tested in Task 5
- `KnowledgeExtractionService.processDocument(String, String, UUID)` defined in Task 3, called in Task 4
- `knowledgeApi(request, rawFetch)` defined in Task 7, used in Task 8
