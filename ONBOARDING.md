# EMCIP Developer Onboarding Guide

Welcome to the EMCIP (Enterprise Messenger Community Intelligence Platform) team! This guide will get you up and running locally.

## Prerequisites

### Required Software

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 21 (LTS) | Development language |
| Maven | 3.9+ | Build tool |
| Docker | 20.10+ | Containerization |
| Docker Compose | 2.0+ | Local infrastructure |
| Git | 2.30+ | Version control |

### Verify Prerequisites

```bash
java -version       # Should show Java 21
mvn -version        # Should show Maven 3.9+
docker --version    # Should show Docker 20.10+
docker-compose --version  # Should show 2.0+
git --version       # Should show 2.30+
```

## Repository Setup

### 1. Clone the Repository

```bash
git clone https://github.com/emcip/community-intelligence.git
cd community-intelligence
```

### 2. Check Out Your Branch

We use GitHub Flow:
```bash
git checkout -b feature/your-feature-name
# or
git checkout -b ecip-your-branch-name
```

## Local Development Setup

### Step 1: Start Infrastructure

```bash
# Start Kafka, PostgreSQL, and UIs
docker-compose up -d

# Verify services are running
docker-compose ps
```

**Services will be available at:**
- Kafka: `localhost:14002` (internal), `localhost:14003` (external)
- PostgreSQL: `localhost:14005`
- Kafka UI: http://localhost:14004
- pgAdmin: http://localhost:14006 (admin/admin)

See [PORT_CONFIGURATION.md](PORT_CONFIGURATION.md) for complete port reference.

### Step 2: Build the Project

```bash
# Install parent POM first
mvn clean install -N

# Build all modules (skip tests for speed)
mvn clean install -DskipTests
```

### Step 3: Run a Service

```bash
# Run conversation-context service
cd emcip-conversation-context
mvn spring-boot:run

# Or run with specific profile
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### Step 4: Verify Health

```bash
# Check service health
curl http://localhost:9081/actuator/health

# Expected response:
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "kafka": { "status": "UP" },
    "ping": { "status": "UP" }
  }
}
```

## Development Workflow

### Code Quality Checks

Before committing, run:

```bash
# Format code
mvn spotless:apply

# Run all checks
mvn spotless:check checkstyle:check pmd:check

# Run tests
mvn test

# Generate coverage report
mvn jacoco:report
```

### Build Docker Images

```bash
# Build image for a service
cd emcip-conversation-context
docker build -t emcip/conversation-context:latest .
```

## Project Structure

```
community-intelligence/
├── pom.xml                          # Parent POM
├── docker-compose.yml               # Local infrastructure
├── emcip-core/                      # Shared library
├── emcip-tdlib-adapter/             # Telegram integration (9080)
├── emcip-conversation-context/      # Thread tracking (9081)
├── emcip-intent-classifier/         # Intent detection (9082)
├── emcip-policy-engine/             # Policy rules (9083)
├── emcip-llm-orchestrator/          # LLM routing (9084)
├── emcip-moderation-service/        # Content moderation (9085)
├── emcip-audit-service/             # Logging/metrics (9086)
├── emcip-admin-api/                 # Admin endpoints (9087)
├── documentation/
│   ├── adrs/                        # Architecture Decision Records
│   ├── diagrams/                    # C4 and sequence diagrams
│   └── planning/                    # User stories, milestones
└── .github/
    └── workflows/                   # CI/CD pipelines
```

## Common Tasks

### View Kafka Messages

```bash
# List topics
docker exec ecip-kafka kafka-topics --list --bootstrap-server localhost:9092

# Consume messages
docker exec -it ecip-kafka kafka-console-consumer \
  --topic telegram.raw.messages \
  --from-beginning \
  --bootstrap-server localhost:9092
```

### Connect to PostgreSQL

```bash
# Via psql
docker exec -it ecip-postgres psql -U emcip -d emcip

# Via pgAdmin
# Open http://localhost:5050, login admin/admin
# Add server: host=postgres, port=5432, user=emcip, password=emcip
```

### Reset Everything

```bash
# Stop and remove containers + volumes (⚠️ deletes data)
docker-compose down -v

# Rebuild everything
mvn clean install -DskipTests
docker-compose up -d
```

## Troubleshooting

### Port Conflicts

If you see "port already in use" errors:

```bash
# Find what's using port 5432
lsof -i :5432

# Kill the process or change ports in docker-compose.yml
```

### Maven Build Failures

```bash
# FIRST: Always run spotless to fix formatting issues (common cause of failures)
mvn spotless:apply

# Then rebuild
mvn clean install -DskipTests

# If still failing, clear local Maven cache (if corrupted)
rm -rf ~/.m2/repository/io/emcip

# Rebuild from scratch
mvn clean install -DskipTests
```

### Database Connection Issues

```bash
# Check PostgreSQL is healthy
docker-compose ps postgres

# View PostgreSQL logs
docker-compose logs postgres

# Test connection
docker exec ecip-postgres pg_isready -U emcip
```

### Kafka Connection Issues

```bash
# Check Kafka is healthy
docker-compose ps kafka

# Restart Kafka
docker-compose restart kafka

# Check Zookeeper is running
docker-compose ps zookeeper
```

## IDE Setup

### IntelliJ IDEA

1. Import as Maven project (File → New → Project from Existing Sources)
2. Select `pom.xml` at root
3. Enable annotation processing (Settings → Build → Compiler → Annotation Processors)
4. Install plugins:
   - Checkstyle-IDEA
   - PlantUML
   - Spotless

### VS Code

Recommended extensions:
- Extension Pack for Java
- Maven for Java
- PlantUML
- Checkstyle for Java
- Spotless Gradle/Maven

## Next Steps

1. Read [ARCHITECTURE.md](documentation/architecture.adoc) for system overview
2. Review [ADRs](documentation/adrs/) for key decisions
3. Check [EVENT_SCHEMAS.md](EVENT_SCHEMAS.md) for event definitions
4. Pick up your first user story from [PHASE-1_USER_STORIES.md](documentation/planning/phases/PHASE-1_USER_STORIES.md)

## Getting Help

- **Technical questions**: Open a GitHub issue
- **Architecture questions**: Review ADRs and architecture docs
- **Process questions**: Check [CONTRIBUTING.md](CONTRIBUTING.md)

## Resources

- [Spring Boot Reactive Documentation](https://docs.spring.io/spring-framework/reference/web-reactive.html)
- [Kafka Documentation](https://kafka.apache.org/documentation/)
- [R2DBC PostgreSQL](https://github.com/pgjdbc/r2dbc-postgresql)
- [Liquibase Documentation](https://docs.liquibase.com/)

Welcome aboard! 🚀
