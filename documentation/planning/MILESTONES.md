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

## 1. Inception & Foundation

### Milestones
- [ ] Initialize monorepo (Maven parent POM, module structure)
- [ ] CI/CD pipeline with Maven and GitHub Actions/Jenkins
- [ ] Base Spring Boot 4 configuration for all services
- [ ] TDLib adapter skeleton (Telegram client, login flow, event queue, connects as real Telegram user, listens in groups/channels/discussions)
- [ ] Event backbone (Kafka broker, topic definition, initial events)

### Definition of Done
- [ ] Monorepo with parent POM and modules in the repo
- [ ] CI/CD builds run for all modules
- [ ] Spring Boot 4 skeleton (WebFlux, Actuator, Security) in every service
- [ ] TDLib adapter can connect to Telegram as a real user and receive initial updates from groups/channels/discussions
- [ ] Kafka runs locally, topics are created, events are published

---

## 2. MVP Core Pipeline

### Milestones
- [ ] Conversation Context Service (thread tracking, speaker roles)
- [ ] Intent Classifier (rule-based, initial classifications)
- [ ] Policy Engine (deterministic decision logic)
- [ ] End-to-end message flow (Telegram [as real user] → Event → Policy → Response)
- [ ] Integration tests for pipeline

### Definition of Done
- [ ] Messages are received from Telegram (as real user), classified, and processed by the policy engine
- [ ] Initial policy decisions are made and logged
- [ ] Integration tests cover main paths

---

## 3. AI & Integration

### Milestones
- [ ] LLM orchestrator (routing, prompt templates, model selection, supports Telegram context)
- [ ] Integration of small models (intent, summaries)
- [ ] Policy layer as gatekeeper before AI responses and Telegram interactions
- [ ] Cost control for LLM calls
- [ ] Extension of event backbone for AI events

### Definition of Done
- [ ] LLM orchestrator can address different models and Telegram contexts
- [ ] AI responses are only generated after policy decision
- [ ] Prompt templates are versioned and tested
- [ ] Costs for AI calls are logged

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
