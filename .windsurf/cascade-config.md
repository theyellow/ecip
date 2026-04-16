# Cascade Configuration for EMCIP Project

## Technology Stack (from ONBOARDING.md analysis)

### Build & Dependencies
- **Build Tool**: Maven 3.9+
- **Java Version**: 21 (LTS)
- **Spring Boot**: 4.x
- **Spring Framework**: Reactive (WebFlux, Reactor)

### Database & Persistence
- **Database**: PostgreSQL
- **Migration Tool**: **LIQUIBASE** (NOT Flyway!)
- **Connection**: R2DBC (reactive) for runtime, JDBC for migrations
- **JPA**: Spring Data JPA for entities and repositories

### Messaging
- **Message Broker**: Apache Kafka
- **Schema**: JSON with validation
- **Topics**:
  - `telegram.raw.messages` - Raw Telegram messages
  - `telegram.raw.updates` - Other Telegram updates
  - `messages.classified` - Intent classification results
  - `policies.decisions` - Policy engine decisions
  - `responses.generated` - LLM-generated responses

### Infrastructure
- **Container**: Docker + Docker Compose
- **Services**: PostgreSQL, Kafka, Zookeeper, Kafka UI, pgAdmin

## Module Structure

| Module | Port | Purpose |
|--------|------|---------|
| emcip-tdlib-adapter | 9080 | Telegram integration |
| emcip-conversation-context-service | 9081 | Thread tracking & persistence |
| emcip-intent-classifier | 9082 | Intent detection |
| emcip-policy-engine | 9083 | Policy rules |
| emcip-llm-orchestrator | 9084 | LLM routing |
| emcip-moderation-service | 9085 | Content moderation |
| emcip-audit-service | 9086 | Logging/metrics |
| emcip-admin-api | 9087 | Admin endpoints |

## Critical Rules

### 1. ALWAYS Use Liquibase (NEVER Flyway)
```
Location: src/main/resources/db/changelog/
Master file: db.changelog-master.xml
Format: XML or YAML (not SQL)
```

### 2. Code Style Requirements
```bash
# ALWAYS run before committing:
mvn spotless:apply

# Then verify:
mvn spotless:check checkstyle:check pmd:check
```

### 3. Java Type Safety
- Use explicit types, avoid `var` in lambdas
- Generic types must be explicit for Mono/Flux

### 4. Reactive Programming
- Use `Mono` and `Flux` from Reactor
- Non-blocking I/O throughout
- R2DBC for database (not JDBC)

### 5. Dependencies
```xml
<!-- emcip-core must be added for shared classes -->
<dependency>
    <groupId>io.emcip</groupId>
    <artifactId>emcip-core</artifactId>
</dependency>

<!-- Lombok for code generation -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <scope>provided</scope>
</dependency>
```

### 6. Lombok Usage (REQUIRED)
- **MUST** use `@Slf4j` for logging (never use `LoggerFactory` directly)
- **MUST** use `@Getter`, `@Setter` for entity classes
- **MUST** use `@RequiredArgsConstructor` for constructor injection
- **MUST** use `@Builder` for complex object creation
- **NEVER** write manual getters/setters/equals/hashCode in entities

### 7. Logging Standards (REQUIRED)
- Always use `log.info()`, `log.debug()`, `log.error()`, `log.warn()`
- Log every important business event:
  * Event received/sent to Kafka
  * Database operations (create, update, delete)
  * Authentication actions (login, logout, failures)
  * Policy decisions (allow, block, escalate)
  * Classification results (intent, confidence)
  * Errors with full context (stack traces in debug)
- Use parameterized logging: `log.info("User {} logged in", userId)`
- Include correlation IDs in all logs

## Phase 2 User Stories Status

### Epic 2.1: TDLib Adapter ✅ COMPLETE
- US-2.1.1: TDLib integration with stubs
- US-2.1.2: Login flow REST endpoints
- US-2.1.3: Kafka producer for Telegram events

### Epic 2.2: Event Backbone ✅ COMPLETE
- US-2.2.1: Event schemas (EventSchemas.java)
- US-2.2.2: Schema validation (EventValidator.java)
- US-2.2.3: Kafka consumers for all services

### Epic 2.3: PostgreSQL Persistence 🔄 IN PROGRESS
- US-2.3.1: JPA entities (User, MessageThread, Message) ✅
- US-2.3.2: Repository layer ✅
- US-2.3.3: **LIQUIBASE MIGRATIONS** ⏳ (fixing now)

### Epic 2.4: Conversation & Intent ⏳ PENDING
- US-2.4.1: Thread tracking
- US-2.4.2: Rule-based classifier (partially done)
- US-2.4.3: Event backbone integration

## Current Task

Fixing US-2.3.2/2.3.3: Replace Flyway with Liquibase migrations

### Files to Create:
1. `db/changelog/db.changelog-master.xml` - Master changelog
2. `db/changelog/changes/001-create-users-table.xml`
3. `db/changelog/changes/002-create-threads-table.xml`
4. `db/changelog/changes/003-create-messages-table.xml`

### Files to Delete:
- ❌ `db/migration/V1__initial_schema.sql` (Flyway - wrong!)

## Common Commands

```bash
# Build everything
mvn clean install -DskipTests

# Run single module
cd emcip-conversation-context-service
mvn spring-boot:run

# Database reset
docker-compose down -v
docker-compose up -d postgres

# Check migrations
mvn liquibase:status
mvn liquibase:update
```

## Resources
- [Liquibase Docs](https://docs.liquibase.com/)
- [R2DBC PostgreSQL](https://github.com/pgjdbc/r2dbc-postgresql)
