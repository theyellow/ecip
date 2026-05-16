# OpenAPI / Swagger — US-4.3.4

**Date:** 2026-05-15
**Status:** Approved

---

## Goal

Expose interactive API documentation at `/swagger-ui.html` on every backend REST service. Each service is independent — no aggregation. The admin-api already has springdoc but on an outdated version; it needs upgrading alongside the six new integrations.

---

## Scope

Services in scope (REST backends only — tdlib-adapter and admin-ui excluded):

| Service | Port | Stack | springdoc artifact |
|---|---|---|---|
| `emcip-conversation-context` | 9081 | WebFlux | `springdoc-openapi-starter-webflux-ui:3.0.2` |
| `emcip-intent-classifier` | 9082 | WebFlux | `springdoc-openapi-starter-webflux-ui:3.0.2` |
| `emcip-policy-engine` | 9083 | WebFlux | `springdoc-openapi-starter-webflux-ui:3.0.2` |
| `emcip-llm-orchestrator` | 9084 | WebMVC | `springdoc-openapi-starter-webmvc-ui:3.0.3` |
| `emcip-moderation-service` | 9085 | WebFlux | `springdoc-openapi-starter-webflux-ui:3.0.2` |
| `emcip-audit-service` | 9086 | WebFlux | `springdoc-openapi-starter-webflux-ui:3.0.2` |
| `emcip-admin-api` | 9087 | WebFlux | upgrade `2.8.6` → `3.0.2` |

---

## Dependency Management

Both starters are added to the **parent POM** `<dependencyManagement>` block so individual services declare only `<groupId>` and `<artifactId>` — no version pinning per service.

```xml
<!-- parent pom.xml — dependencyManagement -->
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

Each service POM adds only the relevant starter (no `<version>` tag).

---

## Per-Service Changes

### 1. Configuration class

Each service gets one new class in its `config` package:

```java
@OpenAPIDefinition(
    info = @Info(
        title = "<Service Name> API",
        description = "<One-line description>",
        version = "1.0"
    )
)
@Configuration
public class OpenApiConfig {}
```

Titles and descriptions are service-specific (see implementation plan).

### 2. application.yml

```yaml
springdoc:
  swagger-ui:
    path: /swagger-ui.html
  api-docs:
    path: /api-docs
```

### 3. Controller annotations

All existing controllers get:
- `@Tag(name = "...", description = "...")` at class level
- `@Operation(summary = "...")` on each handler method

No `@Schema` or request/response body documentation in this phase — the auto-generated schema from existing types is sufficient.

---

## What Is Not In Scope

- Aggregated/federated Swagger UI
- `@Schema` annotations on DTOs/entities
- Security scheme documentation (bearer token, etc.)
- Custom Swagger UI theming

---

## Verification

After implementation, each service must respond:

```
GET http://localhost:<port>/swagger-ui.html   → 200 (HTML)
GET http://localhost:<port>/api-docs          → 200 (JSON, valid OpenAPI 3.x)
```
