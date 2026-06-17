# #43 Entity Resolution Review UI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Resolution Queue" page to the admin UI that lets operators merge or dismiss borderline entity-deduplication flags produced by the 26.5 embedding similarity pipeline.

**Architecture:** Three independent layers — (1) knowledge-engine REST API (`ResolutionReviewController` + `ResolutionReviewService` + `mergeNodes()` in `AgeGraphRepository`); (2) admin-api proxy (`ResolutionReviewProxyController` + new `knowledgeWebClient` bean); (3) admin-ui page (`ResolutionQueue.jsx` + `resolutionReview.js` API module). Each layer can be tested independently.

**Tech Stack:** Spring Boot 4, JPA (`ResolutionFlagRepository`), JDBC/AGE (`AgeGraphRepository`), Spring WebFlux (admin-api proxy), Resilience4j circuit-breaker, React 18, CSS Modules.

**Spec:** `docs/superpowers/specs/2026-06-17-entity-resolution-review-ui-design.md`

---

## File Map

| File | Change |
|------|--------|
| `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/ResolutionFlagRepository.java` | Add `findFiltered()` JPQL query |
| `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/GraphRepository.java` | Add `mergeNodes()` to interface |
| `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/AgeGraphRepository.java` | Implement `mergeNodes()` + private `queryEdges()` helper |
| `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResolutionReviewService.java` | New: list/merge/dismiss logic |
| `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/ResolutionReviewController.java` | New: GET/PATCH endpoints |
| `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResolutionReviewServiceTest.java` | New: 5 unit tests |
| `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/ResolutionReviewIntegrationTest.java` | New: 3 integration tests |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/config/WebClientConfig.java` | Add `knowledgeWebClient` bean |
| `emcip-admin-api/src/main/resources/application.yml` | Add `service.knowledge.url` + resilience4j `knowledge` instances |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ResolutionReviewProxyController.java` | New: proxy controller |
| `emcip-admin-ui/src/main/frontend/src/auth/permissions.js` | Add `RESOLUTION_REVIEW_READ/WRITE` |
| `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx` | Add nav entry |
| `emcip-admin-ui/src/main/frontend/src/App.jsx` | Add route |
| `emcip-admin-ui/src/main/frontend/src/api/resolutionReview.js` | New: API module |
| `emcip-admin-ui/src/main/frontend/src/pages/ResolutionQueue/ResolutionQueue.jsx` | New: page component |
| `emcip-admin-ui/src/main/frontend/src/pages/ResolutionQueue/ResolutionQueue.module.css` | New: page styles |

---

## Task 1: `ResolutionFlagRepository` filtered query + `GraphRepository.mergeNodes()` interface

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/ResolutionFlagRepository.java`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/GraphRepository.java`

No test-first for interface additions — compile verification via the test suite run is sufficient.

- [ ] **Step 1: Add `findFiltered()` to `ResolutionFlagRepository`**

The current file has no custom methods. Add the following (full file content):

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.ResolutionFlag;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ResolutionFlagRepository extends JpaRepository<ResolutionFlag, UUID> {

    @Query("""
            SELECT f FROM ResolutionFlag f
            WHERE (:status IS NULL OR f.status = :status)
              AND (:conceptType IS NULL OR f.conceptType = :conceptType)
              AND (:tenantId IS NULL OR f.tenantId = :tenantId)
            ORDER BY f.createdAt DESC
            """)
    Page<ResolutionFlag> findFiltered(
            @Param("status") String status,
            @Param("conceptType") String conceptType,
            @Param("tenantId") UUID tenantId,
            Pageable pageable);
}
```

- [ ] **Step 2: Add `mergeNodes()` to `GraphRepository` interface**

Full file content of `GraphRepository.java`:

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.model.GraphEdge;
import io.emcip.knowledge.engine.model.GraphNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface GraphRepository {

    GraphNode createNode(
            String conceptType, String label, Map<String, Object> properties, UUID tenantId);

    GraphEdge createRelationship(
            String relationshipType,
            UUID sourceNodeId,
            UUID targetNodeId,
            Map<String, Object> properties,
            UUID sourceMessageId);

    List<GraphNode> findConnected(UUID nodeId, String relationshipType, int depth);

    Optional<GraphNode> findByLabelAndType(String label, String conceptType, UUID tenantId);

    List<GraphNode> findNodesByType(String conceptType, UUID tenantId, int limit);

    /**
     * Reroutes all edges from candidateNodeId to targetNodeId in the AGE graph, then deletes the
     * candidate node. Throws RuntimeException on any failure (triggers rollback at service layer).
     */
    void mergeNodes(UUID candidateNodeId, UUID targetNodeId);
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-knowledge-engine -am 2>&1 | tail -10
```

Expected: `BUILD SUCCESS` (existing tests pass; `AgeGraphRepository` now has a compile error on the unimplemented method — this is expected and will be fixed in Task 2).

If the build fails with "does not override abstract method `mergeNodes`" that is fine — it means the interface is wired. The compile error is fixed in Task 2.

- [ ] **Step 4: Commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-knowledge-engine
git add \
  emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/ResolutionFlagRepository.java \
  emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/GraphRepository.java
git commit -m "feat(knowledge): ResolutionFlagRepository.findFiltered + GraphRepository.mergeNodes interface"
```

---

## Task 2: `AgeGraphRepository.mergeNodes()` + `queryEdges()` helper

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/AgeGraphRepository.java`

The existing `AgeGraphRepository` has:
- `executeCypher(String cypher)` — executes a Cypher statement, throws `RuntimeException` on error
- `queryNodes(String cypher)` — executes Cypher, returns `List<GraphNode>`
- `parseNodeFromAgtype(Object agtype)` — parses AGE agtype string into `GraphNode`
- `parseAgtypeProperties(String agtype)` — parses agtype JSON into `Map<String, Object>`
- `buildEdgePropertiesJson(UUID, Map, UUID, Instant)` — builds Cypher property JSON for edges
- `createRelationship(...)` — creates an edge in AGE

AGE 1.5.0 (targeting PG16) does not support dynamic relationship types in a `CREATE` clause. Edge rerouting is done at the Java level: query edges → recreate them → delete candidate node.

No unit test is possible for AGE operations (AGE not in the test image). The `mergeNodes()` correctness is validated by the service-layer unit test which mocks `GraphRepository`, and a smoke test in the integration test class (which also mocks `GraphRepository`). The Cypher queries will be validated manually on a real deployment.

- [ ] **Step 1: Add `queryEdges()` helper and `mergeNodes()` to `AgeGraphRepository`**

Add these two methods to `AgeGraphRepository.java` **before** the existing private helper methods (i.e. before `executeCypher()`). Also add these imports if not already present:

```java
import io.emcip.knowledge.engine.model.GraphEdge;
import java.time.Instant;
import java.util.ArrayList;
```

Methods to add:

```java
@Override
public void mergeNodes(UUID candidateNodeId, UUID targetNodeId) {
    // Step 1: collect outgoing edges from candidate (excluding edges to target)
    List<GraphEdge> outgoing =
            queryEdges(
                    String.format(
                            "MATCH (c {node_id: '%s'})-[r]->(n)"
                                    + " WHERE NOT n.node_id = '%s' RETURN r",
                            candidateNodeId, targetNodeId));

    // Step 2: collect incoming edges to candidate (excluding edges from target)
    List<GraphEdge> incoming =
            queryEdges(
                    String.format(
                            "MATCH (n)-[r]->(c {node_id: '%s'})"
                                    + " WHERE NOT n.node_id = '%s' RETURN r",
                            candidateNodeId, targetNodeId));

    // Step 3: recreate outgoing edges from target
    for (GraphEdge e : outgoing) {
        createRelationship(
                e.relationshipType(),
                targetNodeId,
                e.targetNodeId(),
                e.properties(),
                e.sourceMessageId());
    }

    // Step 4: recreate incoming edges to target
    for (GraphEdge e : incoming) {
        createRelationship(
                e.relationshipType(),
                e.sourceNodeId(),
                targetNodeId,
                e.properties(),
                e.sourceMessageId());
    }

    // Step 5: delete candidate node (DETACH removes any remaining self-edges)
    executeCypher(
            String.format(
                    "MATCH (c {node_id: '%s'}) DETACH DELETE c", candidateNodeId));

    log.info(
            "Merged AGE node {} into {}: {} outgoing + {} incoming edges rerouted",
            candidateNodeId,
            targetNodeId,
            outgoing.size(),
            incoming.size());
}

private List<GraphEdge> queryEdges(String cypher) {
    String sql =
            String.format(
                    "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (result"
                            + " ag_catalog.agtype)",
                    GRAPH_NAME, cypher);
    try {
        jdbcTemplate.execute("SET search_path = ag_catalog, \"$user\", public");
        jdbcTemplate.execute("LOAD 'age'");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        List<GraphEdge> edges = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            GraphEdge edge = parseEdgeFromAgtype(row.get("result"));
            if (edge != null) edges.add(edge);
        }
        return edges;
    } catch (Exception e) {
        log.error("AGE edge query failed: {}", e.getMessage(), e);
        return List.of();
    }
}

private GraphEdge parseEdgeFromAgtype(Object agtype) {
    if (agtype == null) return null;
    String str = agtype.toString();
    try {
        Map<String, Object> props = parseAgtypeProperties(str);
        UUID edgeId =
                props.containsKey("edge_id")
                        ? UUID.fromString((String) props.get("edge_id"))
                        : UUID.randomUUID();
        UUID sourceNodeId =
                props.containsKey("source_node_id")
                        ? UUID.fromString((String) props.get("source_node_id"))
                        : null;
        UUID targetNodeId =
                props.containsKey("target_node_id")
                        ? UUID.fromString((String) props.get("target_node_id"))
                        : null;
        UUID sourceMessageId =
                props.containsKey("source_message_id")
                        ? UUID.fromString((String) props.get("source_message_id"))
                        : null;
        String relType = (String) props.getOrDefault("relationship_type", "RELATED");
        Map<String, Object> edgeProps = new java.util.HashMap<>(props);
        edgeProps.remove("edge_id");
        edgeProps.remove("source_node_id");
        edgeProps.remove("target_node_id");
        edgeProps.remove("source_message_id");
        edgeProps.remove("relationship_type");
        edgeProps.remove("created_at");
        return new GraphEdge(edgeId, relType, sourceNodeId, targetNodeId,
                edgeProps, sourceMessageId, Instant.now());
    } catch (Exception e) {
        log.warn("Failed to parse agtype edge: {}", str, e);
        return null;
    }
}
```

- [ ] **Step 2: Run tests to verify compilation**

```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-knowledge-engine -am 2>&1 | tail -10
```

Expected: `BUILD SUCCESS` (all 29 existing tests pass).

- [ ] **Step 3: Apply Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/repository/AgeGraphRepository.java
git commit -m "feat(knowledge): AgeGraphRepository.mergeNodes — Java-level edge rerouting via queryEdges"
```

---

## Task 3: `ResolutionReviewService` + `ResolutionReviewController` with unit tests

**Files:**
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResolutionReviewService.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/ResolutionReviewController.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResolutionReviewServiceTest.java`

- [ ] **Step 1: Write 5 failing unit tests**

Create `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResolutionReviewServiceTest.java`:

```java
package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.entity.ResolutionFlag;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.ResolutionFlagRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ResolutionReviewServiceTest {

    @Mock private ResolutionFlagRepository flagRepository;
    @Mock private GraphRepository graphRepository;

    private ResolutionReviewService service;

    @BeforeEach
    void setUp() {
        service = new ResolutionReviewService(flagRepository, graphRepository);
    }

    private ResolutionFlag pendingFlag(UUID id, UUID candidateId, UUID similarId) {
        ResolutionFlag f = new ResolutionFlag();
        f.setId(id);
        f.setCandidateNodeId(candidateId);
        f.setSimilarNodeId(similarId);
        f.setCandidateLabel("AI");
        f.setSimilarLabel("artificial intelligence");
        f.setConceptType("TOPIC");
        f.setSimilarityScore(0.85);
        f.setStatus("PENDING");
        f.setCreatedAt(Instant.now());
        return f;
    }

    @Test
    void merge_happyPath_callsMergeNodesAndSetsStatusMerged() {
        UUID flagId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID similarId = UUID.randomUUID();
        ResolutionFlag flag = pendingFlag(flagId, candidateId, similarId);

        when(flagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(flagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.merge(flagId);

        verify(graphRepository).mergeNodes(candidateId, similarId);
        verify(flagRepository).save(flag);
        assertThat(flag.getStatus()).isEqualTo("MERGED");
    }

    @Test
    void merge_nonPendingFlag_throwsConflict() {
        UUID flagId = UUID.randomUUID();
        ResolutionFlag flag = pendingFlag(flagId, UUID.randomUUID(), UUID.randomUUID());
        flag.setStatus("DISMISSED");

        when(flagRepository.findById(flagId)).thenReturn(Optional.of(flag));

        assertThatThrownBy(() -> service.merge(flagId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("PENDING");

        verify(graphRepository, never()).mergeNodes(any(), any());
    }

    @Test
    void merge_graphThrows_flagUnchanged() {
        UUID flagId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID similarId = UUID.randomUUID();
        ResolutionFlag flag = pendingFlag(flagId, candidateId, similarId);

        when(flagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        org.mockito.Mockito.doThrow(new RuntimeException("AGE error"))
                .when(graphRepository).mergeNodes(candidateId, similarId);

        assertThatThrownBy(() -> service.merge(flagId))
                .isInstanceOf(RuntimeException.class);

        // Flag save must NOT have been called — transaction rolled back
        verify(flagRepository, never()).save(any());
        assertThat(flag.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void dismiss_happyPath_setsStatusDismissed() {
        UUID flagId = UUID.randomUUID();
        ResolutionFlag flag = pendingFlag(flagId, UUID.randomUUID(), UUID.randomUUID());

        when(flagRepository.findById(flagId)).thenReturn(Optional.of(flag));
        when(flagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.dismiss(flagId);

        verify(flagRepository).save(flag);
        assertThat(flag.getStatus()).isEqualTo("DISMISSED");
        verify(graphRepository, never()).mergeNodes(any(), any());
    }

    @Test
    void dismiss_nonPendingFlag_throwsConflict() {
        UUID flagId = UUID.randomUUID();
        ResolutionFlag flag = pendingFlag(flagId, UUID.randomUUID(), UUID.randomUUID());
        flag.setStatus("MERGED");

        when(flagRepository.findById(flagId)).thenReturn(Optional.of(flag));

        assertThatThrownBy(() -> service.dismiss(flagId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("PENDING");
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-knowledge-engine -Dtest=ResolutionReviewServiceTest -am 2>&1 | tail -10
```

Expected: FAIL — `ResolutionReviewService` does not exist yet.

- [ ] **Step 3: Create `ResolutionReviewService`**

Create `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResolutionReviewService.java`:

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.entity.ResolutionFlag;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.ResolutionFlagRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResolutionReviewService {

    private final ResolutionFlagRepository flagRepository;
    private final GraphRepository graphRepository;

    public Page<ResolutionFlag> list(
            String status, String conceptType, UUID tenantId, Pageable pageable) {
        return flagRepository.findFiltered(status, conceptType, tenantId, pageable);
    }

    @Transactional(rollbackFor = Exception.class)
    public void merge(UUID flagId) {
        ResolutionFlag flag =
                flagRepository
                        .findById(flagId)
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                        "Flag not found: " + flagId));
        if (!"PENDING".equals(flag.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Flag is not PENDING: " + flag.getStatus());
        }
        // Graph operation first — throws propagate to trigger rollback before flag update
        graphRepository.mergeNodes(flag.getCandidateNodeId(), flag.getSimilarNodeId());
        flag.setStatus("MERGED");
        flagRepository.save(flag);
        log.info(
                "Merged node {} into {} (flag={})",
                flag.getCandidateNodeId(),
                flag.getSimilarNodeId(),
                flagId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void dismiss(UUID flagId) {
        ResolutionFlag flag =
                flagRepository
                        .findById(flagId)
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                        "Flag not found: " + flagId));
        if (!"PENDING".equals(flag.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Flag is not PENDING: " + flag.getStatus());
        }
        flag.setStatus("DISMISSED");
        flagRepository.save(flag);
        log.info("Dismissed resolution flag {}", flagId);
    }
}
```

- [ ] **Step 4: Create `ResolutionReviewController`**

Create `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/ResolutionReviewController.java`:

```java
package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.entity.ResolutionFlag;
import io.emcip.knowledge.engine.service.ResolutionReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Resolution Review", description = "Operator review queue for entity resolution flags")
@RestController
@RequestMapping("/api/resolution-review")
@RequiredArgsConstructor
@Slf4j
public class ResolutionReviewController {

    private final ResolutionReviewService service;

    @Operation(summary = "List resolution flags with optional filters")
    @GetMapping
    public Page<ResolutionFlag> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String conceptType,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(
                status,
                conceptType,
                tenantId,
                PageRequest.of(page, Math.min(size, 200), Sort.by("createdAt").descending()));
    }

    @Operation(summary = "Merge candidate node into similar node and mark flag MERGED")
    @PatchMapping("/{id}/merge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void merge(@PathVariable UUID id) {
        service.merge(id);
    }

    @Operation(summary = "Dismiss flag without graph changes, mark flag DISMISSED")
    @PatchMapping("/{id}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@PathVariable UUID id) {
        service.dismiss(id);
    }
}
```

- [ ] **Step 5: Run all tests**

```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-knowledge-engine -am 2>&1 | tail -10
```

Expected: `BUILD SUCCESS` — all 5 new unit tests pass, existing 29 tests still pass.

- [ ] **Step 6: Apply Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-knowledge-engine
git add \
  emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/ResolutionReviewService.java \
  emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/ResolutionReviewController.java \
  emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/ResolutionReviewServiceTest.java
git commit -m "feat(knowledge): ResolutionReviewService + ResolutionReviewController with unit tests"
```

---

## Task 4: Integration test for `ResolutionReviewController`

**Files:**
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/ResolutionReviewIntegrationTest.java`

`@IntegrationTest` starts a full Spring context with `pgvector/pgvector:pg16` Testcontainers + Liquibase. `GraphRepository` is always `@MockitoBean` (AGE not in test image). Tests hit the real HTTP endpoints via `TestRestTemplate` or `webEnvironment = RANDOM_PORT`.

- [ ] **Step 1: Create the integration test**

Create `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/ResolutionReviewIntegrationTest.java`:

```java
package io.emcip.knowledge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.entity.ResolutionFlag;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.ResolutionFlagRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@IntegrationTest
class ResolutionReviewIntegrationTest {

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ResolutionFlagRepository flagRepository;

    @MockitoBean private GraphRepository graphRepository;

    @BeforeEach
    void clean() {
        flagRepository.deleteAll();
    }

    private ResolutionFlag insertPendingFlag(UUID tenantId) {
        ResolutionFlag f = new ResolutionFlag();
        f.setCandidateLabel("AI");
        f.setCandidateNodeId(UUID.randomUUID());
        f.setSimilarLabel("artificial intelligence");
        f.setSimilarNodeId(UUID.randomUUID());
        f.setConceptType("TOPIC");
        f.setSimilarityScore(0.85);
        f.setTenantId(tenantId);
        f.setStatus("PENDING");
        f.setCreatedAt(Instant.now());
        return flagRepository.save(f);
    }

    @Test
    void list_returnsPendingFlags() {
        UUID tenantId = UUID.randomUUID();
        insertPendingFlag(tenantId);

        String url = "http://localhost:" + port + "/api/resolution-review?status=PENDING";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"AI\"");
        assertThat(response.getBody()).contains("totalElements");
    }

    @Test
    void dismiss_setsFlagDismissed() {
        UUID tenantId = UUID.randomUUID();
        ResolutionFlag flag = insertPendingFlag(tenantId);

        String url = "http://localhost:" + port
                + "/api/resolution-review/" + flag.getId() + "/dismiss";
        ResponseEntity<Void> response =
                restTemplate.exchange(url, HttpMethod.PATCH, null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResolutionFlag updated = flagRepository.findById(flag.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("DISMISSED");
    }

    @Test
    void dismiss_alreadyDismissed_returns409() {
        UUID tenantId = UUID.randomUUID();
        ResolutionFlag flag = insertPendingFlag(tenantId);
        flag.setStatus("DISMISSED");
        flagRepository.save(flag);

        String url = "http://localhost:" + port
                + "/api/resolution-review/" + flag.getId() + "/dismiss";
        ResponseEntity<Void> response =
                restTemplate.exchange(url, HttpMethod.PATCH, null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
```

- [ ] **Step 2: Run the integration test**

```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-knowledge-engine -Dtest=ResolutionReviewIntegrationTest -am 2>&1 | tail -20
```

Expected: 3 tests PASS.

- [ ] **Step 3: Run full suite**

```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-knowledge-engine -am 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Apply Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/ResolutionReviewIntegrationTest.java
git commit -m "test(knowledge): ResolutionReviewIntegrationTest — list, dismiss, 409 on already-dismissed"
```

---

## Task 5: `emcip-admin-api` proxy

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/config/WebClientConfig.java`
- Modify: `emcip-admin-api/src/main/resources/application.yml`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ResolutionReviewProxyController.java`

`emcip-admin-api` is a Spring WebFlux (reactive) service — all controller methods return `Mono<T>`. The existing `CostsProxyController` is the exact pattern to follow.

- [ ] **Step 1: Add `knowledgeWebClient` bean to `WebClientConfig`**

The current `WebClientConfig.java` has beans `tdlibWebClient` and `orchestratorWebClient` and a private `buildWebClient()` helper. Add the new bean:

```java
@Bean("knowledgeWebClient")
public WebClient knowledgeWebClient(
        @Value("${service.knowledge.url}") String knowledgeUrl,
        @Value("${admin.service-token}") String serviceToken) {
    return buildWebClient(
            WebClient.builder().defaultHeader("X-Service-Token", serviceToken),
            knowledgeUrl,
            Duration.ofSeconds(30));
}
```

Add it after the `orchestratorWebClient` bean and before the private `buildWebClient()` method.

- [ ] **Step 2: Add `service.knowledge.url` and resilience4j `knowledge` to `application.yml`**

In `service:` section add:
```yaml
service:
  knowledge:
    url: ${SERVICE_KNOWLEDGE_URL:http://localhost:9088}
```

In `resilience4j.circuitbreaker.instances:` add:
```yaml
      knowledge:
        baseConfig: default
```

In `resilience4j.retry.instances:` add:
```yaml
      knowledge:
        baseConfig: default
```

- [ ] **Step 3: Create `ResolutionReviewProxyController`**

Create `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ResolutionReviewProxyController.java`:

```java
package io.emcip.admin.api.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Proxies resolution review requests to the knowledge-engine service.
 * Admin-UI → admin-api → knowledge-engine (API Gateway pattern).
 */
@Slf4j
@RestController
@RequestMapping("/api/resolution-review")
@PreAuthorize("hasAuthority('RESOLUTION_REVIEW_READ')")
@Tag(name = "Resolution Review", description = "Proxy to knowledge-engine resolution review API")
public class ResolutionReviewProxyController {

    private final WebClient knowledgeWebClient;
    private final CircuitBreaker circuitBreaker;

    public ResolutionReviewProxyController(
            @Qualifier("knowledgeWebClient") WebClient knowledgeWebClient,
            CircuitBreakerRegistry registry) {
        this.knowledgeWebClient = knowledgeWebClient;
        this.circuitBreaker = registry.circuitBreaker("knowledge");
    }

    @Operation(summary = "List resolution flags")
    @GetMapping
    public Mono<String> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String conceptType,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return knowledgeWebClient
                .get()
                .uri(
                        b ->
                                b.path("/api/resolution-review")
                                        .queryParamIfPresent(
                                                "status", Optional.ofNullable(status))
                                        .queryParamIfPresent(
                                                "conceptType", Optional.ofNullable(conceptType))
                                        .queryParamIfPresent(
                                                "tenantId", Optional.ofNullable(tenantId))
                                        .queryParam("page", page)
                                        .queryParam("size", size)
                                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Merge candidate node into similar node")
    @PatchMapping("/{id}/merge")
    @PreAuthorize("hasAuthority('RESOLUTION_REVIEW_WRITE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> merge(@PathVariable UUID id) {
        return knowledgeWebClient
                .patch()
                .uri("/api/resolution-review/{id}/merge", id)
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    @Operation(summary = "Dismiss flag without graph changes")
    @PatchMapping("/{id}/dismiss")
    @PreAuthorize("hasAuthority('RESOLUTION_REVIEW_WRITE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> dismiss(@PathVariable UUID id) {
        return knowledgeWebClient
                .patch()
                .uri("/api/resolution-review/{id}/dismiss", id)
                .retrieve()
                .bodyToMono(Void.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
```

- [ ] **Step 4: Build admin-api to verify compilation**

```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api -am 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Apply Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-admin-api
git add \
  emcip-admin-api/src/main/java/io/emcip/admin/api/config/WebClientConfig.java \
  emcip-admin-api/src/main/resources/application.yml \
  emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ResolutionReviewProxyController.java
git commit -m "feat(admin-api): proxy ResolutionReviewController → knowledge-engine + knowledgeWebClient"
```

---

## Task 6: Admin UI — permissions, nav, route, API module, page

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/auth/permissions.js`
- Modify: `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/App.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/api/resolutionReview.js`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/ResolutionQueue/ResolutionQueue.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/ResolutionQueue/ResolutionQueue.module.css`

Design rules (from `emcip-admin-ui/CLAUDE.md`):
- Semantic tokens only: `var(--accent)`, `var(--fg-1)`, `var(--signal-warn-fg)`, etc.
- Cinzel uppercase for headings/nav/buttons
- No emoji, no icon libraries, no rounded corners on data surfaces
- Glyph for this page: `⊗` (U+2297, CIRCLED TIMES)
- `ConfirmDialog` lives at `src/components/ConfirmDialog/ConfirmDialog.jsx`

- [ ] **Step 1: Add permissions**

In `emcip-admin-ui/src/main/frontend/src/auth/permissions.js`, add `'RESOLUTION_REVIEW_READ'` and `'RESOLUTION_REVIEW_WRITE'` to both `ADMIN` and `TENANT_ADMIN` arrays:

```js
export const ROLE_PERMISSIONS = {
  ADMIN: [
    'GROUPS_READ', 'GROUPS_WRITE',
    'POLICY_RULES_READ', 'POLICY_RULES_WRITE',
    'MODERATION_RULES_READ', 'MODERATION_RULES_WRITE',
    'AUDIT_READ',
    'TELEGRAM_READ', 'TELEGRAM_WRITE',
    'SIMULATE_WRITE',
    'AI_CONFIG_READ', 'AI_CONFIG_WRITE',
    'COSTS_READ',
    'TENANTS_READ', 'TENANTS_WRITE',
    'USERS_READ', 'USERS_WRITE',
    'RESOLUTION_REVIEW_READ', 'RESOLUTION_REVIEW_WRITE',
  ],
  TENANT_ADMIN: [
    'GROUPS_READ', 'GROUPS_WRITE',
    'POLICY_RULES_READ', 'POLICY_RULES_WRITE',
    'MODERATION_RULES_READ', 'MODERATION_RULES_WRITE',
    'AUDIT_READ',
    'TELEGRAM_READ', 'TELEGRAM_WRITE',
    'SIMULATE_WRITE',
    'COSTS_READ',
    'RESOLUTION_REVIEW_READ', 'RESOLUTION_REVIEW_WRITE',
  ],
}

export function hasPermission(role, permission) {
  return ROLE_PERMISSIONS[role]?.includes(permission) ?? false
}
```

- [ ] **Step 2: Add nav entry to Sidebar**

In `emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx`, add to the `NAV` array after the Decisions entry (`{ to: '/decisions', ... }`):

```js
{ to: '/resolution-queue', label: 'Resolution Queue', icon: '\u2297', permission: 'RESOLUTION_REVIEW_READ' },
```

The full updated NAV array:

```js
const NAV = [
  { to: '/tenants',           label: 'Tenants',           icon: '⬡',     permission: 'TENANTS_READ' },
  { to: '/policy-rules',      label: 'Policy Rules',      icon: '⚖',     permission: 'POLICY_RULES_READ' },
  { to: '/moderation-rules',  label: 'Moderation Rules',  icon: '⊘',     permission: 'MODERATION_RULES_READ' },
  { to: '/decisions',         label: 'Decisions',         icon: '⚑',     permission: 'AUDIT_READ' },
  { to: '/resolution-queue',  label: 'Resolution Queue',  icon: '\u2297', permission: 'RESOLUTION_REVIEW_READ' },
  { to: '/groups',            label: 'Groups',            icon: '◈',     permission: 'GROUPS_READ' },
  { to: '/audit-log',         label: 'Audit Log',         icon: '◎',     permission: 'AUDIT_READ' },
  { to: '/simulate',          label: 'Simulate Event',    icon: '▶',     permission: 'SIMULATE_WRITE' },
  { to: '/telegram',          label: 'Telegram',          icon: '⌘',     permission: 'TELEGRAM_READ' },
  { to: '/ai-config',         label: 'AI Config',         icon: '✦',     permission: 'AI_CONFIG_READ' },
  { to: '/costs',             label: 'LLM Costs',         icon: '\u229B', permission: 'COSTS_READ' },
  { to: '/users',             label: 'Users',             icon: '◉',     permission: 'USERS_READ' },
]
```

- [ ] **Step 3: Add route to `App.jsx`**

Add import (after existing page imports):
```js
import { ResolutionQueue } from './pages/ResolutionQueue/ResolutionQueue'
```

Add route inside the `<Route element={<AppShell />}>` block, after the decisions route:
```jsx
<Route path="resolution-queue" element={<ResolutionQueue />} />
```

- [ ] **Step 4: Create API module**

Create `emcip-admin-ui/src/main/frontend/src/api/resolutionReview.js`:

```js
export function resolutionReviewApi(request) {
  return {
    list: (page = 0, size = 20, status = '', conceptType = '', tenantId = null) => {
      const params = new URLSearchParams({ page, size })
      if (status) params.set('status', status)
      if (conceptType) params.set('conceptType', conceptType)
      if (tenantId) params.set('tenantId', tenantId)
      return request(`/api/resolution-review?${params}`)
    },
    merge: (id) =>
      request(`/api/resolution-review/${encodeURIComponent(id)}/merge`, { method: 'PATCH' }),
    dismiss: (id) =>
      request(`/api/resolution-review/${encodeURIComponent(id)}/dismiss`, { method: 'PATCH' }),
  }
}
```

- [ ] **Step 5: Create page CSS module**

Create `emcip-admin-ui/src/main/frontend/src/pages/ResolutionQueue/ResolutionQueue.module.css`:

```css
.scoreWarn {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--signal-warn-fg);
}

.scoreNormal {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}

.actions {
  display: flex;
  gap: var(--sp-2);
  white-space: nowrap;
}

.error {
  color: var(--signal-stop-fg);
  font-size: 13px;
  padding: var(--sp-3) 0;
}
```

- [ ] **Step 6: Create page component**

Create `emcip-admin-ui/src/main/frontend/src/pages/ResolutionQueue/ResolutionQueue.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { resolutionReviewApi } from '../../api/resolutionReview'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { ConfirmDialog } from '../../components/ConfirmDialog/ConfirmDialog'
import styles from './ResolutionQueue.module.css'

const STATUS_OPTIONS = ['PENDING', 'MERGED', 'DISMISSED', '']
const STATUS_LABELS = { PENDING: 'Pending', MERGED: 'Merged', DISMISSED: 'Dismissed', '': 'All' }
const STATUS_VARIANT = { PENDING: 'yellow', MERGED: 'green', DISMISSED: 'gray' }

const PAGE_SIZES = [10, 20, 50]

function scoreClass(score, styles) {
  return score >= 0.80 && score < 0.92 ? styles.scoreWarn : styles.scoreNormal
}

export function ResolutionQueue() {
  const api = resolutionReviewApi(useAuthRequest())

  const [flags, setFlags] = useState([])
  const [total, setTotal] = useState(0)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [pendingAction, setPendingAction] = useState(null) // { id, action: 'merge'|'dismiss', candidateLabel, similarLabel }
  const [busyIds, setBusyIds] = useState(new Set())

  const [filters, setFilters] = useState({ page: 0, size: 20, status: 'PENDING', conceptType: '' })

  const setFilter = (key, value) =>
    setFilters(f => ({ ...f, [key]: value, ...(key !== 'page' ? { page: 0 } : {}) }))

  const load = () => {
    setLoading(true)
    setError('')
    api
      .list(filters.page, filters.size, filters.status, filters.conceptType)
      .then(data => {
        setFlags(data?.content ?? [])
        setTotal(data?.totalElements ?? 0)
      })
      .catch(e => setError(e.message ?? 'Failed to load flags'))
      .finally(() => setLoading(false))
  }

  useEffect(load, [filters])

  const handleConfirm = () => {
    if (!pendingAction) return
    const { id, action } = pendingAction
    setPendingAction(null)
    setBusyIds(s => new Set(s).add(id))
    const call = action === 'merge' ? api.merge(id) : api.dismiss(id)
    call
      .then(load)
      .catch(e => setError(e.message ?? `Failed to ${action} flag`))
      .finally(() => setBusyIds(s => { const n = new Set(s); n.delete(id); return n }))
  }

  const totalPages = Math.ceil(total / filters.size)

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h2>RESOLUTION QUEUE</h2>
          <div className="system-id">⊗ knowledge-engine · 9088 · entity deduplication</div>
        </div>
      </div>

      {/* Filters */}
      <div className="filter-row">
        <select
          className="filter-select"
          value={filters.status}
          onChange={e => setFilter('status', e.target.value)}
        >
          {STATUS_OPTIONS.map(s => (
            <option key={s} value={s}>{STATUS_LABELS[s]}</option>
          ))}
        </select>

        <select
          className="filter-select"
          value={filters.size}
          onChange={e => setFilter('size', Number(e.target.value))}
        >
          {PAGE_SIZES.map(n => (
            <option key={n} value={n}>{n} / page</option>
          ))}
        </select>
      </div>

      {error && <div className={styles.error}>{error}</div>}

      {/* Table */}
      <table className="tbl">
        <thead>
          <tr>
            <th>Created</th>
            <th>Candidate</th>
            <th>Similar To</th>
            <th>Type</th>
            <th>Score</th>
            <th>Status</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {!loading && flags.length === 0 && (
            <tr>
              <td colSpan={7} style={{ textAlign: 'center', fontStyle: 'italic', color: 'var(--fg-3)', padding: '24px' }}>
                No resolution flags found.
              </td>
            </tr>
          )}
          {flags.map(flag => {
            const busy = busyIds.has(flag.id)
            const canAct = flag.status === 'PENDING' && !busy
            return (
              <tr key={flag.id}>
                <td style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-2)' }}>
                  {flag.createdAt ? new Date(flag.createdAt).toLocaleString() : '—'}
                </td>
                <td>{flag.candidateLabel}</td>
                <td>{flag.similarLabel}</td>
                <td><Badge variant="blue">{flag.conceptType}</Badge></td>
                <td>
                  <span className={scoreClass(flag.similarityScore, styles)}>
                    {flag.similarityScore?.toFixed(3)}
                  </span>
                </td>
                <td>
                  <Badge variant={STATUS_VARIANT[flag.status] ?? 'gray'}>
                    {flag.status}
                  </Badge>
                </td>
                <td>
                  <div className={styles.actions}>
                    <Button
                      variant="primary"
                      disabled={!canAct}
                      onClick={() => setPendingAction({
                        id: flag.id,
                        action: 'merge',
                        candidateLabel: flag.candidateLabel,
                        similarLabel: flag.similarLabel,
                      })}
                    >
                      Merge
                    </Button>
                    <Button
                      variant="secondary"
                      disabled={!canAct}
                      onClick={() => setPendingAction({
                        id: flag.id,
                        action: 'dismiss',
                        candidateLabel: flag.candidateLabel,
                        similarLabel: flag.similarLabel,
                      })}
                    >
                      Dismiss
                    </Button>
                  </div>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="pagination">
          <Button
            variant="secondary"
            disabled={filters.page === 0}
            onClick={() => setFilter('page', filters.page - 1)}
          >
            ◂ Prev
          </Button>
          <span className="pagination-info">
            Page {filters.page + 1} of {totalPages} — {total} flags
          </span>
          <Button
            variant="secondary"
            disabled={filters.page >= totalPages - 1}
            onClick={() => setFilter('page', filters.page + 1)}
          >
            Next ▸
          </Button>
        </div>
      )}

      {/* Confirmation dialog */}
      {pendingAction && (
        <ConfirmDialog
          title={pendingAction.action === 'merge' ? 'Merge Entity' : 'Dismiss Flag'}
          message={
            pendingAction.action === 'merge'
              ? `Merge "${pendingAction.candidateLabel}" into "${pendingAction.similarLabel}"? This will delete the candidate node and reroute all its graph relationships. This cannot be undone.`
              : `Dismiss this resolution flag for "${pendingAction.candidateLabel}"? The candidate node will be kept as a separate entity.`
          }
          confirmLabel={pendingAction.action === 'merge' ? 'Merge' : 'Dismiss'}
          onConfirm={handleConfirm}
          onClose={() => setPendingAction(null)}
        />
      )}
    </div>
  )
}
```

- [ ] **Step 7: Verify the frontend builds**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui && mvn package -DskipTests 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
cd /home/ben/Development/ecip
git add \
  emcip-admin-ui/src/main/frontend/src/auth/permissions.js \
  emcip-admin-ui/src/main/frontend/src/layout/Sidebar/Sidebar.jsx \
  emcip-admin-ui/src/main/frontend/src/App.jsx \
  emcip-admin-ui/src/main/frontend/src/api/resolutionReview.js \
  emcip-admin-ui/src/main/frontend/src/pages/ResolutionQueue/ResolutionQueue.jsx \
  emcip-admin-ui/src/main/frontend/src/pages/ResolutionQueue/ResolutionQueue.module.css
git commit -m "feat(admin-ui): Resolution Queue page — list flags, merge/dismiss with ConfirmDialog"
```

---

## Final check

```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-knowledge-engine -am 2>&1 | tail -5 && mvn test -pl emcip-admin-api -am 2>&1 | tail -5
```

Expected: both `BUILD SUCCESS`.

---

## Self-Review

**Spec coverage:**
- ✅ 1.1 `ResolutionFlagRepository.findFiltered` — Task 1
- ✅ 1.2 `GraphRepository.mergeNodes` interface — Task 1
- ✅ 1.3 AGE 1.5.0 note — in Task 2 comments
- ✅ 1.4 `AgeGraphRepository.mergeNodes` + `queryEdges` — Task 2
- ✅ 1.5 `ResolutionReviewService` — Task 3
- ✅ 1.6 `ResolutionReviewController` — Task 3
- ✅ 1.7 Response shape — covered by controller returning `Page<ResolutionFlag>` with Spring default serialisation
- ✅ 2.1 `knowledgeWebClient` bean — Task 5
- ✅ 2.2 `application.yml` additions — Task 5
- ✅ 2.3 `ResolutionReviewProxyController` — Task 5
- ✅ 3.1 permissions.js — Task 6
- ✅ 3.2 Sidebar nav entry ⊗ — Task 6
- ✅ 3.3 App.jsx route — Task 6
- ✅ 3.4 `resolutionReview.js` API module — Task 6
- ✅ 3.5 Page component with table, filters, Score colour, ConfirmDialog for both Merge and Dismiss — Task 6
- ✅ Unit tests (5) — Task 3
- ✅ Integration tests (3: list, dismiss, 409) — Task 4

**Placeholder scan:** No TBD/TODO found.

**Type consistency:** `ResolutionFlag` fields (`candidateLabel`, `similarLabel`, `candidateNodeId`, `similarNodeId`, `conceptType`, `similarityScore`, `status`, `createdAt`) used consistently across service, controller, and UI. `findFiltered` signature matches between repository and service.
