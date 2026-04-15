# ADR-003: Data Persistence with PostgreSQL

## Status

**Accepted**

Date: 2026-04-15

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

We will use **PostgreSQL 16** as the primary database with **R2DBC** for reactive connectivity.

### Key Design Choices

1. **PostgreSQL 16**: Latest stable with JSONB, improved performance
2. **R2DBC**: Reactive database connectivity (not JDBC)
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
- R2DBC enables non-blocking I/O
- Mature ecosystem (monitoring, backup tools)
- Cost-effective compared to managed NoSQL

### Negative
- Vertical scaling limits (vs. Cassandra/DynamoDB)
- Operational complexity (vs. managed databases)
- R2DBC less mature than JDBC ecosystem
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

R2DBC choice aligns with our reactive architecture decision. If JDBC ecosystem features become critical, we can bridge with `BlockHound` or use JDBC in non-critical paths.
