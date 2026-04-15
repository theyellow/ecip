# Enterprise Messenger Community Intelligence Platform – Domain & Functional Concept

## 1. Project Overview

**Internal Name:** Messenger Community Intelligence Platform

**Short Description:**
An enterprise-grade, microservice-based platform built on Java 25 and Spring Boot 4 that analyzes Telegram groups, channels, and discussion threads in real time, detects communication contexts, and reacts based on rules. Unlike traditional bots, the system is TDLib-first, operating as a full Telegram client. The Bot API is an optional additional channel.

**Vision:**
A secure, auditable, and scalable platform that understands Telegram communication, supports contextually, and acts in a controlled manner without handing over control to AI.

**Mission:**
Automatically analyze communities, provide targeted support, and moderate them—with traceable decisions and strict policy logic.

**Success Criteria:**
- Relevant responses instead of spam
- High domain precision with low model costs
- Clean auditability and observability
- Extendable to additional messenger/community platforms

---

## 2. Functional Objectives

The platform acts as a context-sensitive communication assistant with four operating modes:
1. **React:** On direct mention
2. **Summarize:** When threads become confusing
3. **Moderate:** On rule violations
4. **Observe:** When no intervention is required

**Goals:**
- Identify speaker roles and topics
- Semantically analyze and cluster discussions
- Make policy-driven decisions
- Use AI selectively and transparently

---

## 3. Technical Guidelines

**Base Stack:**
- Java 25
- Spring Boot 4 (Security, WebFlux, Data, Actuator)
- Maven as build system
- TDLib as primary Telegram client

**Architectural Principles:**
- Event-driven first
- Clear separation of ingestion, context, AI, policy, and audit
- TDLib-first; Bot API only as supplement
- Every response passes through policy and moderation logic
- Full auditability and observability

**Non-Functional Requirements:**
- High traceability
- Multi-tenancy
- Low latency & retry strategies
- Cost control for LLM calls
- Secure token/secret management
- Complete logs, metrics, and traces

---

## 4. System Architecture

**Simplified Architecture Diagram:**

Telegram / TDLib → tdlib-adapter/gateway → conversation-context-service → intent-classification-service → policy-engine-service → llm-orchestration-service → moderation + audit + metrics

**Microservices:**
- telegram-tdlib-adapter: TDLib integration, session & update queue
- conversation-context-service: threads, speaker roles, history
- intent-classification-service: semantic classification
- policy-engine-service: decision and escalation
- llm-orchestration-service: model routing (small/large)
- knowledge-service: topic clusters, FAQs, rules
- moderation-service: risk/toxicity filtering
- audit-observability-service: logging, tracing, cost tracking
- admin-service: rules, group profiles, approvals

---

## 5. Maven Setup & Module Structure

**Recommended Plugins:**
- spotless-maven-plugin (code formatting)
- sortpom-maven-plugin (POM order)
- maven-enforcer-plugin (version rules)
- maven-surefire-plugin (unit tests)
- maven-failsafe-plugin (integration tests)
- jacoco-maven-plugin (test coverage)
- optional: checkstyle / pmd

**Maven Coordinates:**
- `groupId`: `io.emcip`
- `artifactId`: `community-intelligence-parent`
- `version`: `0.1.0-SNAPSHOT`
- `package`: `io.emcip`

**Repository Structure:**
```
community-intelligence/
  - pom.xml (parent POM: io.emcip:community-intelligence-parent:0.1.0-SNAPSHOT)
  - README.md
  - docs/
    - architecture.adoc
    - adr/
    - sequence-diagrams/
    - threat-model.md
  - emcip-core/                    (shared library, no port)
  - emcip-tdlib-adapter/             (port 9080)
  - emcip-conversation-context/      (port 9081)
  - emcip-intent-classifier/         (port 9082)
  - emcip-policy-engine/             (port 9083)
  - emcip-llm-orchestrator/          (port 9084)
  - emcip-moderation-service/        (port 9085)
  - emcip-audit-service/             (port 9086)
  - emcip-admin-api/                 (port 9087)
```

---

## 6. Data and Event Flow

1. TDLib receives updates
2. Adapter normalizes, authenticates, and queues events
3. Context service enriches group/thread information
4. Intent service classifies messages
5. Policy engine decides: react, wait, ignore
6. LLM orchestrator invokes models if needed
7. Moderation checks final safety
8. Response is sent via TDLib
9. Audit service logs decisions, costs, and metrics

---

## 7. AI Strategy
- Small model: intent, short summaries, labels
- Large model: complex discussions, sensitive cases
- Policy layer: final authority before external response

**Advantage:** Cost control + escalation only when necessary

---

## 8. Architecture Decisions (ADRs)
- ADR-001: TDLib-first instead of Bot API-first
- ADR-002: Spring Boot 4 as runtime base
- ADR-003: Java 25 as standard
- ADR-004: Event-driven communication
- ADR-005: Model routing instead of single-model approach
- ADR-006: Policy engine before every external response

---

## 9. Next Steps
1. Create Maven monorepo with parent POM and modules
2. Develop TDLib adapter skeleton
3. Provide initial Spring Boot 4 base configuration
4. Create Mermaid diagrams for architecture and data flow
5. Write ADRs and operational documentation

---

## 10. Sources & Further Reading
- [TDLib Getting Started](https://core.telegram.org/tdlib/getting-started)
- [Telegram APIs](https://core.telegram.org)
- [TDLib Docs](https://core.telegram.org/tdlib/docs/)
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [MARGO Spring Boot 4 Migration](https://www.margo.com/en/home/spring-boot-4-migration-guide/)

---

## 11. Business Value & Stakeholder Benefits
- Enables secure, compliant, and scalable community management for enterprises
- Reduces manual moderation effort and risk of spam/toxicity
- Ensures traceability and auditability for all automated actions
- Flexible for future expansion to other messengers and advanced analytics

---

## 12. Glossary
- **TDLib:** Telegram Database Library, official Telegram client library
- **LLM:** Large Language Model (e.g., GPT, Llama)
- **Policy Engine:** Service for deterministic, auditable decision logic
- **Event Backbone:** Kafka-based event-driven communication layer
- **ADR:** Architecture Decision Record

---

