# Policy Rule Versioning — Implementation Plan (Part 1 of 2: Backend Core)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **Part 2 of 2:** `docs/superpowers/plans/2026-06-22-policy-rule-versioning-plan-part2.md` (Tasks 5–8: dry-run, admin-api proxy, frontend, build)

**Goal:** Extend policy-engine with OR-group condition composition (7 types), in-place versioning with snapshot history, and a dry-run evaluation endpoint; wire admin-api proxy and Admin UI components.

**Architecture:** `conditions JSONB` adopts a `{ "groups": [{ "conditions": [...] }] }` shape (backward-compat: no-groups = always pass). A new `policy_rule_history` table captures full-rule snapshots on every PUT. A `ConditionEvaluator` interface with one `@Component` per type is auto-discovered into a registry. A stateless `DryRunService` evaluates unsaved rules with per-group/per-condition detail.

**Tech Stack:** Java 21, Spring Boot 4, WebFlux (JPA wrapped in `Schedulers.boundedElastic()`), Liquibase, JPA/Hibernate, Lombok `@Slf4j` + `@RequiredArgsConstructor`, `tools.jackson`, `@ExtendWith(MockitoExtension.class)` unit tests, Testcontainers integration tests via `@IntegrationTest`.

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `emcip-policy-engine/.../db/changelog/changes/009-policy-rule-history.xml` | New history table |
| Modify | `emcip-policy-engine/.../db/changelog/db.changelog-master.xml` | Register 009 |
| Create | `emcip-policy-engine/.../condition/ConditionType.java` | 7-value enum |
| Create | `emcip-policy-engine/.../condition/EvaluationContext.java` | Evaluation inputs record |
| Create | `emcip-policy-engine/.../condition/ConditionEvaluator.java` | Evaluator interface |
| Create | `emcip-policy-engine/.../condition/evaluator/TimeWindowEvaluator.java` | TIME_WINDOW |
| Create | `emcip-policy-engine/.../condition/evaluator/MinThreadLengthEvaluator.java` | MIN_THREAD_LENGTH |
| Create | `emcip-policy-engine/.../condition/evaluator/AccountAgeDaysEvaluator.java` | ACCOUNT_AGE_DAYS |
| Create | `emcip-policy-engine/.../condition/evaluator/MessageLanguageEvaluator.java` | MESSAGE_LANGUAGE |
| Create | `emcip-policy-engine/.../condition/evaluator/GroupSizeEvaluator.java` | GROUP_SIZE |
| Create | `emcip-policy-engine/.../condition/evaluator/MessageLengthEvaluator.java` | MESSAGE_LENGTH |
| Create | `emcip-policy-engine/.../condition/evaluator/FlaggedCountEvaluator.java` | FLAGGED_COUNT |
| Create | `emcip-policy-engine/.../condition/ConditionEvaluatorRegistry.java` | Spring registry |
| Create | `emcip-policy-engine/src/test/.../condition/ConditionEvaluatorTest.java` | Unit tests per evaluator |
| Modify | `emcip-policy-engine/.../repository/PolicyDecisionRepository.java` | Add sender count query |
| Modify | `emcip-policy-engine/.../service/PolicyEvaluationService.java` | OR-group logic + context building |
| Create | `emcip-policy-engine/.../entity/PolicyRuleHistory.java` | History snapshot entity |
| Create | `emcip-policy-engine/.../repository/PolicyRuleHistoryRepository.java` | History JPA repo |
| Modify | `emcip-policy-engine/.../controller/PolicyRuleController.java` | Snapshot-on-PUT + GET /{id}/history |
| Create | `emcip-policy-engine/src/test/.../service/PolicyRuleHistoryTest.java` | Integration test |

All paths expand from `emcip-policy-engine/src/main/java/io/emcip/policy/engine/` and `src/test/java/io/emcip/policy/engine/`.

---

## Task 1: Liquibase Migration — policy_rule_history table

**Files:**
- Create: `emcip-policy-engine/src/main/resources/db/changelog/changes/009-policy-rule-history.xml`
- Modify: `emcip-policy-engine/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Create the migration file**

```xml
<!-- emcip-policy-engine/src/main/resources/db/changelog/changes/009-policy-rule-history.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="009-create-policy-rule-history" author="system">
        <createTable tableName="policy_rule_history">
            <column name="id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="rule_id" type="VARCHAR(36)">
                <constraints nullable="false"/>
            </column>
            <column name="tenant_id" type="UUID">
                <constraints nullable="false"/>
            </column>
            <column name="snapshot" type="JSONB">
                <constraints nullable="false"/>
            </column>
            <column name="edited_by" type="VARCHAR(64)"/>
            <column name="edited_at" type="TIMESTAMPTZ">
                <constraints nullable="false"/>
            </column>
            <column name="rule_version" type="INTEGER">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex tableName="policy_rule_history" indexName="idx_prh_rule_id">
            <column name="rule_id"/>
        </createIndex>
        <createIndex tableName="policy_rule_history" indexName="idx_prh_tenant">
            <column name="tenant_id"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Register in changelog master**

In `emcip-policy-engine/src/main/resources/db/changelog/db.changelog-master.xml`, add after the 008 line:

```xml
    <include file="changes/009-policy-rule-history.xml" relativeToChangelogFile="true"/>
```

- [ ] **Step 3: Verify migration compiles**

```bash
cd /home/ben/Development/ecip
mvn -pl emcip-policy-engine compile -q | cat
```

Expected: `BUILD SUCCESS` with no errors.

- [ ] **Step 4: Commit**

```bash
git add emcip-policy-engine/src/main/resources/db/changelog/changes/009-policy-rule-history.xml \
        emcip-policy-engine/src/main/resources/db/changelog/db.changelog-master.xml
git commit -m "feat(6): add policy_rule_history Liquibase migration (009)"
```

---

## Task 2: Condition Types — Enum, Interface, 7 Evaluators, Registry

**Files:**
- Create all in `emcip-policy-engine/src/main/java/io/emcip/policy/engine/condition/`
- Evaluators in sub-package `.../condition/evaluator/`
- Test: `emcip-policy-engine/src/test/java/io/emcip/policy/engine/condition/ConditionEvaluatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/io/emcip/policy/engine/condition/ConditionEvaluatorTest.java
package io.emcip.policy.engine.condition;

import io.emcip.policy.engine.condition.evaluator.*;
import java.time.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConditionEvaluatorTest {

    private static final ZonedDateTime NIGHT =
            ZonedDateTime.of(2026, 6, 22, 23, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime DAY =
            ZonedDateTime.of(2026, 6, 22, 12, 0, 0, 0, ZoneOffset.UTC);

    private static EvaluationContext ctx(ZonedDateTime now) {
        return new EvaluationContext("SPAM", 0.9, "en", 5, 120, 45, 2, 1, 90, now);
    }

    // --- TIME_WINDOW ---
    @Test void timeWindow_overnight_inside() {
        assertThat(new TimeWindowEvaluator().evaluate(Map.of("start","22:00","end","06:00"), ctx(NIGHT))).isTrue();
    }
    @Test void timeWindow_overnight_outside() {
        assertThat(new TimeWindowEvaluator().evaluate(Map.of("start","22:00","end","06:00"), ctx(DAY))).isFalse();
    }
    @Test void timeWindow_sameDay_inside() {
        ZonedDateTime noon = ZonedDateTime.of(2026,6,22,13,0,0,0,ZoneOffset.UTC);
        assertThat(new TimeWindowEvaluator().evaluate(Map.of("start","09:00","end","17:00"), ctx(noon))).isTrue();
    }
    @Test void timeWindow_detail_format() {
        String d = new TimeWindowEvaluator().detail(Map.of("start","22:00","end","06:00"), ctx(NIGHT));
        assertThat(d).contains("23:00").contains("22:00").contains("06:00");
    }

    // --- MIN_THREAD_LENGTH ---
    @Test void minThreadLength_passes() {
        assertThat(new MinThreadLengthEvaluator().evaluate(Map.of("min",3), ctx(DAY))).isTrue();
    }
    @Test void minThreadLength_fails() {
        EvaluationContext short_ = new EvaluationContext("S",0.9,"en",1,0,0,0,0,90,DAY);
        assertThat(new MinThreadLengthEvaluator().evaluate(Map.of("min",5), short_)).isFalse();
    }

    // --- ACCOUNT_AGE_DAYS ---
    @Test void accountAge_passes_young() {
        // ctx senderAccountAgeDays = 2, max = 7 → pass
        assertThat(new AccountAgeDaysEvaluator().evaluate(Map.of("max",7), ctx(DAY))).isTrue();
    }
    @Test void accountAge_fails_old() {
        EvaluationContext old = new EvaluationContext("S",0.9,"en",0,0,0,100,0,90,DAY);
        assertThat(new AccountAgeDaysEvaluator().evaluate(Map.of("max",7), old)).isFalse();
    }

    // --- MESSAGE_LANGUAGE ---
    @Test void language_include_match() {
        assertThat(new MessageLanguageEvaluator().evaluate(Map.of("languages",List.of("en","de"),"mode","INCLUDE"), ctx(DAY))).isTrue();
    }
    @Test void language_include_no_match() {
        assertThat(new MessageLanguageEvaluator().evaluate(Map.of("languages",List.of("de","fr"),"mode","INCLUDE"), ctx(DAY))).isFalse();
    }
    @Test void language_exclude_match() {
        assertThat(new MessageLanguageEvaluator().evaluate(Map.of("languages",List.of("ru"),"mode","EXCLUDE"), ctx(DAY))).isTrue();
    }

    // --- GROUP_SIZE ---
    @Test void groupSize_passes() {
        // ctx groupSize = 120, min = 50 → pass
        assertThat(new GroupSizeEvaluator().evaluate(Map.of("min",50), ctx(DAY))).isTrue();
    }
    @Test void groupSize_fails() {
        EvaluationContext small = new EvaluationContext("S",0.9,"en",0,10,0,0,0,90,DAY);
        assertThat(new GroupSizeEvaluator().evaluate(Map.of("min",100), small)).isFalse();
    }

    // --- MESSAGE_LENGTH ---
    @Test void messageLength_withinBounds() {
        // ctx messageLength = 45, min=10 max=100 → pass
        assertThat(new MessageLengthEvaluator().evaluate(Map.of("min",10,"max",100), ctx(DAY))).isTrue();
    }
    @Test void messageLength_tooShort() {
        assertThat(new MessageLengthEvaluator().evaluate(Map.of("min",100), ctx(DAY))).isFalse();
    }
    @Test void messageLength_tooLong() {
        assertThat(new MessageLengthEvaluator().evaluate(Map.of("max",10), ctx(DAY))).isFalse();
    }

    // --- FLAGGED_COUNT ---
    @Test void flaggedCount_passes() {
        // ctx senderFlaggedCount=1, senderFlagWindowDays=90, condition min=1, windowDays=30 → pass
        assertThat(new FlaggedCountEvaluator().evaluate(Map.of("min",1,"windowDays",30), ctx(DAY))).isTrue();
    }
    @Test void flaggedCount_fails_insufficient() {
        assertThat(new FlaggedCountEvaluator().evaluate(Map.of("min",3,"windowDays",30), ctx(DAY))).isFalse();
    }
    @Test void flaggedCount_fails_window_too_wide() {
        // requires 120-day window but context only has 90-day data → fail conservatively
        assertThat(new FlaggedCountEvaluator().evaluate(Map.of("min",1,"windowDays",120), ctx(DAY))).isFalse();
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
mvn -pl emcip-policy-engine test -Dtest=ConditionEvaluatorTest -q | cat
```

Expected: FAIL — classes not found.

- [ ] **Step 3: Create ConditionType enum**

```java
// src/main/java/io/emcip/policy/engine/condition/ConditionType.java
package io.emcip.policy.engine.condition;

public enum ConditionType {
    TIME_WINDOW,
    MIN_THREAD_LENGTH,
    ACCOUNT_AGE_DAYS,
    MESSAGE_LANGUAGE,
    GROUP_SIZE,
    MESSAGE_LENGTH,
    FLAGGED_COUNT
}
```

- [ ] **Step 4: Create EvaluationContext record**

```java
// src/main/java/io/emcip/policy/engine/condition/EvaluationContext.java
package io.emcip.policy.engine.condition;

import java.time.ZonedDateTime;

public record EvaluationContext(
        String intent,
        double confidence,
        String language,
        int threadLength,
        int groupSize,
        int messageLength,
        int senderAccountAgeDays,
        int senderFlaggedCount,
        int senderFlagWindowDays,
        ZonedDateTime now) {}
```

- [ ] **Step 5: Create ConditionEvaluator interface**

```java
// src/main/java/io/emcip/policy/engine/condition/ConditionEvaluator.java
package io.emcip.policy.engine.condition;

import java.util.Map;

public interface ConditionEvaluator {
    ConditionType type();
    boolean evaluate(Map<String, Object> params, EvaluationContext ctx);
    /** Human-readable string explaining the result (used in dry-run). */
    String detail(Map<String, Object> params, EvaluationContext ctx);
}
```

- [ ] **Step 6: Create TimeWindowEvaluator**

```java
// src/main/java/io/emcip/policy/engine/condition/evaluator/TimeWindowEvaluator.java
package io.emcip.policy.engine.condition.evaluator;

import io.emcip.policy.engine.condition.*;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TimeWindowEvaluator implements ConditionEvaluator {

    @Override public ConditionType type() { return ConditionType.TIME_WINDOW; }

    @Override
    public boolean evaluate(Map<String, Object> params, EvaluationContext ctx) {
        String start = (String) params.get("start");
        String end   = (String) params.get("end");
        if (start == null || end == null) return true;
        int now   = ctx.now().getHour() * 60 + ctx.now().getMinute();
        int s     = parseHhmm(start);
        int e     = parseHhmm(end);
        return s <= e ? (now >= s && now < e) : (now >= s || now < e);
    }

    @Override
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        String nowStr = String.format("%02d:%02d", ctx.now().getHour(), ctx.now().getMinute());
        return nowStr + " in [" + params.getOrDefault("start","?") + "–" + params.getOrDefault("end","?") + "]";
    }

    private static int parseHhmm(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }
}
```

- [ ] **Step 7: Create MinThreadLengthEvaluator**

```java
// src/main/java/io/emcip/policy/engine/condition/evaluator/MinThreadLengthEvaluator.java
package io.emcip.policy.engine.condition.evaluator;

import io.emcip.policy.engine.condition.*;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MinThreadLengthEvaluator implements ConditionEvaluator {

    @Override public ConditionType type() { return ConditionType.MIN_THREAD_LENGTH; }

    @Override
    public boolean evaluate(Map<String, Object> params, EvaluationContext ctx) {
        int min = ((Number) params.getOrDefault("min", 0)).intValue();
        return ctx.threadLength() >= min;
    }

    @Override
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        return ctx.threadLength() + " >= " + ((Number) params.getOrDefault("min", 0)).intValue();
    }
}
```

- [ ] **Step 8: Create AccountAgeDaysEvaluator**

```java
// src/main/java/io/emcip/policy/engine/condition/evaluator/AccountAgeDaysEvaluator.java
package io.emcip.policy.engine.condition.evaluator;

import io.emcip.policy.engine.condition.*;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AccountAgeDaysEvaluator implements ConditionEvaluator {

    @Override public ConditionType type() { return ConditionType.ACCOUNT_AGE_DAYS; }

    @Override
    public boolean evaluate(Map<String, Object> params, EvaluationContext ctx) {
        int max = ((Number) params.getOrDefault("max", Integer.MAX_VALUE)).intValue();
        return ctx.senderAccountAgeDays() <= max;
    }

    @Override
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        int max = ((Number) params.getOrDefault("max", Integer.MAX_VALUE)).intValue();
        return ctx.senderAccountAgeDays() + "d <= " + max + "d";
    }
}
```

- [ ] **Step 9: Create MessageLanguageEvaluator**

```java
// src/main/java/io/emcip/policy/engine/condition/evaluator/MessageLanguageEvaluator.java
package io.emcip.policy.engine.condition.evaluator;

import io.emcip.policy.engine.condition.*;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MessageLanguageEvaluator implements ConditionEvaluator {

    @Override public ConditionType type() { return ConditionType.MESSAGE_LANGUAGE; }

    @Override
    @SuppressWarnings("unchecked")
    public boolean evaluate(Map<String, Object> params, EvaluationContext ctx) {
        List<String> languages = (List<String>) params.getOrDefault("languages", List.of());
        String mode = (String) params.getOrDefault("mode", "INCLUDE");
        boolean inList = languages.stream().anyMatch(l -> l.equalsIgnoreCase(ctx.language()));
        return "INCLUDE".equals(mode) ? inList : !inList;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        List<String> languages = (List<String>) params.getOrDefault("languages", List.of());
        String mode = (String) params.getOrDefault("mode", "INCLUDE");
        return ctx.language() + " " + mode + " " + languages;
    }
}
```

- [ ] **Step 10: Create GroupSizeEvaluator**

```java
// src/main/java/io/emcip/policy/engine/condition/evaluator/GroupSizeEvaluator.java
package io.emcip.policy.engine.condition.evaluator;

import io.emcip.policy.engine.condition.*;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GroupSizeEvaluator implements ConditionEvaluator {

    @Override public ConditionType type() { return ConditionType.GROUP_SIZE; }

    @Override
    public boolean evaluate(Map<String, Object> params, EvaluationContext ctx) {
        int min = ((Number) params.getOrDefault("min", 0)).intValue();
        return ctx.groupSize() >= min;
    }

    @Override
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        return ctx.groupSize() + " >= " + ((Number) params.getOrDefault("min", 0)).intValue() + " members";
    }
}
```

- [ ] **Step 11: Create MessageLengthEvaluator**

```java
// src/main/java/io/emcip/policy/engine/condition/evaluator/MessageLengthEvaluator.java
package io.emcip.policy.engine.condition.evaluator;

import io.emcip.policy.engine.condition.*;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MessageLengthEvaluator implements ConditionEvaluator {

    @Override public ConditionType type() { return ConditionType.MESSAGE_LENGTH; }

    @Override
    public boolean evaluate(Map<String, Object> params, EvaluationContext ctx) {
        int len = ctx.messageLength();
        if (params.containsKey("min") && len < ((Number) params.get("min")).intValue()) return false;
        if (params.containsKey("max") && len > ((Number) params.get("max")).intValue()) return false;
        return true;
    }

    @Override
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        StringBuilder sb = new StringBuilder(String.valueOf(ctx.messageLength()) + " chars");
        if (params.containsKey("min")) sb.append(", min=").append(params.get("min"));
        if (params.containsKey("max")) sb.append(", max=").append(params.get("max"));
        return sb.toString();
    }
}
```

- [ ] **Step 12: Create FlaggedCountEvaluator**

```java
// src/main/java/io/emcip/policy/engine/condition/evaluator/FlaggedCountEvaluator.java
package io.emcip.policy.engine.condition.evaluator;

import io.emcip.policy.engine.condition.*;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FlaggedCountEvaluator implements ConditionEvaluator {

    @Override public ConditionType type() { return ConditionType.FLAGGED_COUNT; }

    @Override
    public boolean evaluate(Map<String, Object> params, EvaluationContext ctx) {
        int min = ((Number) params.getOrDefault("min", 0)).intValue();
        int windowDays = ((Number) params.getOrDefault("windowDays", 30)).intValue();
        // Conservatively fail if the rule requires a longer window than pre-computed.
        if (windowDays > ctx.senderFlagWindowDays()) return false;
        return ctx.senderFlaggedCount() >= min;
    }

    @Override
    public String detail(Map<String, Object> params, EvaluationContext ctx) {
        int min = ((Number) params.getOrDefault("min", 0)).intValue();
        int windowDays = ((Number) params.getOrDefault("windowDays", 30)).intValue();
        return ctx.senderFlaggedCount() + " >= " + min + " in last " + windowDays + "d";
    }
}
```

- [ ] **Step 13: Create ConditionEvaluatorRegistry**

```java
// src/main/java/io/emcip/policy/engine/condition/ConditionEvaluatorRegistry.java
package io.emcip.policy.engine.condition;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ConditionEvaluatorRegistry {

    private final Map<ConditionType, ConditionEvaluator> evaluators;

    public ConditionEvaluatorRegistry(List<ConditionEvaluator> evaluators) {
        this.evaluators =
                evaluators.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(ConditionEvaluator::type, e -> e));
    }

    public boolean evaluate(Map<String, Object> condition, EvaluationContext ctx) {
        ConditionEvaluator ev = resolve(condition);
        return ev.evaluate(condition, ctx);
    }

    public String detail(Map<String, Object> condition, EvaluationContext ctx) {
        ConditionEvaluator ev = resolve(condition);
        return ev.detail(condition, ctx);
    }

    private ConditionEvaluator resolve(Map<String, Object> condition) {
        String typeStr = (String) condition.get("type");
        if (typeStr == null) throw new IllegalArgumentException("Condition missing 'type'");
        ConditionType type = ConditionType.valueOf(typeStr);
        ConditionEvaluator ev = evaluators.get(type);
        if (ev == null) throw new IllegalArgumentException("No evaluator registered for: " + type);
        return ev;
    }
}
```

- [ ] **Step 14: Run tests — expect green**

```bash
mvn -pl emcip-policy-engine test -Dtest=ConditionEvaluatorTest -q | cat
```

Expected: `Tests run: 16, Failures: 0, Errors: 0`

- [ ] **Step 15: Commit**

```bash
git add emcip-policy-engine/src/main/java/io/emcip/policy/engine/condition/ \
        emcip-policy-engine/src/test/java/io/emcip/policy/engine/condition/
git commit -m "feat(6): add ConditionType enum, EvaluationContext, ConditionEvaluator interface, 7 evaluators, registry"
```

---

## Task 3: OR-Group Evaluation in PolicyEvaluationService

**Files:**
- Modify: `emcip-policy-engine/.../repository/PolicyDecisionRepository.java`
- Modify: `emcip-policy-engine/.../service/PolicyEvaluationService.java`

- [ ] **Step 1: Write the failing test (new test method in PolicyEvaluationServiceTest)**

Add these test methods to the existing `PolicyEvaluationServiceTest.java`. The existing `@BeforeEach setUp()` already wires `policyService` with mocked repos — extend it to include the registry.

```java
// In PolicyEvaluationServiceTest — update setUp() and add these tests:

// In setUp(), add registry wiring:
private ConditionEvaluatorRegistry registry;

@BeforeEach
void setUp() {
    objectMapper = new ObjectMapper();
    List<ConditionEvaluator> evs = List.of(
        new TimeWindowEvaluator(),
        new MinThreadLengthEvaluator(),
        new AccountAgeDaysEvaluator(),
        new MessageLanguageEvaluator(),
        new GroupSizeEvaluator(),
        new MessageLengthEvaluator(),
        new FlaggedCountEvaluator()
    );
    registry = new ConditionEvaluatorRegistry(evs);
    policyService = new PolicyEvaluationService(
        kafkaTemplate, objectMapper, decisionRepository, ruleConfigRepository, actionService, registry);
}

@Test
@DisplayName("Rule with no conditions.groups passes (backward compat)")
void conditionsAbsent_alwaysPasses() {
    PolicyRuleConfig rule = makeRule("SPAM", 0.7, null);
    when(ruleConfigRepository.findEffectiveRulesAt(any())).thenReturn(List.of(rule));
    stubDecisionSave();

    PolicyDecision d = policyService.evaluate(makeEvent("SPAM", 0.9, Map.of()), UUID.randomUUID());
    assertThat(d.getDecision()).isEqualTo("BLOCK");
}

@Test
@DisplayName("Single OR-group: all conditions pass → rule matches")
void singleGroup_allPass() {
    Map<String, Object> conditions = Map.of("groups", List.of(
        Map.of("conditions", List.of(
            Map.of("type", "MIN_THREAD_LENGTH", "min", 3)
        ))
    ));
    PolicyRuleConfig rule = makeRule("SPAM", 0.7, conditions);
    when(ruleConfigRepository.findEffectiveRulesAt(any())).thenReturn(List.of(rule));
    stubDecisionSave();

    PolicyDecision d = policyService.evaluate(
        makeEvent("SPAM", 0.9, Map.of("threadLength", 5)), UUID.randomUUID());
    assertThat(d.getDecision()).isEqualTo("BLOCK");
}

@Test
@DisplayName("Single OR-group: one condition fails → no match → ALLOW")
void singleGroup_condFails_noMatch() {
    Map<String, Object> conditions = Map.of("groups", List.of(
        Map.of("conditions", List.of(
            Map.of("type", "MIN_THREAD_LENGTH", "min", 10)
        ))
    ));
    PolicyRuleConfig rule = makeRule("SPAM", 0.7, conditions);
    PolicyRuleConfig fallback = makeRule("*", 0.0, null);
    fallback.setAction("ALLOW");
    when(ruleConfigRepository.findEffectiveRulesAt(any())).thenReturn(List.of(rule, fallback));
    stubDecisionSave();

    PolicyDecision d = policyService.evaluate(
        makeEvent("SPAM", 0.9, Map.of("threadLength", 2)), UUID.randomUUID());
    assertThat(d.getDecision()).isEqualTo("ALLOW");
}

@Test
@DisplayName("Multi-group OR: first group fails, second passes → match")
void multiGroup_secondPasses() {
    Map<String, Object> conditions = Map.of("groups", List.of(
        Map.of("conditions", List.of(Map.of("type", "ACCOUNT_AGE_DAYS", "max", 3))),
        Map.of("conditions", List.of(Map.of("type", "GROUP_SIZE", "min", 100)))
    ));
    PolicyRuleConfig rule = makeRule("SPAM", 0.7, conditions);
    when(ruleConfigRepository.findEffectiveRulesAt(any())).thenReturn(List.of(rule));
    stubDecisionSave();

    // senderAccountAgeDays=10 (fails first group), groupSize=200 (passes second)
    PolicyDecision d = policyService.evaluate(
        makeEvent("SPAM", 0.9, Map.of("senderAccountAgeDays", 10, "groupSize", 200)),
        UUID.randomUUID());
    assertThat(d.getDecision()).isEqualTo("BLOCK");
}

// Helpers — add at bottom of test class:
private PolicyRuleConfig makeRule(String intent, double minConf, Map<String, Object> conditions) {
    PolicyRuleConfig r = new PolicyRuleConfig();
    r.setId(UUID.randomUUID().toString());
    r.setTenantId(UUID.randomUUID());
    r.setName("test-rule");
    r.setTargetIntent(intent);
    r.setMinConfidence(minConf);
    r.setAction("BLOCK");
    r.setConditions(conditions);
    r.setActive(true);
    r.setPriority(0);
    r.setRuleVersion(1);
    return r;
}

private EventSchemas.IntentClassifiedEvent makeEvent(String intent, double conf, Map<String, Object> params) {
    return new EventSchemas.IntentClassifiedEvent(
        UUID.randomUUID().toString(), Instant.now().toString(),
        "v1", "IntentClassified", UUID.randomUUID().toString(),
        intent, conf, List.of(), params);
}

private void stubDecisionSave() {
    when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn -pl emcip-policy-engine test -Dtest=PolicyEvaluationServiceTest -q | cat
```

Expected: compile error — `PolicyEvaluationService` constructor doesn't accept registry yet.

- [ ] **Step 3: Add sender count query to PolicyDecisionRepository**

In `PolicyDecisionRepository.java`, add after the existing methods:

```java
/** Count BLOCK/FLAG decisions for a given senderId after the given timestamp. */
@Query(
        nativeQuery = true,
        value =
                "SELECT COUNT(*) FROM policy_decisions "
                        + "WHERE metadata->>'senderId' = :senderId "
                        + "AND decision IN ('BLOCK', 'FLAG') "
                        + "AND timestamp > :since")
int countBlockedBySenderSince(
        @Param("senderId") String senderId, @Param("since") Instant since);
```

- [ ] **Step 4: Update PolicyEvaluationService**

Replace `PolicyEvaluationService.java` with the following complete file:

```java
package io.emcip.policy.engine.service;

import io.emcip.common.events.EventSchemas;
import io.emcip.policy.engine.condition.ConditionEvaluatorRegistry;
import io.emcip.policy.engine.condition.EvaluationContext;
import io.emcip.policy.engine.entity.PolicyDecision;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.repository.PolicyDecisionRepository;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PolicyEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(PolicyEvaluationService.class);
    private static final String TOPIC_OUTPUT = "policies.decisions";
    private static final int FLAG_WINDOW_DAYS = 90;

    private static final Set<String> SIGNAL_PARAM_KEYS =
            Set.of(
                    "foreignScriptRatio", "cyrillicRatio", "lookalikeSuspicion",
                    "zeroWidthAbuse", "capsRatio", "emojiOnly", "stickerOnly",
                    "imageOnly", "toxicityHint");

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final PolicyDecisionRepository decisionRepository;
    private final PolicyRuleConfigRepository ruleConfigRepository;
    private final PolicyActionService actionService;
    private final ConditionEvaluatorRegistry conditionRegistry;

    private final List<DefaultPolicyRule> defaultRules =
            List.of(
                    new DefaultPolicyRule("policy-001","SPAM_BLOCK","SPAM",0.8,null,"BLOCK","Spam detected"),
                    new DefaultPolicyRule("policy-002","GREETING_RESPONSE","GREETING",0.7,null,"RESPOND","Greeting detected"),
                    new DefaultPolicyRule("policy-003","QUESTION_ESCALATE","QUESTION",0.75,null,"ESCALATE","Question requires response"),
                    new DefaultPolicyRule("policy-004","COMMAND_EXECUTE","COMMAND",0.8,null,"EXECUTE","Execute command"),
                    new DefaultPolicyRule("policy-005","MODERATION_CHECK","*",0.0,0.3,"REVIEW","Low confidence"));

    public PolicyEvaluationService(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            PolicyDecisionRepository decisionRepository,
            PolicyRuleConfigRepository ruleConfigRepository,
            PolicyActionService actionService,
            ConditionEvaluatorRegistry conditionRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.decisionRepository = decisionRepository;
        this.ruleConfigRepository = ruleConfigRepository;
        this.actionService = actionService;
        this.conditionRegistry = conditionRegistry;
    }

    @Transactional
    public PolicyDecision evaluate(EventSchemas.IntentClassifiedEvent classification, UUID tenantId) {
        String decision = "ALLOW";
        String reason = "No policy matched";
        String matchedPolicyId = null;

        List<PolicyRuleConfig> dbRules = ruleConfigRepository.findEffectiveRulesAt(Instant.now());
        EvaluationContext ctx = buildContext(classification);

        List<EvaluatedRule> rulesToEvaluate = new ArrayList<>();
        if (dbRules.isEmpty()) {
            for (DefaultPolicyRule r : defaultRules) {
                rulesToEvaluate.add(new EvaluatedRule(r.id, r.name, r.targetIntent,
                        r.minConfidence, r.maxConfidence, r.action, r.reason, null));
            }
        } else {
            for (PolicyRuleConfig r : dbRules) {
                rulesToEvaluate.add(new EvaluatedRule(r.getId(), r.getName(), r.getTargetIntent(),
                        r.getMinConfidence(), r.getMaxConfidence(), r.getAction(), r.getReason(),
                        r.getConditions()));
            }
        }

        for (EvaluatedRule rule : rulesToEvaluate) {
            if (matchesRule(rule, ctx)) {
                decision = rule.action;
                reason = (rule.reason != null && !rule.reason.isBlank())
                        ? rule.reason : rule.name + " matched";
                matchedPolicyId = rule.id;
                log.info("Policy {} matched for event {}: {} -> {}",
                        rule.id, classification.sourceEventId(), classification.intent(), decision);
                break;
            }
        }

        PolicyDecision persisted = persistDecision(classification, matchedPolicyId, decision, reason, tenantId);

        try {
            var event = new EventSchemas.PolicyDecisionEvent(
                    persisted.getId(), Instant.now().toString(), EventSchemas.POLICY_DECISION_V1,
                    "PolicyDecision", classification.eventId(),
                    matchedPolicyId != null ? matchedPolicyId : "default",
                    decision, reason, buildDecisionContext(classification),
                    List.of(decision.toLowerCase()),
                    classification.parameters() != null
                            && classification.parameters().get("messageText") instanceof String t ? t : null);
            String json = objectMapper.writeValueAsString(event);
            ProducerRecord<String, String> rec = new ProducerRecord<>(TOPIC_OUTPUT, null, classification.eventId(), json);
            if (tenantId != null) rec.headers().add("tenant_id", tenantId.toString().getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(rec);
        } catch (Exception e) {
            log.error("Failed to publish policy decision to Kafka: {}", e.getMessage(), e);
        }

        actionService.executeAction(persisted, Map.of(
                "intent", classification.intent(), "confidence", classification.confidence(),
                "matchedRules", classification.matchedRules(), "parameters", classification.parameters()));

        return persisted;
    }

    /** Checks intent + confidence + OR-group conditions. */
    private boolean matchesRule(EvaluatedRule rule, EvaluationContext ctx) {
        boolean intentMatches = "*".equals(rule.targetIntent) || rule.targetIntent.equals(ctx.intent());
        boolean confMatches = ctx.confidence() >= rule.minConfidence
                && (rule.maxConfidence == null || ctx.confidence() <= rule.maxConfidence);
        if (!intentMatches || !confMatches) return false;
        return matchesConditions(rule.conditions, ctx);
    }

    /**
     * Evaluates OR-group conditions. Empty/absent groups = always pass (backward compat).
     * Groups are OR'd; conditions within a group are AND'd.
     */
    @SuppressWarnings("unchecked")
    public boolean matchesConditions(Map<String, Object> conditions, EvaluationContext ctx) {
        if (conditions == null) return true;
        Object groupsObj = conditions.get("groups");
        if (groupsObj == null) return true;
        List<Map<String, Object>> groups = (List<Map<String, Object>>) groupsObj;
        if (groups.isEmpty()) return true;
        for (Map<String, Object> group : groups) {
            List<Map<String, Object>> conds =
                    (List<Map<String, Object>>) group.getOrDefault("conditions", List.of());
            boolean groupPasses = conds.stream().allMatch(c -> conditionRegistry.evaluate(c, ctx));
            if (groupPasses) return true;
        }
        return false;
    }

    private EvaluationContext buildContext(EventSchemas.IntentClassifiedEvent event) {
        Map<String, Object> p = event.parameters() != null ? event.parameters() : Map.of();
        String senderId = p.get("senderId") instanceof String s ? s : null;
        int flaggedCount = 0;
        if (senderId != null) {
            try {
                Instant since = Instant.now().minus(FLAG_WINDOW_DAYS, ChronoUnit.DAYS);
                flaggedCount = decisionRepository.countBlockedBySenderSince(senderId, since);
            } catch (Exception e) {
                log.warn("Failed to fetch sender flagged count: {}", e.getMessage());
            }
        }
        return new EvaluationContext(
                event.intent(), event.confidence(),
                p.get("language") instanceof String l ? l : "",
                p.get("threadLength") instanceof Number n ? n.intValue() : 0,
                p.get("groupSize") instanceof Number n ? n.intValue() : 0,
                p.get("messageLength") instanceof Number n ? n.intValue() : 0,
                p.get("senderAccountAgeDays") instanceof Number n ? n.intValue() : Integer.MAX_VALUE,
                flaggedCount, FLAG_WINDOW_DAYS,
                ZonedDateTime.now());
    }

    public List<PolicyRuleConfig> getActiveRules() {
        return ruleConfigRepository.findByActiveTrueOrderByPriorityAsc();
    }

    private Map<String, Object> buildDecisionContext(EventSchemas.IntentClassifiedEvent c) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("originalIntent", c.intent());
        ctx.put("confidence", c.confidence());
        ctx.put("matchedRules", c.matchedRules());
        Map<String, Object> params = c.parameters() != null ? c.parameters() : Map.of();
        for (String key : SIGNAL_PARAM_KEYS) if (params.containsKey(key)) ctx.put(key, params.get(key));
        return ctx;
    }

    private PolicyDecision persistDecision(EventSchemas.IntentClassifiedEvent classification,
            String matchedPolicyId, String decision, String reason, UUID tenantId) {
        PolicyDecision pd = new PolicyDecision();
        pd.setTenantId(tenantId);
        pd.setEventId(UUID.randomUUID().toString());
        pd.setSourceEventId(classification.eventId());
        pd.setPolicyId(matchedPolicyId != null ? matchedPolicyId : "default");
        pd.setDecision(decision);
        pd.setReason(reason);
        pd.setOriginalIntent(classification.intent());
        pd.setConfidence(classification.confidence());
        pd.setMatchedRules(Map.of("matchedRules", classification.matchedRules()));
        Map<String, Object> params = classification.parameters() != null ? classification.parameters() : Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("intent", classification.intent());
        meta.put("confidence", classification.confidence());
        for (String k : List.of("messageText","chatId","senderId","telegramMessageId")) {
            if (params.containsKey(k)) meta.put(k, params.get(k));
        }
        for (String key : SIGNAL_PARAM_KEYS) if (params.containsKey(key)) meta.put(key, params.get(key));
        pd.setMetadata(meta);
        pd.setTimestamp(Instant.now());
        return decisionRepository.save(pd);
    }

    // Old static helpers preserved for any callers; superseded by evaluators.
    static boolean matchesTimeWindow(Map<String, Object> conditions, ZonedDateTime now) {
        if (conditions == null) return true;
        String start = (String) conditions.get("timeWindowStart");
        String end = (String) conditions.get("timeWindowEnd");
        if (start == null || end == null) return true;
        int n = now.getHour() * 60 + now.getMinute();
        int s = parseHhmm(start), e = parseHhmm(end);
        return s <= e ? (n >= s && n < e) : (n >= s || n < e);
    }

    static boolean matchesContextConditions(Map<String, Object> conditions, Map<String, Object> context) {
        if (conditions == null) return true;
        Object minLen = conditions.get("minThreadLength");
        if (minLen != null) {
            int actual = ((Number) context.getOrDefault("threadLength", 0)).intValue();
            if (actual < ((Number) minLen).intValue()) return false;
        }
        return true;
    }

    private static int parseHhmm(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    private record DefaultPolicyRule(String id, String name, String targetIntent,
            Double minConfidence, Double maxConfidence, String action, String reason) {}

    private record EvaluatedRule(String id, String name, String targetIntent,
            Double minConfidence, Double maxConfidence, String action, String reason,
            Map<String, Object> conditions) {}
}
```

- [ ] **Step 5: Run tests**

```bash
mvn -pl emcip-policy-engine test -Dtest="PolicyEvaluationServiceTest,ConditionEvaluatorTest" -q | cat
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add emcip-policy-engine/src/main/java/io/emcip/policy/engine/repository/PolicyDecisionRepository.java \
        emcip-policy-engine/src/main/java/io/emcip/policy/engine/service/PolicyEvaluationService.java \
        emcip-policy-engine/src/test/java/io/emcip/policy/engine/service/PolicyEvaluationServiceTest.java
git commit -m "feat(6): implement OR-group condition evaluation in PolicyEvaluationService"
```

---

## Task 4: PolicyRuleHistory — Entity, Repository, Snapshot-on-PUT, GET /{id}/history

**Files:**
- Create: `emcip-policy-engine/.../entity/PolicyRuleHistory.java`
- Create: `emcip-policy-engine/.../repository/PolicyRuleHistoryRepository.java`
- Modify: `emcip-policy-engine/.../controller/PolicyRuleController.java`
- Create: `emcip-policy-engine/src/test/.../service/PolicyRuleHistoryTest.java` (integration test)

- [ ] **Step 1: Write the failing integration test**

```java
// src/test/java/io/emcip/policy/engine/service/PolicyRuleHistoryTest.java
package io.emcip.policy.engine.service;

import io.emcip.policy.engine.IntegrationTest;
import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.entity.PolicyRuleHistory;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import io.emcip.policy.engine.repository.PolicyRuleHistoryRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class PolicyRuleHistoryTest {

    @Autowired private PolicyRuleConfigRepository ruleRepo;
    @Autowired private PolicyRuleHistoryRepository historyRepo;

    @Test
    void snapshotWrittenOnUpdate() {
        UUID tenantId = UUID.randomUUID();
        PolicyRuleConfig rule = new PolicyRuleConfig();
        rule.setId(UUID.randomUUID().toString());
        rule.setTenantId(tenantId);
        rule.setName("test-versioning");
        rule.setTargetIntent("SPAM");
        rule.setMinConfidence(0.7);
        rule.setAction("BLOCK");
        rule.setActive(true);
        rule.setPriority(10);
        rule.setRuleVersion(1);
        ruleRepo.save(rule);

        // Simulate a snapshot (as the controller would do it)
        PolicyRuleHistory snap = new PolicyRuleHistory();
        snap.setId(UUID.randomUUID());
        snap.setRuleId(rule.getId());
        snap.setTenantId(tenantId);
        snap.setSnapshot(java.util.Map.of("name", "test-versioning", "action", "BLOCK"));
        snap.setEditedBy("admin");
        snap.setEditedAt(java.time.Instant.now());
        snap.setRuleVersion(1);
        historyRepo.save(snap);

        List<PolicyRuleHistory> history = historyRepo.findByRuleIdOrderByEditedAtDesc(rule.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getEditedBy()).isEqualTo("admin");
        assertThat(history.get(0).getRuleVersion()).isEqualTo(1);
        assertThat(history.get(0).getSnapshot()).containsKey("name");
    }

    @Test
    void historyIsEmptyForNewRule() {
        String ruleId = UUID.randomUUID().toString();
        List<PolicyRuleHistory> history = historyRepo.findByRuleIdOrderByEditedAtDesc(ruleId);
        assertThat(history).isEmpty();
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
mvn -pl emcip-policy-engine test -Dtest=PolicyRuleHistoryTest -q | cat
```

Expected: FAIL — `PolicyRuleHistory` class not found.

- [ ] **Step 3: Create PolicyRuleHistory entity**

```java
// src/main/java/io/emcip/policy/engine/entity/PolicyRuleHistory.java
package io.emcip.policy.engine.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "policy_rule_history",
        indexes = {
            @Index(name = "idx_prh_rule_id", columnList = "rule_id"),
            @Index(name = "idx_prh_tenant", columnList = "tenant_id")
        })
@Data
public class PolicyRuleHistory {

    @Id
    private UUID id;

    @Column(name = "rule_id", nullable = false, length = 36)
    private String ruleId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> snapshot;

    @Column(name = "edited_by", length = 64)
    private String editedBy;

    @Column(name = "edited_at", nullable = false)
    private Instant editedAt;

    @Column(name = "rule_version", nullable = false)
    private Integer ruleVersion;
}
```

- [ ] **Step 4: Create PolicyRuleHistoryRepository**

```java
// src/main/java/io/emcip/policy/engine/repository/PolicyRuleHistoryRepository.java
package io.emcip.policy.engine.repository;

import io.emcip.policy.engine.entity.PolicyRuleHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PolicyRuleHistoryRepository extends JpaRepository<PolicyRuleHistory, UUID> {

    List<PolicyRuleHistory> findByRuleIdOrderByEditedAtDesc(String ruleId);
}
```

- [ ] **Step 5: Run integration test — expect green**

```bash
mvn -pl emcip-policy-engine test -Dtest=PolicyRuleHistoryTest -q | cat
```

Expected: `Tests run: 2, Failures: 0`

- [ ] **Step 6: Update PolicyRuleController (policy-engine)**

Replace `PolicyRuleController.java` with:

```java
package io.emcip.policy.engine.controller;

import io.emcip.policy.engine.entity.PolicyRuleConfig;
import io.emcip.policy.engine.entity.PolicyRuleHistory;
import io.emcip.policy.engine.repository.PolicyRuleConfigRepository;
import io.emcip.policy.engine.repository.PolicyRuleHistoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Tag(name = "Policy Rules", description = "Manage active policy rules and view rule history")
@RestController
@RequestMapping("/api/policy-rules")
@RequiredArgsConstructor
public class PolicyRuleController {

    private final PolicyRuleConfigRepository repository;
    private final PolicyRuleHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    @Operation(summary = "List active policy rules")
    @GetMapping
    public Flux<PolicyRuleConfig> listActive() {
        return Mono.fromCallable(repository::findByActiveTrueOrderByPriorityAsc)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .take(200);
    }

    @Operation(summary = "Create a new policy rule")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PolicyRuleConfig> create(@RequestBody PolicyRuleConfig rule) {
        if (rule.getTenantId() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId is required"));
        }
        rule.setId(UUID.randomUUID().toString());
        if (rule.getTargetIntent() == null || rule.getTargetIntent().isBlank()) rule.setTargetIntent("*");
        if (rule.getMinConfidence() == null) rule.setMinConfidence(0.0);
        if (rule.getPriority() == null) rule.setPriority(0);
        if (rule.getActive() == null) rule.setActive(true);
        if (rule.getRuleVersion() == null) rule.setRuleVersion(1);
        return Mono.fromCallable(() -> repository.save(rule))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Update an existing policy rule; writes a history snapshot first")
    @PutMapping("/{id}")
    public Mono<PolicyRuleConfig> update(
            @PathVariable String id,
            @RequestBody PolicyRuleConfig rule,
            @RequestHeader(value = "X-Edited-By", required = false) String editedBy) {
        return Mono.fromCallable(() -> {
                    PolicyRuleConfig existing = repository.findById(id)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

                    // Snapshot before overwrite
                    PolicyRuleHistory snap = new PolicyRuleHistory();
                    snap.setId(UUID.randomUUID());
                    snap.setRuleId(existing.getId());
                    snap.setTenantId(existing.getTenantId());
                    snap.setSnapshot(toMap(existing));
                    snap.setEditedBy(editedBy);
                    snap.setEditedAt(Instant.now());
                    snap.setRuleVersion(existing.getRuleVersion() != null ? existing.getRuleVersion() : 1);
                    historyRepository.save(snap);

                    // Apply updates
                    existing.setName(rule.getName());
                    if (rule.getTargetIntent() != null) existing.setTargetIntent(rule.getTargetIntent());
                    existing.setAction(rule.getAction());
                    existing.setPriority(rule.getPriority());
                    if (rule.getActive() != null) existing.setActive(rule.getActive());
                    if (rule.getMinConfidence() != null) existing.setMinConfidence(rule.getMinConfidence());
                    existing.setMaxConfidence(rule.getMaxConfidence());
                    existing.setDescription(rule.getDescription());
                    existing.setReason(rule.getReason());
                    existing.setEffectiveFrom(rule.getEffectiveFrom());
                    existing.setEffectiveTo(rule.getEffectiveTo());
                    existing.setConditions(rule.getConditions()); // persist OR-group conditions
                    existing.setRuleVersion((existing.getRuleVersion() != null ? existing.getRuleVersion() : 1) + 1);

                    return repository.save(existing);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Delete a policy rule (no history snapshot)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String id) {
        return Mono.fromRunnable(() -> repository.deleteById(id))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Operation(summary = "List version history snapshots for a rule")
    @GetMapping("/{id}/history")
    public Flux<PolicyRuleHistory> getHistory(@PathVariable String id) {
        return Mono.fromCallable(() -> historyRepository.findByRuleIdOrderByEditedAtDesc(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    private Map<String, Object> toMap(PolicyRuleConfig rule) {
        return objectMapper.convertValue(rule, new TypeReference<Map<String, Object>>() {});
    }
}
```

- [ ] **Step 7: Run all policy-engine tests**

```bash
mvn -pl emcip-policy-engine test -q | cat
```

Expected: all tests green, no compilation errors.

- [ ] **Step 8: Commit**

```bash
git add emcip-policy-engine/src/main/java/io/emcip/policy/engine/entity/PolicyRuleHistory.java \
        emcip-policy-engine/src/main/java/io/emcip/policy/engine/repository/PolicyRuleHistoryRepository.java \
        emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyRuleController.java \
        emcip-policy-engine/src/test/java/io/emcip/policy/engine/service/PolicyRuleHistoryTest.java
git commit -m "feat(6): add PolicyRuleHistory entity, repo, snapshot-on-PUT, GET /{id}/history endpoint"
```

---

**Continue in Part 2:** `docs/superpowers/plans/2026-06-22-policy-rule-versioning-plan-part2.md`
Tasks 5–8: dry-run endpoint, admin-api proxy, Admin UI frontend, full build + BACKLOG update.
