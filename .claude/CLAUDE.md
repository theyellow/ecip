# EMCIP Claude Code Configuration

Configuration for Claude Code agent to work effectively with the EMCIP (Enterprise Messenger Community Intelligence Platform) project.

## Project Overview

**Stack:** Java 21, Spring Boot 4, Maven, Kafka, PostgreSQL, R2DBC, Docker
**Architecture:** Event-driven microservices with 9 modules
**Style:** Reactive programming (WebFlux), non-blocking I/O

## Key Conventions

### Code Style
- Always run `mvn spotless:apply` before committing
- Follow Google Java Format (enforced by Spotless)
- Reactive patterns: use `Mono` and `Flux`, avoid blocking calls
- Use constructor injection with `final` fields

### Project Structure
```
emcip-*/src/main/java/io/emcip/*/
├── /*Application.java          # Main class
├── config/                      # Configuration classes
├── controller/                    # REST endpoints (reactive)
├── service/                       # Business logic
├── repository/                    # R2DBC repositories
├── model/                         # Entities/DTOs
├── health/                        # Health indicators
└── exception/                     # Custom exceptions

emcip-*/src/main/resources/
├── application.yml               # Service config
├── db/changelog/                 # Liquibase migrations
└── events/                       # JSON event schemas
```

### Health Checks
Services use custom health indicators:
- `DatabaseHealthIndicator` - Checks PostgreSQL via R2DBC
- `KafkaHealthIndicator` - Checks Kafka cluster connectivity

Endpoints: `GET /actuator/health`

### Event Topics
| Topic | Purpose |
|-------|---------|
| telegram.raw.messages | Raw Telegram input |
| messages.classified | Intent classification |
| policies.decisions | Policy evaluation |
| responses.generated | LLM responses |
| moderation.flags | Moderation events |
| audit.events | Audit trail |

### Ports
- 9080: tdlib-adapter
- 9081: conversation-context
- 9082: intent-classifier
- 9083: policy-engine
- 9084: llm-orchestrator
- 9085: moderation-service
- 9086: audit-service
- 9087: admin-api

## Development Workflow

1. **Before coding:**
   - Check ADRs in `documentation/adrs/`
   - Review relevant user stories in `documentation/planning/`

2. **During coding:**
   - Use reactive patterns (WebFlux, R2DBC)
   - Add health indicators for new infrastructure dependencies
   - Update Liquibase changelogs for schema changes

3. **Before commit:**
   ```bash
   mvn spotless:apply
   mvn clean compile -DskipTests
   ```

4. **Full verification:**
   ```bash
   mvn clean install
   ```

## Common Tasks

### Add a new service module
1. Create directory `emcip-{service-name}/`
2. Copy structure from existing service
3. Update parent pom.xml modules section
4. Set unique port in application.yml
5. Create main Application class
6. Add Dockerfile
7. Create DatabaseHealthIndicator (and KafkaHealthIndicator if needed)

### Add Kafka event
1. Define topic in `docker-compose.yml` (for local)
2. Create JSON schema in `src/main/resources/events/`
3. Update `EVENT_SCHEMAS.md`
4. Add consumer/producer in relevant service

### Add database table
1. Create Liquibase changeset in `db/changelog/db.changelog-master.xml`
2. Add R2DBC repository in service
3. Add entity class with `@Table` annotation

## Documentation References

- `documentation/adrs/` - Architecture Decision Records
- `documentation/diagrams/` - C4 and sequence diagrams
- `EVENT_SCHEMAS.md` - Kafka event definitions
- `ONBOARDING.md` - Developer setup guide
- `INFRASTRUCTURE.md` - Docker Compose guide
- `HEALTH_ENDPOINTS.md` - Health check documentation

## Testing

```bash
# Run tests
mvn test

# Run with coverage
mvn jacoco:report

# Check code quality
mvn spotless:check checkstyle:check pmd:check
```

## Docker Commands

```bash
# Start all infrastructure
docker-compose up -d

# View logs
docker-compose logs -f kafka

# Reset everything
docker-compose down -v
```

## Git Workflow

- Branch naming: `feature/description` or `ecip-description`
- Commits: `feat(scope): description` or `docs: description`
- PR required before merging to main
- All checks must pass (CI/CD)

## Skills Available

- `spring-reactive` - Spring WebFlux and R2DBC patterns
- `kafka-events` - Kafka producers/consumers with Spring Kafka
- `liquibase-migrations` - Database schema versioning
- `health-indicators` - Custom Spring Boot Actuator health checks
- `docker-compose` - Local infrastructure management

See `.claude/skills/` for detailed skill definitions.
