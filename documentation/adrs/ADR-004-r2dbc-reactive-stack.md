# ADR-004: R2DBC and Reactive Stack for Phase 4 Services

## Status

**Accepted**

Date: 2026-04-21

> **Relationship to ADR-003:** This ADR refines and partially supersedes ADR-003's
> "prefer JPA" guidance. ADR-003 remains authoritative for Phase 2/3 services.
> For Phase 4 services listed in this document, R2DBC is the approved persistence
> approach. See the amendment note at the bottom of ADR-003.

---

## Context

ADR-003 established JPA/Hibernate (JDBC) as the standard persistence approach after
a migration away from R2DBC during Phase 2. That decision was correct for the services
in scope at the time: `conversation-context`, `intent-classifier`, `policy-engine`, and
`llm-orchestrator`. Each of those services has a rich domain model that benefits from
JPA features such as complex queries, entity associations, optimistic locking, and
auditing support.

Phase 4 introduced three new services with different characteristics:

| Service                  | Primary workload |
|--------------------------|-----------------|
| `emcip-moderation-service` | Event-driven; consumes Kafka messages, applies rules, emits events |
| `emcip-audit-service`      | Write-heavy append log; no complex relational queries |
| `emcip-admin-api`          | Request/response CRUD; no deep object graphs |

These services are I/O-bound with simple data models. They do not require multi-entity
transactions, lazy-loaded associations, or JPQL query flexibility. Blocking I/O via
JDBC would introduce unnecessary thread contention given their workload profiles.

---

## Decision

**R2DBC with Spring Data R2DBC is the approved persistence layer for
`emcip-moderation-service`, `emcip-audit-service`, and `emcip-admin-api`.**

These services use Spring WebFlux for their HTTP layer and Spring Kafka with reactive
consumers where applicable. R2DBC is the natural fit: it keeps the entire request
pipeline non-blocking and avoids the dedicated thread-pool overhead that JPA with JDBC
would require under WebFlux.

### Scope of This Decision

This decision applies **only** to Phase 4 services. It does not change the approach
for any Phase 2 or Phase 3 service.

---

## Rationale

### Why R2DBC is appropriate for Phase 4 services

1. **Simple CRUD schemas.** `audit_events`, `metrics_snapshots`, `moderation_rules`,
   `group_profiles`, and `admin_users` are flat tables with no foreign-key traversal
   in hot paths. Spring Data R2DBC repositories handle these patterns without ceremony.

2. **Write-heavy audit service.** `emcip-audit-service` appends events at high
   throughput. Non-blocking inserts allow the event loop to accept new records
   concurrently without waiting for JDBC round-trips to complete.

3. **Event-driven moderation.** `emcip-moderation-service` operates entirely in a
   Kafka consumer loop. The rule cache is loaded reactively on startup and refreshed
   on a schedule; there is no synchronous HTTP path that would benefit from JPA's
   session management.

4. **Admin API request latency.** `emcip-admin-api` serves interactive admin requests
   where individual query latency matters. Non-blocking database access keeps threads
   free to handle concurrent requests during peak admin activity.

5. **Consistency with WebFlux.** All three services already use Spring WebFlux.
   Mixing blocking JDBC calls into a WebFlux application requires explicit
   `subscribeOn(boundedElastic())` wrappers throughout the codebase. R2DBC eliminates
   this boilerplate and the associated risk of accidentally blocking the event loop.

### Why JPA remains correct for Phase 2/3 services

The factors that made JPA the right choice in ADR-003 still apply:

- `conversation-context` manages `Chat`, `User`, and `Message` entities with foreign
  keys, JSONB metadata, and queries that benefit from JPQL and Hibernate's first-level
  cache.
- `intent-classifier` and `policy-engine` use JPA auditing (`@CreatedDate`,
  `@LastModifiedDate`) and optimistic locking (`@Version`).
- `llm-orchestrator` benefits from JPA's transaction management when coordinating
  LLM result persistence with cost tracking.

The R2DBC ecosystem maturity concerns cited in ADR-003 remain valid for complex
domain models. R2DBC's simpler feature set is a liability for those services and an
asset for the flat-schema Phase 4 services.

---

## Consequences

### Positive

- Non-blocking pipeline end-to-end for Phase 4 services.
- Reduced thread-pool sizing requirements for audit and moderation containers.
- Simpler service implementations: `ReactiveCrudRepository` methods map directly to
  the CRUD operations these services perform.
- No risk of accidentally blocking the WebFlux event loop with JDBC calls.

### Negative

- **Two persistence models in one codebase.** Developers must know which approach
  applies to which service. This document and ADR-003 together define the rule.
- **Liquibase requires JDBC.** All R2DBC services include `spring-boot-starter-jdbc`
  as a `runtime` dependency solely for Liquibase schema migrations. This is an
  accepted trade-off; JDBC is not used for application data access.
- **No JPA features.** Services on R2DBC cannot use `@ManyToOne`, JPQL,
  Hibernate-managed caches, or `@Version`-based optimistic locking. Schema design for
  these services must remain simple.

---

## Implementation Notes

- Liquibase is configured via `spring.liquibase.*` (JDBC URL) separately from the R2DBC
  connection (`spring.r2dbc.*`). Both sections are required in `application.yml`.
- `postgresql` (JDBC driver) and `spring-boot-starter-jdbc` are declared as `runtime`
  scope so they are present for Liquibase but not imported into application code.
- R2DBC driver: `org.postgresql:r2dbc-postgresql` (managed by the parent BOM).

---

## Alternatives Considered

| Alternative | Reason not chosen |
|-------------|------------------|
| JPA/Hibernate for all services | Blocking I/O in WebFlux event loop without explicit thread switching; unnecessary complexity for flat schemas. |
| Virtual threads (Project Loom) to unblock JDBC | Requires Java 21+ virtual thread executor configuration everywhere; R2DBC is simpler and already integrated. |
| Single R2DBC adoption across all services | Phase 2/3 services would lose JPA features (complex queries, associations, auditing) with no offsetting benefit. |

---

## References

- [ADR-003: Data Persistence with PostgreSQL](ADR-003-data-persistence.md) — JPA baseline and amendment history
- `emcip-admin-api/pom.xml` — R2DBC + Liquibase dependency configuration example
- `emcip-audit-service/src/main/resources/db/changelog/` — Audit schema (R2DBC service)
- `emcip-moderation-service/src/main/resources/db/changelog/` — Moderation schema (R2DBC service)

---

## Amendment to ADR-003

The "prefer JPA" guidance in ADR-003 is amended as follows:

> **JPA/Hibernate (JDBC) is the default** for services with complex domain models,
> entity associations, or non-trivial transactional requirements.
>
> **R2DBC is approved** for services that are predominantly I/O-bound with simple
> CRUD schemas and a non-blocking runtime (WebFlux + Kafka consumers). See ADR-004
> for the list of approved R2DBC services and the criteria for future classification.
