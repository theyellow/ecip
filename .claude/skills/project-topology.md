---
name: project-topology
description: Service ports, module structure, infrastructure configuration
triggers:
  - "port"
  - "service"
  - "module"
  - "architecture"
  - "9080"
  - "9081"
  - "9082"
  - "9083"
  - "14003"
  - "14005"
---

# Project Topology

## Service Ports

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

## Infrastructure Ports

| Service | Port | Purpose |
|---------|------|---------|
| Zookeeper | 14001 | Kafka coordination |
| Kafka Internal | 14002 | Docker internal |
| Kafka External | **14003** | App connections |
| Kafka UI | 14004 | Management UI (http://localhost:14004) |
| PostgreSQL | **14005** | Database |
| pgAdmin | 14006 | DB admin UI (http://localhost:14006) |

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

emcip-*/src/test/
├── java/.../                   # Test classes
└── resources/
    └── application-test.yml    # Test configuration
```

## Event Topics

| Topic | Producer | Consumer | Purpose |
|-------|----------|----------|---------|
| `telegram.raw.messages` | tdlib-adapter | conversation-context, intent-classifier | Raw Telegram input |
| `messages.classified` | intent-classifier | policy-engine | Intent classification results |
| `policies.decisions` | policy-engine | llm-orchestrator | Policy evaluation results |
| `responses.generated` | llm-orchestrator | (tdlib-adapter) | LLM responses |
| `*.dlq` | - | DLQ consumers | Dead letter queues |

## Docker Compose

```bash
# Start all infrastructure
docker compose up -d

# Start specific services
docker compose up -d postgres kafka kafka-ui

# View logs
docker compose logs -f kafka

# Reset everything (data loss!)
docker compose down -v
```

## Common Commands

```bash
# Build single module + dependencies
mvn clean install -pl emcip-policy-engine -am -DskipTests

# Run tests for module
mvn test -pl emcip-policy-engine

# Run service locally
cd emcip-policy-engine && mvn spring-boot:run

# Check port usage
for port in 14001 14002 14003 14004 14005 14006; do
  lsof -Pi :$port -sTCP:LISTEN
done
```

## Current Phase

**Phase 3 - Intelligence & Policy**:
- Epic 3.1: ✅ Policy Engine (COMPLETE)
- Epic 3.2: 🔄 LLM Orchestrator (In Progress, US-3.2.2 postponed)
- Epic 3.3: ✅ Kafka Monitoring (COMPLETE)
- Epic 3.4: ⏳ Planned (Observability & Audit)

For full details: @documentation/planning/MILESTONES.md
