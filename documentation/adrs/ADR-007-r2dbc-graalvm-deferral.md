# ADR-007: Deferral of GraalVM Native Image Support for R2DBC Services

## Status

**Accepted**

Date: 2026-04-29

---

## Context

Phase 3 introduced GraalVM native image support for the JPA/Hibernate services:
`emcip-policy-engine`, `emcip-conversation-context`, and `emcip-llm-orchestrator`.
Native images provide sub-2-second startup times, lower memory footprint, and
eliminate JVM warm-up latency.

ADR-004 designated a set of future Phase 4 services (`moderation-service`,
`audit-service`) to use R2DBC with Project Reactor instead of JPA/Hibernate.
These services were intentionally excluded from the Phase 3 native image rollout.

---

## Decision

GraalVM native image support is **deferred** for all R2DBC-based services.

Services excluded from native image support at this time:

* `emcip-moderation-service` (R2DBC, not yet implemented)
* `emcip-audit-service` (R2DBC, not yet implemented)
* `emcip-admin-api` (JPA but Vaadin-based UI; native support deferred separately)

---

## Rationale

### 1. R2DBC + GraalVM Maturity

The R2DBC driver ecosystem (PostgreSQL, pool, SPI) and Project Reactor have
significantly less native image test coverage than the Hibernate/JPA stack.
`hibernate-graalvm` and Spring Boot's AOT processing provide tested, documented
paths for JPA services. No equivalent exists for R2DBC at this time.

### 2. Services Are Not Yet Implemented

`moderation-service` and `audit-service` are planned for Phase 4. Adding native image
support before the service logic exists would require maintaining hints against a
moving target — adding maintenance cost with no runtime benefit.

### 3. Hibernate Reflection Complexity Absorbed Once

The JPA services required a custom `RuntimeHintsRegistrar` that performs a full
`org/hibernate/**/*.class` classpath scan at AOT time. This pattern was designed
for Hibernate and does not translate to R2DBC. R2DBC services will need their own
hints strategy when the time comes.

### 4. Risk Profile

The Phase 3 native image work uncovered multiple Hibernate-specific issues
(JBoss Logging `_$logger` classes, strategy class instantiation, array type
reflection) that required non-trivial investigation. Taking on R2DBC-specific
unknowns in the same iteration would have extended scope significantly.

---

## Consequences

**Positive:**
* Phase 3 native image work ships with a clear, tested scope.
* R2DBC services can be addressed as a focused effort in Phase 4, once the services
  exist and the R2DBC native image ecosystem matures.
* No hints debt is incurred for services that do not yet exist.

**Negative:**
* R2DBC services will ship as JVM-based containers at their initial launch, missing
  the startup-time and memory benefits of native images.

---

## Future Work

When `moderation-service` and `audit-service` are implemented, native image support
should be evaluated. Key areas to investigate:

* `io.r2dbc.postgresql` and `io.r2dbc.pool` AOT hint requirements
* Reactor Core and Netty channel/codec reflection hints
* Spring Data R2DBC repository proxy generation under AOT

Consider contributing upstream native image hints to the R2DBC drivers rather than
maintaining project-local `RuntimeHintsRegistrar` implementations.

---

## Related ADRs

* ADR-003: Data Persistence Strategy (JPA/Hibernate baseline)
* ADR-004: R2DBC and Reactive Stack for Phase 4 Services
