# Enterprise Messenger Community Intelligence Platform
Extended Architecture & Implementation Guide

## Overview
This document extends the base architecture with implementation guidance, bootstrap instructions, and references a first milestone roadmap.


## Getting Started
Prerequisites:

- Java 25
- Maven 3.9+
- Docker (recommended)
- Telegram API credentials (api_id, api_hash)

---

### Step 1 — Create Monorepo

mkdir telegram-intelligence-starter
cd telegram-intelligence-starter

Create parent pom.xml:

```xml
<groupId>io.emcip</groupId>
<artifactId>community-intelligence-parent</artifactId>
<version>0.1.0-SNAPSHOT</version>
<packaging>pom</packaging>

<modules>
    <module>emcip-core</module>
    <module>emcip-tdlib-adapter</module>
    <module>emcip-conversation-context</module>
    <module>emcip-intent-classifier</module>
    <module>emcip-policy-engine</module>
    <module>emcip-llm-orchestrator</module>
    <module>emcip-moderation-service</module>
    <module>emcip-audit-service</module>
    <module>emcip-admin-api</module>
</modules>
```


---

### Step 2 — Base Spring Boot Setup
Each service:

-	Spring Boot 4
-	WebFlux
-	Actuator
-	Basic security config

---

### Step 3 — TDLib Adapter Skeleton
Core responsibilities:

-	Initialize TDLib client
-	Handle login flow
-	Subscribe to updates
-	Publish internal events

---

### Step 4 — Event Backbone

Recommended:

-	Apache Kafka (preferred)
-	RabbitMQ (alternative)

Define initial events:

-	MessageReceivedEvent
-	IntentClassifiedEvent
-	PolicyDecisionEvent

---

### Step 5 — Observability
-	OpenTelemetry
-	Prometheus
-	Structured logging (JSON)

---

### Step 6 — Security
-	Secret management (Vault)
-	JWT for admin APIs
-	Service-to-service authentication

---

## Initial Milestone Plan

See MilestonesDraft.md

## Recommended Early Decisions
-	Choose Kafka early (avoid later migration)
-	Define event schema upfront (versioned)
-	Keep policy engine deterministic (no hidden AI decisions)
-	Separate cost tracking from business logic

---

## Next Implementation Steps
1.	Initialize repository
2.	Define module boundaries
3.	Implement TDLib adapter first
4.	Establish event contracts
5.	Add observability from day one
