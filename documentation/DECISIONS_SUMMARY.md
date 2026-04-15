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

- [ ] Maven coordinates (`io.emcip:community-intelligence-parent:0.1.0-SNAPSHOT`) approved
- [ ] Module naming convention (`emcip-*`) approved
- [ ] Service port range (9080-9087) approved
- [ ] CI/CD approach (GitHub Actions, private repo, GitHub Flow) approved
- [ ] Database migration tool (Liquibase) approved
- [ ] Event serialization (JSON) approved
- [ ] Code quality thresholds (80% coverage, etc.) approved
- [ ] Docker base image (eclipse-temurin:25-jre) approved

**Once approved, implementation of US-1.1.1 can begin immediately.**

---

## Next Steps After Approval

1. **Implement US-1.1.1:** Create Maven parent POM and initial module structure
   - Parent POM with GAV: `io.emcip:community-intelligence-parent:0.1.0-SNAPSHOT`
   - 9 modules: emcip-core, emcip-tdlib-adapter, emcip-conversation-context, emcip-intent-classifier, emcip-policy-engine, emcip-llm-orchestrator, emcip-moderation-service, emcip-audit-service, emcip-admin-api
   - Plugins: spotless, sortpom, enforcer, surefire, failsafe, jacoco (80% threshold)

2. **US-1.1.2:** Git repository setup (GitHub, private, GitHub Flow)

3. **US-1.1.3:** GitHub Actions CI/CD pipeline

4. Continue through Phase 1 user stories...

---

## References

- Full decisions: `OPEN_QUESTIONS.md` (your answers)
- Consolidated view: `architecture.adoc` Section 9
- Updated module structure: `concept/DOMAIN_CONCEPT.md` Section 5
- Milestone planning: `planning/MILESTONES.md` and `planning/DEEP-DIVE_MILESTONES.md`

---

**To approve:** Reply with "Approved" or any modifications needed.
