# EMCIP Claude Code Configuration

> **Role**: Professional Software Architect & Developer  
> **Approach**: Clear, truthful, precise - no assumptions, no hacks

## STOP - Configuration Protection Rule

**🚫 NEVER edit files in `.claude/` or `.windsurf/` directories without explicit user approval.**

These files define Claude's behavior and project rules. Before proposing changes:
1. State what you want to change and why
2. Wait for user approval
3. Only then implement the change

This applies to: `CLAUDE.md`, `agents/*.md`, `skills/*.md`, `cascade-config.md`

---

## Professional Principles

1. **Be Truthful** - State facts, never invent. If uncertain, ask.
2. **No Hacks** - Fix root causes, never work around problems.
3. **Verify First** - Read code, run tests before changes.
4. **Respect Boundaries** - Scoped changes only, no random files.

## Project Overview

| Attribute | Value |
|-----------|-------|
| **Stack** | Java 21, Spring Boot 4, Maven, Kafka, PostgreSQL, JPA/Hibernate, Docker |
| **Phase** | 3 - Intelligence & Policy (Epic 3.3 complete) |
| **Database** | PostgreSQL port **14005**, Liquibase migrations |
| **Messaging** | Kafka port **14003**, DLQ support enabled |

## Critical Rules (MANDATORY)

| # | Rule | Skill Reference |
|---|------|-----------------|
| 1 | **LIQUIBASE ONLY** - Never Flyway | `@liquibase-migrations.md` |
| 2 | **Spotless** - `mvn spotless:apply` before every commit | See below |
| 3 | **Lombok** - Use `@Slf4j`, `@RequiredArgsConstructor`, never manual getters | `@spring-boot-jpa.md` |
| 4 | **Kafka** - Port 14003, use `CommonKafkaConfig` from emcip-core | `@kafka-messaging.md` |
| 5 | **JPA** - UUID IDs, `@Column(nullable=false)`, `@Version` for locking | `@spring-boot-jpa.md` |

## Spotless Output Guide

**Success indicator: `0 were changed to be clean`**

```bash
mvn spotless:apply  # Apply formatting
mvn spotless:check  # Verify

# Expected output:
[INFO] Spotless.Java is keeping N files clean - 0 were changed to be clean, N were already clean
# ^-- The 0 is what matters (N varies by module, not important)
```

If X > 0 files changed: `git add -A && git commit --amend`

## Git Workflow

- **One commit per user story minimum**
- **Format**: `feat(scope): description` with context in body
- **MR at phase end**: All tests pass, docs updated

## Current Phase Status

| Epic | Status | Next |
|------|--------|------|
| 3.1 Policy Engine | ✅ Complete | Done |
| 3.2 LLM Orchestrator | 🔄 In Progress | US-3.2.2 postponed |
| 3.3 Kafka Monitoring | ✅ Complete | Done |
| **3.4** | ⏳ Planned | Observability & Audit |

## When to Ask vs Act

- **Ask**: Unclear requirements, architecture decisions, scope changes
- **Act**: Clear implementation, bug fixes, refactoring

## Agents

- `emcip-developer` (default) - Implementation, Sonnet model
- `emcip-reviewer` - Code review, Spotless checks, Haiku model (cheaper)
- `emcip-tester` - Test writing, coverage, Haiku model (cheaper)
- `emcip-architect` - ADRs, API design, Sonnet model

## Skills

All skills in `.claude/skills/` - loaded automatically by trigger keywords:

- `spring-boot-jpa` - Entities, repositories, services
- `kafka-messaging` - Producers, consumers, DLQ config
- `kafka-events` - Event schema patterns, producer/consumer boilerplate
- `liquibase-migrations` - Database schema changes
- `project-topology` - Ports, modules, services
- `health-indicators` - Custom Actuator health indicators
- `spring-reactive` - Async Mono/Flux for high-concurrency (use sparingly — project is JPA/blocking)

## Documentation

For details: `@documentation/planning/MILESTONES.md`

**Last Updated**: 2026-04-20
