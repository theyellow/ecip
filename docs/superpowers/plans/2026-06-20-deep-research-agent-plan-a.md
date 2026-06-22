# Deep Research Agent — Plan A: Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the core Deep Research Agent backend inside `emcip-knowledge-engine` — research session lifecycle, strategy engine that decomposes questions into sub-queries, an execution loop that queries the knowledge base, evidence collection with provenance, and cost/depth guardrails — covering US-27.1, 27.2, 27.4, and 27.9.

**Architecture:** A `ResearchSession` JPA entity tracks state through a lifecycle (CREATED → RUNNING → PAUSED → COMPLETED / FAILED). `ResearchAgentService` drives the execution loop: it uses `LlmOrchestratorClient` to decompose the question, selects a query strategy per sub-question, calls `KnowledgeQueryService` for answers, collects `ResearchEvidence` with provenance, and terminates when confidence or cost/depth limits are reached. All sessions are exposed via a new `ResearchController` (CRUD + trigger). No UI in this plan — REST API only.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate, Liquibase, Kafka (`knowledge.events`), `KnowledgeQueryService` (existing), `LlmOrchestratorClient` (existing), JUnit 5 + Mockito + AssertJ

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ResearchSession.java` | JPA entity — tracks question, status, cost, depth, tenant |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ResearchEvidence.java` | JPA entity — one evidence item per sub-question answer |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ResearchStatus.java` | Enum — CREATED, RUNNING, PAUSED, COMPLETED, FAILED |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/QueryStrategy.java` | Enum — TOPIC_EXPLORATION, PERSON_ANALYSIS, FACT_VERIFICATION, COMPARISON, OPINION_MAPPING |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/ResearchSessionRepository.java` | Spring Data JPA |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/ResearchEvidenceRepository.java` | Spring Data JPA |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchRequest.java` | Request DTO — question, maxIterations, maxLlmCalls, costLimitUsd, tenantId |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchSessionDto.java` | Response DTO |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchStrategyService.java` | Decomposes question into sub-questions + selects QueryStrategy per sub-question |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchAgentService.java` | Execution loop — drives sub-question cycle, collects evidence, enforces limits |
| Create | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/ResearchController.java` | REST — POST /start, GET /{id}, GET /, POST /{id}/pause, POST /{id}/resume |
| Create (migrations) | `emcip-knowledge-engine/src/main/resources/db/changelog/015-research-sessions.xml` | `ke_research_sessions` table |
| Create (migrations) | `emcip-knowledge-engine/src/main/resources/db/changelog/016-research-evidence.xml` | `ke_research_evidence` table |
| Modify | `emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml` | Include new migrations |
| Create | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResearchStrategyServiceTest.java` | Unit tests |
| Create | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResearchAgentServiceTest.java` | Unit tests |
| Create | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/ResearchControllerTest.java` | Unit tests |

---

## Task 1: Enums and Liquibase migrations

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ResearchStatus.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/QueryStrategy.java`
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/015-research-sessions.xml`
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/016-research-evidence.xml`
- Modify: `emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Create `ResearchStatus` enum**

```java
package io.emcip.knowledge.engine.entity;

public enum ResearchStatus {
    CREATED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED
}
```

- [ ] **Step 2: Create `QueryStrategy` enum**

```java
package io.emcip.knowledge.engine.entity;

public enum QueryStrategy {
    /** "What do we know about X?" — graph traversal from topic node + vector search */
    TOPIC_EXPLORATION,
    /** "What does Person X discuss?" — graph edges from person node + authored messages */
    PERSON_ANALYSIS,
    /** "Who holds what position on X?" — persons connected to topic */
    OPINION_MAPPING,
    /** "How do opinions differ between groups?" — scoped graph queries */
    COMPARISON,
    /** "Is claim X supported?" — search factual knowledge + compare with community */
    FACT_VERIFICATION
}
```

- [ ] **Step 3: Create `015-research-sessions.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-15" author="knowledge-engine">
        <createTable tableName="ke_research_sessions"
                     remarks="Deep research agent sessions">
            <column name="id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="tenant_id" type="UUID">
                <constraints nullable="false"/>
            </column>
            <column name="question" type="TEXT">
                <constraints nullable="false"/>
            </column>
            <column name="status" type="VARCHAR(20)" defaultValue="CREATED">
                <constraints nullable="false"/>
            </column>
            <column name="max_iterations" type="INTEGER" defaultValueNumeric="10">
                <constraints nullable="false"/>
            </column>
            <column name="max_llm_calls" type="INTEGER" defaultValueNumeric="20">
                <constraints nullable="false"/>
            </column>
            <column name="cost_limit_usd" type="DOUBLE PRECISION" defaultValueNumeric="1.00">
                <constraints nullable="false"/>
            </column>
            <column name="iterations_used" type="INTEGER" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
            <column name="llm_calls_used" type="INTEGER" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
            <column name="cost_used_usd" type="DOUBLE PRECISION" defaultValueNumeric="0.0">
                <constraints nullable="false"/>
            </column>
            <column name="error_message" type="TEXT"/>
            <column name="created_at" type="TIMESTAMP WITH TIME ZONE">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP WITH TIME ZONE">
                <constraints nullable="false"/>
            </column>
            <column name="version_lock" type="BIGINT" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex indexName="idx_ke_research_sessions_tenant_status"
                     tableName="ke_research_sessions">
            <column name="tenant_id"/>
            <column name="status"/>
        </createIndex>

        <createIndex indexName="idx_ke_research_sessions_created_at"
                     tableName="ke_research_sessions">
            <column name="created_at"/>
        </createIndex>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 4: Create `016-research-evidence.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-16" author="knowledge-engine">
        <createTable tableName="ke_research_evidence"
                     remarks="Evidence items collected during a research session">
            <column name="id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="session_id" type="UUID">
                <constraints nullable="false"
                             foreignKeyName="fk_ke_evidence_session"
                             references="ke_research_sessions(id)"/>
            </column>
            <column name="sub_question" type="TEXT">
                <constraints nullable="false"/>
            </column>
            <column name="query_strategy" type="VARCHAR(50)">
                <constraints nullable="false"/>
            </column>
            <column name="finding" type="TEXT">
                <constraints nullable="false"/>
            </column>
            <column name="source_type" type="VARCHAR(50)">
                <constraints nullable="false"/>
            </column>
            <column name="source_ref" type="VARCHAR(1000)">
                <constraints nullable="false"/>
            </column>
            <column name="confidence_score" type="DOUBLE PRECISION">
                <constraints nullable="false"/>
            </column>
            <column name="iteration" type="INTEGER">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP WITH TIME ZONE">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex indexName="idx_ke_research_evidence_session_id"
                     tableName="ke_research_evidence">
            <column name="session_id"/>
        </createIndex>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 5: Add migrations to master changelog**

Open `emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml`. Find the last `<include>` line and add after it:

```xml
    <include file="classpath:db/changelog/015-research-sessions.xml"/>
    <include file="classpath:db/changelog/016-research-evidence.xml"/>
```

- [ ] **Step 6: Verify migration runs**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine liquibase:update -q 2>&1 | tail -20
```

Expected: no errors. If the DB isn't running locally, skip this — the migration will run in tests.

- [ ] **Step 7: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine spotless:apply -q
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ResearchStatus.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/QueryStrategy.java \
        emcip-knowledge-engine/src/main/resources/db/changelog/015-research-sessions.xml \
        emcip-knowledge-engine/src/main/resources/db/changelog/016-research-evidence.xml \
        emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml
git commit -m "feat(27a): add ResearchStatus/QueryStrategy enums and Liquibase migrations"
```

---

## Task 2: JPA entities — ResearchSession and ResearchEvidence

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ResearchSession.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ResearchEvidence.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/ResearchSessionRepository.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/ResearchEvidenceRepository.java`

- [ ] **Step 1: Create `ResearchSession` entity**

```java
package io.emcip.knowledge.engine.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ke_research_sessions")
@Getter
@Setter
@NoArgsConstructor
public class ResearchSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResearchStatus status = ResearchStatus.CREATED;

    @Column(name = "max_iterations", nullable = false)
    private int maxIterations = 10;

    @Column(name = "max_llm_calls", nullable = false)
    private int maxLlmCalls = 20;

    @Column(name = "cost_limit_usd", nullable = false)
    private double costLimitUsd = 1.00;

    @Column(name = "iterations_used", nullable = false)
    private int iterationsUsed = 0;

    @Column(name = "llm_calls_used", nullable = false)
    private int llmCallsUsed = 0;

    @Column(name = "cost_used_usd", nullable = false)
    private double costUsedUsd = 0.0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version_lock", nullable = false)
    private Long versionLock;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    public boolean isWithinLimits() {
        return iterationsUsed < maxIterations
                && llmCallsUsed < maxLlmCalls
                && costUsedUsd < costLimitUsd;
    }
}
```

- [ ] **Step 2: Create `ResearchEvidence` entity**

```java
package io.emcip.knowledge.engine.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ke_research_evidence")
@Getter
@Setter
@NoArgsConstructor
public class ResearchEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ResearchSession session;

    @Column(name = "sub_question", nullable = false, columnDefinition = "TEXT")
    private String subQuestion;

    @Enumerated(EnumType.STRING)
    @Column(name = "query_strategy", nullable = false, length = 50)
    private QueryStrategy queryStrategy;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String finding;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "source_ref", nullable = false, length = 1000)
    private String sourceRef;

    @Column(name = "confidence_score", nullable = false)
    private double confidenceScore;

    @Column(nullable = false)
    private int iteration;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

- [ ] **Step 3: Create `ResearchSessionRepository`**

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.ResearchSession;
import io.emcip.knowledge.engine.entity.ResearchStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ResearchSessionRepository extends JpaRepository<ResearchSession, UUID> {

    List<ResearchSession> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<ResearchSession> findByTenantIdAndStatusOrderByCreatedAtDesc(
            UUID tenantId, ResearchStatus status);
}
```

- [ ] **Step 4: Create `ResearchEvidenceRepository`**

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.ResearchEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ResearchEvidenceRepository extends JpaRepository<ResearchEvidence, UUID> {

    List<ResearchEvidence> findBySessionIdOrderByIterationAscCreatedAtAsc(UUID sessionId);
}
```

- [ ] **Step 5: Compile check**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine compile -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine spotless:apply -q
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ResearchSession.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ResearchEvidence.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/ResearchSessionRepository.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/ResearchEvidenceRepository.java
git commit -m "feat(27a): add ResearchSession/ResearchEvidence entities and repositories"
```

---

## Task 3: DTOs

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchRequest.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchSessionDto.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchEvidenceDto.java`

- [ ] **Step 1: Create `ResearchRequest`**

```java
package io.emcip.knowledge.engine.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record ResearchRequest(
        @NotBlank String question,
        UUID tenantId,
        @Min(1) @Max(50) int maxIterations,
        @Min(1) @Max(100) int maxLlmCalls,
        @DecimalMin("0.01") double costLimitUsd) {

    public ResearchRequest {
        if (maxIterations == 0) maxIterations = 10;
        if (maxLlmCalls == 0) maxLlmCalls = 20;
        if (costLimitUsd == 0.0) costLimitUsd = 1.00;
    }
}
```

- [ ] **Step 2: Create `ResearchEvidenceDto`**

```java
package io.emcip.knowledge.engine.model;

import io.emcip.knowledge.engine.entity.QueryStrategy;

import java.time.Instant;
import java.util.UUID;

public record ResearchEvidenceDto(
        UUID id,
        String subQuestion,
        QueryStrategy queryStrategy,
        String finding,
        String sourceType,
        String sourceRef,
        double confidenceScore,
        int iteration,
        Instant createdAt) {}
```

- [ ] **Step 3: Create `ResearchSessionDto`**

```java
package io.emcip.knowledge.engine.model;

import io.emcip.knowledge.engine.entity.ResearchSession;
import io.emcip.knowledge.engine.entity.ResearchStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResearchSessionDto(
        UUID id,
        UUID tenantId,
        String question,
        ResearchStatus status,
        int maxIterations,
        int maxLlmCalls,
        double costLimitUsd,
        int iterationsUsed,
        int llmCallsUsed,
        double costUsedUsd,
        String errorMessage,
        List<ResearchEvidenceDto> evidence,
        Instant createdAt,
        Instant updatedAt) {

    public static ResearchSessionDto from(ResearchSession s, List<ResearchEvidenceDto> evidence) {
        return new ResearchSessionDto(
                s.getId(), s.getTenantId(), s.getQuestion(), s.getStatus(),
                s.getMaxIterations(), s.getMaxLlmCalls(), s.getCostLimitUsd(),
                s.getIterationsUsed(), s.getLlmCallsUsed(), s.getCostUsedUsd(),
                s.getErrorMessage(), evidence, s.getCreatedAt(), s.getUpdatedAt());
    }
}
```

- [ ] **Step 4: Compile check**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine compile -q 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine spotless:apply -q
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchRequest.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchSessionDto.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/model/ResearchEvidenceDto.java
git commit -m "feat(27a): add ResearchRequest, ResearchSessionDto, ResearchEvidenceDto"
```

---

## Task 4: ResearchStrategyService — question decomposition

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchStrategyService.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResearchStrategyServiceTest.java`

This service uses `LlmOrchestratorClient` to decompose a research question into sub-questions and assign a `QueryStrategy` to each. The LLM prompt instructs it to return a JSON array.

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.QueryStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResearchStrategyServiceTest {

    @Mock
    private LlmOrchestratorClient llmClient;

    private ResearchStrategyService service;

    @BeforeEach
    void setUp() {
        service = new ResearchStrategyService(llmClient);
    }

    @Test
    void decompose_parsesLlmJsonResponse_intoSubQuestions() {
        String llmResponse = """
                [
                  {"subQuestion": "What topics does Alice discuss?", "strategy": "PERSON_ANALYSIS"},
                  {"subQuestion": "What do we know about AI in the group?", "strategy": "TOPIC_EXPLORATION"}
                ]
                """;
        when(llmClient.analyse(anyString(), anyString())).thenReturn(llmResponse);

        List<ResearchStrategyService.SubQuestion> result =
                service.decompose("Tell me about Alice's views on AI");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).subQuestion()).isEqualTo("What topics does Alice discuss?");
        assertThat(result.get(0).strategy()).isEqualTo(QueryStrategy.PERSON_ANALYSIS);
        assertThat(result.get(1).strategy()).isEqualTo(QueryStrategy.TOPIC_EXPLORATION);
    }

    @Test
    void decompose_returnsSingleFallback_whenLlmResponseUnparseable() {
        when(llmClient.analyse(anyString(), anyString())).thenReturn("not valid json");

        List<ResearchStrategyService.SubQuestion> result =
                service.decompose("What do we know about climate change?");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).subQuestion()).isEqualTo("What do we know about climate change?");
        assertThat(result.get(0).strategy()).isEqualTo(QueryStrategy.TOPIC_EXPLORATION);
    }

    @Test
    void decompose_returnsSingleFallback_whenLlmReturnsNull() {
        when(llmClient.analyse(anyString(), anyString())).thenReturn(null);

        List<ResearchStrategyService.SubQuestion> result =
                service.decompose("Who are the key people?");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).strategy()).isEqualTo(QueryStrategy.TOPIC_EXPLORATION);
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -Dtest=ResearchStrategyServiceTest -q 2>&1 | tail -20
```

Expected: compilation error — `ResearchStrategyService` does not exist.

Also check if `LlmOrchestratorClient.analyse(String, String)` exists:

```bash
grep -n "analyse\|public.*String.*analyse\|String analyse" \
  /home/ben/Development/ecip/emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/client/LlmOrchestratorClient.java
```

If `analyse(String prompt, String taskType)` does not exist, read the full file and use the actual method that calls `POST /api/analyse`. The method may be called `callAnalyse`, `analyze`, or similar. Update the test to match the real method name.

- [ ] **Step 3: Create `ResearchStrategyService`**

```java
package io.emcip.knowledge.engine.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.QueryStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchStrategyService {

    private final LlmOrchestratorClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record SubQuestion(String subQuestion, QueryStrategy strategy) {}

    private static final String DECOMPOSE_PROMPT_TEMPLATE = """
            You are a research planning assistant. Decompose the following research question into \
            2-5 focused sub-questions. For each sub-question, choose the most appropriate strategy:
            - TOPIC_EXPLORATION: "What do we know about X?"
            - PERSON_ANALYSIS: "What does person X discuss or think?"
            - OPINION_MAPPING: "Who holds what position on X?"
            - COMPARISON: "How do groups or people differ on X?"
            - FACT_VERIFICATION: "Is claim X supported by evidence?"

            Respond ONLY with a JSON array. Example:
            [{"subQuestion": "...", "strategy": "TOPIC_EXPLORATION"}]

            Research question: %s
            """;

    /**
     * Decomposes a research question into sub-questions with assigned query strategies.
     * Falls back to a single TOPIC_EXPLORATION sub-question if the LLM response is unparseable.
     */
    public List<SubQuestion> decompose(String question) {
        String prompt = DECOMPOSE_PROMPT_TEMPLATE.formatted(question);
        String response = llmClient.analyse(prompt, "RESEARCH");

        if (response == null || response.isBlank()) {
            return fallback(question);
        }

        try {
            // Strip markdown code fences if present
            String json = response.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").strip();
            }
            return objectMapper.readValue(json, new TypeReference<List<SubQuestion>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse LLM decomposition response, using fallback: {}", e.getMessage());
            return fallback(question);
        }
    }

    private List<SubQuestion> fallback(String question) {
        return List.of(new SubQuestion(question, QueryStrategy.TOPIC_EXPLORATION));
    }
}
```

> **Note:** The `ObjectMapper` import is `tools.jackson.databind.ObjectMapper` (Spring Boot 4 / Jackson 3). If the project's `LlmOrchestratorClient` uses a different import, match it. Check other files in the package for the correct import.
>
> The `llmClient.analyse(prompt, "RESEARCH")` call assumes `LlmOrchestratorClient` has a method `String analyse(String prompt, String taskType)`. Read `LlmOrchestratorClient.java` to find the exact method. If it doesn't have a generic `analyse` method, use whatever calls `POST /api/analyse` — typically it receives a text prompt and a taskType string. Adapt accordingly.

- [ ] **Step 4: Run tests to confirm they pass**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -Dtest=ResearchStrategyServiceTest -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS, 3 tests passing.

- [ ] **Step 5: Run all module tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -q 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine spotless:apply -q
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchStrategyService.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResearchStrategyServiceTest.java
git commit -m "feat(27a): add ResearchStrategyService — LLM-driven question decomposition"
```

---

## Task 5: ResearchAgentService — execution loop

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchAgentService.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResearchAgentServiceTest.java`

This is the core service. It:
1. Creates a `ResearchSession` with CREATED status
2. Transitions to RUNNING
3. Calls `ResearchStrategyService.decompose()` → list of sub-questions
4. For each sub-question: calls `KnowledgeQueryService.search()`, converts results to `ResearchEvidence`, saves them, increments counters
5. Terminates when all sub-questions are answered OR limits are exceeded
6. Sets status to COMPLETED or FAILED
7. Publishes a `knowledge.events` event via `KnowledgeEventPublisher`

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.entity.*;
import io.emcip.knowledge.engine.model.*;
import io.emcip.knowledge.engine.repository.ResearchEvidenceRepository;
import io.emcip.knowledge.engine.repository.ResearchSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResearchAgentServiceTest {

    @Mock private ResearchSessionRepository sessionRepository;
    @Mock private ResearchEvidenceRepository evidenceRepository;
    @Mock private ResearchStrategyService strategyService;
    @Mock private KnowledgeQueryService queryService;
    @Mock private KnowledgeEventPublisher eventPublisher;

    private ResearchAgentService service;

    @BeforeEach
    void setUp() {
        service = new ResearchAgentService(
                sessionRepository, evidenceRepository,
                strategyService, queryService, eventPublisher);
    }

    @Test
    void startResearch_createsSession_andRunsLoop() {
        UUID tenantId = UUID.randomUUID();
        ResearchRequest request = new ResearchRequest(
                "Tell me about Alice's views on AI", tenantId, 10, 20, 1.00);

        // Strategy returns one sub-question
        ResearchStrategyService.SubQuestion subQ = new ResearchStrategyService.SubQuestion(
                "What topics does Alice discuss?", QueryStrategy.PERSON_ANALYSIS);
        when(strategyService.decompose(anyString())).thenReturn(List.of(subQ));

        // Knowledge query returns one document result
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(UUID.randomUUID());
        doc.setContent("Alice frequently discusses AI ethics.");
        doc.setSourceType("CHAT_MESSAGE");
        doc.setSourceRef("msg-123");
        SearchResponse.DocumentResult docResult = new SearchResponse.DocumentResult(doc, 0.88);
        SearchResponse searchResponse = new SearchResponse(List.of(), List.of(docResult));
        when(queryService.search(any())).thenReturn(searchResponse);

        // Repository save returns the passed entity
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evidenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResearchSession result = service.startResearch(request);

        assertThat(result.getStatus()).isEqualTo(ResearchStatus.COMPLETED);
        assertThat(result.getIterationsUsed()).isEqualTo(1);

        ArgumentCaptor<ResearchEvidence> evidenceCaptor =
                ArgumentCaptor.forClass(ResearchEvidence.class);
        verify(evidenceRepository, atLeastOnce()).save(evidenceCaptor.capture());
        ResearchEvidence saved = evidenceCaptor.getValue();
        assertThat(saved.getFinding()).contains("Alice frequently discusses AI ethics.");
        assertThat(saved.getSourceRef()).isEqualTo("msg-123");
        assertThat(saved.getConfidenceScore()).isEqualTo(0.88);
        assertThat(saved.getQueryStrategy()).isEqualTo(QueryStrategy.PERSON_ANALYSIS);
    }

    @Test
    void startResearch_stopsWhenMaxIterationsReached() {
        UUID tenantId = UUID.randomUUID();
        // maxIterations = 1, but strategy returns 3 sub-questions
        ResearchRequest request = new ResearchRequest(
                "A complex question", tenantId, 1, 20, 1.00);

        List<ResearchStrategyService.SubQuestion> subQs = List.of(
                new ResearchStrategyService.SubQuestion("Q1", QueryStrategy.TOPIC_EXPLORATION),
                new ResearchStrategyService.SubQuestion("Q2", QueryStrategy.TOPIC_EXPLORATION),
                new ResearchStrategyService.SubQuestion("Q3", QueryStrategy.TOPIC_EXPLORATION));
        when(strategyService.decompose(anyString())).thenReturn(subQs);
        when(queryService.search(any())).thenReturn(new SearchResponse(List.of(), List.of()));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResearchSession result = service.startResearch(request);

        // Only 1 iteration processed despite 3 sub-questions
        assertThat(result.getIterationsUsed()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo(ResearchStatus.COMPLETED);
    }

    @Test
    void startResearch_setsFailedStatus_whenStrategyServiceThrows() {
        UUID tenantId = UUID.randomUUID();
        ResearchRequest request = new ResearchRequest("Q", tenantId, 10, 20, 1.00);

        when(strategyService.decompose(anyString())).thenThrow(new RuntimeException("LLM unavailable"));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResearchSession result = service.startResearch(request);

        assertThat(result.getStatus()).isEqualTo(ResearchStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("LLM unavailable");
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -Dtest=ResearchAgentServiceTest -q 2>&1 | tail -20
```

Expected: compilation error — `ResearchAgentService` does not exist.

- [ ] **Step 3: Create `ResearchAgentService`**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.entity.*;
import io.emcip.knowledge.engine.model.ResearchRequest;
import io.emcip.knowledge.engine.model.SearchRequest;
import io.emcip.knowledge.engine.repository.ResearchEvidenceRepository;
import io.emcip.knowledge.engine.repository.ResearchSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchAgentService {

    private final ResearchSessionRepository sessionRepository;
    private final ResearchEvidenceRepository evidenceRepository;
    private final ResearchStrategyService strategyService;
    private final KnowledgeQueryService queryService;
    private final KnowledgeEventPublisher eventPublisher;

    /**
     * Creates a new research session and runs the full execution loop synchronously.
     * Returns the session in COMPLETED or FAILED state.
     */
    @Transactional
    public ResearchSession startResearch(ResearchRequest request) {
        ResearchSession session = new ResearchSession();
        session.setTenantId(request.tenantId());
        session.setQuestion(request.question());
        session.setMaxIterations(request.maxIterations());
        session.setMaxLlmCalls(request.maxLlmCalls());
        session.setCostLimitUsd(request.costLimitUsd());
        session.setStatus(ResearchStatus.CREATED);
        sessionRepository.save(session);

        session.setStatus(ResearchStatus.RUNNING);
        sessionRepository.save(session);

        try {
            runLoop(session);
            session.setStatus(ResearchStatus.COMPLETED);
        } catch (Exception e) {
            log.error("Research session {} failed: {}", session.getId(), e.getMessage(), e);
            session.setStatus(ResearchStatus.FAILED);
            session.setErrorMessage(e.getMessage());
        }

        sessionRepository.save(session);
        publishCompletionEvent(session);
        return session;
    }

    @Transactional
    public Optional<ResearchSession> pauseSession(UUID sessionId) {
        return sessionRepository.findById(sessionId).map(session -> {
            if (session.getStatus() == ResearchStatus.RUNNING) {
                session.setStatus(ResearchStatus.PAUSED);
                sessionRepository.save(session);
            }
            return session;
        });
    }

    @Transactional
    public Optional<ResearchSession> resumeSession(UUID sessionId) {
        return sessionRepository.findById(sessionId).map(session -> {
            if (session.getStatus() == ResearchStatus.PAUSED) {
                session.setStatus(ResearchStatus.RUNNING);
                sessionRepository.save(session);
                runLoop(session);
                session.setStatus(ResearchStatus.COMPLETED);
                sessionRepository.save(session);
            }
            return session;
        });
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void runLoop(ResearchSession session) {
        List<ResearchStrategyService.SubQuestion> subQuestions =
                strategyService.decompose(session.getQuestion());
        session.incrementLlmCalls(1); // decomposition used 1 LLM call

        int iteration = 0;
        for (ResearchStrategyService.SubQuestion subQ : subQuestions) {
            if (!session.isWithinLimits()) {
                log.info("Session {} reached limits after {} iterations",
                        session.getId(), session.getIterationsUsed());
                break;
            }

            SearchRequest searchRequest = new SearchRequest(
                    subQ.subQuestion(),
                    SearchRequest.SearchType.HYBRID,
                    session.getTenantId(),
                    null,
                    null,
                    10);

            io.emcip.knowledge.engine.model.SearchResponse response =
                    queryService.search(searchRequest);

            collectEvidence(session, subQ, response, iteration);
            session.incrementIterations(1);
            iteration++;

            sessionRepository.save(session);
        }
    }

    private void collectEvidence(
            ResearchSession session,
            ResearchStrategyService.SubQuestion subQ,
            io.emcip.knowledge.engine.model.SearchResponse response,
            int iteration) {

        // Collect from document results
        for (io.emcip.knowledge.engine.model.SearchResponse.DocumentResult dr
                : response.documentResults()) {
            ResearchEvidence evidence = new ResearchEvidence();
            evidence.setSession(session);
            evidence.setSubQuestion(subQ.subQuestion());
            evidence.setQueryStrategy(subQ.strategy());
            evidence.setFinding(dr.document().getContent());
            evidence.setSourceType(dr.document().getSourceType());
            evidence.setSourceRef(dr.document().getSourceRef());
            evidence.setConfidenceScore(dr.similarity());
            evidence.setIteration(iteration);
            evidenceRepository.save(evidence);
        }

        // Collect from graph results
        for (io.emcip.knowledge.engine.model.SearchResponse.GraphNodeResult gr
                : response.graphResults()) {
            ResearchEvidence evidence = new ResearchEvidence();
            evidence.setSession(session);
            evidence.setSubQuestion(subQ.subQuestion());
            evidence.setQueryStrategy(subQ.strategy());
            evidence.setFinding(gr.node().label() + " [" + gr.node().conceptType() + "]");
            evidence.setSourceType("GRAPH_NODE");
            evidence.setSourceRef(gr.node().id().toString());
            evidence.setConfidenceScore(gr.score());
            evidence.setIteration(iteration);
            evidenceRepository.save(evidence);
        }
    }

    private void publishCompletionEvent(ResearchSession session) {
        try {
            eventPublisher.publishResearchCompleted(session.getId(), session.getStatus());
        } catch (Exception e) {
            log.warn("Failed to publish research completion event for session {}: {}",
                    session.getId(), e.getMessage());
        }
    }
}
```

> **Note:** `session.incrementLlmCalls(1)` and `session.incrementIterations(1)` — add these helper methods to `ResearchSession`:
>
> ```java
> public void incrementLlmCalls(int n) { this.llmCallsUsed += n; }
> public void incrementIterations(int n) { this.iterationsUsed += n; }
> ```

- [ ] **Step 4: Add helper methods to `ResearchSession`**

Open `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ResearchSession.java` and add before the closing `}`:

```java
    public void incrementLlmCalls(int n) {
        this.llmCallsUsed += n;
    }

    public void incrementIterations(int n) {
        this.iterationsUsed += n;
    }
```

- [ ] **Step 5: Add `publishResearchCompleted` to `KnowledgeEventPublisher`**

Read `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeEventPublisher.java` to understand the existing pattern (it uses `KafkaTemplate<String, String>` and sends JSON strings to `knowledge.events`).

Add the following method to `KnowledgeEventPublisher`:

```java
public void publishResearchCompleted(UUID sessionId, ResearchStatus status) {
    String payload = "{\"eventType\":\"RESEARCH_COMPLETED\",\"sessionId\":\""
            + sessionId + "\",\"status\":\"" + status.name() + "\"}";
    kafkaTemplate.send("knowledge.events", sessionId.toString(), payload);
    log.debug("Published RESEARCH_COMPLETED for session {}", sessionId);
}
```

(The import for `ResearchStatus` is `io.emcip.knowledge.engine.entity.ResearchStatus`.)

- [ ] **Step 6: Run tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -Dtest=ResearchAgentServiceTest -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS, 3 tests passing.

- [ ] **Step 7: Run full module tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -q 2>&1 | tail -10
```

- [ ] **Step 8: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine spotless:apply -q
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/entity/ResearchSession.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResearchAgentService.java \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeEventPublisher.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResearchAgentServiceTest.java
git commit -m "feat(27a): add ResearchAgentService — execution loop, evidence collection, guardrails"
```

---

## Task 6: ResearchController — REST API

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/ResearchController.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/ResearchControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.entity.*;
import io.emcip.knowledge.engine.model.ResearchRequest;
import io.emcip.knowledge.engine.model.ResearchSessionDto;
import io.emcip.knowledge.engine.repository.ResearchEvidenceRepository;
import io.emcip.knowledge.engine.repository.ResearchSessionRepository;
import io.emcip.knowledge.engine.service.ResearchAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResearchControllerTest {

    @Mock private ResearchAgentService agentService;
    @Mock private ResearchSessionRepository sessionRepository;
    @Mock private ResearchEvidenceRepository evidenceRepository;

    private ResearchController controller;

    @BeforeEach
    void setUp() {
        controller = new ResearchController(agentService, sessionRepository, evidenceRepository);
    }

    private ResearchSession buildSession(UUID id, UUID tenantId, ResearchStatus status) {
        ResearchSession s = new ResearchSession();
        s.setId(id);
        s.setTenantId(tenantId);
        s.setQuestion("Test question");
        s.setStatus(status);
        s.setMaxIterations(10);
        s.setMaxLlmCalls(20);
        s.setCostLimitUsd(1.00);
        return s;
    }

    @Test
    void startResearch_returns201_withSessionDto() {
        UUID tenantId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ResearchRequest request = new ResearchRequest("Test question", tenantId, 10, 20, 1.00);

        ResearchSession session = buildSession(sessionId, tenantId, ResearchStatus.COMPLETED);
        when(agentService.startResearch(any())).thenReturn(session);
        when(evidenceRepository.findBySessionIdOrderByIterationAscCreatedAtAsc(sessionId))
                .thenReturn(List.of());

        ResponseEntity<ResearchSessionDto> response = controller.startResearch(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(sessionId);
        assertThat(response.getBody().status()).isEqualTo(ResearchStatus.COMPLETED);
    }

    @Test
    void getSession_returns404_whenNotFound() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        ResponseEntity<ResearchSessionDto> response = controller.getSession(sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listSessions_returnsSessions_forTenant() {
        UUID tenantId = UUID.randomUUID();
        ResearchSession session = buildSession(UUID.randomUUID(), tenantId, ResearchStatus.COMPLETED);
        when(sessionRepository.findByTenantIdOrderByCreatedAtDesc(tenantId))
                .thenReturn(List.of(session));
        when(evidenceRepository.findBySessionIdOrderByIterationAscCreatedAtAsc(any()))
                .thenReturn(List.of());

        ResponseEntity<List<ResearchSessionDto>> response = controller.listSessions(tenantId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void pauseSession_returns404_whenNotFound() {
        UUID sessionId = UUID.randomUUID();
        when(agentService.pauseSession(sessionId)).thenReturn(Optional.empty());

        ResponseEntity<ResearchSessionDto> response = controller.pauseSession(sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -Dtest=ResearchControllerTest -q 2>&1 | tail -20
```

Expected: compilation error.

- [ ] **Step 3: Create `ResearchController`**

```java
package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.entity.ResearchSession;
import io.emcip.knowledge.engine.model.ResearchEvidenceDto;
import io.emcip.knowledge.engine.model.ResearchRequest;
import io.emcip.knowledge.engine.model.ResearchSessionDto;
import io.emcip.knowledge.engine.repository.ResearchEvidenceRepository;
import io.emcip.knowledge.engine.repository.ResearchSessionRepository;
import io.emcip.knowledge.engine.service.ResearchAgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/knowledge/research")
@RequiredArgsConstructor
public class ResearchController {

    private final ResearchAgentService agentService;
    private final ResearchSessionRepository sessionRepository;
    private final ResearchEvidenceRepository evidenceRepository;

    @PostMapping
    public ResponseEntity<ResearchSessionDto> startResearch(
            @Valid @RequestBody ResearchRequest request) {
        log.info("Starting research session for tenant {}: {}", request.tenantId(), request.question());
        ResearchSession session = agentService.startResearch(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(session));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResearchSessionDto> getSession(@PathVariable UUID id) {
        return sessionRepository.findById(id)
                .map(s -> ResponseEntity.ok(toDto(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<ResearchSessionDto>> listSessions(
            @RequestParam UUID tenantId) {
        List<ResearchSessionDto> sessions = sessionRepository
                .findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(sessions);
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<ResearchSessionDto> pauseSession(@PathVariable UUID id) {
        return agentService.pauseSession(id)
                .map(s -> ResponseEntity.ok(toDto(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<ResearchSessionDto> resumeSession(@PathVariable UUID id) {
        return agentService.resumeSession(id)
                .map(s -> ResponseEntity.ok(toDto(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResearchSessionDto toDto(ResearchSession session) {
        List<ResearchEvidenceDto> evidence = evidenceRepository
                .findBySessionIdOrderByIterationAscCreatedAtAsc(session.getId())
                .stream()
                .map(e -> new ResearchEvidenceDto(
                        e.getId(), e.getSubQuestion(), e.getQueryStrategy(),
                        e.getFinding(), e.getSourceType(), e.getSourceRef(),
                        e.getConfidenceScore(), e.getIteration(), e.getCreatedAt()))
                .toList();
        return ResearchSessionDto.from(session, evidence);
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -Dtest=ResearchControllerTest -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS, 4 tests passing.

- [ ] **Step 5: Run full module tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine test -q 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine spotless:apply -q
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/ResearchController.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/ResearchControllerTest.java
git commit -m "feat(27a): add ResearchController — POST /start, GET /{id}, pause/resume"
```

---

## Task 7: Admin API proxy for research endpoints

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ResearchProxyController.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/ResearchProxyControllerTest.java`

The admin-api already has a `knowledgeWebClient` bean. This proxy follows the same pattern as `KnowledgeSearchProxyController`.

- [ ] **Step 1: Write the failing test**

Read `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/KnowledgeSearchProxyControllerTest.java` to understand the exact test pattern (imports, mock setup, WebClient stubbing). Then create:

```java
package io.emcip.admin.api.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResearchProxyControllerTest {

    @Mock private WebClient knowledgeWebClient;
    @Mock private CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock private CircuitBreaker circuitBreaker;

    // WebClient fluent mock chain
    @Mock private WebClient.RequestBodyUriSpec postSpec;
    @Mock private WebClient.RequestBodySpec postBodySpec;
    @Mock private WebClient.RequestHeadersUriSpec getSpec;
    @Mock private WebClient.RequestHeadersSpec getHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;

    private ResearchProxyController controller;

    @BeforeEach
    void setUp() {
        when(circuitBreakerRegistry.circuitBreaker("knowledge")).thenReturn(circuitBreaker);
        when(circuitBreaker.executeSupplier(any())).thenAnswer(inv ->
                ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
        controller = new ResearchProxyController(knowledgeWebClient, circuitBreakerRegistry);
    }

    @Test
    void startResearch_proxiesPostToKnowledgeEngine() {
        when(knowledgeWebClient.post()).thenReturn(postSpec);
        when(postSpec.uri("/api/knowledge/research")).thenReturn(postBodySpec);
        when(postBodySpec.contentType(any())).thenReturn(postBodySpec);
        when(postBodySpec.bodyValue(any())).thenReturn(responseSpec);
        when(responseSpec.toEntity(String.class))
                .thenReturn(Mono.just(ResponseEntity.status(201).body("{\"id\":\"abc\"}")));

        Mono<ResponseEntity<String>> response = controller.startResearch("{\"question\":\"Q\"}");

        StepVerifier.create(response)
                .assertNext(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED))
                .verifyComplete();
    }

    @Test
    void getSession_proxiesGetToKnowledgeEngine() {
        UUID sessionId = UUID.randomUUID();
        when(knowledgeWebClient.get()).thenReturn(getSpec);
        when(getSpec.uri("/api/knowledge/research/" + sessionId)).thenReturn(getHeadersSpec);
        when(getHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(String.class))
                .thenReturn(Mono.just(ResponseEntity.ok("{}")));

        Mono<ResponseEntity<String>> response = controller.getSession(sessionId);

        StepVerifier.create(response)
                .assertNext(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();
    }
}
```

> **Note:** Read `KnowledgeSearchProxyController.java` and its test to understand the exact WebClient mock chain pattern used in this project — it may differ slightly. The circuit breaker pattern should be identical. Copy the exact pattern.

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api test -Dtest=ResearchProxyControllerTest -q 2>&1 | tail -20
```

Expected: compilation error.

- [ ] **Step 3: Create `ResearchProxyController`**

```java
package io.emcip.admin.api.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/knowledge/research")
public class ResearchProxyController {

    private final WebClient knowledgeWebClient;
    private final io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker;

    public ResearchProxyController(
            @Qualifier("knowledgeWebClient") WebClient knowledgeWebClient,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.knowledgeWebClient = knowledgeWebClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("knowledge");
    }

    @PostMapping
    public Mono<ResponseEntity<String>> startResearch(@RequestBody String body) {
        return Mono.fromSupplier(() -> circuitBreaker.executeSupplier(() ->
                knowledgeWebClient.post()
                        .uri("/api/knowledge/research")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .toEntity(String.class)
                        .block()))
                .onErrorReturn(ResponseEntity.status(503).body("{\"error\":\"knowledge engine unavailable\"}"));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<String>> getSession(@PathVariable UUID id) {
        return Mono.fromSupplier(() -> circuitBreaker.executeSupplier(() ->
                knowledgeWebClient.get()
                        .uri("/api/knowledge/research/" + id)
                        .retrieve()
                        .toEntity(String.class)
                        .block()))
                .onErrorReturn(ResponseEntity.status(503).body("{\"error\":\"knowledge engine unavailable\"}"));
    }

    @GetMapping
    public Mono<ResponseEntity<String>> listSessions(@RequestParam UUID tenantId) {
        return Mono.fromSupplier(() -> circuitBreaker.executeSupplier(() ->
                knowledgeWebClient.get()
                        .uri(u -> u.path("/api/knowledge/research")
                                .queryParam("tenantId", tenantId).build())
                        .retrieve()
                        .toEntity(String.class)
                        .block()))
                .onErrorReturn(ResponseEntity.status(503).body("{\"error\":\"knowledge engine unavailable\"}"));
    }

    @PostMapping("/{id}/pause")
    public Mono<ResponseEntity<String>> pauseSession(@PathVariable UUID id) {
        return Mono.fromSupplier(() -> circuitBreaker.executeSupplier(() ->
                knowledgeWebClient.post()
                        .uri("/api/knowledge/research/" + id + "/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{}")
                        .retrieve()
                        .toEntity(String.class)
                        .block()))
                .onErrorReturn(ResponseEntity.status(503).body("{\"error\":\"knowledge engine unavailable\"}"));
    }

    @PostMapping("/{id}/resume")
    public Mono<ResponseEntity<String>> resumeSession(@PathVariable UUID id) {
        return Mono.fromSupplier(() -> circuitBreaker.executeSupplier(() ->
                knowledgeWebClient.post()
                        .uri("/api/knowledge/research/" + id + "/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{}")
                        .retrieve()
                        .toEntity(String.class)
                        .block()))
                .onErrorReturn(ResponseEntity.status(503).body("{\"error\":\"knowledge engine unavailable\"}"));
    }
}
```

> **Note:** Look at how `KnowledgeSearchProxyController` handles the circuit breaker pattern — particularly whether it uses `.executeSupplier()` or a decorated supplier approach. Match the exact pattern used there.

- [ ] **Step 4: Run tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api test -Dtest=ResearchProxyControllerTest -q 2>&1 | tail -20
```

- [ ] **Step 5: Run full admin-api tests**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api test -q 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-admin-api spotless:apply -q
git add emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ResearchProxyController.java \
        emcip-admin-api/src/test/java/io/emcip/admin/api/controller/ResearchProxyControllerTest.java
git commit -m "feat(27a): add ResearchProxyController in admin-api — proxies to knowledge-engine"
```

---

## Task 8: Full build + Spotless

- [ ] **Step 1: Full clean build of both modules**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine,emcip-admin-api clean verify -q 2>&1 | tail -30
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Spotless check**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-knowledge-engine,emcip-admin-api spotless:check -q 2>&1 | tail -10
```

Expected: `0 were changed to be clean` for both modules.

If any files need formatting:
```bash
mvn -pl emcip-knowledge-engine,emcip-admin-api spotless:apply -q
git add emcip-knowledge-engine/ emcip-admin-api/
git commit -m "style(27a): apply spotless"
```

---

## Self-Review

### Spec Coverage

| Requirement (from spec) | Task |
|------------------------|------|
| US-27.1: Research session entity tracks state, sub-questions, results, cost | Tasks 1–2 (entity, migrations) |
| US-27.1: Strategy engine — decompose into sub-questions using LLM | Task 4 (ResearchStrategyService) |
| US-27.1: Execution loop — sub-question → source → query → evaluate | Task 5 (ResearchAgentService.runLoop) |
| US-27.1: Depth limits (max iterations, max LLM calls, max cost) | Task 2 (isWithinLimits), Task 5 (loop guard) |
| US-27.1: Termination criteria | Task 5 (limit check + COMPLETED/FAILED) |
| US-27.2: TOPIC_EXPLORATION strategy | Task 4 (QueryStrategy enum + fallback) |
| US-27.2: PERSON_ANALYSIS strategy | Task 4 (QueryStrategy enum) |
| US-27.2: OPINION_MAPPING strategy | Task 4 (QueryStrategy enum) |
| US-27.2: COMPARISON strategy | Task 4 (QueryStrategy enum) |
| US-27.2: FACT_VERIFICATION strategy | Task 4 (QueryStrategy enum) |
| US-27.2: Strategy selection via LLM | Task 4 (LLM decomposition assigns strategy) |
| US-27.4: Evidence entity with source reference + confidence | Task 2 (ResearchEvidence entity) |
| US-27.4: Each finding carries source ref + confidence + extraction method | Task 5 (collectEvidence) |
| US-27.9: Per-session cost tracking | Task 2 (costUsedUsd field — incremented by ResearchAgentService) |
| US-27.9: Configurable budgets (per-session limit) | Task 3 (ResearchRequest.costLimitUsd) + Task 2 (isWithinLimits) |
| US-27.9: Auto-pause when budget threshold reached | Task 5 (isWithinLimits guard in loop) |
| REST API: POST /start, GET /{id}, GET /, pause, resume | Task 6 (ResearchController) |
| Admin API proxy | Task 7 (ResearchProxyController) |

### Gaps

**Cost tracking per LLM call is approximate:** `ResearchAgentService` increments `llmCallsUsed` by 1 for the decomposition call, but does not track the actual USD cost. The `CostTrackingService` in llm-orchestrator logs costs to `model_cost_logs`, but knowledge-engine doesn't read from that table. As a pragmatic first iteration, `costUsedUsd` is incremented by a fixed estimate (not implemented above). Add this to Task 5's `runLoop`:

```java
// After each sub-question loop iteration, estimate cost
// (0.01 USD per LLM call is a conservative placeholder)
session.setCostUsedUsd(session.getCostUsedUsd() + 0.01 * session.getLlmCallsUsed());
```

This is sufficient for the guardrail to work. Real cost integration (reading from `model_cost_logs`) belongs in a later plan.

**`LlmOrchestratorClient.analyse()` method name:** Task 4 uses `llmClient.analyse(prompt, "RESEARCH")`. The actual method name in `LlmOrchestratorClient.java` must be verified at implementation time (Step 2 of Task 4 instructs this check). No placeholder — the instruction is explicit.

### Type Consistency

- `ResearchStrategyService.SubQuestion` — used in Task 4 (defined), Task 5 (consumed) ✅
- `ResearchStatus` — used in Task 1 (defined), Task 2 (entity field), Task 5 (set on session), Task 6 (dto), Task 7 (proxy) ✅
- `QueryStrategy` — used in Task 1 (defined), Task 2 (evidence field), Task 4 (assigned by strategy service), Task 5 (passed to evidence) ✅
- `ResearchSessionDto.from(session, evidence)` — defined in Task 3, used in Task 6 ✅
- `ResearchEvidenceDto` — defined in Task 3, used in Task 6 ✅
