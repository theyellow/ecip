---
name: documentation-checklist
description: Checklist for updating documentation (adoc files + PlantUML diagrams) alongside any code or config change
triggers:
  - "commit"
  - "done"
  - "complete"
  - "finished"
  - "implementation"
  - "implement"
  - "feature"
  - "refactor"
---

# Documentation Checklist

**Rule: Every code or config change must include updates to relevant documentation. Documentation is part of the task, not a follow-up.**

Before marking any implementation task as complete, run through this checklist.

## Documentation File Map

### `documentation/architecture-guide.adoc`
**Scope:** System design — what the system is and how it works.

Update when you change:
- Services (add/remove/rename) → §2 Container Architecture (C2), service table
- Components within a service → §3 Component Details (C3), per-service subsection
- Kafka topics (add/remove) → §5.4 Kafka Topic Reference table
- Consumer groups → §5.3 Kafka Consumer Groups
- Data persistence approach → §6 Data Flows
- Architecture decisions → add/update ADR reference at bottom

### `documentation/developer-guide.adoc`
**Scope:** How to build, run, and work with the codebase.

Update when you change:
- Maven modules (add/remove) → §2 Module Structure table
- Kafka topics or schemas → §4 Kafka Topics Reference
- API endpoints → §5 Service APIs
- Entity/JPA patterns → §3.2 JPA Entities
- Test infrastructure → §6 Testing
- Dependencies or build steps → §1 Quick Start

### `documentation/docker-compose-guide.adoc`
**Scope:** Local development environment with Docker Compose.

Update when you change:
- Services in docker-compose.yml → §1 Infrastructure Overview, service count
- Ports → §5 Port Reference table
- Environment variables → §4 .env File Setup
- Profiles → §3 Profiles
- Health checks → §7 Health Checks
- Observability (Grafana/Loki/Prometheus) → §8 Observability

### `documentation/operations-guide.adoc`
**Scope:** Deploying, operating, and monitoring in production.

Update when you change:
- Docker images → §2.2 Image Overview table
- Helm chart values → §2 Kubernetes Deployment
- Rollout/restart procedures → §2.7 Rolling Out New Images
- Secrets → §2.3 Secrets
- Monitoring or alerting → monitoring sections

### PlantUML Diagrams (`documentation/diagrams/`)

| Diagram | Update when... |
|---------|---------------|
| `c1-context.puml` | External actors or system boundaries change |
| `c2-container.puml` | Services added/removed, inter-service relationships change, Kafka topics added |
| `c3-component.puml` | Internal components of any service change (overview diagram) |
| `c3-<service>.puml` | Components within a specific service change (detailed diagram) |
| `c4-event-flow.puml` | Event classes or schemas change |
| `c4-kafka-consumers.puml` | Kafka consumer classes or topic subscriptions change |
| `c4-code.puml` | Core code structure or domain model changes |
| `c4-policy-domain.puml` | Policy engine domain model changes |
| `kafka-topic-flow.puml` | Kafka topic routing or producer/consumer relationships change |
| `dataflow-audit-trail.puml` | Audit event flow changes |
| `dataflow-context-enrichment.puml` | Context enrichment pipeline changes |
| `deployment-kubernetes.puml` | K8s deployment topology changes |
| `deployment-local-docker.puml` | Docker Compose service topology changes |
| `sequence-*.puml` | Message lifecycle, auth flow, or error handling sequences change |

## Quick Decision Guide

Ask yourself: *"If another developer reads only the docs, would they know about this change?"*

- **Yes** → docs are fine
- **No** → update them now, before committing

## What NOT to document

- Internal refactors that don't change behavior, APIs, or architecture
- Test-only changes (unless test infrastructure patterns change)
- Spotless/formatting commits