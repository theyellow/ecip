# EMCIP - Enterprise Messenger Community Intelligence Platform

[![Java CI with Maven](https://github.com/theyellow/ecip/actions/workflows/maven.yml/badge.svg)](https://github.com/theyellow/ecip/actions/workflows/maven.yml)

An enterprise-grade, microservice-based platform built on Java 25 and Spring Boot 4 that analyzes Telegram groups, channels, and discussion threads in real time, detects communication contexts, and reacts based on rules.

## Overview

Unlike traditional bots, EMCIP is **TDLib-first**, meaning it operates as a full Telegram client (as a real user, not a bot). The Bot API remains an optional additional channel.

### Operating Modes

The platform acts as a context-sensitive communication assistant with four operating modes:
1. **React:** On direct mention
2. **Summarize:** When threads become confusing
3. **Moderate:** On rule violations
4. **Observe:** When no intervention is required

## Project Information

- **Group ID:** `io.emcip`
- **Artifact ID:** `community-intelligence-parent`
- **Version:** `0.1.0-SNAPSHOT`
- **Java Version:** 25
- **Spring Boot:** 4.x

## Module Structure

This is a Maven multi-module project with the following structure:

```
community-intelligence/
├── pom.xml                                 # Parent POM
├── README.md                               # This file
├── emcip-core/                             # Shared library (no port)
│   └── pom.xml
├── emcip-tdlib-adapter/                    # Telegram integration (port 9080)
│   └── pom.xml
├── emcip-conversation-context/             # Thread tracking (port 9081)
│   └── pom.xml
├── emcip-intent-classifier/                # Intent classification (port 9082)
│   └── pom.xml
├── emcip-policy-engine/                    # Policy decisions (port 9083)
│   └── pom.xml
├── emcip-llm-orchestrator/                 # AI model routing (port 9084)
│   └── pom.xml
├── emcip-moderation-service/               # Content moderation (port 9085)
│   └── pom.xml
├── emcip-audit-service/                    # Audit logging (port 9086)
│   └── pom.xml
├── emcip-admin-api/                        # Admin endpoints (port 9087)
│   └── pom.xml
└── documentation/                          # Documentation
    ├── architecture.adoc
    ├── OPEN_QUESTIONS.md
    ├── DECISIONS_SUMMARY.md
    ├── SOUL.md
    └── planning/
```

### Service Ports

| Service | Port | Description |
|---------|------|-------------|
| emcip-tdlib-adapter | 9080 | Telegram TDLib integration |
| emcip-conversation-context | 9081 | Thread and speaker tracking |
| emcip-intent-classifier | 9082 | Rule-based intent classification |
| emcip-policy-engine | 9083 | Policy decision engine |
| emcip-llm-orchestrator | 9084 | LLM routing and cost tracking |
| emcip-moderation-service | 9085 | Toxicity filtering |
| emcip-audit-service | 9086 | Audit logging and metrics |
| emcip-admin-api | 9087 | Admin API and management |

## Prerequisites

- **Java:** JDK 21 or higher (target is Java 25, but 21+ works for development)
- **Maven:** 3.8.0 or higher
- **Docker:** For local development (Kafka, PostgreSQL)
- **Telegram API credentials:** api_id and api_hash (for Phase 2)

## Build

```bash
# Build all modules
mvn clean install

# Skip tests (faster build during development)
mvn clean install -DskipTests

# Run code quality checks
mvn spotless:check
mvn checkstyle:check
mvn pmd:check

# Generate test coverage report
mvn jacoco:report
```

## Local Development

### Docker Compose Setup

A `docker-compose.yml` is available in the project root. It starts:
- Kafka (broker + Zookeeper)
- PostgreSQL

### Running Individual Services

```bash
# Run a specific service
cd emcip-tdlib-adapter
mvn spring-boot:run
```

## Code Quality

This project enforces high code quality standards:

| Metric | Threshold |
|--------|-----------|
| Test Coverage (JaCoCo) | **80%** minimum |
| Spotless | Check only (no auto-format) |
| Checkstyle | Warning only (not blocking) |
| PMD | Medium priority |

## Architecture

See [architecture.adoc](documentation/architecture.adoc) for detailed C4 diagrams, component breakdown, and technical decisions.

### Key Technologies

- **Java 25** - Modern Java features
- **Spring Boot 4** - Microservices framework (WebFlux, Actuator, Security)
- **Apache Kafka** - Event backbone (JSON serialization, no Schema Registry initially)
- **PostgreSQL** - Persistent storage
- **Liquibase** - Database migrations
- **TDLib** - Telegram client library
- **Docker** - Containerization

## Documentation

- [SOUL.md](documentation/SOUL.md) - Vision, mission, and success criteria
- [architecture.adoc](documentation/architecture.adoc) - Technical architecture and decisions
- [DECISIONS_SUMMARY.md](documentation/DECISIONS_SUMMARY.md) - Consolidated technical decisions
- [planning/MILESTONES.md](documentation/planning/MILESTONES.md) - Project milestones and phases
- [planning/DEEP-DIVE_MILESTONES.md](documentation/planning/DEEP-DIVE_MILESTONES.md) - Sprint-level planning

## Contributing

This project uses **GitHub Flow** branching strategy:
- `main` branch is always deployable
- Create feature branches from `main`
- Open pull requests for review
- Merge to `main` after approval

## License

Apache License, Version 2.0

## Contact

- **Organization:** EMCIP
- **Email:** dev@emcip.io
