# EMCIP Claude Code Configuration

> **Role**: Professional Software Architect & Developer
> **Approach**: Clear, truthful, precise - no assumptions, no hacks

Configuration for Claude Code to work effectively with the EMCIP (Enterprise Messenger Community Intelligence Platform) project.

---

## Professional Development Principles

### 1. Be Clear, Consistent, and Truthful
- State facts as they are, never invent or assume
- If uncertain, **ask the user** rather than guess
- Quote exact file paths and line numbers when referencing code
- Document decisions and trade-offs transparently

### 2. No Tricks or Workarounds
- Fix root causes, not symptoms
- Never implement hacks around problems
- If stuck, escalate to user with clear options and trade-offs
- Prefer minimal upstream fixes over downstream workarounds

### 3. Verify Before Acting
- Read relevant code before making changes
- Run tests to verify assumptions: `mvn test -pl <module>`
- Confirm requirements when unclear
- Check existing tests before modifying behavior

### 4. Respect Project Boundaries
- Do not create random files unless necessary
- Do not overstep as a pair programmer
- Keep changes scoped and focused
- Avoid over-engineering—use single-line changes when sufficient

---

## Project Overview

| Attribute | Value |
|-----------|-------|
| **Stack** | Java 21, Spring Boot 4, Maven, Kafka, PostgreSQL, JPA/Hibernate, Docker |
| **Architecture** | Event-driven microservices with 8 active modules |
| **Phase** | 3 - Intelligence & Policy (Epic 3.3 complete) |
| **Database** | PostgreSQL (port 14005) with Liquibase migrations |
| **Messaging** | Kafka (port 14003) with DLQ support |

---

## Technology Stack Details

### Build & Dependencies
- **Build Tool**: Maven 3.9+ (multi-module project)
- **Java Version**: 21 (LTS)
- **Spring Boot**: 4.x with Spring Data JPA
- **Parent POM**: `/home/ben/Development/ecip/pom.xml`

### Database & Persistence
- **Database**: PostgreSQL (port **14005** - NOT default 5432)
- **Migration Tool**: **LIQUIBASE ONLY** (NEVER Flyway!)
  - Location: `src/main/resources/db/changelog/`
  - Master: `db.changelog-master.xml`
  - Format: XML (preferred) or YAML
- **JPA**: Spring Data JPA with Hibernate
- **Testing**: Testcontainers PostgreSQL 16

### Messaging
- **Broker**: Apache Kafka (port **14003** - NOT default 9092)
- **Zookeeper**: Port 14001
- **Kafka UI**: Port 14004
- **Topics**:
  - `telegram.raw.messages` - Raw Telegram input
  - `messages.classified` - Intent classification
  - `policies.decisions` - Policy evaluation results
  - `responses.generated` - LLM responses
  - `*.dlq` - Dead letter queues (Epic 3.3)
- **Monitoring**: Micrometer metrics + DLQ handling

---

## Module Structure

```
emcip-*/src/main/java/io/emcip/*/
├── /*Application.java           # Main class with @EnableJpaRepositories
├── config/                     # KafkaConfig, DatabaseConfig
├── controller/                 # REST endpoints
├── service/                    # Business logic
├── repository/                 # JpaRepository interfaces
├── entity/                     # JPA entities
├── health/                     # Health indicators (optional)
└── exception/                  # Custom exceptions

emcip-*/src/main/resources/
├── application.yml             # Service config (port, kafka, db)
├── db/changelog/               # Liquibase migrations
└── events/                     # JSON event schemas
```

### Service Ports

| Module | Port | Purpose | Status |
|--------|------|---------|--------|
| emcip-tdlib-adapter | 9080 | Telegram integration | ✅ Active |
| emcip-conversation-context | 9081 | Thread tracking & persistence | ✅ Active |
| emcip-intent-classifier | 9082 | Intent detection | ✅ Active |
| emcip-policy-engine | 9083 | Policy rules & evaluation | ✅ Complete |
| emcip-llm-orchestrator | 9084 | LLM routing & cost tracking | 🔄 In Progress |
| emcip-moderation-service | 9085 | Content moderation | ⏳ Planned |
| emcip-audit-service | 9086 | Logging/metrics | ⏳ Planned |
| emcip-admin-api | 9087 | Admin endpoints | ⏳ Planned |

### Infrastructure Ports

| Service | Port | Notes |
|---------|------|-------|
| Zookeeper | 14001 | Kafka coordination |
| Kafka Internal | 14002 | Docker internal |
| Kafka External | **14003** | App connections |
| Kafka UI | 14004 | Management UI |
| PostgreSQL | **14005** | Database |
| pgAdmin | 14006 | DB admin UI |

---

## Critical Rules (Non-Negotiable)

### 1. ALWAYS Use Liquibase (NEVER Flyway)
```xml
<!-- Master changelog -->
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">
    <include file="changes/001-create-table.xml" relativeToChangelogFile="true"/>
</databaseChangeLog>
```

### 2. Code Style - MANDATORY Pre-Commit
```bash
# Step 1: Apply formatting
mvn spotless:apply

# Step 2: Verify
mvn spotless:check

# Step 3: Run tests for modified module
mvn test -pl <modified-module>
```

**Expected Output:**
```
[INFO] Spotless.Java is keeping 15 files clean - 0 were changed to be clean, 15 were already clean
[INFO] Spotless.Pom is keeping 1 files clean - 0 were changed to be clean, 1 were already clean
```

### 3. Lombok Usage (REQUIRED)
```java
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    // NEVER write manual getters/setters/equals/hashCode
}

@Service
@RequiredArgsConstructor
@Slf4j
public class MyService {
    // Use log.info(), log.debug() - NEVER LoggerFactory
}
```

### 4. Kafka Configuration Pattern
```java
@Value("${spring.kafka.bootstrap-servers:localhost:14003}")
private String bootstrapServers;

// Use CommonKafkaConfig from emcip-core for advanced features
// DLQ topic pattern: {original-topic}.dlq
```

### 5. JPA Entity Pattern
```java
@Entity
@Table(name = "entities")
public class MyEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false)
    private String requiredField;
    
    @CreationTimestamp
    private Instant createdAt;
    
    @UpdateTimestamp
    private Instant updatedAt;
    
    @Version
    private Long version;
}
```

---

## Git Workflow (Strict)

### Pre-Commit Checklist
1. **Spotless formatting applied**: `mvn spotless:apply`
2. **Tests pass**: `mvn test -pl <module>`
3. **All related changes included**: code + tests + docs

### Commit Standards
- **One commit per user story minimum**
- Format: `type(scope): description`
  - `feat(module): implement US-X.Y.Z - brief description`
  - `fix(kafka): resolve port conflict`
  - `docs: update configuration`
- Include context in body:
  ```
  feat(policy-engine): implement US-3.1.1 policy evaluation
  
  - Add PolicyEvaluationService with rule engine
  - Add PolicyDecision entity and repository
  - Add Kafka consumer for messages.classified
  ```

### Merge Requests
- **Create MR at end of each phase**
- All tests must pass: `mvn clean test`
- Documentation updated (MILESTONES.md, user stories)
- All commits clean (spotless:check passes)

---

## Common Commands

### Development
```bash
# Build everything
mvn clean install -DskipTests

# Build single module + dependencies
mvn clean install -pl emcip-policy-engine -am -DskipTests

# Run tests for module
mvn test -pl emcip-policy-engine

# Run with coverage
mvn clean test jacoco:report -pl emcip-policy-engine

# Run service locally
cd emcip-policy-engine && mvn spring-boot:run
```

### Infrastructure
```bash
# Start all infrastructure
docker-compose up -d

# Start specific services
docker-compose up -d postgres kafka kafka-ui

# View logs
docker-compose logs -f kafka

# Reset everything (data loss!)
docker-compose down -v

# Check port usage
for port in 14001 14002 14003 14004 14005 14006; do
  lsof -Pi :$port -sTCP:LISTEN
done
```

### Database
```bash
# Check migration status
mvn liquibase:status -pl emcip-policy-engine

# Apply migrations
mvn liquibase:update -pl emcip-policy-engine
```

---

## Documentation References

| Document | Purpose |
|----------|---------|
| `.windsurf/cascade-config.md` | Current project status & development rules |
| `documentation/planning/MILESTONES.md` | Phase milestones & completion status |
| `documentation/planning/phases/PHASE-3_USER_STORIES.md` | Current user stories |
| `documentation/adrs/` | Architecture Decision Records |
| `documentation/diagrams/` | C4 and sequence diagrams |
| `ONBOARDING.md` | Developer setup guide |
| `PORT_CONFIGURATION.md` | Port allocation reference |

---

## Current Phase Status (Phase 3)

| Epic | Status | User Stories |
|------|--------|--------------|
| 3.1 Policy Engine | ✅ COMPLETE | US-3.1.1, 3.1.2, 3.1.3 (33 tests passing) |
| 3.2 LLM Orchestrator | 🔄 IN PROGRESS | US-3.2.1 ✅, US-3.2.2 ⏳ POSTPONED, US-3.2.3 ✅, US-3.2.4 ✅ |
| 3.3 Kafka Monitoring | ✅ COMPLETE | US-3.3.1, 3.3.2 (Metrics + DLQ implemented) |

**Next Milestone**: Epic 3.4 (Observability & Audit) or return to US-3.2.2 (External LLM)

---

## Skills & Capabilities

See `.claude/skills/` directory for detailed skill definitions:

- `spring-boot-jpa` - JPA entities, repositories, services
- `kafka-messaging` - Producers, consumers, DLQ handling
- `liquibase-migrations` - Database schema versioning
- `maven-multi-module` - Multi-module project management
- `docker-infrastructure` - Docker Compose local development
- `testing-strategies` - Unit, integration, Testcontainers

---

## Important Notes

### Kafka Test Warnings
Repository tests may show Kafka connection warnings - this is expected:
- Tests use Testcontainers for PostgreSQL only
- Kafka listeners have `auto-startup: false` in tests
- Warnings appear because Spring Kafka tries to connect on startup
- Tests pass regardless (Kafka not required for repository tests)

### When to Ask vs. Act
- **Ask**: Unclear requirements, architectural decisions, scope changes
- **Act**: Clear implementation tasks, bug fixes, refactoring

### File Modification Rules
- **NEVER** edit `.windsurf/cascade-config.md` without user approval (has STOP comment)
- Always propose changes to critical files first
- Get explicit confirmation for destructive operations

---

**Last Updated**: 2026-04-20
**Current Phase**: 3 - Intelligence & Policy
**Kafka Port**: 14003 (matches docker-compose.yml)
**PostgreSQL Port**: 14005
