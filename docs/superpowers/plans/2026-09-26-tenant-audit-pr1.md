# TENANT-AUDIT PR 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close F-1 (CRITICAL), P-1, F-2 (HIGH) and C-1 (MEDIUM): a tenant-bound admin-api caller can no longer read or change another tenant's policy rules, flags or costs, and can never reply into another tenant's Telegram group.

**Architecture:** admin-api sends the bound tenant as `X-Tenant-Id` on every policy-engine and cost call. policy-engine (WebFlux) gets a reactive `TenantWebFilter` that puts the header into the Reactor context; its controllers pass the tenant **explicitly** into tenant-scoped queries and ownership checks (404 = denied = missing). llm-orchestrator (MVC) takes the header as an explicit `@RequestHeader` on the cost endpoints and filters every aggregate. `FlagService.reply` adds its own tenant check so F-1 never rests on one layer.

**Tech Stack:** Java 21, Spring Boot 4.0.5 (WebFlux in policy-engine and admin-api, MVC in llm-orchestrator), JPA/Hibernate 7, R2DBC (admin-api), Reactor, JUnit 5, Testcontainers, MockWebServer (mockwebserver3), WebTestClient, MockMvc.

**Spec:** `docs/superpowers/specs/2026-09-26-tenant-audit-pr1-design.md` (§1 revised during planning: policy-engine is WebFlux).

## Global Constraints

- Work only in `/home/ben/Development/ecip/.worktrees/tenant-audit` (branch `fix/tenant-audit`). Never touch `/home/ben/Development/ecip` (another session owns it). Never bare `git stash`.
- `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` before any `mvn` (spotless crashes on JDK 25).
- Never `mvn install` emcip-core (shared `~/.m2`). This PR does not change emcip-core.
- Header name: `TenantContext.HEADER_NAME` (`"X-Tenant-Id"`); Reactor keys via `ReactorTenantContext.withTenant/getTenantId`.
- **Denied = missing:** another tenant's item answers **404**, same as a missing one (ADR-009 rule 8).
- **No tenant = trusted caller** (ADR-009 rule 5): every endpoint behaves exactly as today when no header is sent.
- Every new assertion is observed **red first** (or forced red by breaking the implementation for one run). Record the red output in the task notes.
- **New tests go in new files.** Before creating a test file, check it does not exist (`ls <path>`). Existing tests (`PolicyRuleControllerTest`, `PolicyDecisionControllerTest`, `PolicyEngineClientTest`, `FlagServiceTest`, `CostsProxyControllerTest`, `OrchestratorControllerCostsTest`, `CostTrackingServiceTest`) get signature updates only. Compare the per-module test count before/after — it must only grow.
- `mvn spotless:apply` before each commit; commit format `fix(scope): …`, body with context, ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Do not run the flag-reply path against the live cluster.

---

### Task 1: policy-engine `TenantWebFilter`

**Files:**
- Create: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/config/TenantWebFilter.java`
- Create: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/config/RequestTenant.java`
- Test: `emcip-policy-engine/src/test/java/io/emcip/policy/engine/config/TenantWebFilterTest.java`

**Interfaces:**
- Produces: `TenantWebFilter` (`@Component WebFilter`); `RequestTenant.of(ContextView ctx) → UUID` (null when no tenant).

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.policy.engine.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.tenant.TenantContext;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TenantWebFilterTest {

    private final TenantWebFilter filter = new TenantWebFilter();

    @Test
    void validHeaderPutsTheTenantIntoTheReactorContext() {
        UUID tenant = UUID.randomUUID();
        AtomicReference<UUID> seen = new AtomicReference<>();
        WebFilterChain chain =
                ex -> Mono.deferContextual(ctx -> {
                    seen.set(RequestTenant.of(ctx));
                    return Mono.empty();
                });

        StepVerifier.create(filter.filter(exchange(tenant.toString()), chain)).verifyComplete();

        assertThat(seen.get()).isEqualTo(tenant);
    }

    @Test
    void noHeaderRunsTheChainWithoutATenant() {
        AtomicBoolean ran = new AtomicBoolean();
        AtomicReference<UUID> seen = new AtomicReference<>(UUID.randomUUID());
        WebFilterChain chain =
                ex -> Mono.deferContextual(ctx -> {
                    ran.set(true);
                    seen.set(RequestTenant.of(ctx));
                    return Mono.empty();
                });

        StepVerifier.create(filter.filter(exchange(null), chain)).verifyComplete();

        assertThat(ran).isTrue();
        assertThat(seen.get()).isNull();
    }

    @Test
    void malformedHeaderIsRejectedWith400AndTheChainNeverRuns() {
        AtomicBoolean ran = new AtomicBoolean();
        MockServerWebExchange exchange = exchange("not-a-uuid");

        StepVerifier.create(filter.filter(exchange, ex -> Mono.fromRunnable(() -> ran.set(true))))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ran).isFalse();
    }

    private static MockServerWebExchange exchange(String tenant) {
        MockServerHttpRequest.BaseBuilder<?> req = MockServerHttpRequest.get("/api/policy-rules");
        if (tenant != null) {
            req.header(TenantContext.HEADER_NAME, tenant);
        }
        return MockServerWebExchange.from(req);
    }
}
```

- [ ] **Step 2: Run it — expect a compile failure** (`TenantWebFilter`, `RequestTenant` missing)

Run: `mvn -q -pl emcip-policy-engine test -Dtest=TenantWebFilterTest`

- [ ] **Step 3: Implement**

`RequestTenant.java`:

```java
package io.emcip.policy.engine.config;

import io.emcip.common.tenant.ReactorTenantContext;
import java.util.UUID;
import reactor.util.context.ContextView;

/**
 * The tenant asserted by the caller of this request, as put into the Reactor context by {@link
 * TenantWebFilter}. {@code null} means no tenant was asserted: a trusted caller (ADR-009 rule 5).
 */
public final class RequestTenant {

    private RequestTenant() {}

    public static UUID of(ContextView ctx) {
        String tenantId = ReactorTenantContext.getTenantId(ctx);
        return tenantId != null ? UUID.fromString(tenantId) : null;
    }
}
```

`TenantWebFilter.java`:

```java
package io.emcip.policy.engine.config;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Puts the {@code X-Tenant-Id} header into the Reactor context for the request (ADR-009 rule 8).
 * Controllers read it with {@link RequestTenant#of} and pass it explicitly into their queries: the
 * blocking JPA calls run on {@code boundedElastic}, so the ThreadLocal {@link TenantContext} and
 * the Hibernate {@code tenantFilter} (Kafka path) never see an HTTP request's tenant.
 */
@Slf4j
@Component
public class TenantWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String tenantId = exchange.getRequest().getHeaders().getFirst(TenantContext.HEADER_NAME);
        if (tenantId == null || tenantId.isBlank()) {
            return chain.filter(exchange);
        }
        try {
            UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected request with a malformed {} header", TenantContext.HEADER_NAME);
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange)
                .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, tenantId));
    }
}
```

- [ ] **Step 4: Run — expect 3 passing.** Force red once: comment out the `contextWrite` → test 1 fails; restore.
- [ ] **Step 5: Commit** — `fix(policy-engine): bind X-Tenant-Id into the Reactor context (TENANT-AUDIT)`

---

### Task 2: policy-engine rules — tenant-scoped list, ownership on update/delete/history (P-1)

**Files:**
- Modify: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/repository/PolicyRuleConfigRepository.java`
- Modify: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyRuleController.java`
- Test: `emcip-policy-engine/src/test/java/io/emcip/policy/engine/controller/PolicyTenantScopingIT.java` (new; Task 3 adds decision tests to it)

**Interfaces:**
- Consumes: `TenantWebFilter`, `RequestTenant.of(ContextView)` (Task 1).
- Produces: `List<PolicyRuleConfig> findByActiveTrueAndTenantIdOrderByPriorityAsc(UUID tenantId)`.

- [ ] **Step 1: Write the failing IT**

```java
package io.emcip.policy.engine.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.tenant.TenantContext;
import io.emcip.policy.engine.IntegrationTest;
import io.emcip.policy.engine.config.TenantWebFilter;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.entity.PolicyRuleHistory;
import io.emcip.policy.engine.repository.PolicyDecisionRepository;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import io.emcip.policy.engine.repository.PolicyRuleHistoryRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * TENANT-AUDIT P-1 / F-2: with a tenant asserted, policy-engine must never list, change or reveal
 * another tenant's rules or decisions. Goes through the real {@link TenantWebFilter} and real
 * controllers against PostgreSQL — the header-to-query path is what was broken, so mocking the
 * repository would test nothing.
 */
@IntegrationTest
class PolicyTenantScopingIT {

    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();

    @Autowired PolicyRuleController ruleController;
    @Autowired PolicyDecisionController decisionController;
    @Autowired PolicyRuleConfigRepository ruleRepository;
    @Autowired PolicyRuleHistoryRepository historyRepository;
    @Autowired PolicyDecisionRepository decisionRepository;

    WebTestClient client;
    String ruleOfA;
    String ruleOfB;

    @BeforeEach
    void setUp() {
        historyRepository.deleteAll();
        ruleRepository.deleteAll();
        decisionRepository.deleteAll();
        client =
                WebTestClient.bindToController(ruleController, decisionController)
                        .webFilter(new TenantWebFilter())
                        .build();
        ruleOfA = saveRule(A, "rule-a").getId();
        ruleOfB = saveRule(B, "rule-b").getId();
        saveHistory(ruleOfB, B);
    }

    @Test
    void listShowsOnlyTheCallersRules() {
        client.get().uri("/api/policy-rules").header(TenantContext.HEADER_NAME, A.toString())
                .exchange().expectStatus().isOk()
                .expectBodyList(PolicyRuleConfig.class)
                .value(rules -> assertThat(rules).extracting(PolicyRuleConfig::getId)
                        .containsExactly(ruleOfA));
    }

    @Test
    void listWithoutATenantIsUnchanged() {
        client.get().uri("/api/policy-rules").exchange().expectStatus().isOk()
                .expectBodyList(PolicyRuleConfig.class).hasSize(2);
    }

    @Test
    void updatingAnotherTenantsRuleIs404AndLeavesItUnchanged() {
        client.put().uri("/api/policy-rules/{id}", ruleOfB)
                .header(TenantContext.HEADER_NAME, A.toString())
                .bodyValue(Map.of("name", "hijacked", "action", "ALLOW", "priority", 1))
                .exchange().expectStatus().isNotFound();

        assertThat(ruleRepository.findById(ruleOfB)).get()
                .extracting(PolicyRuleConfig::getName).isEqualTo("rule-b");
        assertThat(historyRepository.findByRuleIdOrderByEditedAtDesc(ruleOfB)).hasSize(1);
    }

    @Test
    void updatingOwnRuleWorks() {
        client.put().uri("/api/policy-rules/{id}", ruleOfA)
                .header(TenantContext.HEADER_NAME, A.toString())
                .bodyValue(Map.of("name", "renamed", "action", "FLAG", "priority", 1))
                .exchange().expectStatus().isOk();

        assertThat(ruleRepository.findById(ruleOfA)).get()
                .extracting(PolicyRuleConfig::getName).isEqualTo("renamed");
    }

    @Test
    void deletingAnotherTenantsRuleIs404AndKeepsIt() {
        client.delete().uri("/api/policy-rules/{id}", ruleOfB)
                .header(TenantContext.HEADER_NAME, A.toString())
                .exchange().expectStatus().isNotFound();

        assertThat(ruleRepository.findById(ruleOfB)).isPresent();
    }

    @Test
    void deletingOwnRuleWorks() {
        client.delete().uri("/api/policy-rules/{id}", ruleOfA)
                .header(TenantContext.HEADER_NAME, A.toString())
                .exchange().expectStatus().isNoContent();

        assertThat(ruleRepository.findById(ruleOfA)).isEmpty();
    }

    @Test
    void historyOfAnotherTenantsRuleIs404() {
        client.get().uri("/api/policy-rules/{id}/history", ruleOfB)
                .header(TenantContext.HEADER_NAME, A.toString())
                .exchange().expectStatus().isNotFound();
    }

    private PolicyRuleConfig saveRule(UUID tenant, String name) {
        PolicyRuleConfig rule = new PolicyRuleConfig();
        rule.setId(UUID.randomUUID().toString());
        rule.setTenantId(tenant);
        rule.setName(name);
        rule.setTargetIntent("*");
        rule.setMinConfidence(0.5);
        rule.setAction("FLAG");
        rule.setPriority(10);
        rule.setActive(true);
        rule.setRuleVersion(1);
        return ruleRepository.save(rule);
    }

    private void saveHistory(String ruleId, UUID tenant) {
        PolicyRuleHistory snap = new PolicyRuleHistory();
        snap.setId(UUID.randomUUID());
        snap.setRuleId(ruleId);
        snap.setTenantId(tenant);
        snap.setSnapshot(Map.of("name", "old"));
        snap.setEditedBy("seed");
        snap.setEditedAt(Instant.now());
        snap.setRuleVersion(1);
        historyRepository.save(snap);
    }
}
```

- [ ] **Step 2: Run — expect red.** `mvn -q -pl emcip-policy-engine verify -Dit.test=PolicyTenantScopingIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` (check the module's failsafe/surefire setup first: `grep -n "failsafe\|IT.java\|<include>" emcip-policy-engine/pom.xml pom.xml`; if `*IT` runs under surefire, use `-Dtest=PolicyTenantScopingIT`). Expected failures: list returns 2, update/delete/history of B's rule succeed. `listWithoutATenantIsUnchanged`, `updatingOwnRuleWorks`, `deletingOwnRuleWorks` pass already (they guard against over-blocking).

- [ ] **Step 3: Implement**

Repository — add:

```java
    /** Find the active rules of one tenant ordered by priority (HTTP path, ADR-009 rule 8). */
    List<PolicyRuleConfig> findByActiveTrueAndTenantIdOrderByPriorityAsc(UUID tenantId);
```

(import `java.util.UUID`).

Controller — `listActive`:

```java
    public Flux<PolicyRuleConfig> listActive() {
        return Flux.deferContextual(
                        ctx -> {
                            UUID tenant = RequestTenant.of(ctx);
                            return Mono.fromCallable(
                                            () ->
                                                    tenant != null
                                                            ? repository
                                                                    .findByActiveTrueAndTenantIdOrderByPriorityAsc(
                                                                            tenant)
                                                            : repository
                                                                    .findByActiveTrueOrderByPriorityAsc())
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .flatMapMany(Flux::fromIterable);
                        })
                .take(200);
    }
```

`update` — wrap in `Mono.deferContextual(ctx -> { UUID tenant = RequestTenant.of(ctx); return Mono.fromCallable(() -> { … }) … })` and replace the lookup with:

```java
                            PolicyRuleConfig existing = findOwned(id, tenant);
```

`delete`:

```java
    public Mono<Void> delete(@PathVariable String id) {
        return Mono.deferContextual(
                ctx -> {
                    UUID tenant = RequestTenant.of(ctx);
                    return Mono.fromRunnable(
                                    () -> {
                                        if (tenant != null) {
                                            findOwned(id, tenant);
                                        }
                                        repository.deleteById(id);
                                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            .then();
                });
    }
```

`getHistory`:

```java
    public Flux<PolicyRuleHistory> getHistory(@PathVariable String id) {
        return Flux.deferContextual(
                ctx -> {
                    UUID tenant = RequestTenant.of(ctx);
                    return Mono.fromCallable(
                                    () -> {
                                        if (tenant != null) {
                                            findOwned(id, tenant);
                                        }
                                        return historyRepository.findByRuleIdOrderByEditedAtDesc(
                                                id);
                                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMapMany(Flux::fromIterable);
                });
    }
```

Helper:

```java
    /**
     * The rule, if it exists and — when a tenant is asserted — belongs to it. Another tenant's
     * rule answers 404 exactly like a missing one (ADR-009 rule 8). {@code findById} is not
     * covered by the Hibernate tenant filter, so the check must be explicit.
     */
    private PolicyRuleConfig findOwned(String id, UUID tenant) {
        return repository
                .findById(id)
                .filter(rule -> tenant == null || tenant.equals(rule.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
```

(import `io.emcip.policy.engine.config.RequestTenant`.)

- [ ] **Step 4: Run the IT — all 7 green.** Force red once: change the filter predicate to `tenant == null || true` → the update/delete/history tests fail; restore. Run the existing `PolicyRuleControllerTest` — unchanged and green (no tenant → same code path).
- [ ] **Step 5: Commit** — `fix(policy-engine): scope policy rules to the asserted tenant (TENANT-AUDIT P-1)`

---

### Task 3: policy-engine decisions — tenant from the Reactor context, ownership on get/update (F-2)

**Files:**
- Modify: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyDecisionController.java`
- Modify: `emcip-policy-engine/src/test/java/io/emcip/policy/engine/controller/PolicyDecisionControllerTest.java` — only if a test sets `TenantContext` (grep showed none; expect no change)
- Test: `PolicyTenantScopingIT.java` (from Task 2) — add decision tests

**Interfaces:**
- Consumes: `RequestTenant.of` (Task 1); existing `findByFilters(UUID tenantId, …)`, `updateSignalStatus(String, String)`.

- [ ] **Step 1: Add failing tests to `PolicyTenantScopingIT`**

```java
    @Test
    void decisionListShowsOnlyTheCallersDecisions() {
        String own = saveDecision(A).getId();
        saveDecision(B);

        client.get().uri("/api/policy-decisions")
                .header(TenantContext.HEADER_NAME, A.toString())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(1)
                .jsonPath("$.items[0].id").isEqualTo(own);
    }

    @Test
    void readingAnotherTenantsDecisionIs404() {
        String other = saveDecision(B).getId();

        client.get().uri("/api/policy-decisions/{id}", other)
                .header(TenantContext.HEADER_NAME, A.toString())
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void readingAGlobalDecisionIsAllowed() {
        String global = saveDecision(null).getId();

        client.get().uri("/api/policy-decisions/{id}", global)
                .header(TenantContext.HEADER_NAME, A.toString())
                .exchange().expectStatus().isOk();
    }

    @Test
    void updatingAnotherTenantsDecisionIs404AndLeavesItUnchanged() {
        String other = saveDecision(B).getId();

        client.put().uri("/api/policy-decisions/{id}", other)
                .header(TenantContext.HEADER_NAME, A.toString())
                .bodyValue(Map.of("signalStatus", "RESOLVED"))
                .exchange().expectStatus().isNotFound();

        assertThat(decisionRepository.findById(other)).get()
                .extracting(PolicyDecision::getSignalStatus).isEqualTo("NEW");
    }

    @Test
    void updatingAGlobalDecisionIs404ForATenant() {
        String global = saveDecision(null).getId();

        client.put().uri("/api/policy-decisions/{id}", global)
                .header(TenantContext.HEADER_NAME, A.toString())
                .bodyValue(Map.of("signalStatus", "RESOLVED"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void updatingOwnDecisionWorks() {
        String own = saveDecision(A).getId();

        client.put().uri("/api/policy-decisions/{id}", own)
                .header(TenantContext.HEADER_NAME, A.toString())
                .bodyValue(Map.of("signalStatus", "RESOLVED"))
                .exchange().expectStatus().isNoContent();

        assertThat(decisionRepository.findById(own)).get()
                .extracting(PolicyDecision::getSignalStatus).isEqualTo("RESOLVED");
    }

    private PolicyDecision saveDecision(UUID tenant) {
        PolicyDecision d = new PolicyDecision();
        d.setTenantId(tenant);
        d.setEventId(UUID.randomUUID().toString());
        d.setSourceEventId(UUID.randomUUID().toString());
        d.setPolicyId("rule-x");
        d.setDecision("FLAG");
        d.setOriginalIntent("SPAM");
        d.setConfidence(0.9);
        d.setMetadata(Map.of("chatId", -100123L));
        return decisionRepository.save(d);
    }
```

(import `io.emcip.policy.engine.entity.PolicyDecision`.) Check `signalStatus` values in the Liquibase changelog (`grep -rn "signal_status" emcip-policy-engine/src/main/resources/db`) — if there is a CHECK constraint, use an allowed value instead of `RESOLVED`.

- [ ] **Step 2: Run — expect red:** list total 2, B's decision readable/updatable, global decision updatable. `readingAGlobalDecisionIsAllowed` and `updatingOwnDecisionWorks` pass already.

- [ ] **Step 3: Implement** in `PolicyDecisionController`:

```java
    public Mono<PolicyDecision> getById(@PathVariable String id) {
        return Mono.deferContextual(
                ctx -> {
                    UUID tenant = RequestTenant.of(ctx);
                    return Mono.fromCallable(
                                    () ->
                                            repository
                                                    .findById(id)
                                                    .filter(d -> readableBy(d, tenant))
                                                    .orElseThrow(
                                                            () ->
                                                                    new ResponseStatusException(
                                                                            HttpStatus.NOT_FOUND,
                                                                            "Decision not found: "
                                                                                    + id)))
                            .subscribeOn(Schedulers.boundedElastic());
                });
    }
```

`list`: delete the `TenantContext` lines and wrap the existing `Mono.fromCallable(...)` in `Mono.deferContextual(ctx -> { UUID tenantId = RequestTenant.of(ctx); return Mono.fromCallable(...)...; })`. Remove the now-unused `TenantContext` import.

`updateStatus` — keep the validation, then:

```java
        return Mono.deferContextual(
                ctx -> {
                    UUID tenant = RequestTenant.of(ctx);
                    return Mono.fromRunnable(
                                    () -> {
                                        if (tenant != null
                                                && repository
                                                        .findById(id)
                                                        .filter(d -> tenant.equals(d.getTenantId()))
                                                        .isEmpty()) {
                                            throw new ResponseStatusException(
                                                    HttpStatus.NOT_FOUND,
                                                    "Decision not found: " + id);
                                        }
                                        repository.updateSignalStatus(id, status);
                                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            .then();
                });
```

Helper:

```java
    /** Own or global decisions are readable; no tenant asserted = trusted caller (ADR-009). */
    private static boolean readableBy(PolicyDecision decision, UUID tenant) {
        return tenant == null
                || decision.getTenantId() == null
                || tenant.equals(decision.getTenantId());
    }
```

- [ ] **Step 4: Run the IT (13 green) and `PolicyDecisionControllerTest`** (green unchanged). Force red once: make `readableBy` return `true` → `readingAnotherTenantsDecisionIs404` fails; restore. Run the full module: `mvn -q -pl emcip-policy-engine verify` and compare the test count with the baseline taken before Task 1.
- [ ] **Step 5: Commit** — `fix(policy-engine): scope policy decisions to the asserted tenant (TENANT-AUDIT F-2)`

---

### Task 4: llm-orchestrator cost endpoints — explicit tenant (C-1, backend)

**Files:**
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/repository/ModelCostLogRepository.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/service/CostTrackingService.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java:232-270`
- Modify (signatures only): `CostTrackingServiceTest.java`, `OrchestratorControllerCostsTest.java`
- Test: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/service/CostTenantScopingIT.java` (new)
- Test: `emcip-llm-orchestrator/src/test/java/io/emcip/llm/orchestrator/controller/OrchestratorControllerCostsTenantTest.java` (new)

**Interfaces:**
- Produces: `CostTrackingService.getTotalCostForPeriod(Instant, Instant, UUID)`, `getTotals(Instant, Instant, UUID)`, `getByModel(Instant, Instant, UUID)`, `getByDay(Instant, Instant, UUID)` — `null` tenant = all tenants. The orchestrator cost endpoints accept optional header `X-Tenant-Id` (UUID; malformed → 400).

- [ ] **Step 1: Write the failing IT**

```java
package io.emcip.llm.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.tenant.TenantContext;
import io.emcip.llm.orchestrator.TestcontainersInitializer;
import io.emcip.llm.orchestrator.entity.ModelConfig;
import io.emcip.llm.orchestrator.repository.ModelCostLogRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * TENANT-AUDIT C-1: a tenant's cost views must aggregate only its own cost logs — including the
 * native by-day query, which no Hibernate filter would ever reach.
 */
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersInitializer.class)
class CostTenantScopingIT {

    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();

    @Autowired CostTrackingService service;
    @Autowired ModelCostLogRepository repository;

    Instant from;
    Instant to;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        repository.deleteAll();
        logCallFor(A, "req-a", 1000); // A: 1 call, 1000+1000 tokens
        logCallFor(B, "req-b1", 2000);
        logCallFor(B, "req-b2", 2000); // B: 2 calls
        from = Instant.now().minus(1, ChronoUnit.HOURS);
        to = Instant.now().plus(1, ChronoUnit.HOURS);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void totalsCountOnlyTheTenantsCalls() {
        assertThat(service.getTotals(from, to, A)).containsEntry("callCount", 1L);
        assertThat(service.getTotals(from, to, null)).containsEntry("callCount", 3L);
    }

    @Test
    void summaryCostsOnlyTheTenantsCalls() {
        assertThat(service.getTotalCostForPeriod(from, to, A))
                .isLessThan(service.getTotalCostForPeriod(from, to, null));
    }

    @Test
    void byModelCountsOnlyTheTenantsCalls() {
        List<Map<String, Object>> rows = service.getByModel(from, to, A);
        assertThat(rows).singleElement().satisfies(r -> assertThat(r).containsEntry("callCount", 1L));
    }

    @Test
    void byDayNativeQueryCountsOnlyTheTenantsCalls() {
        List<Map<String, Object>> rows = service.getByDay(from, to, A);
        assertThat(rows).isNotEmpty();
        assertThat(rows.stream().mapToLong(r -> (Long) r.get("callCount")).sum()).isEqualTo(1L);
        assertThat(service.getByDay(from, to, null).stream()
                        .mapToLong(r -> (Long) r.get("callCount")).sum())
                .isEqualTo(3L);
    }

    private void logCallFor(UUID tenant, String requestId, int tokens) {
        TenantContext.setTenantId(tenant.toString());
        try {
            service.logSuccessfulCall(
                    requestId, model(), "template-a", tokens, tokens, 100L, "event", null);
        } finally {
            TenantContext.clear();
        }
    }

    private static ModelConfig model() {
        ModelConfig model = new ModelConfig();
        model.setModelKey("model-a");
        model.setProvider("local-litellm");
        model.setModelName("model-a");
        model.setInputCostPer1kTokens(0.01);
        model.setOutputCostPer1kTokens(0.02);
        return model;
    }
}
```

(Note on by-day: a day boundary between the seed and `from/to` can split rows over two days; the test sums `callCount`, so it is stable.)

- [ ] **Step 2: Run — expect compile failure** (3-arg methods missing): `mvn -q -pl emcip-llm-orchestrator test-compile`.

- [ ] **Step 3: Implement**

Repository — replace the four aggregate queries (add `:tenantId` predicates, add parameter `@Param("tenantId") UUID tenantId` as the last parameter of each):

```java
    @Query(
            "SELECT SUM(m.totalCostUsd) FROM ModelCostLog m WHERE m.createdAt BETWEEN :start AND"
                    + " :end AND m.status = 'SUCCESS'"
                    + " AND (:tenantId IS NULL OR m.tenantId = :tenantId)")
    Double calculateTotalCostForPeriod(
            @Param("start") Instant start,
            @Param("end") Instant end,
            @Param("tenantId") UUID tenantId);
```

`calculateTotals`: append `+ " AND (:tenantId IS NULL OR m.tenantId = :tenantId)"` after the `WHERE m.createdAt BETWEEN :start AND :end` line. `aggregateByModel`: insert the same predicate before `" GROUP BY m.modelName"`. `aggregateByDay` (native):

```java
                            + " WHERE created_at BETWEEN :start AND :end AND status = 'SUCCESS'"
                            + " AND (CAST(:tenantId AS uuid) IS NULL"
                            + " OR tenant_id = CAST(:tenantId AS uuid))"
```

Service — each of the four methods gains `UUID tenantId` as third parameter and passes it through, e.g.

```java
    public Map<String, Object> getTotals(Instant start, Instant end, UUID tenantId) {
        List<Object[]> rows = costLogRepository.calculateTotals(start, end, tenantId);
```

Javadoc on each: `@param tenantId the caller's tenant, or null for all tenants (trusted caller, ADR-009 rule 5)`.

Controller — each of the four cost endpoints gains

```java
            @RequestHeader(value = TenantContext.HEADER_NAME, required = false) UUID tenantId
```

and passes `tenantId` to the service (import `io.emcip.common.tenant.TenantContext`, `java.util.UUID`, `RequestHeader` if not covered by a wildcard import).

Existing tests: in `CostTrackingServiceTest` add `null` as third argument to the service calls and `, null` / `isNull()` to the repository stubs; in `OrchestratorControllerCostsTest` change `any(), any()` to `any(), any(), any()` and add `null` to the direct controller calls. No assertion changes.

- [ ] **Step 4: Run the IT — 4 green.** Force red once: remove the predicate from `aggregateByDay` → `byDayNativeQueryCountsOnlyTheTenantsCalls` fails with 3; restore.

- [ ] **Step 5: Write the controller binding test** (`OrchestratorControllerCostsTenantTest`, standalone MockMvc so real header conversion runs):

```java
package io.emcip.llm.orchestrator.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.emcip.common.tenant.TenantContext;
import io.emcip.llm.orchestrator.client.OpenAiCompatibleLlmClient;
import io.emcip.llm.orchestrator.repository.LlmProviderConfigRepository;
import io.emcip.llm.orchestrator.repository.ModelConfigRepository;
import io.emcip.llm.orchestrator.repository.PromptTemplateRepository;
import io.emcip.llm.orchestrator.service.CostTrackingService;
import io.emcip.llm.orchestrator.service.LlmOrchestratorService;
import io.emcip.llm.orchestrator.service.LlmProviderConfigService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** TENANT-AUDIT C-1: the cost endpoints pass the X-Tenant-Id header through to the queries. */
@ExtendWith(MockitoExtension.class)
class OrchestratorControllerCostsTenantTest {

    private static final String RANGE = "?from=2026-09-01T00:00:00Z&to=2026-09-30T00:00:00Z";

    @Mock private LlmOrchestratorService orchestratorService;
    @Mock private CostTrackingService costTrackingService;
    @Mock private ModelConfigRepository modelConfigRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;
    @Mock private LlmProviderConfigService providerConfigService;
    @Mock private LlmProviderConfigRepository providerConfigRepository;
    @Mock private OpenAiCompatibleLlmClient llmClient;
    @InjectMocks private OrchestratorController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void headerTenantReachesEveryCostQuery() throws Exception {
        UUID tenant = UUID.randomUUID();
        when(costTrackingService.getTotals(any(), any(), any())).thenReturn(new LinkedHashMap<>());
        when(costTrackingService.getByModel(any(), any(), any())).thenReturn(List.of());
        when(costTrackingService.getByDay(any(), any(), any())).thenReturn(List.of());
        when(costTrackingService.getTotalCostForPeriod(any(), any(), any())).thenReturn(0.0);

        for (String path : List.of("totals", "by-model", "by-day", "summary")) {
            mvc.perform(get("/api/costs/" + path + RANGE)
                            .header(TenantContext.HEADER_NAME, tenant.toString()))
                    .andExpect(status().isOk());
        }

        verify(costTrackingService).getTotals(any(), any(), eq(tenant));
        verify(costTrackingService).getByModel(any(), any(), eq(tenant));
        verify(costTrackingService).getByDay(any(), any(), eq(tenant));
        verify(costTrackingService).getTotalCostForPeriod(any(), any(), eq(tenant));
    }

    @Test
    void noHeaderMeansAllTenants() throws Exception {
        when(costTrackingService.getTotals(any(), any(), any())).thenReturn(new LinkedHashMap<>());

        mvc.perform(get("/api/costs/totals" + RANGE)).andExpect(status().isOk());

        verify(costTrackingService).getTotals(any(), any(), isNull());
    }

    @Test
    void malformedHeaderIs400() throws Exception {
        mvc.perform(get("/api/costs/totals" + RANGE).header(TenantContext.HEADER_NAME, "nope"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(costTrackingService);
    }
}
```

First check the controller's base path: `grep -n "@RequestMapping" emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java` and adjust `/api/costs/` accordingly. Force red once: pass `null` instead of `tenantId` in one endpoint → `headerTenantReachesEveryCostQuery` fails; restore.

- [ ] **Step 6: Run the module** (`mvn -q -pl emcip-llm-orchestrator verify`), compare the test count with the baseline.
- [ ] **Step 7: Commit** — `fix(llm-orchestrator): scope cost aggregates to the X-Tenant-Id header (TENANT-AUDIT C-1)`

---

### Task 5: admin-api `PolicyEngineClient` — tenant header on every call, 404 passes through

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/client/PolicyEngineClient.java`
- Test: `emcip-admin-api/src/test/java/io/emcip/admin/api/client/PolicyEngineClientTenantTest.java` (new)

**Interfaces:**
- Produces: every public method sends `X-Tenant-Id` when the Reactor context carries a tenant; a policy-engine 404 surfaces as `ResponseStatusException(NOT_FOUND)` from `updateRule`, `deleteRule`, `getDecision`, `updateDecision`, `updateDecisionStatus`. `listRules`/`getRuleHistory` keep their `onErrorResume` fallback (history of another tenant's rule → empty list).

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.admin.api.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * TENANT-AUDIT P-1 / F-2: policy-engine can only enforce a tenant it is told about. Before this,
 * only createRule and listDecisions sent one.
 */
class PolicyEngineClientTenantTest {

    private static final String TENANT = UUID.randomUUID().toString();

    private MockWebServer server;
    private PolicyEngineClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new PolicyEngineClient(server.url("/").toString(), "t",
                CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    void everyCallSendsTheBoundTenant() throws Exception {
        var body = JsonNodeFactory.instance.objectNode().put("name", "r");
        List<Supplier<Mono<?>>> calls = List.of(
                () -> client.listRules().collectList(),
                () -> client.createRule(body),
                () -> client.updateRule("r1", body, "me"),
                () -> client.deleteRule("r1"),
                () -> client.dryRun(body),
                () -> client.getRuleHistory("r1").collectList(),
                () -> client.getDecision("d1"),
                () -> client.listDecisions(0, 50, null, null, null, null, null),
                () -> client.updateDecision("d1", body),
                () -> client.updateDecisionStatus("d1", "RESOLVED"));

        for (Supplier<Mono<?>> call : calls) {
            server.enqueue(new MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "application/json").body("{}").build());
            StepVerifier.create(call.get()
                            .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, TENANT)))
                    .expectNextCount(0).thenConsumeWhile(x -> true).verifyComplete();
            RecordedRequest req = server.takeRequest();
            assertThat(req.getHeaders().get(TenantContext.HEADER_NAME))
                    .as("%s %s", req.getMethod(), req.getTarget())
                    .isEqualTo(TENANT);
        }
    }

    @Test
    void noBoundTenantSendsNoHeader() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json").body("{}").build());

        StepVerifier.create(client.getDecision("d1")).expectNextCount(1).verifyComplete();

        assertThat(server.takeRequest().getHeaders().get(TenantContext.HEADER_NAME)).isNull();
    }

    @Test
    void policyEngine404IsA404() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        StepVerifier.create(client.getDecision("d1")
                        .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, TENANT)))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOfSatisfying(ResponseStatusException.class,
                                r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)))
                .verify();
    }
}
```

Check the mockwebserver3 `RecordedRequest` API in use (`grep -rn "RecordedRequest\|takeRequest" emcip-admin-api/src/test | head`) and use the same accessors (`getTarget()`/`getUrl()`, `getHeaders().get(...)`). If `listDecisions` with body `{}` trips on the page parse, give that call a page body.

- [ ] **Step 2: Run — expect red** at the first call without the header (`listRules`), and the 404 test failing with `WebClientResponseException$NotFound`.
  `mvn -q -pl emcip-admin-api test -Dtest=PolicyEngineClientTenantTest`

- [ ] **Step 3: Implement.** Add helpers:

```java
    /** Sends the bound tenant so policy-engine can enforce it (ADR-009 rule 8). */
    private static <S extends WebClient.RequestHeadersSpec<?>> S withTenant(S spec, ContextView ctx) {
        String tenantId = ReactorTenantContext.getTenantId(ctx);
        if (tenantId != null) {
            spec.header(TenantContext.HEADER_NAME, tenantId);
        }
        return spec;
    }

    /** A policy-engine 404 (missing, or another tenant's) is a 404 here, not a 500. */
    private static Throwable notFoundAsStatus(Throwable e) {
        return e instanceof WebClientResponseException.NotFound
                ? new ResponseStatusException(HttpStatus.NOT_FOUND)
                : e;
    }
```

Rewrite each method to build its request inside `Mono.deferContextual` / `Flux.deferContextual` and pass the spec through `withTenant(spec, ctx)` before `.retrieve()`; retry and circuit-breaker operators stay outside, unchanged. Example (`getDecision`):

```java
    public Mono<JsonNode> getDecision(String id) {
        return Mono.deferContextual(
                        ctx ->
                                withTenant(
                                                webClient.get().uri("/api/policy-decisions/{id}", id),
                                                ctx)
                                        .retrieve()
                                        .bodyToMono(JsonNode.class))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(PolicyEngineClient::notFoundAsStatus);
    }
```

Apply the same to `listRules`, `updateRule` (keep `X-Edited-By`), `deleteRule`, `dryRun`, `getRuleHistory`, `updateDecision`, `updateDecisionStatus`; in `createRule` and `listDecisions` replace the inline header code with `withTenant`. Add `.onErrorMap(PolicyEngineClient::notFoundAsStatus)` to `updateRule`, `deleteRule`, `getDecision`, `updateDecision`, `updateDecisionStatus` (after the circuit breaker; the retry config already ignores 404). Imports: `io.emcip.common.tenant.TenantContext`, `org.springframework.web.reactive.function.client.WebClientResponseException`, `reactor.util.context.ContextView`.

- [ ] **Step 4: Run** the new test (3 green) and the existing `PolicyEngineClientTest` (green). Force red once: make `withTenant` return `spec` without the header → `everyCallSendsTheBoundTenant` fails; restore.
- [ ] **Step 5: Commit** — `fix(admin-api): send the bound tenant on every policy-engine call (TENANT-AUDIT P-1/F-2)`

---

### Task 6: admin-api `FlagService.reply` — own tenant check (F-1)

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/FlagService.java:75-128`
- Test: `emcip-admin-api/src/test/java/io/emcip/admin/api/service/FlagServiceTenantTest.java` (new)

**Interfaces:**
- Consumes: `GroupProfileRepository.findByTelegramChatIdAndTenantId(Long, UUID) → Mono<GroupProfile>` (exists).

- [ ] **Step 1: Write the failing test** — same mock setup as `FlagServiceTest` (copy its fields and `setUp`), plus:

```java
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private JsonNode decisionOf(UUID tenant) {
        ObjectNode d = JsonNodeFactory.instance.objectNode();
        d.put("id", "flag-1");
        if (tenant != null) {
            d.put("tenantId", tenant.toString());
        }
        d.putObject("metadata").put("chatId", -100123L).put("telegramMessageId", 7L);
        return d;
    }

    @Test
    void replyToAnotherTenantsFlagIs404AndNothingIsSent() {
        when(policyEngineClient.getDecision("flag-1")).thenReturn(Mono.just(decisionOf(B)));

        StepVerifier.create(flagService.reply("flag-1", "hi", "GROUP", false, false, null)
                        .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, A.toString())))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOfSatisfying(ResponseStatusException.class,
                                r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)))
                .verify();

        verifyNoInteractions(groupProfileRepository, watchedGroupRepository, accountRepository,
                tdlibClient, auditPublisher);
    }

    @Test
    void noteOnAnotherTenantsFlagIs404AndNoAuditEvent() {
        when(policyEngineClient.getDecision("flag-1")).thenReturn(Mono.just(decisionOf(B)));

        StepVerifier.create(flagService.reply("flag-1", "hi", "NOTE", false, false, null)
                        .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, A.toString())))
                .expectError(ResponseStatusException.class)
                .verify();

        verifyNoInteractions(auditPublisher);
    }

    @Test
    void replyToOwnFlagLooksUpTheGroupWithinTheTenant() {
        when(policyEngineClient.getDecision("flag-1")).thenReturn(Mono.just(decisionOf(A)));
        when(groupProfileRepository.findByTelegramChatIdAndTenantId(-100123L, A))
                .thenReturn(Mono.empty());

        StepVerifier.create(flagService.reply("flag-1", "hi", "GROUP", false, false, null)
                        .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, A.toString())))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(groupProfileRepository).findByTelegramChatIdAndTenantId(-100123L, A);
        verify(groupProfileRepository, never()).findByTelegramChatId(any());
    }
```

Use the target value the controller really sends for a group reply (`grep -n "target" emcip-admin-api/src/main/java/io/emcip/admin/api/service/FlagService.java | head`), and check `publishNoteAuditEvent` uses `auditPublisher`. With `MockitoExtension` strict stubs, `setUp`'s `circuitBreakerRegistry` stub is used by the constructor — fine.

- [ ] **Step 2: Run — expect red:** today the NOT-matching flag reaches `groupProfileRepository.findByTelegramChatId` (unstubbed → NPE or empty), not a 404.

- [ ] **Step 3: Implement.** Wrap the body of `reply` in `Mono.deferContextual(ctx -> { … })`; directly after `getDecision(flagId)`:

```java
                        flag -> {
                            String bound = ReactorTenantContext.getTenantId(ctx);
                            if (bound != null && !bound.equals(tenantOf(flag))) {
                                // F-1: never act on another tenant's flag — its group, its
                                // account. Answer like a missing flag (ADR-009 rule 8).
                                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
                            }
```

and replace the group lookup with

```java
                            Mono<GroupProfile> profile =
                                    bound != null
                                            ? groupProfileRepository
                                                    .findByTelegramChatIdAndTenantId(
                                                            chatId, UUID.fromString(bound))
                                            : groupProfileRepository.findByTelegramChatId(chatId);
                            return profile.switchIfEmpty(…unchanged…)
```

Helper:

```java
    private static String tenantOf(JsonNode decision) {
        JsonNode t = decision.get("tenantId");
        return t == null || t.isNull() ? null : t.asText();
    }
```

(Imports: `ReactorTenantContext`, `ResponseStatusException`, `HttpStatus`, `GroupProfile` if not present.) Check how `FlagController` maps errors from `reply` (`grep -n "onErrorResume\|onErrorMap\|IllegalArgumentException" emcip-admin-api/src/main/java/io/emcip/admin/api/controller/FlagController.java`) — a `ResponseStatusException` must not be rewritten into another status there; if it is, let `ResponseStatusException` pass through.

- [ ] **Step 4: Run** the new test (3 green) and `FlagServiceTest`, `FlagControllerTest` (green). Force red once: remove the tenant comparison → test 1 fails; restore.
- [ ] **Step 5: Commit** — `fix(admin-api): flag reply acts only on the caller's own flag and group (TENANT-AUDIT F-1)`

---

### Task 7: admin-api `CostsProxyController` — send the bound tenant (C-1, edge)

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/CostsProxyController.java`
- Test: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/CostsProxyControllerTenantTest.java` (new)

- [ ] **Step 1: Write the failing test**

```java
package io.emcip.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.common.tenant.ReactorTenantContext;
import io.emcip.common.tenant.TenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** TENANT-AUDIT C-1: a tenant-bound caller's cost view must be scoped by llm-orchestrator. */
@ExtendWith(MockitoExtension.class)
class CostsProxyControllerTenantTest {

    @Mock private ExchangeFunction exchangeFunction;
    private CostsProxyController controller;

    @BeforeEach
    void setUp() {
        controller = new CostsProxyController(
                WebClient.builder().exchangeFunction(exchangeFunction).build(),
                CircuitBreakerRegistry.ofDefaults());
        when(exchangeFunction.exchange(any())).thenAnswer(inv -> Mono.just(
                ClientResponse.create(HttpStatus.OK).body("{}").build()));
    }

    @Test
    void everyCostCallSendsTheBoundTenant() {
        String tenant = UUID.randomUUID().toString();
        for (Function<CostsProxyController, Mono<String>> call : java.util.List.<Function<CostsProxyController, Mono<String>>>of(
                c -> c.getTotals("a", "b"),
                c -> c.getCostsByModel("a", "b"),
                c -> c.getCostsByDay("a", "b"))) {
            StepVerifier.create(call.apply(controller)
                            .contextWrite(ctx -> ReactorTenantContext.withTenant(ctx, tenant)))
                    .expectNext("{}").verifyComplete();
        }
        ArgumentCaptor<ClientRequest> sent = ArgumentCaptor.forClass(ClientRequest.class);
        verify(exchangeFunction, org.mockito.Mockito.times(3)).exchange(sent.capture());
        assertThat(sent.getAllValues())
                .allSatisfy(r -> assertThat(r.headers().getFirst(TenantContext.HEADER_NAME))
                        .isEqualTo(tenant));
    }

    @Test
    void adminModeSendsNoTenant() {
        StepVerifier.create(controller.getTotals("a", "b")
                        .contextWrite(ReactorTenantContext::withAdminMode))
                .expectNext("{}").verifyComplete();
        ArgumentCaptor<ClientRequest> sent = ArgumentCaptor.forClass(ClientRequest.class);
        verify(exchangeFunction).exchange(sent.capture());
        assertThat(sent.getValue().headers().getFirst(TenantContext.HEADER_NAME)).isNull();
    }
}
```

- [ ] **Step 2: Run — expect red** (header null).
- [ ] **Step 3: Implement.** Extract one private method and make the three endpoints call it:

```java
    private Mono<String> forward(String path, String from, String to) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantId = ReactorTenantContext.getTenantId(ctx);
                            var spec =
                                    orchestratorClient
                                            .get()
                                            .uri(
                                                    b ->
                                                            b.path(path)
                                                                    .queryParam("from", from)
                                                                    .queryParam("to", to)
                                                                    .build());
                            // A tenant-bound caller sees its own costs only (ADR-009 rule 8);
                            // ADMIN admin mode sends none = all tenants.
                            if (tenantId != null) {
                                spec.header(TenantContext.HEADER_NAME, tenantId);
                            }
                            return spec.retrieve().bodyToMono(String.class);
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
```

e.g. `getTotals` → `return forward("/api/costs/totals", from, to);`.

- [ ] **Step 4: Run** new + existing `CostsProxyControllerTest` (green). Force red once (skip the header line) → test 1 fails; restore.
- [ ] **Step 5: Commit** — `fix(admin-api): send the bound tenant on cost queries (TENANT-AUDIT C-1)`

---

### Task 8: Documentation — ADR-009 amendment, diagram, guide, BACKLOG

**Files:**
- Modify: `documentation/adrs/ADR-009-multi-tenancy.md` (rule 8, Consequences "Negative", Related Specs)
- Modify: `documentation/diagrams/sequence-tenant-propagation.puml`
- Modify: `documentation/architecture-guide.adoc` (the section that includes the diagram / describes tenant propagation — `grep -n "tenant" documentation/architecture-guide.adoc`)
- Modify: `docs/superpowers/BACKLOG.md` (row TENANT-AUDIT at line ~85, At-a-glance line 10; add TENANT-WEBFILTER row, P4)
- Check the documentation-checklist skill mapping (`.claude/skills/documentation-checklist*` — read only) for anything else touched by policy-engine / llm-orchestrator / admin-api changes.

- [ ] **Step 1: ADR-009 rule 8** — replace the bullet "**Tenant-bound reads in JPA services:** the Hibernate `tenantFilter`." with:

```markdown
   - **Downstream services on the HTTP path take the tenant explicitly** (TENANT-AUDIT): admin-api
     sends the bound tenant as `X-Tenant-Id` on every call. Reactive services (audit, moderation,
     policy-engine) put it into the Reactor context with their `TenantWebFilter` (malformed → 400)
     and pass it into queries and ownership checks; servlet endpoints take it as an explicit header
     parameter (llm-orchestrator costs). No header = trusted caller (rule 5).
   - **Kafka path in JPA services:** the Hibernate `tenantFilter`, enabled from the `TenantContext`
     ThreadLocal the consumer binds. It never sees an HTTP request's tenant (blocking calls run on
     other threads), so the HTTP path must not rely on it.
```

In "Negative / accepted" replace the TENANT-AUDIT bullet with: "Three copies of `TenantWebFilter` (audit, moderation, policy-engine) — consolidation tracked as TENANT-WEBFILTER. TENANT-AUDIT PR 2 (Telegram account create, simulation, moderation global rules, audit correlation lookup, UI permission) is still open." Add the PR 1 spec to Related Specs.

- [ ] **Step 2: Diagram** — add a section before `== Kafka Path ==`:

```plantuml
== HTTP Path — admin-api -> policy-engine / llm-orchestrator (TENANT-AUDIT) ==

participant "PolicyEngineClient /\nCostsProxyController" as PEC
participant "TenantWebFilter\n(policy-engine)" as PTWF
participant "Policy controllers\n(RequestTenant.of)" as PCtl
participant "OrchestratorController\ncost endpoints" as OCtl
database "PostgreSQL" as PDB

PEC -> PTWF : X-Tenant-Id: <bound tenant>\n(none in ADMIN admin mode)
alt header malformed
    PTWF --> PEC : 400
else valid or absent
    PTWF -> PCtl : Reactor context tenant | none
    PCtl -> PDB : tenant-scoped query /\nfindById + ownership check
    alt another tenant's rule or decision
        PCtl --> PEC : 404 (same as missing)
    else own (or global decision, read only)
        PCtl --> PEC : 200 / 204
    end
end
PEC -> OCtl : /api/costs/* + X-Tenant-Id
OCtl -> PDB : aggregate WHERE tenant_id = :tenantId\n(null = all tenants)
```

- [ ] **Step 3: Architecture guide** — in the tenant propagation text, name the policy-engine `TenantWebFilter` and the llm-orchestrator cost header, and state that the Hibernate filter is Kafka-path only.

- [ ] **Step 4: BACKLOG** — TENANT-AUDIT row: status "🟡 PR 1 (F-1, P-1, F-2, C-1) in review; PR 2 (T-1, S-1, M-1, A-1, U-1) open", with the findings table from spec §0 plus the PR 2 findings (T-1 MEDIUM, S-1 MEDIUM, A-1 LOW, M-1 LOW, U-1 LOW, D-1 doc — D-1 closed by this PR). Add row `TENANT-WEBFILTER | Move the three reactive TenantWebFilter copies (audit, moderation, policy-engine) into emcip-core | LOW | P4 | S | ⏳`. Update the At-a-glance "Next:" sentence.

- [ ] **Step 5: Render** — build the docs the way the project does (`grep -n "asciidoctor" pom.xml documentation/pom.xml 2>/dev/null | head`; run that goal), then open the generated diagram image and grep the build log for `Syntax Error`/`ERROR` (diagram errors do not fail the build — DOCS-FAILIF).
- [ ] **Step 6: Commit** — `docs(tenant): ADR-009 HTTP-path enforcement, tenant propagation diagram, TENANT-AUDIT status`

---

### Task 9: Verify and open the PR

- [ ] **Step 1:** `mvn spotless:apply` (0 changed), then the full CI equivalent for the three modules plus reactor dependencies: `mvn -q -pl emcip-policy-engine,emcip-llm-orchestrator,emcip-admin-api -am verify` (the `-am` builds emcip-core into `target/` only — no `install`). Run `mvn -pl emcip-policy-engine,emcip-llm-orchestrator,emcip-admin-api pmd:check` (not part of `verify`).
- [ ] **Step 2:** Compare test counts with the baselines taken before Task 1 — each module only grows; list the new test files.
- [ ] **Step 3:** `git fetch origin && git log --oneline origin/main -5` — if main moved, rebase `fix/tenant-audit` onto `origin/main` and re-run Step 1.
- [ ] **Step 4:** Push `fix/tenant-audit`, open the PR "fix(tenant): TENANT-AUDIT PR 1 — policy rules, flags, flag reply, costs scoped to the caller's tenant" with the findings table, the design correction (§1), test evidence (red → green per task), deploy notes (policy-engine, llm-orchestrator, admin-api; no migrations; deploy order irrelevant — without the header both downstream services behave as before), and "Round 3 T-04 stays held until PR 2". Wait for all checks green (CodeQL, build, code-quality, trufflehog, ci-gate); report to the user. Do not merge.
