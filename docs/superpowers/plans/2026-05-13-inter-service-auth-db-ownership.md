# Inter-Service Auth & DB Ownership Fix

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the shared-database anti-pattern — admin-api currently holds JPA/R2DBC repos for tables it doesn't own; replace those with WebClient proxy calls so each domain service is the sole owner of its data.

**Architecture:** Domain services (`moderation-service` :9085, `policy-engine` :9083, `audit-service` :9086) each expose a REST management API protected by `X-Service-Token`. Admin-api drops all foreign entities/repos and becomes a pure BFF: its controllers forward calls to the owning service via a configured `WebClient` that injects the service token. Liquibase migrations that touch a table stay exclusively in the service that creates that table.

**Tech Stack:** Java 21, Spring Boot 4, Spring WebFlux, R2DBC (moderation + audit), JPA+WebFlux hybrid (policy-engine), Liquibase, Mockito + Spring WebFlux test slice.

**Dev note:** DB can be wiped and recreated freely — no production data.

---

## File Map

### Track A — moderation-service (independent)
| Action | Path |
|--------|------|
| Create | `emcip-moderation-service/src/main/java/io/emcip/moderation/service/config/ServiceTokenFilter.java` |
| Modify | `emcip-moderation-service/src/main/java/io/emcip/moderation/service/repository/ModerationRuleRepository.java` |
| Create | `emcip-moderation-service/src/main/java/io/emcip/moderation/service/controller/ModerationRuleController.java` |
| Modify | `emcip-moderation-service/src/main/resources/application.yml` |
| Create | `emcip-moderation-service/src/test/java/io/emcip/moderation/service/controller/ModerationRuleControllerTest.java` |

### Track B — policy-engine (independent)
| Action | Path |
|--------|------|
| Create | `emcip-policy-engine/src/main/java/io/emcip/policy/engine/config/ServiceTokenFilter.java` |
| Create | `emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyRuleController.java` |
| Create | `emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyDecisionController.java` |
| Modify | `emcip-policy-engine/src/main/resources/application.yml` |
| Modify | `emcip-policy-engine/src/main/resources/db/changelog/changes/002-create-policy-rules-table.xml` |
| Create | `emcip-policy-engine/src/test/java/io/emcip/policy/engine/controller/PolicyRuleControllerTest.java` |
| Create | `emcip-policy-engine/src/test/java/io/emcip/policy/engine/controller/PolicyDecisionControllerTest.java` |

### Track C — audit-service (independent)
| Action | Path |
|--------|------|
| Create | `emcip-audit-service/src/main/java/io/emcip/audit/service/config/ServiceTokenFilter.java` |
| Modify | `emcip-audit-service/src/main/resources/application.yml` |
| Create | `emcip-audit-service/src/test/java/io/emcip/audit/service/config/ServiceTokenFilterTest.java` |

### Track D — admin-api cleanup (runs after A + B + C)
| Action | Path |
|--------|------|
| Create | `emcip-admin-api/src/main/java/io/emcip/admin/api/client/ModerationServiceClient.java` |
| Create | `emcip-admin-api/src/main/java/io/emcip/admin/api/client/PolicyEngineClient.java` |
| Create | `emcip-admin-api/src/main/java/io/emcip/admin/api/client/AuditServiceClient.java` |
| Replace | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ModerationRuleController.java` |
| Replace | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/PolicyRuleController.java` |
| Replace | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/FlagController.java` |
| Replace | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AuditController.java` |
| Delete | `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/{ModerationRule,PolicyRule,PolicyDecision,AuditEvent}.java` |
| Delete | `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/{ModerationRule,PolicyRule,PolicyDecision,AuditEvent}Repository.java` |
| Delete | `emcip-admin-api/src/main/resources/db/changelog/changes/010-policy-rules-priority-default.xml` |
| Modify | `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml` |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/security/SecurityConfig.java` |
| Modify | `emcip-admin-api/src/main/resources/application.yml` |

---

## Track A — moderation-service

### Task A1: ServiceTokenFilter

**Files:**
- Create: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/config/ServiceTokenFilter.java`
- Modify: `emcip-moderation-service/src/main/resources/application.yml`

- [ ] **Step 1: Add `admin.service-token` to application.yml**

Add to `emcip-moderation-service/src/main/resources/application.yml`:
```yaml
admin:
  service-token: ${ADMIN_SERVICE_TOKEN:internal-service-token}
```

- [ ] **Step 2: Create ServiceTokenFilter**

```java
// emcip-moderation-service/src/main/java/io/emcip/moderation/service/config/ServiceTokenFilter.java
package io.emcip.moderation.service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class ServiceTokenFilter implements WebFilter {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    @Value("${admin.service-token:internal-service-token}")
    private String configuredToken;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst(SERVICE_TOKEN_HEADER);
        if (token == null || !configuredToken.equals(token)) {
            log.warn("Rejected request to {} — missing or invalid X-Service-Token", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
```

- [ ] **Step 3: Verify it compiles**
```bash
cd /home/ben/Development/ecip && mvn compile -pl emcip-moderation-service 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

---

### Task A2: ModerationRuleController

**Files:**
- Modify: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/repository/ModerationRuleRepository.java`
- Create: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/controller/ModerationRuleController.java`
- Create: `emcip-moderation-service/src/test/java/io/emcip/moderation/service/controller/ModerationRuleControllerTest.java`

- [ ] **Step 1: Write the failing controller test**

```java
// emcip-moderation-service/src/test/java/io/emcip/moderation/service/controller/ModerationRuleControllerTest.java
package io.emcip.moderation.service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(
        controllers = ModerationRuleController.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = io.emcip.moderation.service.config.ServiceTokenFilter.class))
class ModerationRuleControllerTest {

    @Autowired WebTestClient client;
    @MockitoBean ModerationRuleRepository repository;

    private ModerationRule rule(long id, String name) {
        ModerationRule r = new ModerationRule();
        r.setId(id);
        r.setName(name);
        r.setRuleType("KEYWORD");
        r.setPattern("spam");
        r.setSeverity("HIGH");
        r.setAction("FLAG");
        r.setEnabled(true);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    @Test
    void list_returnsAllRulesOrdered() {
        when(repository.findAllOrdered()).thenReturn(Flux.just(rule(1L, "spam-rule")));
        client.get().uri("/api/moderation-rules")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ModerationRule.class).hasSize(1);
    }

    @Test
    void create_returns201() {
        ModerationRule r = rule(null, "new-rule");
        when(repository.save(any())).thenReturn(Mono.just(rule(2L, "new-rule")));
        client.post().uri("/api/moderation-rules")
                .bodyValue(r)
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void update_returns200() {
        ModerationRule existing = rule(1L, "old");
        ModerationRule update = rule(1L, "updated");
        when(repository.findById(1L)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenReturn(Mono.just(update));
        client.put().uri("/api/moderation-rules/1")
                .bodyValue(update)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void delete_returns204() {
        when(repository.deleteById(1L)).thenReturn(Mono.empty());
        client.delete().uri("/api/moderation-rules/1")
                .exchange()
                .expectStatus().isNoContent();
    }
}
```

- [ ] **Step 2: Run test — expect FAIL (controller does not exist yet)**
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-moderation-service \
  -Dtest=ModerationRuleControllerTest 2>&1 | tail -10
```
Expected: compilation error or `NoSuchBeanDefinitionException`

- [ ] **Step 3: Add `findAllOrdered` to repository**

Replace the body of `ModerationRuleRepository.java`:
```java
package io.emcip.moderation.service.repository;

import io.emcip.moderation.service.entity.ModerationRule;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ModerationRuleRepository extends ReactiveCrudRepository<ModerationRule, Long> {

    Flux<ModerationRule> findByEnabledTrue();

    @Query("SELECT * FROM moderation_rules ORDER BY rule_type ASC, name ASC")
    Flux<ModerationRule> findAllOrdered();
}
```

- [ ] **Step 4: Create ModerationRuleController**

```java
// emcip-moderation-service/src/main/java/io/emcip/moderation/service/controller/ModerationRuleController.java
package io.emcip.moderation.service.controller;

import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/moderation-rules")
@RequiredArgsConstructor
public class ModerationRuleController {

    private final ModerationRuleRepository repository;

    @GetMapping
    public Flux<ModerationRule> list() {
        return repository.findAllOrdered();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ModerationRule> create(@RequestBody ModerationRule rule) {
        rule.setId(null);
        rule.setCreatedAt(Instant.now());
        rule.setUpdatedAt(Instant.now());
        if (rule.getSeverity() == null || rule.getSeverity().isBlank()) {
            rule.setSeverity("MEDIUM");
        }
        if (rule.getAction() == null || rule.getAction().isBlank()) {
            rule.setAction("FLAG");
        }
        rule.setEnabled(true);
        return repository.save(rule);
    }

    @PutMapping("/{id}")
    public Mono<ModerationRule> update(@PathVariable Long id, @RequestBody ModerationRule rule) {
        return repository
                .findById(id)
                .flatMap(
                        existing -> {
                            existing.setName(rule.getName());
                            existing.setRuleType(rule.getRuleType());
                            existing.setPattern(rule.getPattern());
                            existing.setSeverity(rule.getSeverity());
                            existing.setAction(rule.getAction());
                            existing.setEnabled(rule.isEnabled());
                            existing.setUpdatedAt(Instant.now());
                            return repository.save(existing);
                        });
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable Long id) {
        return repository.deleteById(id);
    }
}
```

- [ ] **Step 5: Run tests — expect PASS**
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-moderation-service \
  -Dtest=ModerationRuleControllerTest 2>&1 | tail -10
```
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 6: Run all moderation-service tests**
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-moderation-service 2>&1 | tail -8
```
Expected: `BUILD SUCCESS`

- [ ] **Step 7: Spotless + commit**
```bash
cd /home/ben/Development/ecip && mvn spotless:apply -pl emcip-moderation-service
git add emcip-moderation-service/
git commit -m "feat(moderation-service): add REST management API + X-Service-Token guard

moderation-service now owns its /api/moderation-rules CRUD endpoints.
ServiceTokenFilter rejects requests missing a valid X-Service-Token header.
Enables admin-api to proxy rule management here instead of hitting the DB directly."
```

---

## Track B — policy-engine

### Task B1: Liquibase fix — priority DEFAULT 0

**Files:**
- Modify: `emcip-policy-engine/src/main/resources/db/changelog/changes/002-create-policy-rules-table.xml`

- [ ] **Step 1: Add `DEFAULT 0` and `rule_version` column to base create-table migration**

In `002-create-policy-rules-table.xml`, find the `priority` column and change it to:
```xml
<column name="priority" type="INTEGER" defaultValueNumeric="0">
    <constraints nullable="false"/>
</column>
```

Also add `rule_version` column (currently added in migration 005, but cleanest to have it at creation in dev):
```xml
<column name="rule_version" type="INTEGER" defaultValueNumeric="1">
    <constraints nullable="false"/>
</column>
```

Note: Migration `005` will still run and try to add `rule_version` again — prevent the conflict by wrapping that `addColumn` in a `preConditions` check, or simply remove the `addColumn rule_version` from `005` since the column now exists from `002`. Edit `005-policy-rule-versioning.xml` to remove the `rule_version` addColumn block (keep `effective_from`, `effective_to`, and the indexes).

After editing `005`, the file should contain only:
```xml
<changeSet id="005" author="phase5">
    <addColumn tableName="policy_rules">
        <column name="effective_from" type="TIMESTAMPTZ"/>
    </addColumn>
    <addColumn tableName="policy_rules">
        <column name="effective_to" type="TIMESTAMPTZ"/>
    </addColumn>
    <createIndex indexName="idx_policy_rules_name_version" tableName="policy_rules">
        <column name="name"/>
        <column name="rule_version"/>
    </createIndex>
    <createIndex indexName="idx_policy_rules_effective" tableName="policy_rules">
        <column name="effective_from"/>
        <column name="effective_to"/>
    </createIndex>
</changeSet>
```

- [ ] **Step 2: Verify policy-engine still compiles**
```bash
cd /home/ben/Development/ecip && mvn compile -pl emcip-policy-engine 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

---

### Task B2: policy-engine — ServiceTokenFilter + PolicyRuleController

**Files:**
- Create: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/config/ServiceTokenFilter.java`
- Create: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyRuleController.java`
- Modify: `emcip-policy-engine/src/main/resources/application.yml`
- Create: `emcip-policy-engine/src/test/java/io/emcip/policy/engine/controller/PolicyRuleControllerTest.java`

- [ ] **Step 1: Add `admin.service-token` to application.yml**

Add to `emcip-policy-engine/src/main/resources/application.yml`:
```yaml
admin:
  service-token: ${ADMIN_SERVICE_TOKEN:internal-service-token}
```

- [ ] **Step 2: Create ServiceTokenFilter (identical pattern to moderation-service)**

```java
// emcip-policy-engine/src/main/java/io/emcip/policy/engine/config/ServiceTokenFilter.java
package io.emcip.policy.engine.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class ServiceTokenFilter implements WebFilter {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    @Value("${admin.service-token:internal-service-token}")
    private String configuredToken;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst(SERVICE_TOKEN_HEADER);
        if (token == null || !configuredToken.equals(token)) {
            log.warn("Rejected request to {} — missing or invalid X-Service-Token", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
```

- [ ] **Step 3: Write the failing controller test**

```java
// emcip-policy-engine/src/test/java/io/emcip/policy/engine/controller/PolicyRuleControllerTest.java
package io.emcip.policy.engine.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(
        controllers = PolicyRuleController.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = io.emcip.policy.engine.config.ServiceTokenFilter.class))
class PolicyRuleControllerTest {

    @Autowired WebTestClient client;
    @MockitoBean PolicyRuleConfigRepository repository;

    private PolicyRuleConfig rule(String id, String name) {
        PolicyRuleConfig r = new PolicyRuleConfig();
        r.setId(id);
        r.setName(name);
        r.setTargetIntent("SPAM");
        r.setAction("BLOCK");
        r.setMinConfidence(0.8);
        r.setPriority(10);
        r.setActive(true);
        r.setRuleVersion(1);
        r.setCreatedAt(Instant.now());
        return r;
    }

    @Test
    void listActive_returnsActiveRules() {
        when(repository.findByActiveTrueOrderByPriorityAsc()).thenReturn(List.of(rule("r1", "spam-rule")));
        client.get().uri("/api/policy-rules")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(PolicyRuleConfig.class).hasSize(1);
    }

    @Test
    void create_returns201() {
        PolicyRuleConfig r = rule(null, "new-rule");
        when(repository.save(any())).thenReturn(rule("r2", "new-rule"));
        client.post().uri("/api/policy-rules")
                .bodyValue(r)
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void update_returns200() {
        PolicyRuleConfig existing = rule("r1", "old");
        PolicyRuleConfig update = rule("r1", "updated");
        when(repository.findById("r1")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenReturn(update);
        client.put().uri("/api/policy-rules/r1")
                .bodyValue(update)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void delete_returns204() {
        client.delete().uri("/api/policy-rules/r1")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void history_returnsRulesByName() {
        when(repository.findByName(anyString())).thenReturn(Optional.of(rule("r1", "spam-rule")));
        // history returns versions — use findByName or a dedicated query
        // for now history returns list wrapping the single findByName result
        client.get().uri("/api/policy-rules/history/spam-rule")
                .exchange()
                .expectStatus().isOk();
    }
}
```

- [ ] **Step 4: Run test — expect FAIL**
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-policy-engine \
  -Dtest=PolicyRuleControllerTest 2>&1 | tail -10
```
Expected: compilation error — `PolicyRuleController` does not exist

- [ ] **Step 5: Create PolicyRuleController**

Policy-engine uses WebFlux for HTTP but blocking JPA for DB — wrap repository calls with `Mono.fromCallable`.

```java
// emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyRuleController.java
package io.emcip.policy.engine.controller;

import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/policy-rules")
@RequiredArgsConstructor
public class PolicyRuleController {

    private final PolicyRuleConfigRepository repository;

    @GetMapping
    public Flux<PolicyRuleConfig> listActive() {
        return Mono.fromCallable(repository::findByActiveTrueOrderByPriorityAsc)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PolicyRuleConfig> create(@RequestBody PolicyRuleConfig rule) {
        rule.setId(UUID.randomUUID().toString());
        if (rule.getTargetIntent() == null || rule.getTargetIntent().isBlank()) {
            rule.setTargetIntent("*");
        }
        if (rule.getMinConfidence() == null) rule.setMinConfidence(0.0);
        if (rule.getPriority() == null) rule.setPriority(0);
        if (rule.getActive() == null) rule.setActive(true);
        if (rule.getRuleVersion() == null) rule.setRuleVersion(1);
        return Mono.fromCallable(() -> repository.save(rule))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}")
    public Mono<PolicyRuleConfig> update(
            @PathVariable String id, @RequestBody PolicyRuleConfig rule) {
        return Mono.fromCallable(() -> repository.findById(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        opt ->
                                opt.map(
                                                existing -> {
                                                    existing.setName(rule.getName());
                                                    existing.setTargetIntent(
                                                            rule.getTargetIntent() != null
                                                                    ? rule.getTargetIntent()
                                                                    : existing.getTargetIntent());
                                                    existing.setAction(rule.getAction());
                                                    existing.setPriority(rule.getPriority());
                                                    existing.setActive(rule.getActive());
                                                    existing.setMinConfidence(
                                                            rule.getMinConfidence());
                                                    existing.setMaxConfidence(
                                                            rule.getMaxConfidence());
                                                    existing.setDescription(rule.getDescription());
                                                    existing.setReason(rule.getReason());
                                                    existing.setEffectiveFrom(
                                                            rule.getEffectiveFrom());
                                                    existing.setEffectiveTo(rule.getEffectiveTo());
                                                    return Mono.fromCallable(
                                                                    () ->
                                                                            repository.save(
                                                                                    existing))
                                                            .subscribeOn(
                                                                    Schedulers.boundedElastic());
                                                })
                                        .orElse(Mono.empty()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String id) {
        return Mono.fromRunnable(() -> repository.deleteById(id))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @GetMapping("/history/{name}")
    public Mono<List<PolicyRuleConfig>> history(@PathVariable String name) {
        return Mono.fromCallable(() -> repository.findAll().stream()
                        .filter(r -> name.equals(r.getName()))
                        .sorted(java.util.Comparator.comparingInt(PolicyRuleConfig::getRuleVersion))
                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }
}
```

- [ ] **Step 6: Run tests — expect PASS**
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-policy-engine \
  -Dtest=PolicyRuleControllerTest 2>&1 | tail -10
```
Expected: `Tests run: 5, Failures: 0, Errors: 0`

---

### Task B3: policy-engine — PolicyDecisionController

**Files:**
- Create: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyDecisionController.java`
- Create: `emcip-policy-engine/src/test/java/io/emcip/policy/engine/controller/PolicyDecisionControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
// emcip-policy-engine/src/test/java/io/emcip/policy/engine/controller/PolicyDecisionControllerTest.java
package io.emcip.policy.engine.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import io.emcip.policy.engine.entity.PolicyDecision;
import io.emcip.policy.engine.repository.PolicyDecisionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(
        controllers = PolicyDecisionController.class,
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = io.emcip.policy.engine.config.ServiceTokenFilter.class))
class PolicyDecisionControllerTest {

    @Autowired WebTestClient client;
    @MockitoBean PolicyDecisionRepository repository;

    private PolicyDecision decision(String id) {
        PolicyDecision d = new PolicyDecision();
        d.setId(id);
        d.setDecision("BLOCK");
        d.setTimestamp(Instant.now());
        d.setSignalStatus("PENDING");
        return d;
    }

    @Test
    void getFlags_returnsDecisions() {
        when(repository.findTopByDecisionNotOrderByTimestampDesc(any(), anyInt()))
                .thenReturn(List.of(decision("d1")));
        client.get().uri("/api/policy-decisions?size=10")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(PolicyDecision.class).hasSize(1);
    }

    @Test
    void updateStatus_returns204() {
        when(repository.updateSignalStatus(any(), any())).thenReturn(1);
        client.patch().uri("/api/policy-decisions/d1/status")
                .bodyValue(Map.of("status", "REVIEWED"))
                .exchange()
                .expectStatus().isNoContent();
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-policy-engine \
  -Dtest=PolicyDecisionControllerTest 2>&1 | tail -10
```

- [ ] **Step 3: Add query methods to PolicyDecisionRepository**

Open `emcip-policy-engine/src/main/java/io/emcip/policy/engine/repository/PolicyDecisionRepository.java` and add:
```java
// Add to existing repository interface:
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

List<PolicyDecision> findTopByDecisionNotOrderByTimestampDesc(String decision, int limit);

@Query("SELECT p FROM PolicyDecision p WHERE p.decision = :decision ORDER BY p.timestamp DESC LIMIT :limit")
List<PolicyDecision> findByDecisionOrderByTimestampDesc(
    @org.springframework.data.repository.query.Param("decision") String decision,
    @org.springframework.data.repository.query.Param("limit") int limit);

@Modifying
@Transactional
@Query("UPDATE PolicyDecision p SET p.signalStatus = :status WHERE p.id = :id")
int updateSignalStatus(
    @org.springframework.data.repository.query.Param("id") String id,
    @org.springframework.data.repository.query.Param("status") String status);
```

- [ ] **Step 4: Check PolicyDecision entity has `signalStatus` field**

Open `emcip-policy-engine/src/main/java/io/emcip/policy/engine/entity/PolicyDecision.java` — verify it has `signalStatus` column. If it doesn't exist yet:

```java
// emcip-policy-engine/src/main/java/io/emcip/policy/engine/entity/PolicyDecision.java
package io.emcip.policy.engine.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Entity
@Table(name = "policy_decisions")
@Data
public class PolicyDecision {

    @Id private String id;

    @Column(name = "event_id") private String eventId;
    @Column(name = "source_event_id") private String sourceEventId;
    @Column(name = "policy_id") private String policyId;
    @Column(nullable = false) private String decision;
    private String reason;
    @Column(name = "original_intent") private String originalIntent;
    private Double confidence;
    @Column(name = "matched_rules") private String matchedRules;
    private String metadata;
    private Instant timestamp;
    @Column(name = "signal_status") private String signalStatus;
    @Column(name = "tenant_id") private java.util.UUID tenantId;

    @Version private Long version;
}
```

- [ ] **Step 5: Create PolicyDecisionController**

```java
// emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyDecisionController.java
package io.emcip.policy.engine.controller;

import io.emcip.policy.engine.entity.PolicyDecision;
import io.emcip.policy.engine.repository.PolicyDecisionRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/policy-decisions")
@RequiredArgsConstructor
public class PolicyDecisionController {

    private final PolicyDecisionRepository repository;

    @GetMapping
    public Flux<PolicyDecision> list(
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String decision) {
        return Mono.fromCallable(
                        () -> {
                            if (decision != null && !decision.isBlank()) {
                                return repository.findByDecisionOrderByTimestampDesc(
                                        decision, size);
                            }
                            return repository.findTopByDecisionNotOrderByTimestampDesc(
                                    "ALLOW", size);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> updateStatus(
            @PathVariable String id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        if (status == null || status.isBlank()) {
            return Mono.error(new IllegalArgumentException("status is required"));
        }
        return Mono.fromRunnable(() -> repository.updateSignalStatus(id, status))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
```

- [ ] **Step 6: Run all policy-engine tests**
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-policy-engine 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`

- [ ] **Step 7: Spotless + commit**
```bash
cd /home/ben/Development/ecip && mvn spotless:apply -pl emcip-policy-engine
git add emcip-policy-engine/
git commit -m "feat(policy-engine): add REST management API + X-Service-Token guard

policy-engine now owns /api/policy-rules CRUD and /api/policy-decisions query.
ServiceTokenFilter guards all /api/** paths.
Liquibase: priority DEFAULT 0 baked into 002, rule_version moved out of 005."
```

---

## Track C — audit-service

### Task C1: ServiceTokenFilter

**Files:**
- Create: `emcip-audit-service/src/main/java/io/emcip/audit/service/config/ServiceTokenFilter.java`
- Modify: `emcip-audit-service/src/main/resources/application.yml`
- Create: `emcip-audit-service/src/test/java/io/emcip/audit/service/config/ServiceTokenFilterTest.java`

audit-service already has `AuditController` at `/api/audit/**` — only the filter needs adding.

- [ ] **Step 1: Add `admin.service-token` to application.yml**

Add to `emcip-audit-service/src/main/resources/application.yml`:
```yaml
admin:
  service-token: ${ADMIN_SERVICE_TOKEN:internal-service-token}
```

- [ ] **Step 2: Write failing test**

```java
// emcip-audit-service/src/test/java/io/emcip/audit/service/config/ServiceTokenFilterTest.java
package io.emcip.audit.service.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.emcip.audit.service.controller.AuditController;
import io.emcip.audit.service.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

@WebFluxTest(controllers = AuditController.class)
class ServiceTokenFilterTest {

    @Autowired WebTestClient client;
    @MockitoBean AuditService auditService;

    @Test
    void request_withoutToken_returns401() {
        client.get().uri("/api/audit/events")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void request_withValidToken_returns200() {
        when(auditService.findByDateRange(any(), any())).thenReturn(Flux.empty());
        client.get().uri("/api/audit/events")
                .header("X-Service-Token", "internal-service-token")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void actuator_withoutToken_isAllowed() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }
}
```

- [ ] **Step 3: Run test — expect FAIL (filter does not exist)**
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-audit-service \
  -Dtest=ServiceTokenFilterTest 2>&1 | tail -10
```

- [ ] **Step 4: Create ServiceTokenFilter**

```java
// emcip-audit-service/src/main/java/io/emcip/audit/service/config/ServiceTokenFilter.java
package io.emcip.audit.service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class ServiceTokenFilter implements WebFilter {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    @Value("${admin.service-token:internal-service-token}")
    private String configuredToken;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst(SERVICE_TOKEN_HEADER);
        if (token == null || !configuredToken.equals(token)) {
            log.warn("Rejected request to {} — missing or invalid X-Service-Token", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
```

- [ ] **Step 5: Run all audit-service tests**
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-audit-service 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`

- [ ] **Step 6: Spotless + commit**
```bash
cd /home/ben/Development/ecip && mvn spotless:apply -pl emcip-audit-service
git add emcip-audit-service/
git commit -m "feat(audit-service): add X-Service-Token guard on /api/** endpoints"
```

---

## Track D — admin-api cleanup (run after A, B, C are committed)

### Task D1: Add WebClient beans and service URL config

**Files:**
- Modify: `emcip-admin-api/src/main/resources/application.yml`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/client/ModerationServiceClient.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/client/PolicyEngineClient.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/client/AuditServiceClient.java`

- [ ] **Step 1: Add service URLs to application.yml**

Add to `emcip-admin-api/src/main/resources/application.yml`:
```yaml
services:
  moderation-service:
    url: ${MODERATION_SERVICE_URL:http://localhost:9085}
  policy-engine:
    url: ${POLICY_ENGINE_URL:http://localhost:9083}
  audit-service:
    url: ${AUDIT_SERVICE_URL:http://localhost:9086}
```

- [ ] **Step 2: Create ModerationServiceClient**

```java
// emcip-admin-api/src/main/java/io/emcip/admin/api/client/ModerationServiceClient.java
package io.emcip.admin.api.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
public class ModerationServiceClient {

    private final WebClient webClient;

    public ModerationServiceClient(
            @Value("${services.moderation-service.url}") String baseUrl,
            @Value("${admin.service-token:internal-service-token}") String serviceToken) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
    }

    public Flux<JsonNode> listRules() {
        return webClient.get().uri("/api/moderation-rules").retrieve().bodyToFlux(JsonNode.class);
    }

    public Mono<JsonNode> createRule(JsonNode rule) {
        return webClient.post().uri("/api/moderation-rules").bodyValue(rule).retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Mono<JsonNode> updateRule(Long id, JsonNode rule) {
        return webClient.put().uri("/api/moderation-rules/{id}", id).bodyValue(rule).retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Mono<Void> deleteRule(Long id) {
        return webClient.delete().uri("/api/moderation-rules/{id}", id).retrieve()
                .bodyToMono(Void.class);
    }
}
```

- [ ] **Step 3: Create PolicyEngineClient**

```java
// emcip-admin-api/src/main/java/io/emcip/admin/api/client/PolicyEngineClient.java
package io.emcip.admin.api.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
public class PolicyEngineClient {

    private final WebClient webClient;

    public PolicyEngineClient(
            @Value("${services.policy-engine.url}") String baseUrl,
            @Value("${admin.service-token:internal-service-token}") String serviceToken) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
    }

    public Flux<JsonNode> listActiveRules() {
        return webClient.get().uri("/api/policy-rules").retrieve().bodyToFlux(JsonNode.class);
    }

    public Mono<JsonNode> createRule(JsonNode rule) {
        return webClient.post().uri("/api/policy-rules").bodyValue(rule).retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Mono<JsonNode> updateRule(String id, JsonNode rule) {
        return webClient.put().uri("/api/policy-rules/{id}", id).bodyValue(rule).retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Mono<Void> deleteRule(String id) {
        return webClient.delete().uri("/api/policy-rules/{id}", id).retrieve()
                .bodyToMono(Void.class);
    }

    public Mono<JsonNode> getRuleHistory(String name) {
        return webClient.get().uri("/api/policy-rules/history/{name}", name).retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Flux<JsonNode> listDecisions(int size, String decision) {
        String uri = decision != null
                ? "/api/policy-decisions?size={size}&decision={decision}"
                : "/api/policy-decisions?size={size}";
        WebClient.RequestHeadersSpec<?> req = decision != null
                ? webClient.get().uri(uri, size, decision)
                : webClient.get().uri(uri, size);
        return req.retrieve().bodyToFlux(JsonNode.class);
    }

    public Mono<Void> updateDecisionStatus(String id, JsonNode body) {
        return webClient.patch().uri("/api/policy-decisions/{id}/status", id)
                .bodyValue(body).retrieve().bodyToMono(Void.class);
    }
}
```

- [ ] **Step 4: Create AuditServiceClient**

```java
// emcip-admin-api/src/main/java/io/emcip/admin/api/client/AuditServiceClient.java
package io.emcip.admin.api.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

@Component
public class AuditServiceClient {

    private final WebClient webClient;

    public AuditServiceClient(
            @Value("${services.audit-service.url}") String baseUrl,
            @Value("${admin.service-token:internal-service-token}") String serviceToken) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .defaultHeader("X-Service-Token", serviceToken)
                        .build();
    }

    public Flux<JsonNode> getEvents(String eventType, String from, String to) {
        return webClient.get()
                .uri(
                        uriBuilder -> {
                            uriBuilder.path("/api/audit/events");
                            if (eventType != null) uriBuilder.queryParam("eventType", eventType);
                            if (from != null) uriBuilder.queryParam("from", from);
                            if (to != null) uriBuilder.queryParam("to", to);
                            return uriBuilder.build();
                        })
                .retrieve()
                .bodyToFlux(JsonNode.class);
    }
}
```

- [ ] **Step 5: Verify admin-api compiles**
```bash
cd /home/ben/Development/ecip && mvn compile -pl emcip-admin-api 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

---

### Task D2: Replace admin-api controllers

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ModerationRuleController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/PolicyRuleController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/FlagController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AuditController.java`

- [ ] **Step 1: Replace ModerationRuleController**

```java
// emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ModerationRuleController.java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.ModerationServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/moderation-rules")
@RequiredArgsConstructor
public class ModerationRuleController {

    private final ModerationServiceClient client;

    @GetMapping
    public Flux<JsonNode> list() {
        return client.listRules();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<JsonNode> create(@RequestBody JsonNode rule) {
        return client.createRule(rule);
    }

    @PutMapping("/{id}")
    public Mono<JsonNode> update(@PathVariable Long id, @RequestBody JsonNode rule) {
        return client.updateRule(id, rule);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable Long id) {
        return client.deleteRule(id);
    }
}
```

- [ ] **Step 2: Replace PolicyRuleController**

```java
// emcip-admin-api/src/main/java/io/emcip/admin/api/controller/PolicyRuleController.java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.PolicyEngineClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/policy-rules")
@RequiredArgsConstructor
public class PolicyRuleController {

    private final PolicyEngineClient client;

    @GetMapping
    public Flux<JsonNode> listActive() {
        return client.listActiveRules();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<JsonNode> create(@RequestBody JsonNode rule) {
        return client.createRule(rule);
    }

    @PutMapping("/{id}")
    public Mono<JsonNode> update(@PathVariable String id, @RequestBody JsonNode rule) {
        return client.updateRule(id, rule);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String id) {
        return client.deleteRule(id);
    }

    @GetMapping("/history/{name}")
    public Mono<JsonNode> history(@PathVariable String name) {
        return client.getRuleHistory(name);
    }
}
```

- [ ] **Step 3: Replace FlagController**

```java
// emcip-admin-api/src/main/java/io/emcip/admin/api/controller/FlagController.java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.PolicyEngineClient;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/flags")
@RequiredArgsConstructor
public class FlagController {

    private final PolicyEngineClient client;
    private final ObjectMapper objectMapper;

    @GetMapping
    public Flux<JsonNode> getFlags(
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String decision) {
        return client.listDecisions(size, decision);
    }

    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> updateStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        return client.updateDecisionStatus(id, objectMapper.valueToTree(body));
    }
}
```

- [ ] **Step 4: Replace AuditController**

```java
// emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AuditController.java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.AuditServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditServiceClient client;

    @GetMapping("/events")
    public Flux<JsonNode> getEvents(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return client.getEvents(eventType, from, to);
    }
}
```

- [ ] **Step 5: Verify admin-api compiles**
```bash
cd /home/ben/Development/ecip && mvn compile -pl emcip-admin-api 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

---

### Task D3: Delete foreign entities, repos, and rogue migration

**Files to delete:**
- `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/ModerationRule.java`
- `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/PolicyRule.java`
- `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/PolicyDecision.java`
- `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/AuditEvent.java`
- `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/ModerationRuleRepository.java`
- `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/PolicyRuleRepository.java`
- `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/PolicyDecisionRepository.java`
- `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/AuditEventRepository.java`
- `emcip-admin-api/src/main/resources/db/changelog/changes/010-policy-rules-priority-default.xml`
- `emcip-admin-api/src/main/java/io/emcip/admin/api/dto/AuditEventResponse.java`

**Files to modify:**
- `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml` (remove 010 include)
- `emcip-admin-api/src/main/java/io/emcip/admin/api/security/SecurityConfig.java` (remove permitAll for policy-rules)

- [ ] **Step 1: Delete the files**
```bash
cd /home/ben/Development/ecip
rm emcip-admin-api/src/main/java/io/emcip/admin/api/entity/ModerationRule.java
rm emcip-admin-api/src/main/java/io/emcip/admin/api/entity/PolicyRule.java
rm emcip-admin-api/src/main/java/io/emcip/admin/api/entity/PolicyDecision.java
rm emcip-admin-api/src/main/java/io/emcip/admin/api/entity/AuditEvent.java
rm emcip-admin-api/src/main/java/io/emcip/admin/api/repository/ModerationRuleRepository.java
rm emcip-admin-api/src/main/java/io/emcip/admin/api/repository/PolicyRuleRepository.java
rm emcip-admin-api/src/main/java/io/emcip/admin/api/repository/PolicyDecisionRepository.java
rm emcip-admin-api/src/main/java/io/emcip/admin/api/repository/AuditEventRepository.java
rm emcip-admin-api/src/main/resources/db/changelog/changes/010-policy-rules-priority-default.xml
rm emcip-admin-api/src/main/java/io/emcip/admin/api/dto/AuditEventResponse.java
```

- [ ] **Step 2: Remove `010` include from `db.changelog-master.xml`**

In `db.changelog-master.xml`, delete this line:
```xml
<include file="db/changelog/changes/010-policy-rules-priority-default.xml"/>
```

- [ ] **Step 3: Fix SecurityConfig — remove the policy-rules permitAll workaround**

In `SecurityConfig.java`, remove this stanza from `authorizeExchange`:
```java
.pathMatchers("/api/policy-rules/**", "/policy-rules/**")
.permitAll()
```
The block should now read:
```java
.authorizeExchange(
    auth ->
        auth.pathMatchers(HttpMethod.POST, "/api/auth/token", "/auth/token")
                .permitAll()
                .pathMatchers("/actuator/**")
                .permitAll()
                .anyExchange()
                .authenticated())
```

- [ ] **Step 4: Verify admin-api compiles cleanly**
```bash
cd /home/ben/Development/ecip && mvn compile -pl emcip-admin-api 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Run all admin-api tests**
```bash
cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api 2>&1 | tail -10
```
Expected: `BUILD SUCCESS` (some existing tests for removed repos may need deletion if they reference the deleted classes — delete any test that imports the deleted entities/repos)

- [ ] **Step 6: Spotless + final commit**
```bash
cd /home/ben/Development/ecip && mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "refactor(admin-api): remove shared-DB access, delegate to domain services via REST

admin-api no longer holds repos or entities for moderation_rules, policy_rules,
policy_decisions, or audit_events. All four controllers now proxy to the owning
domain service via WebClient with X-Service-Token.

Removes: ModerationRule, PolicyRule, PolicyDecision, AuditEvent entities + repos.
Removes: rogue migration 010 (policy_rules priority DEFAULT — now in policy-engine 002).
Removes: permitAll workaround for /api/policy-rules/** — token auth covers it now."
```

---

## Verification

- [ ] **Full build passes**
```bash
cd /home/ben/Development/ecip && mvn test \
  -pl emcip-moderation-service,emcip-policy-engine,emcip-audit-service,emcip-admin-api \
  2>&1 | grep -E "Tests run|BUILD"
```
Expected: all modules `BUILD SUCCESS`, 0 failures.

- [ ] **Update OPEN_POINTS.md**

Mark `US-4.3.3` as ✅ fixed. Update the DB-sharing note in Known Pre-existing Issues to resolved.

- [ ] **Update docs/OPEN_POINTS.md decision on moderation REST controller**

The entry previously marked "✅ By design" was wrong — correct it to "✅ Fixed: moderation-service now owns `/api/moderation-rules`; admin-api proxies to it."
