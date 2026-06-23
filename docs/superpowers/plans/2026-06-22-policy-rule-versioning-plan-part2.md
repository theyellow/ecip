# Policy Rule Versioning — Implementation Plan (Part 2 of 2: Dry-Run, Proxy, Frontend, Build)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **Part 1 of 2:** `docs/superpowers/plans/2026-06-22-policy-rule-versioning-plan-part1.md` (Tasks 1–4: DB migration, condition evaluators, OR-group evaluation, history entity)
> **Start this part only after Part 1 is fully committed.**

**Goal (this part):** Add `POST /api/policy-rules/dry-run` to policy-engine, proxy it through admin-api, and build the Admin UI: `ConditionGroupBuilder`, `DryRunPanel`, `RuleHistoryTab`, and updated `PolicyRules.jsx`.

**Tech Stack (additions):** React + CSS Modules, design tokens (`var(--accent)`, `var(--fg-*)`, `var(--sp-*)`, `var(--border)`), `border-radius: 0` on all data surfaces, existing `Modal` + `Button` + `Badge` + `DataTable` components, `ReactiveSecurityContextHolder` for edited-by extraction in admin-api.

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `emcip-policy-engine/.../dto/DryRunRequest.java` | Request record |
| Create | `emcip-policy-engine/.../dto/ConditionResult.java` | Per-condition result record |
| Create | `emcip-policy-engine/.../dto/GroupResult.java` | Per-group result record |
| Create | `emcip-policy-engine/.../dto/DryRunResult.java` | Top-level result record |
| Create | `emcip-policy-engine/.../service/DryRunService.java` | Stateless evaluation |
| Create | `emcip-policy-engine/.../controller/DryRunController.java` | POST /api/policy-rules/dry-run |
| Create | `emcip-policy-engine/src/test/.../controller/DryRunControllerTest.java` | Unit test |
| Modify | `emcip-admin-api/.../client/PolicyEngineClient.java` | dryRun(), getRuleHistory(), updateRule(editedBy) |
| Modify | `emcip-admin-api/.../controller/PolicyRuleController.java` | dry-run + history proxy endpoints |
| Modify | `emcip-admin-ui/.../api/policyRules.js` | dryRun(), getHistory() |
| Create | `emcip-admin-ui/.../pages/PolicyRules/ConditionGroupBuilder.jsx` | OR-group condition editor |
| Create | `emcip-admin-ui/.../pages/PolicyRules/ConditionGroupBuilder.module.css` | Styles |
| Create | `emcip-admin-ui/.../pages/PolicyRules/DryRunPanel.jsx` | Dry-run test inputs + results |
| Create | `emcip-admin-ui/.../pages/PolicyRules/DryRunPanel.module.css` | Styles |
| Create | `emcip-admin-ui/.../pages/PolicyRules/RuleHistoryTab.jsx` | History table + diff viewer |
| Create | `emcip-admin-ui/.../pages/PolicyRules/RuleHistoryTab.module.css` | Styles |
| Modify | `emcip-admin-ui/.../pages/PolicyRules/PolicyRules.jsx` | Wire new components, tabbed modal |

Frontend paths expand from `emcip-admin-ui/src/main/frontend/src/`.

---

## Task 5: Dry-Run Endpoint (policy-engine)

**Files:** all in `emcip-policy-engine/src/main/java/io/emcip/policy/engine/`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/io/emcip/policy/engine/controller/DryRunControllerTest.java
package io.emcip.policy.engine.controller;

import io.emcip.policy.engine.condition.*;
import io.emcip.policy.engine.condition.evaluator.*;
import io.emcip.policy.engine.dto.*;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.service.DryRunService;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class DryRunControllerTest {

    @Mock private DryRunService dryRunService;
    @InjectMocks private DryRunController controller;

    @Test
    void matched_resultIsReturned() {
        DryRunResult result = new DryRunResult(true, 0, "BLOCK", List.of(
            new GroupResult(0, true, List.of(
                new ConditionResult(ConditionType.MIN_THREAD_LENGTH, true, "5 >= 3")
            ))
        ));
        when(dryRunService.evaluate(any(), any())).thenReturn(result);

        DryRunRequest req = new DryRunRequest(
            new PolicyRuleConfig(),
            new EvaluationContext("SPAM", 0.9, "en", 5, 120, 45, 2, 0, 90, ZonedDateTime.now())
        );

        StepVerifier.create(controller.dryRun(req))
            .assertNext(r -> {
                assertThat(r.matched()).isTrue();
                assertThat(r.matchedGroupIndex()).isEqualTo(0);
                assertThat(r.action()).isEqualTo("BLOCK");
                assertThat(r.groupResults()).hasSize(1);
            })
            .verifyComplete();
    }

    @Test
    void notMatched_resultHasMatchedFalse() {
        DryRunResult result = new DryRunResult(false, -1, "BLOCK", List.of(
            new GroupResult(0, false, List.of(
                new ConditionResult(ConditionType.FLAGGED_COUNT, false, "0 >= 3 in last 30d")
            ))
        ));
        when(dryRunService.evaluate(any(), any())).thenReturn(result);

        DryRunRequest req = new DryRunRequest(
            new PolicyRuleConfig(),
            new EvaluationContext("SPAM", 0.9, "en", 0, 0, 0, 0, 0, 90, ZonedDateTime.now())
        );

        StepVerifier.create(controller.dryRun(req))
            .assertNext(r -> assertThat(r.matched()).isFalse())
            .verifyComplete();
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
mvn -pl emcip-policy-engine test -Dtest=DryRunControllerTest -q | cat
```

Expected: FAIL — DTOs not found.

- [ ] **Step 3: Create DTOs**

```java
// src/main/java/io/emcip/policy/engine/dto/DryRunRequest.java
package io.emcip.policy.engine.dto;

import io.emcip.policy.engine.condition.EvaluationContext;
import io.emcip.policy.engine.entity.PolicyRuleConfig;

public record DryRunRequest(PolicyRuleConfig rule, EvaluationContext context) {}
```

```java
// src/main/java/io/emcip/policy/engine/dto/ConditionResult.java
package io.emcip.policy.engine.dto;

import io.emcip.policy.engine.condition.ConditionType;

public record ConditionResult(ConditionType type, boolean passed, String detail) {}
```

```java
// src/main/java/io/emcip/policy/engine/dto/GroupResult.java
package io.emcip.policy.engine.dto;

import java.util.List;

public record GroupResult(int index, boolean matched, List<ConditionResult> conditionResults) {}
```

```java
// src/main/java/io/emcip/policy/engine/dto/DryRunResult.java
package io.emcip.policy.engine.dto;

import java.util.List;

public record DryRunResult(boolean matched, int matchedGroupIndex, String action, List<GroupResult> groupResults) {}
```

- [ ] **Step 4: Create DryRunService**

```java
// src/main/java/io/emcip/policy/engine/service/DryRunService.java
package io.emcip.policy.engine.service;

import io.emcip.policy.engine.condition.*;
import io.emcip.policy.engine.dto.*;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DryRunService {

    private final ConditionEvaluatorRegistry registry;

    public DryRunResult evaluate(PolicyRuleConfig rule, EvaluationContext ctx) {
        // Intent + confidence check
        boolean intentOk = "*".equals(rule.getTargetIntent()) ||
                (rule.getTargetIntent() != null && rule.getTargetIntent().equals(ctx.intent()));
        boolean confOk = ctx.confidence() >= (rule.getMinConfidence() != null ? rule.getMinConfidence() : 0.0)
                && (rule.getMaxConfidence() == null || ctx.confidence() <= rule.getMaxConfidence());

        if (!intentOk || !confOk) {
            return new DryRunResult(false, -1, rule.getAction(), List.of());
        }

        Map<String, Object> conditions = rule.getConditions();
        if (conditions == null || !conditions.containsKey("groups")) {
            return new DryRunResult(true, -1, rule.getAction(), List.of());
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) conditions.get("groups");
        if (groups.isEmpty()) {
            return new DryRunResult(true, -1, rule.getAction(), List.of());
        }

        List<GroupResult> groupResults = new ArrayList<>();
        int matchedGroupIndex = -1;

        for (int i = 0; i < groups.size(); i++) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conds = (List<Map<String, Object>>)
                    groups.get(i).getOrDefault("conditions", List.of());
            List<ConditionResult> condResults = new ArrayList<>();
            boolean groupPassed = true;

            for (Map<String, Object> cond : conds) {
                boolean passed;
                String detail;
                try {
                    passed = registry.evaluate(cond, ctx);
                    detail = registry.detail(cond, ctx);
                } catch (Exception e) {
                    passed = false;
                    detail = "Error: " + e.getMessage();
                }
                ConditionType type = ConditionType.valueOf((String) cond.get("type"));
                condResults.add(new ConditionResult(type, passed, detail));
                if (!passed) groupPassed = false;
            }

            groupResults.add(new GroupResult(i, groupPassed, condResults));
            if (groupPassed && matchedGroupIndex == -1) matchedGroupIndex = i;
        }

        return new DryRunResult(matchedGroupIndex != -1, matchedGroupIndex, rule.getAction(), groupResults);
    }
}
```

- [ ] **Step 5: Create DryRunController**

```java
// src/main/java/io/emcip/policy/engine/controller/DryRunController.java
package io.emcip.policy.engine.controller;

import io.emcip.policy.engine.dto.*;
import io.emcip.policy.engine.service.DryRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Tag(name = "Policy Rules")
@RestController
@RequestMapping("/api/policy-rules")
@RequiredArgsConstructor
public class DryRunController {

    private final DryRunService dryRunService;

    @Operation(summary = "Evaluate an unsaved rule against a test context — no side effects")
    @PostMapping("/dry-run")
    public Mono<DryRunResult> dryRun(@RequestBody DryRunRequest request) {
        return Mono.fromCallable(() -> dryRunService.evaluate(request.rule(), request.context()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
```

- [ ] **Step 6: Run tests**

```bash
mvn -pl emcip-policy-engine test -Dtest=DryRunControllerTest -q | cat
```

Expected: `Tests run: 2, Failures: 0`

- [ ] **Step 7: Run all policy-engine tests**

```bash
mvn -pl emcip-policy-engine test -q | cat
```

Expected: all green.

- [ ] **Step 8: Commit**

```bash
git add emcip-policy-engine/src/main/java/io/emcip/policy/engine/dto/ \
        emcip-policy-engine/src/main/java/io/emcip/policy/engine/service/DryRunService.java \
        emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/DryRunController.java \
        emcip-policy-engine/src/test/java/io/emcip/policy/engine/controller/DryRunControllerTest.java
git commit -m "feat(6): add dry-run endpoint POST /api/policy-rules/dry-run"
```

---

## Task 6: admin-api Proxy Updates

**Files:** both in `emcip-admin-api/src/main/java/io/emcip/admin/api/`

- [ ] **Step 1: Update PolicyEngineClient**

In `emcip-admin-api/.../client/PolicyEngineClient.java`, make three changes:

**a) Change `updateRule` signature to accept `editedBy`:**

```java
// Replace the existing updateRule method:
public Mono<JsonNode> updateRule(String id, JsonNode body, String editedBy) {
    return webClient
            .put()
            .uri("/api/policy-rules/{id}", id)
            .header("X-Edited-By", editedBy != null ? editedBy : "unknown")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .transformDeferred(RetryOperator.of(retry))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
}
```

**b) Add `dryRun` method (after `deleteRule`):**

```java
public Mono<JsonNode> dryRun(JsonNode body) {
    return webClient
            .post()
            .uri("/api/policy-rules/dry-run")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .transformDeferred(RetryOperator.of(retry))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
}
```

**c) Add `getRuleHistory` method:**

```java
public Flux<JsonNode> getRuleHistory(String ruleId) {
    return webClient
            .get()
            .uri("/api/policy-rules/{id}/history", ruleId)
            .retrieve()
            .bodyToFlux(JsonNode.class)
            .transformDeferred(RetryOperator.of(retry))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .onErrorResume(
                    e -> {
                        log.warn("policy-engine unavailable for getRuleHistory: {}", e.getMessage());
                        return Flux.empty();
                    });
}
```

- [ ] **Step 2: Update admin-api PolicyRuleController**

Replace `emcip-admin-api/.../controller/PolicyRuleController.java` with:

```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.client.PolicyEngineClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/policy-rules")
@RequiredArgsConstructor
@Tag(name = "Policy Rules", description = "Proxy to policy-engine rule management")
public class PolicyRuleController {

    private final PolicyEngineClient policyEngineClient;

    @Operation(summary = "List active policy rules")
    @GetMapping
    public Flux<JsonNode> listActiveRules() {
        return policyEngineClient.listRules();
    }

    @Operation(summary = "Create a policy rule")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<JsonNode> createRule(@RequestBody JsonNode body) {
        return policyEngineClient.createRule(body);
    }

    @Operation(summary = "Update a policy rule; passes caller identity for history snapshot")
    @PutMapping("/{id}")
    public Mono<JsonNode> updateRule(
            @PathVariable("id") String id, @RequestBody JsonNode body) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getName())
                .defaultIfEmpty("unknown")
                .flatMap(editedBy -> policyEngineClient.updateRule(id, body, editedBy));
    }

    @Operation(summary = "Delete a policy rule")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteRule(@PathVariable("id") String id) {
        return policyEngineClient.deleteRule(id);
    }

    @Operation(summary = "Evaluate an unsaved rule against a test context — no side effects")
    @PostMapping("/dry-run")
    public Mono<JsonNode> dryRun(@RequestBody JsonNode body) {
        return policyEngineClient.dryRun(body);
    }

    @Operation(summary = "Get version history snapshots for a rule")
    @GetMapping("/{id}/history")
    public Flux<JsonNode> getRuleHistory(@PathVariable("id") String id) {
        return policyEngineClient.getRuleHistory(id);
    }
}
```

- [ ] **Step 3: Compile both modules**

```bash
mvn -pl emcip-admin-api,emcip-policy-engine compile -q | cat
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add emcip-admin-api/src/main/java/io/emcip/admin/api/client/PolicyEngineClient.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/controller/PolicyRuleController.java
git commit -m "feat(6): add dry-run and history proxy endpoints to admin-api"
```

---

## Task 7: Frontend — policyRules.js, ConditionGroupBuilder, DryRunPanel, RuleHistoryTab, PolicyRules.jsx

All files under `emcip-admin-ui/src/main/frontend/src/`.

- [ ] **Step 1: Update policyRules.js**

Replace `api/policyRules.js` with:

```js
export function policyRulesApi(request) {
  return {
    list: () => request('/api/policy-rules'),
    getHistory: id => request(`/api/policy-rules/${encodeURIComponent(id)}/history`),
    create: body =>
      request('/api/policy-rules', { method: 'POST', body: JSON.stringify(body) }),
    update: (id, body) =>
      request(`/api/policy-rules/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    remove: id =>
      request(`/api/policy-rules/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    dryRun: (rule, context) =>
      request('/api/policy-rules/dry-run', {
        method: 'POST',
        body: JSON.stringify({ rule, context }),
      }),
  }
}
```

- [ ] **Step 2: Create ConditionGroupBuilder.module.css**

```css
/* pages/PolicyRules/ConditionGroupBuilder.module.css */
.root { display: flex; flex-direction: column; gap: 0; }

.group {
  border: 1px solid var(--border);
  padding: var(--sp-3);
  margin-bottom: var(--sp-2);
}
.groupActive { border-color: var(--accent); }

.groupHeader {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--sp-2);
}
.groupLabel {
  font-family: var(--font-display);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.10em;
  color: var(--accent);
}
.groupLabelMuted { color: var(--fg-3); }
.removeGroup {
  font-size: 11px;
  color: var(--fg-3);
  background: none;
  border: none;
  cursor: pointer;
  font-family: var(--font-mono);
}
.removeGroup:hover { color: var(--signal-stop-fg); }

.conditionRow {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
  background: var(--bg-input);
  padding: var(--sp-1) var(--sp-2);
  margin-bottom: var(--sp-1);
}
.typeSelect, .paramInput {
  font-size: 12px;
  font-family: var(--font-body);
  background: var(--bg-card);
  border: 1px solid var(--border);
  color: var(--fg-1);
  padding: 3px 6px;
}
.typeSelect { min-width: 150px; }
.paramInput { width: 70px; }
.paramInputWide { width: 120px; }
.paramLabel {
  font-size: 12px;
  color: var(--fg-2);
  white-space: nowrap;
}
.removeCondition {
  margin-left: auto;
  font-size: 11px;
  color: var(--fg-3);
  background: none;
  border: none;
  cursor: pointer;
  font-family: var(--font-mono);
}
.removeCondition:hover { color: var(--signal-stop-fg); }

.addCondition {
  font-size: 11px;
  background: none;
  border: 1px dashed var(--border);
  color: var(--fg-3);
  padding: 3px 10px;
  cursor: pointer;
  margin-top: var(--sp-2);
}
.addCondition:hover { border-color: var(--accent); color: var(--accent); }

.orLabel {
  text-align: center;
  font-size: 11px;
  font-family: var(--font-display);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--fg-3);
  margin: var(--sp-1) 0;
}

.addGroup {
  font-size: 12px;
  background: none;
  border: 1px dashed var(--accent);
  color: var(--accent);
  padding: var(--sp-2) var(--sp-3);
  cursor: pointer;
  width: 100%;
  font-family: var(--font-display);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin-top: var(--sp-2);
}
.addGroup:hover { background: var(--accent-soft); }

.hint {
  font-size: 11px;
  color: var(--fg-3);
  margin-bottom: var(--sp-2);
  font-style: italic;
}
```

- [ ] **Step 3: Create ConditionGroupBuilder.jsx**

```jsx
// pages/PolicyRules/ConditionGroupBuilder.jsx
import styles from './ConditionGroupBuilder.module.css'

const CONDITION_TYPES = [
  { value: 'TIME_WINDOW',       label: 'Time window' },
  { value: 'MIN_THREAD_LENGTH', label: 'Min thread length' },
  { value: 'ACCOUNT_AGE_DAYS',  label: 'Account age (days)' },
  { value: 'MESSAGE_LANGUAGE',  label: 'Message language' },
  { value: 'GROUP_SIZE',        label: 'Group size' },
  { value: 'MESSAGE_LENGTH',    label: 'Message length' },
  { value: 'FLAGGED_COUNT',     label: 'Flagged count' },
]

function defaultParams(type) {
  switch (type) {
    case 'TIME_WINDOW':       return { start: '22:00', end: '06:00' }
    case 'MIN_THREAD_LENGTH': return { min: 3 }
    case 'ACCOUNT_AGE_DAYS':  return { max: 7 }
    case 'MESSAGE_LANGUAGE':  return { languages: 'en', mode: 'INCLUDE' }
    case 'GROUP_SIZE':        return { min: 50 }
    case 'MESSAGE_LENGTH':    return { min: '', max: '' }
    case 'FLAGGED_COUNT':     return { min: 3, windowDays: 30 }
    default:                  return {}
  }
}

function ConditionParams({ type, params, onChange }) {
  const set = (k, v) => onChange({ ...params, [k]: v })
  switch (type) {
    case 'TIME_WINDOW': return <>
      <span className={styles.paramLabel}>from</span>
      <input className={styles.paramInput} value={params.start ?? ''} onChange={e => set('start', e.target.value)} placeholder="22:00" />
      <span className={styles.paramLabel}>to</span>
      <input className={styles.paramInput} value={params.end ?? ''} onChange={e => set('end', e.target.value)} placeholder="06:00" />
    </>
    case 'MIN_THREAD_LENGTH': return <>
      <span className={styles.paramLabel}>min</span>
      <input type="number" className={styles.paramInput} value={params.min ?? ''} onChange={e => set('min', parseInt(e.target.value) || 0)} />
    </>
    case 'ACCOUNT_AGE_DAYS': return <>
      <span className={styles.paramLabel}>max days</span>
      <input type="number" className={styles.paramInput} value={params.max ?? ''} onChange={e => set('max', parseInt(e.target.value) || 0)} />
    </>
    case 'MESSAGE_LANGUAGE': return <>
      <select className={styles.typeSelect} style={{minWidth:80}} value={params.mode ?? 'INCLUDE'} onChange={e => set('mode', e.target.value)}>
        <option>INCLUDE</option><option>EXCLUDE</option>
      </select>
      <input className={styles.paramInputWide} value={params.languages ?? ''} onChange={e => set('languages', e.target.value)} placeholder="en,de" />
    </>
    case 'GROUP_SIZE': return <>
      <span className={styles.paramLabel}>min members</span>
      <input type="number" className={styles.paramInput} value={params.min ?? ''} onChange={e => set('min', parseInt(e.target.value) || 0)} />
    </>
    case 'MESSAGE_LENGTH': return <>
      <span className={styles.paramLabel}>min chars</span>
      <input type="number" className={styles.paramInput} value={params.min ?? ''} onChange={e => set('min', e.target.value === '' ? undefined : parseInt(e.target.value))} placeholder="—" />
      <span className={styles.paramLabel}>max chars</span>
      <input type="number" className={styles.paramInput} value={params.max ?? ''} onChange={e => set('max', e.target.value === '' ? undefined : parseInt(e.target.value))} placeholder="—" />
    </>
    case 'FLAGGED_COUNT': return <>
      <span className={styles.paramLabel}>min</span>
      <input type="number" className={styles.paramInput} value={params.min ?? ''} onChange={e => set('min', parseInt(e.target.value) || 0)} />
      <span className={styles.paramLabel}>in last</span>
      <input type="number" className={styles.paramInput} value={params.windowDays ?? ''} onChange={e => set('windowDays', parseInt(e.target.value) || 30)} />
      <span className={styles.paramLabel}>days</span>
    </>
    default: return null
  }
}

export function ConditionGroupBuilder({ groups, onChange }) {
  // groups: [{ conditions: [{ type, ...params }] }]
  const setGroups = g => onChange(g)

  const addGroup = () => setGroups([...groups, { conditions: [] }])
  const removeGroup = i => setGroups(groups.filter((_, idx) => idx !== i))

  const addCondition = i => {
    const updated = groups.map((g, idx) => idx !== i ? g : {
      ...g, conditions: [...g.conditions, { type: 'TIME_WINDOW', ...defaultParams('TIME_WINDOW') }]
    })
    setGroups(updated)
  }
  const removeCondition = (gi, ci) => {
    const updated = groups.map((g, idx) => idx !== gi ? g : {
      ...g, conditions: g.conditions.filter((_, cidx) => cidx !== ci)
    })
    setGroups(updated)
  }
  const updateConditionType = (gi, ci, newType) => {
    const updated = groups.map((g, idx) => idx !== gi ? g : {
      ...g, conditions: g.conditions.map((c, cidx) => cidx !== ci
        ? c : { type: newType, ...defaultParams(newType) })
    })
    setGroups(updated)
  }
  const updateConditionParams = (gi, ci, params) => {
    const updated = groups.map((g, idx) => idx !== gi ? g : {
      ...g, conditions: g.conditions.map((c, cidx) => cidx !== ci
        ? c : { type: c.type, ...params })
    })
    setGroups(updated)
  }

  return (
    <div className={styles.root}>
      <p className={styles.hint}>Groups are OR'd together. Conditions within a group are AND'd.</p>
      {groups.map((group, gi) => (
        <div key={gi}>
          {gi > 0 && <div className={styles.orLabel}>— OR —</div>}
          <div className={`${styles.group} ${gi === 0 ? styles.groupActive : ''}`}>
            <div className={styles.groupHeader}>
              <span className={gi === 0 ? styles.groupLabel : styles.groupLabelMuted}>
                Group {gi + 1} — AND
              </span>
              <button className={styles.removeGroup} onClick={() => removeGroup(gi)}>✕ remove group</button>
            </div>
            {group.conditions.map((cond, ci) => {
              const { type, ...params } = cond
              return (
                <div key={ci} className={styles.conditionRow}>
                  <select className={styles.typeSelect} value={type}
                    onChange={e => updateConditionType(gi, ci, e.target.value)}>
                    {CONDITION_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
                  </select>
                  <ConditionParams type={type} params={params}
                    onChange={p => updateConditionParams(gi, ci, p)} />
                  <button className={styles.removeCondition} onClick={() => removeCondition(gi, ci)}>✕</button>
                </div>
              )
            })}
            <button className={styles.addCondition} onClick={() => addCondition(gi)}>+ Add condition</button>
          </div>
        </div>
      ))}
      <button className={styles.addGroup} onClick={addGroup}>+ Add OR Group</button>
    </div>
  )
}
```

- [ ] **Step 4: Create DryRunPanel.module.css**

```css
/* pages/PolicyRules/DryRunPanel.module.css */
.panel {
  border: 1px solid var(--accent);
  margin-top: var(--sp-3);
}
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--sp-2) var(--sp-3);
  background: var(--bg-input);
  cursor: pointer;
}
.headerLabel {
  font-family: var(--font-display);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.10em;
  color: var(--accent);
}
.toggle { font-size: 12px; color: var(--fg-3); }

.body { padding: var(--sp-3); }

.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-2) var(--sp-3);
  margin-bottom: var(--sp-3);
}
.field { display: flex; flex-direction: column; gap: 3px; }
.label { font-size: 11px; color: var(--fg-3); font-family: var(--font-mono); }
.input {
  font-size: 12px;
  font-family: var(--font-body);
  background: var(--bg-input);
  border: 1px solid var(--border);
  color: var(--fg-1);
  padding: 4px 6px;
}

.result { margin-top: var(--sp-3); }
.resultHeader {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-bottom: var(--sp-2);
}
.match {
  font-family: var(--font-display);
  font-size: 11px;
  letter-spacing: 0.10em;
  padding: 2px 8px;
  border-radius: var(--r-pill);
}
.matchYes { background: var(--signal-ok-bg); color: var(--signal-ok-fg); }
.matchNo  { background: var(--signal-stop-bg); color: var(--signal-stop-fg); }

.group { margin-bottom: var(--sp-2); }
.groupLabel {
  font-family: var(--font-mono);
  font-size: 11px;
  margin-bottom: 2px;
}
.groupPass { color: var(--signal-ok-fg); }
.groupFail { color: var(--signal-stop-fg); }

.condRow {
  font-family: var(--font-mono);
  font-size: 11px;
  padding-left: var(--sp-3);
  color: var(--fg-3);
}
.condPass::before { content: '✓ '; color: var(--signal-ok-fg); }
.condFail::before { content: '✗ '; color: var(--signal-stop-fg); }

.action {
  margin-top: var(--sp-2);
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 600;
}
.actionMatch { color: var(--signal-ok-fg); }
.actionNoMatch { color: var(--fg-3); }
```

- [ ] **Step 5: Create DryRunPanel.jsx**

```jsx
// pages/PolicyRules/DryRunPanel.jsx
import { useState } from 'react'
import { Button } from '../../components/Button/Button'
import styles from './DryRunPanel.module.css'

const DEFAULT_CTX = {
  intent: '',
  confidence: 0.8,
  language: 'en',
  threadLength: 3,
  groupSize: 100,
  messageLength: 45,
  senderAccountAgeDays: 999,
  senderFlaggedCount: 0,
  senderFlagWindowDays: 90,
}

export function DryRunPanel({ rule, api }) {
  const [open, setOpen] = useState(false)
  const [ctx, setCtx] = useState(DEFAULT_CTX)
  const [result, setResult] = useState(null)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState('')

  const set = (k, v) => setCtx(c => ({ ...c, [k]: v }))
  const num = (k, e) => set(k, parseFloat(e.target.value) || 0)

  const run = async () => {
    setRunning(true); setError(''); setResult(null)
    try {
      const r = await api.dryRun(rule, ctx)
      setResult(r)
    } catch (e) { setError(e.message) }
    finally { setRunning(false) }
  }

  return (
    <div className={styles.panel}>
      <div className={styles.header} onClick={() => setOpen(o => !o)}>
        <span className={styles.headerLabel}>⚡ Test This Rule (Dry Run)</span>
        <span className={styles.toggle}>{open ? '▾' : '▸'}</span>
      </div>
      {open && (
        <div className={styles.body}>
          <div className={styles.grid}>
            <div className={styles.field}>
              <label className={styles.label}>Intent</label>
              <input className={styles.input} value={ctx.intent} onChange={e => set('intent', e.target.value)} placeholder="SPAM" />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Confidence (0–1)</label>
              <input type="number" className={styles.input} step="0.01" min="0" max="1" value={ctx.confidence} onChange={e => num('confidence', e)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Language</label>
              <input className={styles.input} value={ctx.language} onChange={e => set('language', e.target.value)} placeholder="en" />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Thread Length</label>
              <input type="number" className={styles.input} value={ctx.threadLength} onChange={e => num('threadLength', e)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Group Size</label>
              <input type="number" className={styles.input} value={ctx.groupSize} onChange={e => num('groupSize', e)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Message Length (chars)</label>
              <input type="number" className={styles.input} value={ctx.messageLength} onChange={e => num('messageLength', e)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Sender Account Age (days)</label>
              <input type="number" className={styles.input} value={ctx.senderAccountAgeDays} onChange={e => num('senderAccountAgeDays', e)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Sender Flagged Count</label>
              <input type="number" className={styles.input} value={ctx.senderFlaggedCount} onChange={e => num('senderFlaggedCount', e)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Flagged Window (days)</label>
              <input type="number" className={styles.input} value={ctx.senderFlagWindowDays} onChange={e => num('senderFlagWindowDays', e)} />
            </div>
          </div>
          <Button variant="secondary" onClick={run} disabled={running}>
            {running ? 'Running…' : 'Run Dry Run'}
          </Button>
          {error && <p style={{ color: 'var(--signal-stop-fg)', fontFamily: 'var(--font-mono)', fontSize: '12px', marginTop: 'var(--sp-2)' }}>{error}</p>}
          {result && (
            <div className={styles.result}>
              <div className={styles.resultHeader}>
                <span className={`${styles.match} ${result.matched ? styles.matchYes : styles.matchNo}`}>
                  {result.matched ? 'MATCH' : 'NO MATCH'}
                </span>
                {result.matched && <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>→ Group {result.matchedGroupIndex + 1}</span>}
              </div>
              {result.groupResults.map((g, i) => (
                <div key={i} className={styles.group}>
                  <div className={`${styles.groupLabel} ${g.matched ? styles.groupPass : styles.groupFail}`}>
                    {g.matched ? '✓' : '✗'} Group {i + 1}
                  </div>
                  {g.conditionResults.map((c, j) => (
                    <div key={j} className={`${styles.condRow} ${c.passed ? styles.condPass : styles.condFail}`}>
                      {c.type}: {c.detail}
                    </div>
                  ))}
                </div>
              ))}
              {result.matched && (
                <div className={`${styles.action} ${styles.actionMatch}`}>→ {result.action}</div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 6: Create RuleHistoryTab.module.css**

```css
/* pages/PolicyRules/RuleHistoryTab.module.css */
.root { padding: var(--sp-2) 0; }

.empty { color: var(--fg-3); font-style: italic; font-size: 13px; }

.table { width: 100%; border-collapse: collapse; font-size: 12px; margin-top: var(--sp-2); }
.table th {
  text-align: left;
  padding: var(--sp-1) var(--sp-2);
  background: var(--bg-input);
  font-family: var(--font-display);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: var(--accent);
  border-bottom: 1px solid var(--border);
}
.table td {
  padding: var(--sp-1) var(--sp-2);
  border-bottom: 1px solid var(--border);
  vertical-align: middle;
}
.table tr:nth-child(even) td { background: var(--bg-input); }
.mono { font-family: var(--font-mono); color: var(--fg-2); }
.muted { color: var(--fg-3); }

.diffToggle {
  font-size: 11px;
  background: none;
  border: 1px solid var(--border);
  color: var(--fg-3);
  padding: 2px 8px;
  cursor: pointer;
  font-family: var(--font-mono);
}
.diffToggle:hover { border-color: var(--accent); color: var(--accent); }

.diff {
  margin-top: var(--sp-2);
  background: var(--bg-input);
  border: 1px solid var(--border);
  padding: var(--sp-2);
}
.diffLabel {
  font-family: var(--font-display);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.10em;
  color: var(--fg-3);
  margin-bottom: var(--sp-1);
}
.diffPre {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-2);
  overflow: auto;
  max-height: 300px;
  white-space: pre;
}
```

- [ ] **Step 7: Create RuleHistoryTab.jsx**

```jsx
// pages/PolicyRules/RuleHistoryTab.jsx
import { useEffect, useState } from 'react'
import styles from './RuleHistoryTab.module.css'

export function RuleHistoryTab({ ruleId, api }) {
  const [history, setHistory] = useState([])
  const [loading, setLoading] = useState(true)
  const [openDiff, setOpenDiff] = useState(null) // index of expanded diff row

  useEffect(() => {
    if (!ruleId) return
    api.getHistory(ruleId)
      .then(h => setHistory(Array.isArray(h) ? h : []))
      .catch(() => setHistory([]))
      .finally(() => setLoading(false))
  }, [ruleId])

  if (loading) return <p className={styles.muted}>Loading…</p>
  if (history.length === 0) return <p className={styles.empty}>No history recorded yet.</p>

  return (
    <div className={styles.root}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>Version</th>
            <th>Edited By</th>
            <th>When</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {history.map((h, i) => (
            <>
              <tr key={h.ruleVersion ?? i}>
                <td className={styles.mono}>v{h.ruleVersion} → v{(h.ruleVersion ?? 0) + 1}</td>
                <td className={styles.mono}>{h.editedBy ?? '—'}</td>
                <td className={styles.muted}>{h.editedAt ? new Date(h.editedAt).toLocaleString() : '—'}</td>
                <td>
                  <button className={styles.diffToggle}
                    onClick={() => setOpenDiff(openDiff === i ? null : i)}>
                    {openDiff === i ? 'Hide diff' : 'View diff'}
                  </button>
                </td>
              </tr>
              {openDiff === i && (
                <tr key={`diff-${i}`}>
                  <td colSpan={4}>
                    <div className={styles.diff}>
                      <div className={styles.diffLabel}>— Snapshot at v{h.ruleVersion} —</div>
                      <pre className={styles.diffPre}>{JSON.stringify(h.snapshot, null, 2)}</pre>
                    </div>
                  </td>
                </tr>
              )}
            </>
          ))}
        </tbody>
      </table>
    </div>
  )
}
```

- [ ] **Step 8: Update PolicyRules.jsx**

Replace `pages/PolicyRules/PolicyRules.jsx` with:

```jsx
import { useEffect, useState } from 'react'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { policyRulesApi } from '../../api/policyRules'
import { tenantsApi } from '../../api/tenants'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { DataTable } from '../../components/DataTable/DataTable'
import { Modal } from '../../components/Modal/Modal'
import { ConditionGroupBuilder } from './ConditionGroupBuilder'
import { DryRunPanel } from './DryRunPanel'
import { RuleHistoryTab } from './RuleHistoryTab'
import styles from './PolicyRules.module.css'

const ACTIONS = ['ALLOW', 'FLAG', 'BLOCK', 'RESPOND', 'ESCALATE', 'EXECUTE', 'REVIEW']
const ACTION_VARIANT = { ALLOW: 'green', FLAG: 'blue', BLOCK: 'red', RESPOND: 'gray', ESCALATE: 'yellow', EXECUTE: 'red', REVIEW: 'yellow' }
const LIVE_EFFECT_ACTIONS = new Set(['RESPOND', 'EXECUTE', 'BLOCK'])

const TAB_EDIT = 'edit'
const TAB_HISTORY = 'history'

function RuleModal({ rule, onClose, onSave, tenants, api }) {
  const [tab, setTab] = useState(TAB_EDIT)
  const [form, setForm] = useState({
    name: rule?.name ?? '',
    targetIntent: rule?.targetIntent ?? '',
    action: rule?.action ?? 'FLAG',
    priority: rule?.priority ?? 0,
    description: rule?.description ?? '',
    effectiveFrom: rule?.effectiveFrom?.slice(0, 16) ?? '',
    effectiveTo: rule?.effectiveTo?.slice(0, 16) ?? '',
    tenantId: rule?.tenantId ?? '',
    conditions: rule?.conditions ?? { groups: [] },
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))
  const isExisting = !!rule?.id

  const currentRuleForDryRun = {
    ...form,
    effectiveFrom: form.effectiveFrom ? new Date(form.effectiveFrom).toISOString() : null,
    effectiveTo: form.effectiveTo ? new Date(form.effectiveTo).toISOString() : null,
    tenantId: form.tenantId || null,
    minConfidence: rule?.minConfidence ?? 0,
    maxConfidence: rule?.maxConfidence ?? null,
  }

  return (
    <Modal
      title={isExisting ? 'Edit Rule' : 'Create Rule'}
      onClose={onClose}
      onSubmit={tab === TAB_EDIT ? () => onSave(form) : undefined}
    >
      {isExisting && (
        <div className={styles.tabs}>
          <button className={`${styles.tab} ${tab === TAB_EDIT ? styles.tabActive : ''}`}
            onClick={() => setTab(TAB_EDIT)}>Edit Rule</button>
          <button className={`${styles.tab} ${tab === TAB_HISTORY ? styles.tabActive : ''}`}
            onClick={() => setTab(TAB_HISTORY)}>History</button>
        </div>
      )}

      {tab === TAB_EDIT && (
        <>
          <div className={styles.field}>
            <label>Rule Name *</label>
            <input type="text" className={styles.input} value={form.name}
              onChange={e => set('name', e.target.value)} required disabled={isExisting} />
          </div>
          <div className={styles.field}>
            <label>Target Intent</label>
            <input type="text" className={styles.input} value={form.targetIntent}
              onChange={e => set('targetIntent', e.target.value)} placeholder="e.g. SPAM, GREETING, * (wildcard)" />
          </div>
          <div className={styles.field}>
            <label>Action</label>
            <select className={styles.input} value={form.action}
              onChange={e => set('action', e.target.value)}>
              {ACTIONS.map(a => <option key={a}>{a}</option>)}
            </select>
            {LIVE_EFFECT_ACTIONS.has(form.action) && (
              <div className={styles.actionWarn} role="alert">
                This action will take effect in Telegram. Use FLAG or REVIEW for safe observation-only rules.
              </div>
            )}
          </div>
          <div className={styles.field}>
            <label>Priority</label>
            <input type="number" className={styles.input} value={form.priority}
              onChange={e => set('priority', parseInt(e.target.value) || 0)} min={0} />
          </div>
          <div className={styles.field}>
            <label>Description</label>
            <textarea className={styles.input} value={form.description}
              onChange={e => set('description', e.target.value)} rows={3} placeholder="Optional" />
          </div>
          <div className={styles.field}>
            <label>Effective From</label>
            <input type="datetime-local" className={styles.input} value={form.effectiveFrom}
              onChange={e => set('effectiveFrom', e.target.value)} />
          </div>
          <div className={styles.field}>
            <label>Effective To</label>
            <input type="datetime-local" className={styles.input} value={form.effectiveTo}
              onChange={e => set('effectiveTo', e.target.value)} />
          </div>
          <div className={styles.field}>
            <label>Tenant</label>
            <select className={styles.input} value={form.tenantId ?? ''}
              onChange={e => set('tenantId', e.target.value || null)}>
              <option value="">None</option>
              {tenants.map(t => (
                <option key={t.id} value={t.id}>{t.name} ({t.id.slice(0, 8)})</option>
              ))}
            </select>
          </div>
          <div className={styles.field}>
            <label>Conditions</label>
            <ConditionGroupBuilder
              groups={form.conditions?.groups ?? []}
              onChange={groups => set('conditions', { groups })}
            />
          </div>
          <DryRunPanel rule={currentRuleForDryRun} api={api} />
        </>
      )}

      {tab === TAB_HISTORY && (
        <RuleHistoryTab ruleId={rule?.id} api={api} />
      )}
    </Modal>
  )
}

export function PolicyRules() {
  const authRequest = useAuthRequest()
  const { currentTenant } = useAuth()
  const api = policyRulesApi(authRequest)
  const [rules, setRules] = useState([])
  const [modal, setModal] = useState(null)
  const [error, setError] = useState('')
  const [tenants, setTenants] = useState([])

  const load = () => api.list().then(setRules).catch(e => setError(e.message))
  useEffect(() => { load() }, [])
  useEffect(() => { tenantsApi(authRequest).list().then(setTenants).catch(() => {}) }, [])

  const save = async form => {
    try {
      const payload = {
        ...form,
        tenantId: form.tenantId || null,
        effectiveFrom: form.effectiveFrom ? new Date(form.effectiveFrom).toISOString() : null,
        effectiveTo: form.effectiveTo ? new Date(form.effectiveTo).toISOString() : null,
      }
      if (modal === 'add') await api.create(payload)
      else await api.update(modal.id, payload)
      setModal(null); load()
    } catch (e) { setError(e.message) }
  }

  const remove = async rule => {
    try { await api.remove(rule.id); load() }
    catch (e) { setError(e.message) }
  }

  const columns = [
    { key: 'name', label: 'Rule Name' },
    { key: 'targetIntent', label: 'Intent', render: v => <Badge variant="gray">{v}</Badge> },
    { key: 'action', label: 'Action', width: 110, render: v => <Badge variant={ACTION_VARIANT[v] ?? 'gray'}>{v}</Badge> },
    { key: 'priority', label: 'Priority', mono: true, width: 80 },
    { key: 'effectiveFrom', label: 'From', mono: true, width: 110, render: v => v ? new Date(v).toLocaleDateString() : '\u2014' },
    { key: 'effectiveTo', label: 'To', mono: true, width: 110, render: v => v ? new Date(v).toLocaleDateString() : '\u2014' },
  ]

  return (
    <>
      {error && <p style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }} role="alert">{error}</p>}
      <DataTable
        title="Policy Rules"
        systemId={`\u2696 policy-rules \u00b7 ${rules.length} rules`}
        addLabel="+ Create Rule"
        onAdd={() => setModal('add')}
        columns={columns}
        rows={rules}
        onEdit={setModal}
        onDelete={remove}
        deleteMessage={r => `Delete rule "${r.name}"? This cannot be undone.`}
        emptyText="No policy rules defined"
      />
      {modal && (
        <RuleModal
          rule={modal === 'add' ? { tenantId: currentTenant?.id ?? null } : modal}
          onClose={() => setModal(null)}
          onSave={save}
          tenants={tenants}
          api={api}
        />
      )}
    </>
  )
}
```

- [ ] **Step 9: Add tab styles to PolicyRules.module.css**

In `pages/PolicyRules/PolicyRules.module.css`, append:

```css
.tabs {
  display: flex;
  gap: 2px;
  margin-bottom: var(--sp-3);
  border-bottom: 1px solid var(--border);
}
.tab {
  padding: 6px 14px;
  font-family: var(--font-display);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.10em;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  color: var(--fg-3);
  cursor: pointer;
  margin-bottom: -1px;
}
.tab:hover { color: var(--fg-1); }
.tabActive {
  color: var(--accent);
  border-bottom-color: var(--accent);
}
```

- [ ] **Step 10: Verify frontend compiles**

```bash
mvn -pl emcip-admin-ui frontend:install frontend:build -q | cat
```

Expected: `BUILD SUCCESS` with no JS errors.

- [ ] **Step 11: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/api/policyRules.js \
        emcip-admin-ui/src/main/frontend/src/pages/PolicyRules/
git commit -m "feat(6): add ConditionGroupBuilder, DryRunPanel, RuleHistoryTab; update PolicyRules.jsx and policyRules.js"
```

---

## Task 8: Full Build + Spotless + BACKLOG Update

- [ ] **Step 1: Apply Spotless across all modules**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply | cat
```

If any files changed:
```bash
git add -A
git commit -m "style: apply spotless formatting for #6 feature branch"
```

- [ ] **Step 2: Full build with all tests**

```bash
mvn clean install -q | cat
```

Expected: `BUILD SUCCESS`. If any test fails, fix it before proceeding.

- [ ] **Step 3: Update BACKLOG.md**

In `docs/superpowers/BACKLOG.md`, mark `#6 — Policy versioning — complex rule logic` as complete and add a note:
```
#6 — Policy versioning — complex rule logic ✅ (feat/6-policy-rule-versioning)
  - OR-group condition composition (7 condition types)
  - In-place versioning with policy_rule_history snapshot table
  - Dry-run endpoint POST /api/policy-rules/dry-run
  - Admin UI: ConditionGroupBuilder, DryRunPanel, RuleHistoryTab
```

- [ ] **Step 4: Commit BACKLOG update**

```bash
git add docs/superpowers/BACKLOG.md
git commit -m "docs(6): mark #6 complete in BACKLOG"
```

- [ ] **Step 5: Apply finishing-a-development-branch skill**

Use `superpowers:finishing-a-development-branch` to verify, present options, and create the PR.
