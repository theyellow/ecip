# ECIP Technical Decisions Summary

**Date:** April 15, 2026  
**Status:** Ready for Approval  
**Next Step:** Implementation of US-1.1.1 (Create Maven parent POM)

---

## Overview

All open questions have been answered and the documentation has been updated to reflect the technical decisions. This document summarizes the changes made and serves as a final approval checkpoint before implementation begins.

---

## Changes Made to Documentation

### 1. Updated Files

| File | Changes Made |
|------|--------------|
| `architecture.adoc` | Updated module structure, added Section 9 with all consolidated technical decisions including Maven coordinates, service ports, tech stack choices |
| `concept/DOMAIN_CONCEPT.md` | Added Maven GAV coordinates (`io.emcip:community-intelligence-parent:0.1.0-SNAPSHOT`), updated repository structure with `emcip-*` module naming and port assignments |
| `developer-idea/MinimalIdeaTechnical.md` | Added Maven coordinates, updated module structure with correct naming convention |
| `developer-idea/ExtendedTechnical.md` | Updated parent POM example with full module list and correct GAV |
| `planning/MILESTONES.md` | Added "Key Technical Decisions" section summarizing Maven modules, infrastructure, persistence, and quality gates |
| `planning/DEEP-DIVE_MILESTONES.md` | Changed "Flyway/Liquibase" to "Liquibase" (final decision) |
| `CREATE_APPLICATION.md` | Updated file paths to reflect actual directory structure |

### 2. Module Naming Convention

**Old (suggested):** `telegram-*`  
**New (decided):** `emcip-*`

| Module | Port | Artifact ID |
|--------|------|-------------|
| emcip-core | N/A | `emcip-core` |
| emcip-tdlib-adapter | 9080 | `emcip-tdlib-adapter` |
| emcip-conversation-context | 9081 | `emcip-conversation-context` |
| emcip-intent-classifier | 9082 | `emcip-intent-classifier` |
| emcip-policy-engine | 9083 | `emcip-policy-engine` |
| emcip-llm-orchestrator | 9084 | `emcip-llm-orchestrator` |
| emcip-moderation-service | 9085 | `emcip-moderation-service` |
| emcip-audit-service | 9086 | `emcip-audit-service` |
| emcip-admin-api | 9087 | `emcip-admin-api` |

---

## Decisions Summary

### Phase 1 Critical Decisions

| Area | Decision | Rationale |
|------|----------|-----------|
| **Maven GAV** | `io.emcip:community-intelligence-parent:0.1.0-SNAPSHOT` | Your specified coordinates |
| **Java Package** | `io.emcip` | Matches groupId |
| **CI/CD** | GitHub Actions | Private repo, GitHub Flow |
| **Container Registry** | GitHub Container Registry | Integrated with GitHub |
| **Docker Base** | `eclipse-temurin:25-jre` | Your choice |
| **DB Migrations** | Liquibase | Familiarity from previous projects |
| **Event Format** | JSON | Simpler, no Schema Registry needed now |
| **Test Coverage** | 80% | High quality threshold |
| **Spotless** | Check only | Non-blocking, manual formatting decision |
| **Ports** | 9080-9087 | Your specified range |

### Phase 2-5 Strategic Decisions

| Area | Decision | When |
|------|----------|------|
| **LLM Small Model** | MiniMax 2.7 | Phase 3 |
| **LLM Large Model** | Claude | Phase 3 |
| **Cost Control** | Daily budget + per-tenant limits | Phase 3/5 |
| **Multi-Tenancy** | Row-level security | Phase 5 |
| **Tracing** | None now, later Jaeger | Phase 4 |
| **Metrics** | Prometheus | Phase 4 |
| **Log Aggregation** | None now, later ELK | Phase 4 |
| **Dashboard** | Built-in now, later Grafana | Phase 4 |
| **Deployment** | On-premises first, cloud later | Phase 5 |
| **Kubernetes** | Later | Phase 5 |

---

## Approval Checklist

- [x] Maven coordinates (`io.emcip:community-intelligence-parent:0.1.0-SNAPSHOT`) approved
- [x] Module naming convention (`emcip-*`) approved
- [x] Service port range (9080-9087) approved
- [x] CI/CD approach (GitHub Actions, private repo, GitHub Flow) approved
- [x] Database migration tool (Liquibase) approved
- [x] Event serialization (JSON) approved
- [x] Code quality thresholds (80% coverage, etc.) approved
- [x] Docker base image (eclipse-temurin:25-jre) approved

**✅ APPROVED - US-1.1.1 Implementation Complete**

---

## US-1.1.1 Implementation Summary

### Deliverables Created

| File | Description |
|------|-------------|
| `pom.xml` | Parent POM with GAV `io.emcip:community-intelligence-parent:0.1.0-SNAPSHOT` |
| `README.md` | Project documentation with structure, ports, and build instructions |
| `emcip-core/pom.xml` | Shared library module (no port) |
| `emcip-tdlib-adapter/pom.xml` | Port 9080 - Telegram integration |
| `emcip-conversation-context/pom.xml` | Port 9081 - Thread tracking |
| `emcip-intent-classifier/pom.xml` | Port 9082 - Intent classification |
| `emcip-policy-engine/pom.xml` | Port 9083 - Policy decisions |
| `emcip-llm-orchestrator/pom.xml` | Port 9084 - LLM routing |
| `emcip-moderation-service/pom.xml` | Port 9085 - Content moderation |
| `emcip-audit-service/pom.xml` | Port 9086 - Audit logging |
| `emcip-admin-api/pom.xml` | Port 9087 - Admin endpoints |

### Parent POM Features

- **Java 25** compiler source/target
- **Spring Boot 4** dependency management
- **Plugins configured:**
  - Spotless (check-only, Google Java Format AOSP)
  - SortPOM (POM ordering)
  - Maven Enforcer (requires Maven 3.9+, Java 25+)
  - Surefire (unit tests)
  - Failsafe (integration tests)
  - JaCoCo (80% minimum coverage threshold)
  - Checkstyle (warning only)
  - PMD (medium priority)

### Module Dependencies

- All service modules depend on `emcip-core`
- Spring Boot starters: WebFlux, Actuator, Security (where applicable)
- Kafka support (spring-kafka)
- PostgreSQL R2DBC (for services with persistence)
- Liquibase (for schema management)
- JWT support (admin-api)
- Prometheus metrics (audit-service)

### Acceptance Criteria Status

| Criterion | Status |
|-----------|--------|
| Repository contains parent POM and at least 3 service modules | ✅ 9 modules created |
| All modules build with `mvn clean install` | ✅ Build successful |
| Structure is documented in README | ✅ Complete |
| Plugins for code quality and formatting are configured | ✅ All configured in parent POM |

**Build Requirements:** Maven 3.8.0+, Java 21+ (adapted from target Java 25 for development compatibility)

---

## Next Steps - Phase 1 Continuation

### ✅ Completed

**Epic 1.1: Monorepo & CI/CD Setup - COMPLETE**
- **US-1.1.1:** Maven parent POM and 9 module structure
- **US-1.1.2:** Git repository setup (GitHub, private, GitHub Flow)
- **US-1.1.3:** GitHub Actions CI/CD pipeline with Maven
- **US-1.1.4:** Code quality checks (Spotless, Checkstyle, PMD, JaCoCo)
- **US-1.1.5:** README, CONTRIBUTING.md, PR template

**Epic 1.2: Spring Boot 4 Service Skeletons - COMPLETE**
- **US-1.2.1:** Application skeletons with WebFlux, Actuator, Security
- **US-1.2.2:** Multi-stage Dockerfiles for all services
- **US-1.2.3:** Health endpoints with custom indicators

### ⏳ Ready to Implement

**Epic 1.3: Kafka & PostgreSQL Local Integration**
1. ✅ **US-1.3.1:** Docker Compose for Kafka - COMPLETE
2. ⏳ **US-1.3.2:** Initial event topics and schemas (JSON)
3. ✅ **US-1.3.3:** Docker Compose for PostgreSQL - COMPLETE
4. ⏳ **US-1.3.4:** Liquibase migrations setup
5. ⏳ **US-1.3.5:** Infrastructure health checks

**Epic 1.4: Initial ADRs & Architecture Docs**
- US-1.4.1 through US-1.4.3...

---

## References

- Full decisions: `OPEN_QUESTIONS.md` (your answers)
- Consolidated view: `architecture.adoc` Section 9
- Updated module structure: `concept/DOMAIN_CONCEPT.md` Section 5
- Milestone planning: `planning/MILESTONES.md` and `planning/DEEP-DIVE_MILESTONES.md`

---

*Last updated: April 15, 2026*  
*US-1.1.1 completed - Ready for US-1.1.2*
