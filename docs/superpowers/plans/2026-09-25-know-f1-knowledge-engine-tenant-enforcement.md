# KNOW-F1 Knowledge-Engine Tenant Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A tenant-bound user can no longer read or change another tenant's research sessions, ingestion jobs, resolution flags or graph neighbours, can read but not change global items, and the tenancy rules are recorded once (ADR-009) and drawn consistently everywhere.

**Architecture:** admin-api asserts the caller's tenant on every knowledge call (`KnowledgeTenantResolver`, from P3.8a) and passes knowledge-engine's 404 through. knowledge-engine enforces an asserted tenant with one policy class, `TenantAccess` (read: own or global; write: own only; otherwise 404), and trusts callers that assert none. The Admin UI stops offering changes to global items to non-`ADMIN` users.

**Tech Stack:** Java 21, Spring Boot 4 (knowledge-engine: Spring MVC + JPA + Apache AGE; admin-api: WebFlux), Jackson 3 (`tools.jackson`), JUnit 5, AssertJ, Mockito, Testcontainers, OkHttp `mockwebserver3`, React 19 + vitest + Testing Library, PlantUML via `asciidoctorj-diagram`.

**Spec:** `docs/superpowers/specs/2026-09-25-know-f1-knowledge-engine-tenant-enforcement-design.md`

## Global Constraints

- Rule table (spec §3): tenant `t` asserted → read allowed if item is `t`'s or global; change allowed only if item is `t`'s; else **404**. No tenant asserted → unchanged behaviour. Search expansion uses the P3.8a rule (tenant → own + global; null → global only).
- Research sessions are never global (`ke_research_sessions.tenant_id NOT NULL`): research list query stays as is.
- admin-api passes a knowledge-engine **404 through as 404**; all other errors keep their current mapping.
- Every new assertion is observed failing once before it counts (project verification rule).
- Build with JDK 21: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`. `mvn spotless:apply` before every commit; `pmd:check` before the PR.
- Admin UI: frontend lives in `emcip-admin-ui/src/main/frontend/`; run vitest with `npx vitest run <path>` from there.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- knowledge-engine ITs share one Testcontainers DB per run: seed with unique values (random UUIDs, a unique `conceptType`) and assert on what you seeded.

---

### Task 1: `TenantAccess` policy (knowledge-engine)

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/tenant/TenantAccess.java`
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/tenant/TenantAccessTest.java`

**Interfaces:**
- Produces: `TenantAccess.canRead(UUID itemTenant, UUID asserted)`, `TenantAccess.canWrite(UUID itemTenant, UUID asserted)`, `TenantAccess.visibleInSearch(UUID itemTenant, UUID searchTenant)` — all `static boolean`.

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.knowledge.engine.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantAccessTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void readAllowsOwnAndGlobalButNotAnotherTenants() {
        assertThat(TenantAccess.canRead(A, A)).isTrue();
        assertThat(TenantAccess.canRead(null, A)).isTrue();
        assertThat(TenantAccess.canRead(B, A)).isFalse();
    }

    @Test
    void writeAllowsOwnOnly() {
        assertThat(TenantAccess.canWrite(A, A)).isTrue();
        assertThat(TenantAccess.canWrite(null, A)).isFalse();
        assertThat(TenantAccess.canWrite(B, A)).isFalse();
    }

    @Test
    void noAssertedTenantIsATrustedCaller() {
        assertThat(TenantAccess.canRead(B, null)).isTrue();
        assertThat(TenantAccess.canWrite(B, null)).isTrue();
        assertThat(TenantAccess.canWrite(null, null)).isTrue();
    }

    @Test
    void searchVisibilityFollowsTheP38aRule() {
        assertThat(TenantAccess.visibleInSearch(A, A)).isTrue();
        assertThat(TenantAccess.visibleInSearch(null, A)).isTrue();
        assertThat(TenantAccess.visibleInSearch(B, A)).isFalse();
        // null search tenant = global only, NOT "trusted caller"
        assertThat(TenantAccess.visibleInSearch(null, null)).isTrue();
        assertThat(TenantAccess.visibleInSearch(A, null)).isFalse();
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn -B test -pl emcip-knowledge-engine -Dtest=TenantAccessTest -Djacoco.skip=true` → compilation error (class missing).

- [ ] **Step 3: Implement**

```java
package io.emcip.knowledge.engine.tenant;

import java.util.Objects;
import java.util.UUID;

/**
 * Ownership rules for tenant-owned knowledge items (ADR-009). {@code asserted} is the tenant the
 * caller asserted; {@code null} means a trusted caller (platform ADMIN in admin mode, or an
 * in-cluster service), which is unrestricted. {@code itemTenant == null} marks a global item.
 */
public final class TenantAccess {

    private TenantAccess() {}

    /** Own items and global items; never another tenant's. */
    public static boolean canRead(UUID itemTenant, UUID asserted) {
        return asserted == null || itemTenant == null || itemTenant.equals(asserted);
    }

    /** Own items only: a tenant must not change shared (global) knowledge. */
    public static boolean canWrite(UUID itemTenant, UUID asserted) {
        return asserted == null || Objects.equals(itemTenant, asserted);
    }

    /**
     * Knowledge search (P3.8a): a tenant sees own + global; a search without a tenant sees global
     * only. Unlike {@link #canRead}, a null search tenant is not a trusted caller.
     */
    public static boolean visibleInSearch(UUID itemTenant, UUID searchTenant) {
        return itemTenant == null || itemTenant.equals(searchTenant);
    }
}
```

- [ ] **Step 4: Run to verify it passes** — same command → 4/4 PASS.
- [ ] **Step 5: Commit** — `git add emcip-knowledge-engine && git commit -m "feat(knowledge-engine): TenantAccess ownership policy (KNOW-F1)"`

---

### Task 2: Research — ownership on id-addressed endpoints (knowledge-engine)

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/ResearchController.java` (`getSession`, `pauseSession`, `resumeSession`, `getReport`, `getReportMarkdown`)
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/ResearchTenantScopeTest.java`

**Interfaces:**
- Consumes: `TenantAccess.canRead/canWrite` (Task 1); entities `ResearchSession` (setters; `tenantId` NOT NULL, `question` NOT NULL), `ResearchReport` (setters: `tenantId`, `session`, `template`, `title`, `content`), enum `ReportTemplate.TOPIC`, enum `ResearchStatus`.
- Produces: each id-addressed endpoint gains `@RequestParam(required = false) UUID tenantId`.

- [ ] **Step 1: Write the failing test** (calls the controller bean directly — the logic under test is the controller's, the repositories are real):

```java
package io.emcip.knowledge.engine.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.ReportTemplate;
import io.emcip.knowledge.engine.entity.ResearchReport;
import io.emcip.knowledge.engine.entity.ResearchSession;
import io.emcip.knowledge.engine.entity.ResearchStatus;
import io.emcip.knowledge.engine.repository.ResearchReportRepository;
import io.emcip.knowledge.engine.repository.ResearchSessionRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/** KNOW-F1: research sessions are readable and changeable only by their own tenant. */
@IntegrationTest
class ResearchTenantScopeTest {

    @Autowired ResearchController controller;
    @Autowired ResearchSessionRepository sessions;
    @Autowired ResearchReportRepository reports;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    private ResearchSession session(UUID tenant, ResearchStatus status) {
        ResearchSession s = new ResearchSession();
        s.setTenantId(tenant);
        s.setQuestion("know-f1 probe");
        s.setStatus(status);
        return sessions.save(s);
    }

    @Test
    void anotherTenantsSessionIsNotFound() {
        ResearchSession own = session(tenantA, ResearchStatus.CREATED);
        ResearchSession other = session(tenantB, ResearchStatus.CREATED);

        assertThat(controller.getSession(own.getId(), tenantA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.getSession(other.getId(), tenantA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // no tenant asserted = trusted caller, unchanged
        assertThat(controller.getSession(other.getId(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anotherTenantCannotPauseASession() {
        ResearchSession other = session(tenantB, ResearchStatus.RUNNING);

        assertThat(controller.pauseSession(other.getId(), tenantA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(sessions.findById(other.getId()).orElseThrow().getStatus())
                .isEqualTo(ResearchStatus.RUNNING);
    }

    @Test
    void anotherTenantsReportIsNotFound() {
        ResearchSession other = session(tenantB, ResearchStatus.COMPLETED);
        ResearchReport r = new ResearchReport();
        r.setTenantId(tenantB);
        r.setSession(other);
        r.setTemplate(ReportTemplate.TOPIC);
        r.setTitle("t");
        r.setContent("secret findings");
        reports.save(r);

        assertThat(controller.getReport(other.getId(), tenantB).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.getReport(other.getId(), tenantA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.getReportMarkdown(other.getId(), tenantA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn -B test -pl emcip-knowledge-engine -Dtest=ResearchTenantScopeTest -Djacoco.skip=true` → compilation error (no 2-arg methods). Add only the `UUID tenantId` parameters (unused), rerun: the three `...NotFound` / pause assertions FAIL.

- [ ] **Step 3: Implement** — in `ResearchController` add imports `io.emcip.knowledge.engine.tenant.TenantAccess`, `java.util.Optional`, and:

```java
    /** KNOW-F1: an asserted tenant may read its own sessions only (sessions are never global). */
    private Optional<ResearchSession> readable(UUID id, UUID tenantId) {
        return sessionRepository
                .findById(id)
                .filter(s -> TenantAccess.canRead(s.getTenantId(), tenantId));
    }

    private Optional<ResearchSession> writable(UUID id, UUID tenantId) {
        return sessionRepository
                .findById(id)
                .filter(s -> TenantAccess.canWrite(s.getTenantId(), tenantId));
    }
```

Change the endpoints (each gains `@RequestParam(required = false) UUID tenantId`):

```java
    public ResponseEntity<ResearchSessionDto> getSession(
            @PathVariable UUID id, @RequestParam(required = false) UUID tenantId) {
        return readable(id, tenantId)
                .map(s -> ResponseEntity.ok(toDto(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    public ResponseEntity<ResearchSessionDto> pauseSession(
            @PathVariable UUID id, @RequestParam(required = false) UUID tenantId) {
        if (writable(id, tenantId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return agentService
                .pauseSession(id)
                .map(s -> ResponseEntity.ok(toDto(s)))
                .orElse(ResponseEntity.notFound().build());
    }
```

`resumeSession` exactly like `pauseSession` with `agentService.resumeSession(id)`. `getReport` and `getReportMarkdown`: first line `if (readable(id, tenantId).isEmpty()) { return ResponseEntity.notFound().build(); }`, then the existing body.

- [ ] **Step 4: Run to verify it passes** — 3/3 PASS.
- [ ] **Step 5: Commit** — `fix(knowledge-engine): research sessions are owner-only by id (KNOW-F1)`

---

### Task 3: Ingestion — list own + global, ownership by id, missing job = 404 (knowledge-engine)

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/IngestionJobRepository.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/DocumentIngestionService.java` (`listJobs`, ~line 142)
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/DocumentIngestionController.java` (`getJob`, `getJobDetails`, `deleteJob`, `reingestJob`)
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/IngestionTenantScopeTest.java`

**Interfaces:**
- Consumes: `TenantAccess` (Task 1); `IngestionJob` setters (`tenantId`, `sourceType` = `IngestionJob.SourceType.URL`, `sourceRef`, `status` = `IngestionJob.IngestionStatus.COMPLETED`); `DocumentIngestionService.getJob(UUID)` throws `IllegalArgumentException` when missing.
- Produces: `IngestionJobRepository.findAllByTenantIdOrTenantIdIsNullOrderByCreatedAtDesc(UUID, Pageable)`.

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.knowledge.engine.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.IngestionJob;
import io.emcip.knowledge.engine.repository.IngestionJobRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

/** KNOW-F1: ingestion jobs — read own + global, change own only, others (and missing) are 404. */
@IntegrationTest
class IngestionTenantScopeTest {

    @Autowired DocumentIngestionController controller;
    @Autowired IngestionJobRepository jobs;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    private IngestionJob job(UUID tenant) {
        IngestionJob j = new IngestionJob();
        j.setTenantId(tenant);
        j.setSourceType(IngestionJob.SourceType.URL);
        j.setSourceRef("https://know-f1.example/" + UUID.randomUUID());
        j.setStatus(IngestionJob.IngestionStatus.COMPLETED);
        return jobs.save(j);
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void readsOwnAndGlobalButNotAnotherTenants() {
        IngestionJob own = job(tenantA);
        IngestionJob global = job(null);
        IngestionJob other = job(tenantB);

        assertThat(controller.getJob(own.getId(), tenantA).jobId()).isEqualTo(own.getId().toString());
        assertThat(controller.getJob(global.getId(), tenantA).jobId())
                .isEqualTo(global.getId().toString());
        assertNotFound(() -> controller.getJob(other.getId(), tenantA));
        assertNotFound(() -> controller.getJobDetails(other.getId(), tenantA));
    }

    @Test
    void deletesOnlyOwnJobs() {
        IngestionJob global = job(null);
        IngestionJob other = job(tenantB);

        assertNotFound(() -> controller.deleteJob(other.getId(), tenantA));
        assertNotFound(() -> controller.deleteJob(global.getId(), tenantA));
        assertThat(jobs.findById(other.getId())).isPresent();
        assertThat(jobs.findById(global.getId())).isPresent();
    }

    @Test
    void cannotReingestAGlobalJob() {
        IngestionJob global = job(null);
        assertNotFound(() -> controller.reingestJob(global.getId(), tenantA));
    }

    @Test
    void aMissingJobIsNotFoundNotAServerError() {
        assertNotFound(() -> controller.getJob(UUID.randomUUID(), null));
    }

    @Test
    void listIsOwnPlusGlobal() {
        IngestionJob own = job(tenantA);
        IngestionJob global = job(null);
        IngestionJob other = job(tenantB);

        var ids =
                controller.listJobs(tenantA, PageRequest.of(0, 200)).getContent().stream()
                        .map(d -> d.jobId())
                        .toList();

        assertThat(ids)
                .contains(own.getId().toString(), global.getId().toString())
                .doesNotContain(other.getId().toString());
    }
}
```


- [ ] **Step 2: Run to verify it fails** — compilation error first; after adding only the `UUID tenantId` parameters, the denial, missing-job and list tests FAIL.

- [ ] **Step 3: Implement**

Repository — add:

```java
    /** KNOW-F1: a tenant sees its own jobs plus global ones. */
    Page<IngestionJob> findAllByTenantIdOrTenantIdIsNullOrderByCreatedAtDesc(
            UUID tenantId, Pageable pageable);
```

Service `listJobs`: replace `findAllByTenantIdOrderByCreatedAtDesc(tenantId, pageable)` with `findAllByTenantIdOrTenantIdIsNullOrderByCreatedAtDesc(tenantId, pageable)`.

Controller — add imports `io.emcip.knowledge.engine.tenant.TenantAccess`, `org.springframework.web.server.ResponseStatusException`, and:

```java
    /**
     * KNOW-F1: loads a job the caller may access. Missing and not-yours are the same 404, so ids
     * reveal nothing (a missing job was a 500 before).
     */
    private IngestionJob requireJob(UUID jobId, UUID tenantId, boolean write) {
        IngestionJob job;
        try {
            job = ingestionService.getJob(jobId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion job not found");
        }
        boolean allowed =
                write
                        ? TenantAccess.canWrite(job.getTenantId(), tenantId)
                        : TenantAccess.canRead(job.getTenantId(), tenantId);
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion job not found");
        }
        return job;
    }
```

Endpoints (each gains `@RequestParam(required = false) UUID tenantId`):
- `getJob`: `return IngestionJobDto.from(requireJob(jobId, tenantId, false));`
- `getJobDetails`: `requireJob(jobId, tenantId, false); return ingestionService.getJobDetails(jobId);`
- `deleteJob`: `requireJob(jobId, tenantId, true); ingestionService.deleteJob(jobId);`
- `reingestJob`: first line `requireJob(jobId, tenantId, true);`, rest unchanged.

- [ ] **Step 4: Run to verify it passes** — 5/5 PASS. Also run `mvn -B test -pl emcip-knowledge-engine -Dtest='*Ingestion*' -Djacoco.skip=true` so existing ingestion tests that call the old signatures are updated (pass `null` for the new parameter).
- [ ] **Step 5: Commit** — `fix(knowledge-engine): ingestion jobs are own+global to read, own to change; missing = 404 (KNOW-F1)`

---

### Task 4: Resolution review — list own + global, merge/dismiss own only (knowledge-engine)

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/ResolutionFlagRepository.java` (`findFiltered` query)
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResolutionReviewService.java` (`merge`, `dismiss`)
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/ResolutionReviewController.java` (`merge`, `dismiss`)
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResolutionReviewTenantScopeTest.java`

**Interfaces:**
- Consumes: `TenantAccess.canWrite`; `ResolutionFlag` (`@Data`; required: `candidateLabel`, `candidateNodeId`, `similarLabel`, `similarNodeId`, `conceptType`, `similarityScore`).
- Produces: `ResolutionReviewService.merge(UUID flagId, UUID tenantId)`, `dismiss(UUID flagId, UUID tenantId)`.

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.ResolutionFlag;
import io.emcip.knowledge.engine.repository.ResolutionFlagRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

/** KNOW-F1: resolution flags — list own + global, merge/dismiss own only. */
@IntegrationTest
class ResolutionReviewTenantScopeTest {

    @Autowired ResolutionReviewService service;
    @Autowired ResolutionFlagRepository flags;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final String conceptType = "KnowF1Probe" + UUID.randomUUID().toString().substring(0, 8);

    private ResolutionFlag flag(UUID tenant) {
        ResolutionFlag f = new ResolutionFlag();
        f.setCandidateLabel("cand");
        f.setCandidateNodeId(UUID.randomUUID());
        f.setSimilarLabel("sim");
        f.setSimilarNodeId(UUID.randomUUID());
        f.setConceptType(conceptType);
        f.setSimilarityScore(0.9);
        f.setTenantId(tenant);
        return flags.save(f);
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void listIsOwnPlusGlobalWithATenant() {
        ResolutionFlag own = flag(tenantA);
        ResolutionFlag global = flag(null);
        ResolutionFlag other = flag(tenantB);

        var ids =
                service.list(null, conceptType, tenantA, PageRequest.of(0, 50)).getContent().stream()
                        .map(ResolutionFlag::getId)
                        .toList();

        assertThat(ids).contains(own.getId(), global.getId()).doesNotContain(other.getId());
    }

    @Test
    void cannotDismissOrMergeAnotherTenantsOrAGlobalFlag() {
        ResolutionFlag other = flag(tenantB);
        ResolutionFlag global = flag(null);

        assertNotFound(() -> service.dismiss(other.getId(), tenantA));
        assertNotFound(() -> service.dismiss(global.getId(), tenantA));
        assertNotFound(() -> service.merge(other.getId(), tenantA));
        assertThat(flags.findById(other.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(flags.findById(global.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void canDismissOwnFlag() {
        ResolutionFlag own = flag(tenantA);

        service.dismiss(own.getId(), tenantA);

        assertThat(flags.findById(own.getId()).orElseThrow().getStatus()).isEqualTo("DISMISSED");
    }
}
```

- [ ] **Step 2: Run to verify it fails** — compilation error; after adding only the parameters, the list and denial tests FAIL; `canDismissOwnFlag` PASSES (guard).

- [ ] **Step 3: Implement**

Repository query — replace the tenant line with:

```java
              AND (:tenantId IS NULL OR f.tenantId = :tenantId OR f.tenantId IS NULL)
```

Service — `merge(UUID flagId)` → `merge(UUID flagId, UUID tenantId)`, `dismiss` likewise; directly after the existing `findById(...).orElseThrow(...)` lookup in each, add:

```java
        if (!TenantAccess.canWrite(flag.getTenantId(), tenantId)) {
            // Same 404 as a missing flag: another tenant's ids reveal nothing (KNOW-F1).
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resolution flag not found");
        }
```

(use the variable name the method already uses for the loaded flag; add import `io.emcip.knowledge.engine.tenant.TenantAccess`).

Controller:

```java
    public void merge(@PathVariable UUID id, @RequestParam(required = false) UUID tenantId) {
        service.merge(id, tenantId);
    }

    public void dismiss(@PathVariable UUID id, @RequestParam(required = false) UUID tenantId) {
        service.dismiss(id, tenantId);
    }
```

Update any other caller of `merge(UUID)` / `dismiss(UUID)` (`grep -rn "\.merge(\|\.dismiss(" emcip-knowledge-engine/src`) to pass `null`.

- [ ] **Step 4: Run to verify it passes** — 3/3 PASS; also rerun existing `*Resolution*` tests.
- [ ] **Step 5: Commit** — `fix(knowledge-engine): resolution flags list own+global, merge/dismiss own only (KNOW-F1)`

---

### Task 5: Graph neighbours and search expansion (knowledge-engine; absorbs KNOW-F2)

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/KnowledgeSearchController.java` (`getNeighbors`)
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/KnowledgeQueryService.java` (the two `graphRepository.findConnected(node.id(), null, 1)` calls, ~lines 76–79 and 90–93)
- Test: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/GraphNeighborsTenantScopeTest.java`, `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeQueryServiceTenantScopeTest.java`

**Interfaces:**
- Consumes: `TenantAccess.canRead`, `TenantAccess.visibleInSearch`; `GraphRepository.createNode(String, String, Map, UUID)`, `createRelationship(String, UUID, UUID, Map, UUID)`, `findNodeById(UUID)` → `Optional<GraphNode>`, `findConnected(UUID, String, int)`; `GraphNode.tenantId()`.
- Produces: `KnowledgeSearchController.getNeighbors(UUID id, String relationshipType, int depth, UUID tenantId)`; `KnowledgeQueryService.connectionsVisibleTo(UUID nodeId, UUID searchTenant)` (package-private).

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.knowledge.engine.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.repository.GraphRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** KNOW-F1 / KNOW-F2: graph traversal never reaches another tenant's nodes. */
@IntegrationTest
class GraphNeighborsTenantScopeTest {

    @Autowired KnowledgeSearchController controller;
    @Autowired GraphRepository graph;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @Test
    void neighboursOfOwnNodeExcludeAnotherTenantsNodes() {
        GraphNode start = graph.createNode("KnowF1Probe", "start", Map.of(), tenantA);
        GraphNode global = graph.createNode("KnowF1Probe", "global", Map.of(), null);
        GraphNode other = graph.createNode("KnowF1Probe", "other", Map.of(), tenantB);
        graph.createRelationship("RELATES_TO", start.id(), global.id(), Map.of(), null);
        graph.createRelationship("RELATES_TO", start.id(), other.id(), Map.of(), null);

        var ids = controller.getNeighbors(start.id(), null, 1, tenantA).stream().map(GraphNode::id).toList();

        assertThat(ids).contains(global.id()).doesNotContain(other.id());
    }

    @Test
    void neighboursOfAnotherTenantsNodeAreNotFound() {
        GraphNode other = graph.createNode("KnowF1Probe", "other-start", Map.of(), tenantB);

        assertThatThrownBy(() -> controller.getNeighbors(other.id(), null, 1, tenantA))
                .isInstanceOf(ResponseStatusException.class);
    }
}
```

Search expansion is tested in the service package, so `connectionsVisibleTo` stays package-private —
`emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/KnowledgeQueryServiceTenantScopeTest.java`:

```java
package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.repository.GraphRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** KNOW-F2: a search hit's connections follow the P3.8a search rule. */
@IntegrationTest
class KnowledgeQueryServiceTenantScopeTest {

    @Autowired KnowledgeQueryService queryService;
    @Autowired GraphRepository graph;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @Test
    void searchExpansionFollowsTheSearchRule() {
        GraphNode start = graph.createNode("KnowF1Probe", "s-start", Map.of(), null);
        GraphNode ownOfA = graph.createNode("KnowF1Probe", "s-a", Map.of(), tenantA);
        GraphNode ofB = graph.createNode("KnowF1Probe", "s-b", Map.of(), tenantB);
        graph.createRelationship("RELATES_TO", start.id(), ownOfA.id(), Map.of(), null);
        graph.createRelationship("RELATES_TO", start.id(), ofB.id(), Map.of(), null);

        var forA = queryService.connectionsVisibleTo(start.id(), tenantA).stream().map(GraphNode::id).toList();
        var forNoTenant = queryService.connectionsVisibleTo(start.id(), null).stream().map(GraphNode::id).toList();

        assertThat(forA).contains(ownOfA.id()).doesNotContain(ofB.id());
        assertThat(forNoTenant).doesNotContain(ownOfA.id(), ofB.id());
    }
}
```

- [ ] **Step 2: Run to verify it fails** — compilation error; after adding the parameter / an unfiltered `connectionsVisibleTo`, all three FAIL (B's node visible; no 404).

- [ ] **Step 3: Implement**

`KnowledgeSearchController.getNeighbors`:

```java
    public List<GraphNode> getNeighbors(
            @PathVariable UUID id,
            @Pattern(regexp = "[a-zA-Z_]{1,100}") @RequestParam(required = false)
                    String relationshipType,
            @RequestParam(defaultValue = "1") int depth,
            @RequestParam(required = false) UUID tenantId) {
        if (tenantId == null) {
            return graphRepository.findConnected(id, relationshipType, depth);
        }
        // KNOW-F1: the start node must be readable, and so must every neighbour returned.
        graphRepository
                .findNodeById(id)
                .filter(n -> TenantAccess.canRead(n.tenantId(), tenantId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Node not found"));
        return graphRepository.findConnected(id, relationshipType, depth).stream()
                .filter(n -> TenantAccess.canRead(n.tenantId(), tenantId))
                .toList();
    }
```

`KnowledgeQueryService`:

```java
    /** KNOW-F2: a hit's connections, filtered by the search tenant rule (P3.8a). */
    List<GraphNode> connectionsVisibleTo(UUID nodeId, UUID searchTenant) {
        return graphRepository.findConnected(nodeId, null, 1).stream()
                .filter(n -> TenantAccess.visibleInSearch(n.tenantId(), searchTenant))
                .toList();
    }
```

and replace both `graphRepository.findConnected(node.id(), null, 1)` expressions with `connectionsVisibleTo(node.id(), request.tenantId())`.

- [ ] **Step 4: Run to verify it passes** — 3/3 PASS. If `neighboursOfOwnNodeExcludeAnotherTenantsNodes` still sees B's node, `findConnected` is not populating `GraphNode.tenantId` — fix the mapping in `AgeGraphRepository.queryNodes`, do not weaken the test.
- [ ] **Step 5: Full module** — `mvn -B verify -pl emcip-knowledge-engine` → BUILD SUCCESS.
- [ ] **Step 6: Commit** — `fix(knowledge-engine): graph neighbours and search expansion respect tenants (KNOW-F1, KNOW-F2)`

---

### Task 6: admin-api — research and ingestion proxies assert the tenant, 404 passes through

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/KnowledgeTenantResolver.java` (add `path` helper)
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ResearchProxyController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/DocumentIngestionProxyController.java`
- Test: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/ResearchProxyControllerTest.java`, `DocumentIngestionProxyControllerTest.java`

**Interfaces:**
- Consumes: `KnowledgeTenantResolver.resolve(ContextView, String)` (P3.8a); `ReactorTenantContext.withTenant/withAdminMode` (tests).
- Produces: `KnowledgeTenantResolver.path(UriBuilder b, String path, String tenant, Object... vars)` → `URI`; both proxy constructors gain `ObjectMapper objectMapper` (tools.jackson) as last parameter.

- [ ] **Step 1: Write the failing tests** — `ResearchProxyControllerTest` (pattern of `KnowledgeSearchProxyControllerTest`):

```java
package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.tenant.ReactorTenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.context.Context;
import tools.jackson.databind.ObjectMapper;

/** KNOW-F1: research calls carry the caller's tenant; knowledge-engine 404 stays 404. */
class ResearchProxyControllerTest {

    private static final String TENANT_A = UUID.randomUUID().toString();
    private static final String TENANT_B = UUID.randomUUID().toString();
    private static final Context BOUND_A = ReactorTenantContext.withTenant(Context.empty(), TENANT_A);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer ke;
    private ResearchProxyController controller;

    @BeforeEach
    void setUp() throws Exception {
        ke = new MockWebServer();
        ke.start();
        controller =
                new ResearchProxyController(
                        WebClient.create(ke.url("/").toString()),
                        CircuitBreakerRegistry.ofDefaults(),
                        objectMapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        ke.close();
    }

    private void respond(int code, String body) {
        ke.enqueue(new MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build());
    }

    private RecordedRequest taken() throws Exception {
        return ke.takeRequest(5, TimeUnit.SECONDS);
    }

    @Test
    void startUsesTheBoundTenantNotTheBody() throws Exception {
        respond(201, "{}");
        controller.startResearch("{\"question\":\"q\",\"tenantId\":\"" + TENANT_B + "\"}")
                .contextWrite(BOUND_A).block();
        assertThat(objectMapper.readTree(taken().getBody().utf8()).path("tenantId").asString())
                .isEqualTo(TENANT_A);
    }

    @Test
    void listUsesTheBoundTenantNotTheQueryParameter() throws Exception {
        respond(200, "[]");
        controller.listSessions(UUID.fromString(TENANT_B)).contextWrite(BOUND_A).block();
        assertThat(taken().getTarget()).contains("tenantId=" + TENANT_A).doesNotContain(TENANT_B);
    }

    @Test
    void idAddressedCallsCarryTheBoundTenant() throws Exception {
        UUID id = UUID.randomUUID();
        respond(200, "{}");
        controller.getSession(id).contextWrite(BOUND_A).block();
        assertThat(taken().getTarget()).contains(id.toString()).contains("tenantId=" + TENANT_A);
        respond(200, "{}");
        controller.pauseSession(id).contextWrite(BOUND_A).block();
        assertThat(taken().getTarget()).contains("/pause").contains("tenantId=" + TENANT_A);
    }

    @Test
    void adminModeSendsNoTenantOnIdAddressedCalls() throws Exception {
        respond(200, "{}");
        controller.getSession(UUID.randomUUID())
                .contextWrite(ReactorTenantContext.withAdminMode(Context.empty())).block();
        assertThat(taken().getTarget()).doesNotContain("tenantId");
    }

    @Test
    void knowledgeEngine404StaysA404() {
        respond(404, "");
        var response = controller.getSession(UUID.randomUUID()).contextWrite(BOUND_A).block();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

`DocumentIngestionProxyControllerTest` — same setup (`new DocumentIngestionProxyController(webClient, registry, objectMapper)`), tests: `ingestUrlUsesTheBoundTenantNotTheBody` (body `{"url":"https://x","tenantId":"B"}` → forwarded `tenantId` = A), `listUsesTheBoundTenant` (`listJobs(UUID B, 0, 20)` → target has `tenantId=A`, not B), `deleteCarriesTheBoundTenant` (`deleteJob(id)` → target contains `tenantId=A`), `knowledgeEngine404StaysA404` (`getJobStatus(id)` with a 404 response → 404).

- [ ] **Step 2: Run to verify they fail** — `mvn -B test -pl emcip-admin-api -Dtest='ResearchProxyControllerTest,DocumentIngestionProxyControllerTest' -Djacoco.skip=true -Dskip.npm` → compilation error (3-arg constructors); after adding only the constructor parameter, every test except `adminModeSendsNoTenantOnIdAddressedCalls` FAILS.

- [ ] **Step 3: Implement**

`KnowledgeTenantResolver` — add:

```java
    /** Builds a knowledge-engine URI and appends {@code tenantId} when a tenant is asserted. */
    static java.net.URI path(
            org.springframework.web.util.UriBuilder b, String path, String tenant, Object... vars) {
        b.path(path);
        if (tenant != null) {
            b.queryParam("tenantId", tenant);
        }
        return b.build(vars);
    }
```

In **every** endpoint of both proxies:
1. Wrap the existing pipeline in `Mono.deferContextual(ctx -> { String tenant = KnowledgeTenantResolver.resolve(ctx, <requested>); ... })`, where `<requested>` is `null` for id-addressed calls and the caller's `tenantId` (`toString()`, or the body value) for creates and lists. Catch `IllegalArgumentException` from `resolve` → `ResponseEntity.badRequest().build()`.
2. Replace `.uri("/api/knowledge/research/{id}", id)`-style calls with `.uri(b -> KnowledgeTenantResolver.path(b, "/api/knowledge/research/{id}", tenant, id))` (same for `/pause`, `/resume`, `/report`, `/report/markdown`, and `/api/knowledge/ingest/{jobId}`, `/details`, `/reingest`, delete, list).
3. Creates (`startResearch`, `ingestUrl`): parse the body into an `ObjectNode` exactly as `KnowledgeSearchProxyController.search` does (non-object → 400), set or null `tenantId`, forward `objectMapper.writeValueAsString(node)`.
4. `ingestUpload`: `parts.add("tenantId", tenant)` only when `tenant != null`, using the resolved tenant.
5. Insert **before** each existing generic `onErrorResume`:

```java
                .onErrorResume(
                        WebClientResponseException.NotFound.class,
                        e -> Mono.just(ResponseEntity.notFound().<String>build()))
```

(`<Void>` for `deleteJob`; keep the 409 handling in the ingestion creates first.)

- [ ] **Step 4: Run to verify they pass**; then `mvn -B test -pl emcip-admin-api -Djacoco.skip=true -Dskip.npm` for the whole module.
- [ ] **Step 5: Force a guard red once** — make `resolve` return `requested` first (as in P3.8a); `startUsesTheBoundTenantNotTheBody` and `listUsesTheBoundTenant...` must FAIL; restore.
- [ ] **Step 6: Commit** — `fix(admin-api): research and ingestion proxies assert the caller's tenant; 404 passes through (KNOW-F1)`

---

### Task 7: admin-api — resolution review, graph neighbours, backfill

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ResolutionReviewProxyController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/KnowledgeSearchProxyController.java` (`getNeighbors`)
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/BackfillProxyController.java` (`triggerBackfill`)
- Test: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/ResolutionReviewProxyControllerTest.java`; extend `KnowledgeSearchProxyControllerTest`

**Interfaces:**
- Consumes: `KnowledgeTenantResolver.resolve`, `KnowledgeTenantResolver.path` (Task 6).
- Produces: `merge(UUID)` / `dismiss(UUID)` now return `Mono<ResponseEntity<Void>>` (204 on success, 404 passthrough); `@ResponseStatus(NO_CONTENT)` removed from both.

- [ ] **Step 1: Write the failing tests** — `ResolutionReviewProxyControllerTest` (setup as Task 6, constructor unchanged: `new ResolutionReviewProxyController(webClient, registry)`):
  - `listUsesTheBoundTenant`: `list(null, null, UUID B, 0, 20)` under `BOUND_A` → target contains `tenantId=A`, not B.
  - `mergeCarriesTheBoundTenantAndReturns204`: enqueue 204 → `merge(id)` under `BOUND_A` → target contains `tenantId=A`, response status 204.
  - `dismiss404StaysA404`: enqueue 404 → `dismiss(id)` → response status 404.

  Extend `KnowledgeSearchProxyControllerTest`: `neighborsCarryTheBoundTenant` → `getNeighbors(id, null, 1)` under `withTenant(A)` → target contains `tenantId=A`.

- [ ] **Step 2: Run to verify they fail.**

- [ ] **Step 3: Implement** — apply Task 6's steps 1, 2 and 5 to `list`, `merge`, `dismiss` and `getNeighbors`. `merge`/`dismiss`:

```java
    public Mono<ResponseEntity<Void>> merge(@PathVariable UUID id) {
        return Mono.deferContextual(
                        ctx ->
                                knowledgeWebClient
                                        .patch()
                                        .uri(b -> KnowledgeTenantResolver.path(
                                                b, "/api/resolution-review/{id}/merge",
                                                KnowledgeTenantResolver.resolve(ctx, null), id))
                                        .retrieve()
                                        .toBodilessEntity()
                                        .map(r -> ResponseEntity.noContent().<Void>build())
                                        .onErrorResume(
                                                WebClientResponseException.NotFound.class,
                                                e -> Mono.just(ResponseEntity.notFound().<Void>build())))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
```

`dismiss` identical with `/dismiss`. `BackfillProxyController.triggerBackfill`: replace the inline tenant block with `String tenantIdStr = KnowledgeTenantResolver.resolve(ctx, request.tenantId() == null ? null : request.tenantId().toString());` — behaviour unchanged (bound tenant wins; admin may choose).

- [ ] **Step 4: Run to verify they pass**; then the whole admin-api module, including `AdminApiBootIT` (wiring of the new constructor parameters).
- [ ] **Step 5: Commit** — `fix(admin-api): resolution review and graph neighbours assert the caller's tenant; backfill uses the shared resolver (KNOW-F1)`

---

### Task 8: Remove the dead servlet `TenantContextFilter` (emcip-core)

**Files:**
- Delete: `emcip-core/src/main/java/io/emcip/common/tenant/TenantContextFilter.java`
- Delete: `emcip-core/src/test/java/io/emcip/common/tenant/TenantContextFilterTest.java`

- [ ] **Step 1:** `grep -rn "new TenantContextFilter\|TenantContextFilter.class\|import io.emcip.common.tenant.TenantContextFilter" --include=*.java emcip-*/src` → only the two files above (verified 2026-09-25). If anything else appears, STOP and report.
- [ ] **Step 2:** `git rm` both files; `mvn -B install -pl emcip-core` → BUILD SUCCESS; `mvn -B -q compile -DskipTests` at root → no module breaks.
- [ ] **Step 3: Commit** — `refactor(core): remove TenantContextFilter — registered by no service, contradicts ADR-009 rule 5`

---

### Task 9: Admin UI — no change offers on global items for non-ADMIN, ingestion tenant picker ADMIN-only

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/auth/permissions.js` (add `canChangeKnowledgeItem`)
- Test: `emcip-admin-ui/src/main/frontend/src/auth/permissions.test.js` (create)
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/JobActions.jsx`, `JobActions.test.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/KnowledgePage.jsx` (job action column, ~line 236–266)
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Knowledge/IngestionModal.jsx` (tenant `<select>`, ~line 136–147); Test: `IngestionModal.test.jsx` (create)
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/ResolutionQueue/ResolutionQueue.jsx` (action buttons, ~line 140–166); Test: `ResolutionQueue.test.jsx` (create)
- **No change:** `Research/SessionDetailPage.jsx` — research sessions are never global, so pause/resume is always on the user's own session.

**Interfaces:**
- Produces: `canChangeKnowledgeItem(role, itemTenantId)` → `role === 'ADMIN' || itemTenantId != null`; `JobActions({ row, canChange, onDelete, onReingest })`.

- [ ] **Step 1: Write the failing tests**

`permissions.test.js`:

```js
import { describe, it, expect } from 'vitest'
import { canChangeKnowledgeItem } from './permissions'

describe('canChangeKnowledgeItem', () => {
  it('lets ADMIN change global items', () => {
    expect(canChangeKnowledgeItem('ADMIN', null)).toBe(true)
  })
  it('does not let tenant roles change global items', () => {
    expect(canChangeKnowledgeItem('TENANT_ADMIN', null)).toBe(false)
    expect(canChangeKnowledgeItem('MODERATOR', undefined)).toBe(false)
  })
  it('lets tenant roles change tenant-owned items', () => {
    expect(canChangeKnowledgeItem('TENANT_ADMIN', 'a-tenant-id')).toBe(true)
  })
})
```

`JobActions.test.jsx`:

```jsx
import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { JobActions } from './JobActions'

const row = { jobId: 'j1', rawStatus: 'COMPLETED', rawTenantId: null }

describe('JobActions', () => {
  it('offers delete and re-ingest when the item can be changed', () => {
    render(<JobActions row={row} canChange onDelete={vi.fn()} onReingest={vi.fn()} />)
    expect(screen.getByTitle('Delete')).toBeInTheDocument()
    expect(screen.getByTitle('Re-ingest')).toBeInTheDocument()
  })
  it('offers no change and explains why on a global item', () => {
    render(<JobActions row={row} canChange={false} onDelete={vi.fn()} onReingest={vi.fn()} />)
    expect(screen.queryByTitle('Delete')).not.toBeInTheDocument()
    expect(screen.queryByTitle('Re-ingest')).not.toBeInTheDocument()
    expect(screen.getByTitle(/managed by a platform admin/i)).toBeInTheDocument()
  })
})
```

`ResolutionQueue.test.jsx`:

```jsx
import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'

let mockRole = 'MODERATOR'
vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ role: mockRole }),
  useAuthRequest: () => vi.fn(),
}))
vi.mock('../../api/resolutionReview', () => ({
  resolutionReviewApi: () => ({
    list: () => Promise.resolve({
      content: [{ id: 'f1', candidateLabel: 'c', similarLabel: 's', conceptType: 'Topic',
                  similarityScore: 0.9, status: 'PENDING', tenantId: null }],
      totalElements: 1,
    }),
    merge: vi.fn(), dismiss: vi.fn(),
  }),
}))

import { ResolutionQueue } from './ResolutionQueue'

describe('ResolutionQueue on a global flag', () => {
  it('offers no merge/dismiss to a tenant role', async () => {
    mockRole = 'MODERATOR'
    render(<ResolutionQueue />)
    await waitFor(() => expect(screen.getByTitle(/managed by a platform admin/i)).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Merge' })).not.toBeInTheDocument()
  })
  it('offers merge/dismiss to ADMIN', async () => {
    mockRole = 'ADMIN'
    render(<ResolutionQueue />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Merge' })).toBeInTheDocument())
  })
})
```


`IngestionModal.test.jsx`:

```jsx
import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import ToastProvider from '../../components/Toast/ToastProvider'

let mockRole = 'TENANT_ADMIN'
vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ role: mockRole }) }))

import { IngestionModal } from './IngestionModal'

const api = { warmUp: () => Promise.resolve({}) }
const tenants = [{ id: 't1', name: 'Tenant One' }]

function renderModal() {
  return render(
    <ToastProvider>
      <IngestionModal api={api} tenants={tenants} onClose={vi.fn()} onJobCreated={vi.fn()} />
    </ToastProvider>
  )
}

describe('IngestionModal tenant choice', () => {
  it('is not offered to tenant roles', () => {
    mockRole = 'TENANT_ADMIN'
    renderModal()
    expect(screen.queryByRole('option', { name: /Global/ })).not.toBeInTheDocument()
    expect(screen.getByText(/ingested into your tenant/i)).toBeInTheDocument()
  })
  it('is offered to ADMIN', () => {
    mockRole = 'ADMIN'
    renderModal()
    expect(screen.getByRole('option', { name: /Global/ })).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run to verify they fail** — from `emcip-admin-ui/src/main/frontend`: `npx vitest run src/auth/permissions.test.js src/pages/Knowledge/JobActions.test.jsx src/pages/Knowledge/IngestionModal.test.jsx src/pages/ResolutionQueue/ResolutionQueue.test.jsx`.

- [ ] **Step 3: Implement**

`permissions.js`:

```js
/**
 * KNOW-F1 / ADR-009: global knowledge items (no tenant) may be changed only by a platform ADMIN.
 * The backend enforces this; the UI just stops offering what would be refused.
 */
export function canChangeKnowledgeItem(role, itemTenantId) {
  return role === 'ADMIN' || itemTenantId != null
}
```

`JobActions.jsx` — the existing action markup from `KnowledgePage` moved into a component:

```jsx
import styles from './KnowledgePage.module.css'

export function JobActions({ row, canChange, onDelete, onReingest }) {
  if (!canChange) {
    return (
      <span className={styles.actionBtns} title="Global — managed by a platform admin">
        Global
      </span>
    )
  }
  return (
    <span className={styles.actionBtns} onClick={e => e.stopPropagation()}>
      <button type="button" className={styles.actionBtn} title="Delete" onClick={() => onDelete(row)}>
        {'✕'}
      </button>
      {(row.rawStatus === 'COMPLETED' || row.rawStatus === 'FAILED') && (
        <button type="button" className={styles.actionBtn} title="Re-ingest" onClick={() => onReingest(row)}>
          {'↻'}
        </button>
      )}
    </span>
  )
}
```

`KnowledgePage.jsx`: `const { token, role } = useAuth()`; the `_actions` column `render` becomes `(_, row) => <JobActions row={row} canChange={canChangeKnowledgeItem(role, row.rawTenantId)} onDelete={setConfirmDelete} onReingest={setReingestJob} />`, and the `useMemo` dependency list becomes `[role]`.

`IngestionModal.jsx`: import `useAuth`; `const { role } = useAuth()`; render the tenant `<select>` only when `role === 'ADMIN'`, otherwise `<p className={styles.warmUpStatus}>Documents are ingested into your tenant.</p>`.

`ResolutionQueue.jsx`: `const { role } = useAuth()` (import `useAuth` beside `useAuthRequest`); inside the row, if `!canChangeKnowledgeItem(role, flag.tenantId)` render `<span className={styles.actions} title="Global — managed by a platform admin">Global</span>` instead of the two buttons.

- [ ] **Step 4: Run to verify they pass**, then the whole UI suite: `npx vitest run` → all green.
- [ ] **Step 5: Commit** — `feat(admin-ui): no change actions on global knowledge items for tenant roles; ingestion tenant picker ADMIN-only (KNOW-F1)`

---

### Task 10: ADR-009 — Multi-tenancy

**Files:**
- Create: `documentation/adrs/ADR-009-multi-tenancy.md`
- Modify: `documentation/architecture-guide.adoc` (ADR summaries, after `== ADR-008`, ~line 523)

- [ ] **Step 1: Write the ADR** in ADR-008's format (`# ADR-009: …`, `## Status` **Accepted**, Date 2026-09-25, `## Context`, `## Decision`, `## Consequences`, `## Alternatives considered`). `## Decision` lists spec §4 rules 1–10 verbatim in substance; `## Context` states the defects that forced each rule (P3.8a search, PROMPT-TENANT, KNOW-F1 IDOR); `## Alternatives considered` names: reject null tenants everywhere (rejected: breaks trusted callers, no way to ask for global), null = all tenants in search (rejected: fail-open), ownership checked only at the admin-api edge (rejected: two calls, rule far from the data), a Hibernate filter for id lookups (rejected: filters do not apply to `findById`).
- [ ] **Step 2:** Add an `== ADR-009: Multi-Tenancy` summary block to the architecture guide in the same shape as ADR-008's (*Status*, *Decision*, *Context*, *Alternatives rejected*), linking the ADR file.
- [ ] **Step 3: Commit** — `docs(adr): ADR-009 multi-tenancy (3.14)`

---

### Task 11: Documentation and diagrams (spec §8)

**Files:** exactly the table in spec §8.

- [ ] **Step 1: `sequence-tenant-propagation.puml`** — redraw with two sections. HTTP: `Admin UI → AdminTenantContextFilter (WebFlux)` binds JWT tenant / `X-Tenant-Id` (ADMIN) into the Reactor context → `KnowledgeTenantResolver` → proxy adds `tenantId` → knowledge-engine controller → `TenantAccess` → 200 or 404. Kafka: producer sets `tenant_id` header → `TenantAwareKafkaSupport.validateTenantHeader` → `TenantContext` → `TenantFilterAspect` enables Hibernate `tenantFilter`. Remove `TenantContextFilter`. Keep the file's existing theme/include lines.
- [ ] **Step 2: `c3-knowledge-engine.puml`** — replace the single `KnowledgeController` with `KnowledgeSearchController`, `ResearchController`, `DocumentIngestionController`, `ResolutionReviewController`, `OntologyController`, `BackfillController`; add `Component(tenant_access, "TenantAccess", "Policy", "read own+global / write own / 404")` with `Rel`s from the four tenant-checking controllers; change `KnowledgeQueryService`'s "Tenant isolation" text to "Tenant rule: own + global (P3.8a)".
- [ ] **Step 3: `c3-admin-api.puml`** — add knowledge proxies (`KnowledgeSearchProxyController`, `ResearchProxyController`, `DocumentIngestionProxyController`, `ResolutionReviewProxyController`, `BackfillProxyController`) and `KnowledgeTenantResolver`, with `Rel(tenant_filter, knowledge_resolver, …)` and `Rel(proxies, knowledge_engine, "tenantId asserted", "REST")`.
- [ ] **Step 4: `c3-llm-orchestrator.puml`** — `PolicyDecisionConsumer` description: "RESPOND/ESCALATE/EXECUTE → LLM only if emcip.llm.automated-responses.enabled (default off)"; `LlmOrchestratorService`: "system templates global"; `CostTrackingService` present with "per-tenant cost log".
- [ ] **Step 5: `sequence-llm-orchestration.puml`** — before `callForTask`, an `alt automated responses disabled` branch that logs and returns; the template lookup note: "tenantFilter: tenant_id = :t OR system = true".
- [ ] **Step 6: `architecture-guide.adoc`** — knowledge-engine: one paragraph on the enforcement model (asserted tenant enforced, `TenantAccess`, 404) after the P3.8a paragraph; admin-api: knowledge proxies bind the tenant via `KnowledgeTenantResolver`.
- [ ] **Step 7: `developer-guide.adoc`** — Admin API Endpoints Summary: add rows (same `[cols="1,2,2"]` format) for `POST /api/admin/knowledge/search`, `GET /api/admin/knowledge/graph/node/{id}/neighbors`, `POST|GET /api/admin/knowledge/research`, `GET /api/admin/knowledge/research/{id}` (+ pause/resume/report), `POST /api/admin/knowledge/ingest/url|upload`, `GET|DELETE /api/admin/knowledge/ingest/{jobId}` (+ details/reingest), `GET /api/resolution-review`, `PATCH /api/resolution-review/{id}/merge|dismiss`; Description column states the tenant rule; Auth column the permission (`KNOWLEDGE_READ`, `KNOWLEDGE_WRITE`, `RESOLUTION_REVIEW_READ/WRITE`).
- [ ] **Step 8: `user-guide.adoc`** — new section `=== Knowledge, Research and Resolution Review` (after `=== AI Config`): what tenant users see (own + global), that global items show "Global" instead of actions, that ingestion goes into their own tenant, that research sessions always belong to a tenant.
- [ ] **Step 9: Render and read the log** — `mvn -B -N generate-resources 2>&1 | tee /tmp/docs.log | tail -5`, then `grep -iE "error|warn.*plantuml|syntax" /tmp/docs.log` → no diagram errors (the plugin has no `failIf`; the log is the only evidence). Open the generated images for the five diagrams under `target/generated-docs` and confirm they rendered (non-error images).
- [ ] **Step 10: Commit** — `docs: tenancy diagrams and guides in line with ADR-009 (incl. #255/#256 catch-up)`

---

### Task 12: Tracking, verification, PR

- [ ] **Step 1: BACKLOG** — KNOW-F1 ✅ (premise correction: four areas incl. IDOR, research never global, 404 passthrough); KNOW-F2 ✅ (closed into KNOW-F1); add **TENANT-AUDIT** (P3, other admin-api proxies vs ADR-009) and **DOCS-FAILIF** (P4, make diagram/Asciidoctor errors fail the build); move the "Next" pointer to 3.14 (ADR-010/011).
- [ ] **Step 2: ROADMAP** — 3.14: ADR-009 ✅, ADR-010 gains "internal service authentication (knowledge-engine, Kafka / RT-005)"; 3.20: add "NetworkPolicy — knowledge-engine ingress limited to admin-api and llm-orchestrator".
- [ ] **Step 3: Verify** — `mvn -B verify -pl emcip-core,emcip-knowledge-engine,emcip-admin-api,emcip-admin-ui` → BUILD SUCCESS (admin-ui's build runs vitest); `mvn -B pmd:check -pl emcip-core,emcip-knowledge-engine,emcip-admin-api`; `mvn -B spotless:check` on the same modules.
- [ ] **Step 4: Commit tracking docs; push; open the PR** — body: premise correction (four areas, IDOR), trust model, 404 passthrough, UI change, ADR-009, docs catch-up, out of scope (spec §10), verification numbers, and the note that the PR is larger by design (one tenancy model, recorded once).
