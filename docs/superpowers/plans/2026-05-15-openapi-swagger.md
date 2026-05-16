# OpenAPI / Swagger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose `/swagger-ui.html` and `/api-docs` on all 7 backend REST services (6 new integrations + admin-api upgrade from 2.8.6 → 3.0.2).

**Architecture:** Each service is independent — no aggregation. springdoc-openapi is added via parent POM `<dependencyManagement>` so individual services declare no version. Each service gets an `OpenApiConfig` class plus `@Tag`/`@Operation` annotations on existing controllers.

**Tech Stack:** `springdoc-openapi-starter-webflux-ui:3.0.2` (WebFlux services), `springdoc-openapi-starter-webmvc-ui:3.0.3` (llm-orchestrator), Java annotation API from `io.swagger.v3.oas.annotations`.

---

## File Map

| Action | File |
|--------|------|
| Modify | `pom.xml` (parent) |
| Create | `emcip-conversation-context/src/main/java/io/emcip/conversation/context/config/OpenApiConfig.java` |
| Modify | `emcip-conversation-context/src/main/resources/application.yml` |
| Modify | `emcip-conversation-context/pom.xml` |
| Create | `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/config/OpenApiConfig.java` |
| Modify | `emcip-intent-classifier/src/main/resources/application.yml` |
| Modify | `emcip-intent-classifier/pom.xml` |
| Create | `emcip-policy-engine/src/main/java/io/emcip/policy/engine/config/OpenApiConfig.java` |
| Modify | `emcip-policy-engine/src/main/resources/application.yml` |
| Modify | `emcip-policy-engine/pom.xml` |
| Modify | `emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyDecisionController.java` |
| Modify | `emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyRuleController.java` |
| Create | `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/OpenApiConfig.java` |
| Modify | `emcip-llm-orchestrator/src/main/resources/application.yml` |
| Modify | `emcip-llm-orchestrator/pom.xml` |
| Modify | `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java` |
| Create | `emcip-moderation-service/src/main/java/io/emcip/moderation/service/config/OpenApiConfig.java` |
| Modify | `emcip-moderation-service/src/main/resources/application.yml` |
| Modify | `emcip-moderation-service/pom.xml` |
| Modify | `emcip-moderation-service/src/main/java/io/emcip/moderation/service/controller/ModerationRuleController.java` |
| Create | `emcip-audit-service/src/main/java/io/emcip/audit/service/config/OpenApiConfig.java` |
| Modify | `emcip-audit-service/src/main/resources/application.yml` |
| Modify | `emcip-audit-service/pom.xml` |
| Modify | `emcip-audit-service/src/main/java/io/emcip/audit/service/controller/AuditController.java` |
| Create | `emcip-admin-api/src/main/java/io/emcip/admin/api/config/OpenApiConfig.java` |
| Modify | `emcip-admin-api/pom.xml` (version bump only) |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AuditController.java` |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/FlagController.java` |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ModerationRuleController.java` |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/PolicyRuleController.java` |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java` |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/SimulateController.java` |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/GroupProfileController.java` |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TenantController.java` |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java` |
| Modify | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AuthController.java` |

---

## Task 1: Parent POM — add springdoc to dependencyManagement

**Files:**
- Modify: `pom.xml` (root)

- [ ] **Step 1: Add both springdoc starters to `<dependencyManagement>`**

Open `pom.xml` and locate the `<dependencyManagement><dependencies>` block. Add the following two entries:

```xml
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
  <version>3.0.2</version>
</dependency>
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
  <version>3.0.3</version>
</dependency>
```

- [ ] **Step 2: Verify the parent POM compiles**

```bash
mvn validate -N | cat
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add springdoc-openapi 3.0.x to parent dependencyManagement"
```

---

## Task 2: conversation-context — add springdoc (Kafka-only service, no controllers)

**Files:**
- Modify: `emcip-conversation-context/pom.xml`
- Create: `emcip-conversation-context/src/main/java/io/emcip/conversation/context/config/OpenApiConfig.java`
- Modify: `emcip-conversation-context/src/main/resources/application.yml`

- [ ] **Step 1: Add springdoc dependency to service pom.xml**

In `emcip-conversation-context/pom.xml`, add inside `<dependencies>`:

```xml
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
</dependency>
```

- [ ] **Step 2: Create OpenApiConfig**

Create `emcip-conversation-context/src/main/java/io/emcip/conversation/context/config/OpenApiConfig.java`:

```java
package io.emcip.conversation.context.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
    info =
        @Info(
            title = "Conversation Context API",
            description = "Thread tracking, speaker roles, and conversation history",
            version = "1.0"))
@Configuration
public class OpenApiConfig {}
```

- [ ] **Step 3: Add springdoc config to application.yml**

Append to `emcip-conversation-context/src/main/resources/application.yml`:

```yaml
springdoc:
  swagger-ui:
    path: /swagger-ui.html
  api-docs:
    path: /api-docs
```

- [ ] **Step 4: Apply Spotless and run tests**

```bash
mvn spotless:apply -pl emcip-conversation-context | cat
mvn test -pl emcip-conversation-context | cat
```

Expected: `BUILD SUCCESS`, all existing tests pass. (Integration tests annotated `@EnableIfDockerAvailable` are skipped if Docker is unavailable — that is expected.)

- [ ] **Step 5: Commit**

```bash
git add emcip-conversation-context/
git commit -m "feat(openapi): add springdoc to conversation-context service"
```

---

## Task 3: intent-classifier — add springdoc (Kafka-only service, no controllers)

**Files:**
- Modify: `emcip-intent-classifier/pom.xml`
- Create: `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/config/OpenApiConfig.java`
- Modify: `emcip-intent-classifier/src/main/resources/application.yml`

- [ ] **Step 1: Add springdoc dependency**

In `emcip-intent-classifier/pom.xml`, add inside `<dependencies>`:

```xml
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
</dependency>
```

- [ ] **Step 2: Create OpenApiConfig**

Create `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/config/OpenApiConfig.java`:

```java
package io.emcip.intent.classifier.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
    info =
        @Info(
            title = "Intent Classifier API",
            description = "Rule-based intent classification for Telegram messages",
            version = "1.0"))
@Configuration
public class OpenApiConfig {}
```

- [ ] **Step 3: Add springdoc config to application.yml**

Append to `emcip-intent-classifier/src/main/resources/application.yml`:

```yaml
springdoc:
  swagger-ui:
    path: /swagger-ui.html
  api-docs:
    path: /api-docs
```

- [ ] **Step 4: Apply Spotless and run tests**

```bash
mvn spotless:apply -pl emcip-intent-classifier | cat
mvn test -pl emcip-intent-classifier | cat
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add emcip-intent-classifier/
git commit -m "feat(openapi): add springdoc to intent-classifier service"
```

---

## Task 4: policy-engine — add springdoc + annotate 2 controllers

**Files:**
- Modify: `emcip-policy-engine/pom.xml`
- Create: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/config/OpenApiConfig.java`
- Modify: `emcip-policy-engine/src/main/resources/application.yml`
- Modify: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyDecisionController.java`
- Modify: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyRuleController.java`

- [ ] **Step 1: Add springdoc dependency**

In `emcip-policy-engine/pom.xml`, add inside `<dependencies>`:

```xml
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
</dependency>
```

- [ ] **Step 2: Create OpenApiConfig**

Create `emcip-policy-engine/src/main/java/io/emcip/policy/engine/config/OpenApiConfig.java`:

```java
package io.emcip.policy.engine.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
    info =
        @Info(
            title = "Policy Engine API",
            description = "Deterministic policy evaluation and rule management",
            version = "1.0"))
@Configuration
public class OpenApiConfig {}
```

- [ ] **Step 3: Add springdoc config to application.yml**

Append to `emcip-policy-engine/src/main/resources/application.yml`:

```yaml
springdoc:
  swagger-ui:
    path: /swagger-ui.html
  api-docs:
    path: /api-docs
```

- [ ] **Step 4: Annotate PolicyDecisionController**

Open `emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyDecisionController.java`.

Add imports (after existing imports):

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

Add `@Tag` to the class declaration:

```java
@Tag(name = "Policy Decisions", description = "Query and update policy decision records")
@RestController
// (keep existing annotations)
```

Add `@Operation` to each handler method:

```java
@Operation(summary = "List recent policy decisions")
@GetMapping("/api/policy-decisions")
public /* existing signature */ list(...) { ... }

@Operation(summary = "Update decision signal status")
@PutMapping("/api/policy-decisions/{id}")
public /* existing signature */ updateStatus(...) { ... }
```

- [ ] **Step 5: Annotate PolicyRuleController**

Open `emcip-policy-engine/src/main/java/io/emcip/policy/engine/controller/PolicyRuleController.java`.

Add imports:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

Add `@Tag` to class:

```java
@Tag(name = "Policy Rules", description = "Manage active policy rules and view rule history")
@RestController
```

Add `@Operation` to each method:

```java
@Operation(summary = "List active policy rules")
@GetMapping("/api/policy-rules")
...

@Operation(summary = "Create a new policy rule")
@PostMapping("/api/policy-rules")
...

@Operation(summary = "Update an existing policy rule")
@PutMapping("/api/policy-rules/{id}")
...

@Operation(summary = "Delete a policy rule")
@DeleteMapping("/api/policy-rules/{id}")
...

@Operation(summary = "List version history for a rule by name")
@GetMapping("/api/policy-rules/history/{name}")
...
```

- [ ] **Step 6: Apply Spotless and run tests**

```bash
mvn spotless:apply -pl emcip-policy-engine | cat
mvn test -pl emcip-policy-engine | cat
```

Expected: `BUILD SUCCESS`. Controller tests (`PolicyDecisionControllerTest`, `PolicyRuleControllerTest`) pass — annotations are additive and do not affect behavior.

- [ ] **Step 7: Commit**

```bash
git add emcip-policy-engine/
git commit -m "feat(openapi): add springdoc to policy-engine service"
```

---

## Task 5: llm-orchestrator — add springdoc (WebMVC) + annotate 1 controller

**Files:**
- Modify: `emcip-llm-orchestrator/pom.xml`
- Create: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/OpenApiConfig.java`
- Modify: `emcip-llm-orchestrator/src/main/resources/application.yml`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java`

> **Note:** llm-orchestrator uses `spring-boot-starter-web` (blocking WebMVC), not WebFlux. Use the `webmvc-ui` starter.

- [ ] **Step 1: Add springdoc dependency**

In `emcip-llm-orchestrator/pom.xml`, add inside `<dependencies>`:

```xml
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
</dependency>
```

- [ ] **Step 2: Create OpenApiConfig**

Create `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/OpenApiConfig.java`:

```java
package io.emcip.llm.orchestrator.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
    info =
        @Info(
            title = "LLM Orchestrator API",
            description = "Model configuration, prompt templates, and cost tracking",
            version = "1.0"))
@Configuration
public class OpenApiConfig {}
```

- [ ] **Step 3: Add springdoc config to application.yml**

Append to `emcip-llm-orchestrator/src/main/resources/application.yml`:

```yaml
springdoc:
  swagger-ui:
    path: /swagger-ui.html
  api-docs:
    path: /api-docs
```

- [ ] **Step 4: Annotate OrchestratorController**

Open `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/controller/OrchestratorController.java`.

Add imports:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

Add `@Tag` to class:

```java
@Tag(name = "LLM Orchestrator", description = "Manage AI models, prompt templates, and query cost summaries")
@RestController
```

Add `@Operation` to each method:

```java
@Operation(summary = "List all model configurations")
@GetMapping("/api/models")
...

@Operation(summary = "Create a new model configuration")
@PostMapping("/api/models")
...

@Operation(summary = "Update an existing model configuration")
@PutMapping("/api/models/{id}")
...

@Operation(summary = "Delete a model configuration")
@DeleteMapping("/api/models/{id}")
...

@Operation(summary = "List all prompt templates")
@GetMapping("/api/templates")
...

@Operation(summary = "Create a new prompt template")
@PostMapping("/api/templates")
...

@Operation(summary = "Update an existing prompt template")
@PutMapping("/api/templates/{id}")
...

@Operation(summary = "Delete a prompt template")
@DeleteMapping("/api/templates/{id}")
...

@Operation(summary = "Get LLM cost summary for a time range")
@GetMapping("/api/costs/summary")
...
```

- [ ] **Step 5: Apply Spotless and run tests**

```bash
mvn spotless:apply -pl emcip-llm-orchestrator | cat
mvn test -pl emcip-llm-orchestrator | cat
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add emcip-llm-orchestrator/
git commit -m "feat(openapi): add springdoc to llm-orchestrator service"
```

---

## Task 6: moderation-service — add springdoc + annotate 1 controller

**Files:**
- Modify: `emcip-moderation-service/pom.xml`
- Create: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/config/OpenApiConfig.java`
- Modify: `emcip-moderation-service/src/main/resources/application.yml`
- Modify: `emcip-moderation-service/src/main/java/io/emcip/moderation/service/controller/ModerationRuleController.java`

- [ ] **Step 1: Add springdoc dependency**

In `emcip-moderation-service/pom.xml`, add inside `<dependencies>`:

```xml
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
</dependency>
```

- [ ] **Step 2: Create OpenApiConfig**

Create `emcip-moderation-service/src/main/java/io/emcip/moderation/service/config/OpenApiConfig.java`:

```java
package io.emcip.moderation.service.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
    info =
        @Info(
            title = "Moderation Service API",
            description = "Moderation rule management and toxicity filtering",
            version = "1.0"))
@Configuration
public class OpenApiConfig {}
```

- [ ] **Step 3: Add springdoc config to application.yml**

Append to `emcip-moderation-service/src/main/resources/application.yml`:

```yaml
springdoc:
  swagger-ui:
    path: /swagger-ui.html
  api-docs:
    path: /api-docs
```

- [ ] **Step 4: Annotate ModerationRuleController**

Open `emcip-moderation-service/src/main/java/io/emcip/moderation/service/controller/ModerationRuleController.java`.

Add imports:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

Add `@Tag` to class:

```java
@Tag(name = "Moderation Rules", description = "Create, read, update, and delete moderation rules")
@RestController
```

Add `@Operation` to each method:

```java
@Operation(summary = "List all moderation rules")
@GetMapping("/api/moderation-rules")
...

@Operation(summary = "Create a new moderation rule")
@PostMapping("/api/moderation-rules")
...

@Operation(summary = "Update an existing moderation rule")
@PutMapping("/api/moderation-rules/{id}")
...

@Operation(summary = "Delete a moderation rule")
@DeleteMapping("/api/moderation-rules/{id}")
...
```

- [ ] **Step 5: Apply Spotless and run tests**

```bash
mvn spotless:apply -pl emcip-moderation-service | cat
mvn test -pl emcip-moderation-service | cat
```

Expected: `BUILD SUCCESS`. `ModerationRuleControllerTest` passes — it uses `WebTestClient.bindToController()` which is unaffected by springdoc annotations.

- [ ] **Step 6: Commit**

```bash
git add emcip-moderation-service/
git commit -m "feat(openapi): add springdoc to moderation-service"
```

---

## Task 7: audit-service — add springdoc + annotate 1 controller

**Files:**
- Modify: `emcip-audit-service/pom.xml`
- Create: `emcip-audit-service/src/main/java/io/emcip/audit/service/config/OpenApiConfig.java`
- Modify: `emcip-audit-service/src/main/resources/application.yml`
- Modify: `emcip-audit-service/src/main/java/io/emcip/audit/service/controller/AuditController.java`

- [ ] **Step 1: Add springdoc dependency**

In `emcip-audit-service/pom.xml`, add inside `<dependencies>`:

```xml
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
</dependency>
```

- [ ] **Step 2: Create OpenApiConfig**

Create `emcip-audit-service/src/main/java/io/emcip/audit/service/config/OpenApiConfig.java`:

```java
package io.emcip.audit.service.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
    info =
        @Info(
            title = "Audit Service API",
            description = "Audit event querying and pipeline metrics",
            version = "1.0"))
@Configuration
public class OpenApiConfig {}
```

- [ ] **Step 3: Add springdoc config to application.yml**

Append to `emcip-audit-service/src/main/resources/application.yml`:

```yaml
springdoc:
  swagger-ui:
    path: /swagger-ui.html
  api-docs:
    path: /api-docs
```

- [ ] **Step 4: Annotate AuditController**

Open `emcip-audit-service/src/main/java/io/emcip/audit/service/controller/AuditController.java`.

Add imports:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

Add `@Tag` to class:

```java
@Tag(name = "Audit Events", description = "Query audit events and pipeline summaries")
@RestController
```

Add `@Operation` to each method:

```java
@Operation(summary = "List audit events filtered by type and time range")
@GetMapping("/api/audit/events")
...

@Operation(summary = "Get a single audit event by event ID")
@GetMapping("/api/audit/events/{eventId}")
...

@Operation(summary = "Get event-type counts for a time range")
@GetMapping("/api/audit/summary")
...
```

- [ ] **Step 5: Apply Spotless and run tests**

```bash
mvn spotless:apply -pl emcip-audit-service | cat
mvn test -pl emcip-audit-service | cat
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add emcip-audit-service/
git commit -m "feat(openapi): add springdoc to audit-service"
```

---

## Task 8: admin-api — upgrade springdoc 2.8.6 → 3.0.2 + add OpenApiConfig + annotate all controllers

**Files:**
- Modify: `emcip-admin-api/pom.xml` (version bump — remove hardcoded version, let parent manage it)
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/config/OpenApiConfig.java`
- Modify: all 10 controllers in `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/`

> **Note:** `application.yml` already has the springdoc block — no change needed there.

- [ ] **Step 1: Upgrade springdoc version in admin-api pom.xml**

Open `emcip-admin-api/pom.xml`. Find the existing springdoc dependency:

```xml
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
  <version>2.8.6</version>
</dependency>
```

Remove the `<version>` tag so it inherits from parent `<dependencyManagement>`:

```xml
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
</dependency>
```

- [ ] **Step 2: Create OpenApiConfig**

Create `emcip-admin-api/src/main/java/io/emcip/admin/api/config/OpenApiConfig.java`:

```java
package io.emcip.admin.api.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
    info =
        @Info(
            title = "EMCIP Admin API",
            description =
                "Administrative endpoints for rules, tenants, group profiles, and Telegram accounts",
            version = "1.0"))
@Configuration
public class OpenApiConfig {}
```

- [ ] **Step 3: Annotate AuditController**

Open `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AuditController.java`.

Add imports:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

Add to class and method:

```java
@Tag(name = "Audit", description = "Read audit events from the audit service")
@RestController
...

@Operation(summary = "List recent audit events, optionally filtered by type")
@GetMapping("/api/audit/events")
...
```

- [ ] **Step 4: Annotate FlagController**

Open `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/FlagController.java`.

Add imports:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

```java
@Tag(name = "Flags", description = "View and action moderation flags from the policy engine")
@RestController
...

@Operation(summary = "List recent policy flags")
@GetMapping("/api/flags")
...

@Operation(summary = "Update the status of a policy flag")
@PatchMapping("/api/flags/{id}/status")
...
```

- [ ] **Step 5: Annotate ModerationRuleController**

Open `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/ModerationRuleController.java`.

Add imports:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

```java
@Tag(name = "Moderation Rules", description = "Proxy to moderation-service rule management")
@RestController
...

@Operation(summary = "List all moderation rules")
@GetMapping("/api/moderation-rules")
...

@Operation(summary = "Create a moderation rule")
@PostMapping("/api/moderation-rules")
...

@Operation(summary = "Update a moderation rule")
@PutMapping("/api/moderation-rules/{id}")
...

@Operation(summary = "Delete a moderation rule")
@DeleteMapping("/api/moderation-rules/{id}")
...
```

- [ ] **Step 6: Annotate PolicyRuleController**

Open `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/PolicyRuleController.java`.

Add imports:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

```java
@Tag(name = "Policy Rules", description = "Proxy to policy-engine rule management")
@RestController
...

@Operation(summary = "List active policy rules")
@GetMapping("/api/policy-rules")
...

@Operation(summary = "Create a policy rule")
@PostMapping("/api/policy-rules")
...

@Operation(summary = "Update a policy rule")
@PutMapping("/api/policy-rules/{id}")
...

@Operation(summary = "Delete a policy rule")
@DeleteMapping("/api/policy-rules/{id}")
...
```

- [ ] **Step 7: Annotate TelegramAccountController**

Open `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java`.

Add imports:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

```java
@Tag(name = "Telegram Accounts", description = "Manage Telegram account connections, authentication, and group watching")
@RestController
...

@Operation(summary = "List all Telegram accounts")
@GetMapping("/api/telegram/accounts")
...

@Operation(summary = "Create and connect a new Telegram account")
@PostMapping("/api/telegram/accounts")
...

@Operation(summary = "Delete a Telegram account")
@DeleteMapping("/api/telegram/accounts/{id}")
...

@Operation(summary = "Get connection status of a Telegram account")
@GetMapping("/api/telegram/accounts/{id}/status")
...

@Operation(summary = "Reconnect a disconnected Telegram account")
@PostMapping("/api/telegram/accounts/{id}/reconnect")
...

@Operation(summary = "Submit authentication code for a Telegram account")
@PostMapping("/api/telegram/accounts/{id}/code")
...

@Operation(summary = "Submit 2FA password for a Telegram account")
@PostMapping("/api/telegram/accounts/{id}/password")
...

@Operation(summary = "Log out a Telegram account")
@PostMapping("/api/telegram/accounts/{id}/logout")
...

@Operation(summary = "Sync watched groups across all accounts")
@PostMapping("/api/telegram/accounts/sync")
...

@Operation(summary = "Discover available Telegram chats for an account")
@GetMapping("/api/telegram/accounts/{id}/chats")
...

@Operation(summary = "List watched groups for an account")
@GetMapping("/api/telegram/accounts/{id}/watched")
...

@Operation(summary = "Start watching a Telegram group")
@PostMapping("/api/telegram/accounts/{id}/watch")
...

@Operation(summary = "Stop watching a Telegram group")
@DeleteMapping("/api/telegram/accounts/{id}/watch/{chatId}")
...
```

- [ ] **Step 8: Annotate SimulateController**

Open `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/SimulateController.java`.

Add imports:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

```java
@Tag(name = "Simulation", description = "Inject test messages through the full pipeline")
@RestController
...

@Operation(summary = "Simulate a Telegram message through the processing pipeline")
@PostMapping("/api/simulate/message")
...
```

- [ ] **Step 9: Annotate GroupProfileController**

Open `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/GroupProfileController.java`.

Add imports:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

```java
@Tag(name = "Group Profiles", description = "Manage Telegram group configuration profiles")
@RestController
...

@Operation(summary = "List all group profiles")
@GetMapping("/api/groups")
...

@Operation(summary = "Get a group profile by chat ID")
@GetMapping("/api/groups/{chatId}")
...

@Operation(summary = "Create a group profile")
@PostMapping("/api/groups")
...

@Operation(summary = "Update a group profile")
@PutMapping("/api/groups/{chatId}")
...

@Operation(summary = "Delete a group profile")
@DeleteMapping("/api/groups/{chatId}")
...
```

- [ ] **Step 10: Annotate TenantController**

Open `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TenantController.java`.

Add imports:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

```java
@Tag(name = "Tenants", description = "Manage EMCIP tenants")
@RestController
...

@Operation(summary = "List all tenants")
@GetMapping("/api/tenants")
...

@Operation(summary = "Create a new tenant")
@PostMapping("/api/tenants")
...

@Operation(summary = "Delete a tenant")
@DeleteMapping("/api/tenants/{id}")
...
```

- [ ] **Step 11: Annotate AIProxyController**

Open `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AIProxyController.java`.

Add imports:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

```java
@Tag(name = "AI Proxy", description = "Proxy to llm-orchestrator model and template management")
@RestController
...

@Operation(summary = "List AI model configurations")
@GetMapping("/api/ai/models")
...

@Operation(summary = "Create an AI model configuration")
@PostMapping("/api/ai/models")
...

@Operation(summary = "Update an AI model configuration")
@PutMapping("/api/ai/models/{id}")
...

@Operation(summary = "Delete an AI model configuration")
@DeleteMapping("/api/ai/models/{id}")
...

@Operation(summary = "List prompt templates")
@GetMapping("/api/ai/templates")
...

@Operation(summary = "Create a prompt template")
@PostMapping("/api/ai/templates")
...

@Operation(summary = "Update a prompt template")
@PutMapping("/api/ai/templates/{id}")
...

@Operation(summary = "Delete a prompt template")
@DeleteMapping("/api/ai/templates/{id}")
...
```

- [ ] **Step 12: Annotate AuthController**

Open `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/AuthController.java`.

Add imports:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

```java
@Tag(name = "Authentication", description = "Obtain JWT tokens for API access")
@RestController
...

@Operation(summary = "Authenticate and receive a JWT token")
@PostMapping("/api/auth/token")
...
```

- [ ] **Step 13: Apply Spotless and run tests**

```bash
mvn spotless:apply -pl emcip-admin-api | cat
mvn test -pl emcip-admin-api | cat
```

Expected: `BUILD SUCCESS`, all existing controller tests pass.

- [ ] **Step 14: Commit**

```bash
git add emcip-admin-api/
git commit -m "feat(openapi): upgrade admin-api springdoc to 3.0.2 and annotate all controllers"
```

---

## Task 9: Smoke-test all services + update backlog

**Files:**
- Modify: `docs/superpowers/BACKLOG.md`

- [ ] **Step 1: Start all services**

```bash
docker compose up -d | cat
```

Wait ~30 seconds for services to become healthy.

- [ ] **Step 2: Verify /api-docs on each service**

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:9081/api-docs | cat
curl -s -o /dev/null -w "%{http_code}" http://localhost:9082/api-docs | cat
curl -s -o /dev/null -w "%{http_code}" http://localhost:9083/api-docs | cat
curl -s -o /dev/null -w "%{http_code}" http://localhost:9084/api-docs | cat
curl -s -o /dev/null -w "%{http_code}" http://localhost:9085/api-docs | cat
curl -s -o /dev/null -w "%{http_code}" http://localhost:9086/api-docs | cat
curl -s -o /dev/null -w "%{http_code}" http://localhost:9087/api-docs | cat
```

Expected: all return `200`

- [ ] **Step 3: Spot-check the admin-api spec includes all tags**

```bash
curl -s http://localhost:9087/api-docs | python3 -c "import sys,json; d=json.load(sys.stdin); print([t['name'] for t in d.get('tags',[])])" | cat
```

Expected output includes: `Authentication`, `Audit`, `Flags`, `Moderation Rules`, `Policy Rules`, `Telegram Accounts`, `Simulation`, `Group Profiles`, `Tenants`, `AI Proxy`

- [ ] **Step 4: Remove US-4.3.4 from BACKLOG.md**

In `docs/superpowers/BACKLOG.md`, remove the entire row for `US-4.3.4 — OpenAPI / Swagger` from the "Now — Phase 4 Completion" table.

- [ ] **Step 5: Final commit**

```bash
git add docs/
git commit -m "docs: mark US-4.3.4 complete, remove from backlog"
```
