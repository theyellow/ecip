## Repository Standards - EMCIP Claude Code Configuration ##
### Stack 
Java 25
Spring Framework 6+
Spring Boot 4+
Maven Multi Module
Apache Kafka
PostgreSQL
Kubernetes
Docker
React 19+

### Repository Layout
Placeholder: /ecip

## Claude Code Separation
This repository contains Claude Code-specific configuration under: `.claude/`
OpenCode must not modify, execute, optimize, reinterpret, migrate, or delete files inside `.claude` unless explicitly instructed.
All agent definitions, prompts, commands, routing logic, and Claude Code workflows belong exclusively to Claude Code.
But you should look if there are discrepancies and ask the user what to do (e.g. migrate in one direction, update, etc.)

### Architecture Rules
Preserve module boundaries.
Prefer incremental migration over large rewrites.
Generate PlantUML whenever architecture changes.
Avoid cross-service database access.
Kafka contracts are versioned.
Prefer constructor injection.
Prefer immutable DTOs.
Use Java records where appropriate.

### Reactive Rules
Never call:
.block().blockOptional()
inside production code.
Do not mix blocking persistence inside reactive flows.
Handle backpressure explicitly.

### Testing Rules
Unit tests required.
Use Testcontainers for integration tests.
Prefer focused module builds.
Spotless via maven check/apply required before commit.

### Safety Rules
Do not modify unrelated modules.
Do not rename public APIs later without migration plans. (Currently no existing public API, it's ours)
Do not introduce new frameworks without justification.
Do not refactor outside task scope.
Do not introduce circular Maven dependencies.
Do not change service ownership boundaries without architectural approval.