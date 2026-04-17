# ADR-003: Data Persistence with PostgreSQL

## Status

**Accepted** (Amended 2026-04-17)

Date: 2026-04-15

**Amendment:** Migrated from R2DBC to JPA/Hibernate (JDBC) during Phase 2 implementation

## Context

EMCIP requires persistent storage for:
- Conversation context (threads, messages, users)
- Intent classification results
- Policy definitions and decisions
- Audit events and metrics
- Admin configurations

Requirements:
- ACID compliance for policy decisions
- JSON support for flexible schemas
- Reactive/non-blocking access
- Cost-effective operation

## Decision

We will use **PostgreSQL 16** as the primary database with **JPA/Hibernate (JDBC)** for data access.

### Key Design Choices

1. **PostgreSQL 16**: Latest stable with JSONB, improved performance
2. **JPA/Hibernate**: JPA 3.1 with Hibernate 6.x for reliable, feature-rich persistence
3. **Liquibase**: Schema migrations and versioning
4. **Separate Schemas per Service**: Logical separation within single database

### Database Schema Overview

| Service | Tables | Key Features |
|---------|--------|--------------|
| conversation-context | chats, users, messages | Foreign keys, JSONB for metadata |
| intent-classifier | intent_classifications | JSONB for intent details |
| policy-engine | policies, policy_decisions | JSONB for conditions/actions |
| audit-service | audit_events, metrics | JSONB for flexible audit data |
| admin-api | admin_users, group_profiles | JSONB for group settings |

### Migration Strategy

- **Liquibase** for version-controlled schema changes
- One changelog per service: `db/changelog/db.changelog-master.xml`
- Auto-run on startup in development
- Manual approval for production migrations

## Consequences

### Positive
- ACID compliance for critical operations
- JSONB for flexible, schema-evolving data
- JPA/Hibernate mature, well-documented, feature-rich
- Better integration with Spring Data (repositories, auditing)
- Easier testing and transaction management
- Mature ecosystem (monitoring, backup tools)
- Cost-effective compared to managed NoSQL

### Negative
- Vertical scaling limits (vs. Cassandra/DynamoDB)
- Operational complexity (vs. managed databases)
- Blocking I/O (vs R2DBC), but mitigated by connection pooling
- No built-in sharding (required for massive scale)

## Alternatives Considered

| Alternative | Pros | Cons | Decision |
|-------------|------|------|----------|
| MongoDB | Native JSON, horizontal scaling | No ACID transactions, eventual consistency | Rejected |
| Cassandra | Massive scale, write-heavy | Complex operations, no joins | Rejected |
| DynamoDB | Managed, auto-scaling | AWS lock-in, pricing complexity | Rejected |
| CockroachDB | Distributed, PostgreSQL-compatible | Higher complexity, newer | Future consideration |

## References

- [docker-compose.yml](../../docker-compose.yml) - PostgreSQL setup
- [US-1.3.4](../planning/phases/PHASE-1_USER_STORIES.md) - Liquibase migrations
- Liquibase changelogs in `*/src/main/resources/db/changelog/`

## Notes

**Migration Rationale (2026-04-17):**
During Phase 2 implementation, we migrated from R2DBC to JPA/Hibernate because:
1. R2DBC ecosystem is less mature (missing features, driver limitations)
2. JPA provides better integration with Spring Data (complex queries, auditing)
3. Testing is significantly easier with JPA/Hibernate
4. Transaction management is more straightforward
5. The reactive benefits of R2DBC were outweighed by development complexity

WebFlux with JPA (blocking) is an acceptable trade-off for our use case. Connection pooling (HikariCP) mitigates blocking concerns.
