# EMCIP Claude Code Configuration

> **Role**: Professional Software Architect & Developer  
> **Approach**: Clear, truthful, precise - no assumptions, no hacks

## STOP - Configuration Protection Rule

**🚫 NEVER edit files in `.claude/` or `.windsurf/` or `.opencode/` directories without explicit user approval.**

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

## OpenCode Separation
This repository contains OpenCode-specific configuration under: `.opencode/`
Claude Code must not modify, execute, optimize, reinterpret, migrate, or delete files inside `.opencode` unless explicitly instructed.
All agent definitions, prompts, commands, routing logic, and OpenCode workflows belong exclusively to OpenCode.
But you should look if there are discrepancies and ask the user what to do (e.g. migrate in one direction, update, etc.)

## Project Overview

| Attribute | Value |
|-----------|-------|
| **Stack** | Java 21, Spring Boot 4, Maven, Kafka, PostgreSQL, Docker |
| **DB access** | **Mixed**: admin-api / audit-service / moderation-service → Spring WebFlux + R2DBC; intent-classifier / llm-orchestrator / policy-engine / tdlib-adapter → JPA/Hibernate (blocking) |
| **Phase** | **P3 — pre-1.0.0 release-readiness** (P0–P2 done). Sequence: `documentation/ROADMAP.md`; live status: `docs/superpowers/BACKLOG.md` |
| **Database** | PostgreSQL port **14005**, Liquibase migrations |
| **Messaging** | Kafka port **14003**, DLQ support enabled |

## Critical Rules (MANDATORY)

| # | Rule | Skill Reference |
|---|------|-----------------|
| 1 | **LIQUIBASE ONLY** - Never Flyway | `@liquibase-migrations.md` |
| 2 | **Spotless** - `mvn spotless:apply` before every commit | See below |
| 3 | **Lombok** - Use `@Slf4j`, `@RequiredArgsConstructor`, never manual getters | `@spring-boot-jpa.md` |
| 4 | **Kafka** - Port 14003, use `CommonKafkaConfig` from emcip-core | `@kafka-messaging.md` |
| 5 | **JPA services** - UUID IDs, `@Column(nullable=false)`, `@Version` for locking; **R2DBC services** - Spring Data R2DBC, `Mono`/`Flux`, no `@Transactional` on reactive | `@spring-boot-jpa.md` / `@spring-reactive.md` |
| 6 | **Cron timing** - Never schedule at exact round times (`:00.000`). Always use offset seconds/millis (e.g. `03:00:17.891`) to spread load across services | — |

## Spotless Output Guide

**Success indicator: `0 were changed to be clean`**

```bash
mvn spotless:apply  # Apply formatting
mvn spotless:check  # Verify

# Expected output:
[INFO] Spotless.Java is keeping N files clean - 0 were changed to be clean, N were already clean
# ^-- The 0 is what matters (N varies by module, not important)
```

If X > 0 files changed: `git add -A && git commit -m "style: apply spotless"`

## Git Workflow

- **One commit per user story minimum**
- **Format**: `feat(scope): description` with context in body
- **MR at phase end**: All tests pass, docs updated

## Current Phase Status

**P0–P2 complete. Now in P3 — pre-1.0.0 release-readiness (the only pre-release phase; 1.0.0 ships at the end of P3).**

`documentation/ROADMAP.md` is the authoritative meta-plan (owns *sequence + rationale*, phased P0–P5);
`docs/superpowers/BACKLOG.md` owns *live status*. Do not track phase status here — this block is a
pointer only.

| Phase | Status |
|-------|--------|
| P0 Reconcile & baseline | ✅ done (PR #205) |
| P1 Critical security quick-wins | ✅ done (#206/#207/#208) |
| P2 Security structural hardening (2.0–2.8) | ✅ done (through PR #224) |
| **P3 Pre-1.0.0 release-readiness** | **← active** (gate 3.1–3.7; recommended 3.8–3.17) |
| P4 Post-1.0.0 (features + deferred polish) | post-release |
| P5 Long horizon | post-release |

> Older "SC1–SC9 structural changes" and Epic work are all delivered — see `BACKLOG.md` §1 and §5.

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
- `emcip-admin-ui` - React frontend: design tokens, components, layout, handoff workflow
- `health-indicators` - Custom Actuator health indicators
- `spring-reactive` - Async Mono/Flux for high-concurrency (use sparingly — project is JPA/blocking)
- `documentation-checklist` - Mapping of which docs/diagrams to update for any code change

## Documentation

For details: `@documentation/planning/MILESTONES.md`

**Last Updated**: 2026-08-05
