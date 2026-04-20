# MILESTONES.md

## ECIP – Milestones & Phase Overview

This overview lists the most important milestones and phases of the ECIP project. For details on epics and user stories, see the respective user story documents.

---

**Telegram Integration Principle:**
The ECIP platform connects to Telegram as a real user (not a bot), listens in various groups, channels, and discussion chats, collects information passively, and can later interact with other users. All milestones and phases reflect this integration principle. All communication with Telegram is performed via TDLib as a real user, not via the Bot API.

### Phase 1: Foundation & Infrastructure
- See: [PHASE-1_USER_STORIES.md](phases/PHASE-1_USER_STORIES.md)
- Monorepo, CI/CD, Spring Boot skeletons, Kafka & PostgreSQL locally, initial ADRs
- TDLib adapter connects as a real Telegram user, listens in groups/channels/discussions, collects messages/events

### Phase 2: Core Messaging Pipeline
- See: [PHASE-2_USER_STORIES.md](phases/PHASE-2_USER_STORIES.md)
- TDLib adapter (real Telegram user, passive listening and event collection), event backbone, persistence, intent classifier

### Phase 3: Intelligence & Policy
- See: [PHASE-3_USER_STORIES.md](phases/PHASE-3_USER_STORIES.md)
- Policy engine, LLM orchestrator, Kafka monitoring, policy-driven interaction with Telegram users

### Phase 4: Observability, Moderation & Audit
- See: [PHASE-4_USER_STORIES.md](phases/PHASE-4_USER_STORIES.md)
- Moderation, audit, admin API, event log, moderation of all collected and generated content

### Phase 5: Production Hardening & Admin
- See: [PHASE-5_USER_STORIES.md](phases/PHASE-5_USER_STORIES.md)
- Multi-tenancy, performance, policy logic, admin UI, backup/restore, advanced Telegram interaction

---

For deeper technical details and sub-milestones, see [DEEP-DIVE_MILESTONES.md](DEEP-DIVE_MILESTONES.md).

---

## Phase Overview

1. **Inception & Foundation** (Complexity: Medium)
2. **MVP Core Pipeline** (Complexity: High)
3. **AI & Integration** (Complexity: High)
4. **Observability, Moderation & Audit** (Complexity: Medium)
5. **Production Hardening & Admin** (Complexity: High)
6. **Risk Spikes & Technical Risks** (Complexity: Medium)

---

## Key Technical Decisions

*Based on OPEN_QUESTIONS.md - see architecture.adoc Section 9 for full details*

**Maven & Modules:**
- Parent POM: `io.emcip:community-intelligence-parent:0.1.0-SNAPSHOT`
- Module naming: `emcip-*` (e.g., `emcip-tdlib-adapter`, `emcip-policy-engine`)
- Service ports: 9080-9087

**Infrastructure:**
- CI/CD: GitHub Actions (private repo, GitHub Flow)
- Container Registry: GitHub Container Registry
- Docker Base Image: `eclipse-temurin:25-jre` with multi-stage builds

**Persistence & Events:**
- DB Migrations: Liquibase
- Event Format: JSON (no Schema Registry initially)
- Kafka: Latest stable (3.6+), 3 partitions, replication factor 1

**Quality Gates:**
- Test Coverage: 80% (JaCoCo)
- Spotless: Check only
- Checkstyle: Warning only
- PMD: Medium priority

---

## 1. Inception & Foundation ✅ COMPLETE

### Milestones
- [x] Initialize monorepo (Maven parent POM, module structure)
- [x] CI/CD pipeline with Maven and GitHub Actions/Jenkins
- [x] Base Spring Boot 4 configuration for all services
- [x] TDLib adapter skeleton (Telegram client, login flow, event queue, connects as real Telegram user, listens in groups/channels/discussions)
- [x] Event backbone (Kafka broker, topic definition, initial events)

### Definition of Done
- [x] Monorepo with parent POM and modules in the repo
- [x] CI/CD builds run for all modules
- [x] Spring Boot 4 skeleton (WebFlux, Actuator, Security) in every service
- [x] TDLib adapter can connect to Telegram as a real user and receive initial updates from groups/channels/discussions
- [x] Kafka runs locally, topics are created, events are published

---

## 2. MVP Core Pipeline ✅ COMPLETE

### Milestones
- [x] Conversation Context Service (thread tracking, speaker roles)
- [x] Intent Classifier (rule-based, initial classifications)
- [x] Policy Engine skeleton (deterministic decision logic)
- [x] End-to-end message flow (Telegram [as real user] → Event → Classification → Persistence)
- [x] Integration tests for pipeline

### Definition of Done
- [x] Messages are received from Telegram (as real user), classified, and persisted
- [x] Initial policy decisions are made and logged
- [x] Integration tests cover main paths

---

## 3. Intelligence & Policy 🔄 IN PROGRESS

### Milestones
- [x] Policy Engine with deterministic rule evaluation (US-3.1.1)
- [x] Escalation paths and policy outcomes (US-3.1.2)
- [x] Policy engine event backbone integration (US-3.1.3)
- [x] LLM orchestrator (routing, prompt templates, model selection) (US-3.2.1)
- [ ] Integration of small models (intent, summaries) (US-3.2.2)
- [x] Cost control for LLM calls (US-3.2.3)
- [x] Policy layer as gatekeeper for AI responses (US-3.2.4)
- [ ] Extension of event backbone for AI events

### Definition of Done
- [x] Policy decisions are deterministic, persisted, and auditable
- [x] Policy actions are executed (BLOCK, RESPOND, ESCALATE, EXECUTE, REVIEW, ALLOW)
- [x] LLM orchestrator can address different models based on task type
- [x] AI responses are only generated after policy decision (RESPOND, ESCALATE, EXECUTE)
- [x] Prompt templates are versioned with priority-based selection
- [x] Costs for AI calls are logged with token usage and latency
- [ ] External LLM providers integrated (OpenAI, Anthropic, etc.)

---

## 4. Observability, Moderation & Audit

### Milestones
- [ ] Moderation service (toxicity filter, rule violations)
- [ ] Audit and observability service (logging, tracing, metrics via OpenTelemetry/Prometheus, covers all Telegram events)
- [ ] Complete audit logs for all decisions
- [ ] Admin API (JWT-secured, service-to-service auth)

### Definition of Done
- [ ] Moderation reliably filters risky content
- [ ] Audit logs are complete and traceable
- [ ] Prometheus metrics and OpenTelemetry traces available
- [ ] Admin API is secured and documented

---

## 5. Production Hardening & Admin

### Milestones
- [ ] Multi-tenancy (tenant capability, isolation, Telegram user separation)
- [ ] Performance tuning (load tests, latency optimization)
- [ ] Advanced policy logic (versioning, complex rules, Telegram-specific policies)
- [ ] Admin UI (optional, stretch goal)
- [ ] Operations documentation and ADRs

### Definition of Done
- [ ] Multi-tenancy is technically verifiable
- [ ] Load tests show acceptable performance
- [ ] Policy engine supports versioned rules
- [ ] Operations documentation is complete

---

## 6. Risk Spikes & Technical Risks

### Identified Risks
- TDLib integration (stability, authentication, real user session management)
- Kafka setup (operation, topic management)
- LLM costs and model selection
- Multi-tenancy complexity
- Service-to-service security

### Planned Spikes
- [ ] TDLib adapter: Test authentication and connection stability
- [ ] Kafka: Simulate topic management and error cases
- [ ] LLM: Cost estimation and model comparison
- [ ] Multi-tenancy: Evaluate isolation concepts
- [ ] Security: Prototype JWT and Vault integration

---

## Appendix: Checklists for Sprint Planning

- [ ] Build and start all modules locally
- [ ] CI/CD runs for all services
- [ ] Integration tests for all critical paths
- [ ] Documentation up to date (ADR, operations docs, API docs)

---

**Note:** This structure enables direct adoption into sprint planning. All milestones are technically verifiable and tailored for Java/Spring Boot/Kafka.
