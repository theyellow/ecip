# Open Questions for ECIP Project

This document contains questions that need to be answered before implementing Phase 1 (Foundation & Infrastructure). These answers will help refine the documentation and ensure correct technical decisions.

---

## Phase 1 Critical Questions (Must Answer Before Starting)

### 1. Maven GAV Coordinates (Required for US-1.1.1)

| Question | Current State | Your Answer                     |
|----------|---------------|---------------------------------|
| What is the `groupId` for the parent POM? | Not defined | `io.emcip`                      |
| What is the `artifactId` for the parent POM? | "telegram-intelligence-starter" suggested | `community-intelligence-parent` |
| What is the initial `version`? | Not defined | `0.1.0-SNAPSHOT`                |
| What Java package prefix should be used? | Not defined | e.g., `io.emcip`                |

### 2. Repository & CI/CD (Required for US-1.1.2, US-1.1.3)

| Question | Options | Your Answer                                                                   |
|----------|---------|-------------------------------------------------------------------------------|
| Which Git hosting platform? |  / GitLab / Other | GitHub                                                                        |
| Which CI/CD tool? | GitHub Actions / Jenkins / GitLab CI | GitHub Actions                                                                |
| Should the repository be public or private? | Public / Private | private, for the moment a local feature branch only (perhaps with MRs on that |
| Preferred branching strategy? | Git Flow (main/develop/feature) / GitHub Flow (main/PR) / Trunk-based | GitHub Flow                                                                   |
| Do you have existing infrastructure for CI/CD? | Yes / No | not yet, but possibly in future                                               |
| If Jenkins: Where is it hosted? | On-premise / Cloud | -                                                                             |
| If GitHub Actions: Any specific runner requirements? | Standard / Self-hosted | standard                                                                      |

### 3. Docker & Container Registry (Required for US-1.2.2)

| Question | Options | Your Answer                                       |
|----------|---------|---------------------------------------------------|
| Which container registry? | Docker Hub / GitHub Container Registry / GitLab Registry / Private / None (local only) | GitHub Container Registry                         |
| If private registry: URL and credentials approach? | | don't know yet, have to look at my other projects |
| Base Docker image preference? | `eclipse-temurin:25-jre` / `amazoncorretto:25` / Other | eclipse-temurin:25-jre                            |
| Multi-stage builds required? | Yes (recommended) / No | Yes                                               |

### 4. Telegram API Credentials (Required for Phase 2 TDLib Integration)

| Question | Your Answer                               |
|----------|-------------------------------------------|
| Do you already have a Telegram `api_id` and `api_hash`? | No, but soon                              |
| If yes: Where are they stored? | -                                         |
| If no: Which phone number will be used for registration? | -                                         |
| Will 2FA be enabled on the Telegram account? | Unknown                        |
| How should credentials be managed in development? | Environment files  |

### 5. Database Migration Tool (Required for US-1.3.4)

| Question | Options | Your Answer                    |
|----------|---------|--------------------------------|
| Flyway or Liquibase? | Flyway / Liquibase | Liquibase                      |
| Reason for preference? | | knowledge from projects before |

### 6. Event Schema Format (Required for US-1.3.2)

| Question | Options | Your Answer                      |
|----------|---------|----------------------------------|
| Event serialization format? | JSON (simpler) / Avro (schema registry) / Protobuf | json                             |
| Will you use Confluent Schema Registry? | Yes / No / Later | don't know                       |
| If not using Schema Registry: How will schema evolution be handled? | | we have to look while developing |

### 7. Service Ports (Required for US-1.2.1, US-1.2.2)

| Service |  Your Answer | |
|---------|---------------------|-------------|
| telegram-tdlib-adapter | 9080 | |
| conversation-context | 9081 | |
| intent-classifier | 9082 | |
| policy-engine | 9083 | |
| llm-orchestrator | 9084 | |
| moderation-service | 9085 | |
| audit-service | 9086 | |
| admin-api | 9087 | |
| telegram-core (shared lib) | N/A |  |

### 8. Code Quality Thresholds (Required for US-1.1.4)

| Metric | Suggested | Your Answer  |
|--------|-----------|--------------|
| Minimum test coverage (JaCoCo)? | 70% / 80% | 80%          |
| Spotless enforcement? | Check only / Auto-format on build | check only   |
| Checkstyle max violations? | 0 / Warning only | warning only |
| PMD priority threshold? | High / Medium / Low | medium       |

### 9. Kafka Configuration (Required for US-1.3.1)

| Question | Options | Your Answer   |
|----------|---------|---------------|
| Kafka version? | 3.6+ (latest stable) / Specific version | latest stable |
| Number of partitions per topic (default)? | 3 / 6 / Other | 3             |
| Replication factor (local dev)? | 1 (single broker) | 1             |
| Initial topic list (beyond the core 3)? | |               |

---

## Phase 2-5 Strategic Questions (Answer Soon)

### 10. LLM Provider Strategy (Required for Phase 3)

| Question                               | Your Answer                              | |
|----------|----------------------------------------------------|------------------------------------------|
| Primary LLM provider? | Multiple                                           |                                          |
| Small model preference? | MiniMax 2.7                                        |                                          |
| Large model preference? |  Claude              |                                          |
| Cost control approach? | Per-call limits / Daily budget / Per-tenant limits | Daily budget and later Per-tenant limits |
| API key management strategy? | / Environment + Vault /      |                                          |

### 11. Security & Authentication (Required for Phase 4)

| Question | Options | Your Answer                       |
|----------|---------|-----------------------------------|
| JWT signing method? | HS256 (shared secret) / RS256 (key pair) | open                              |
| Service-to-service auth? | mTLS / JWT trust / Both | Both possible                     |
| Vault implementation? | HashiCorp Vault / AWS Secrets Manager / Azure Key Vault / None for dev | None                              |
| Admin user store? | In-DB / LDAP / OAuth2 / OIDC | In-DB, later Oauth2 with keycloak |

### 12. Multi-Tenancy Strategy (Required for Phase 5)

| Question | Options                                                      | Your Answer                      |
|----------|--------------------------------------------------------------|----------------------------------|
| Isolation strategy? | Schema per tenant / Row-level security / Database per tenant | Row-level security               |
| Expected number of tenants (initially)? | 1                                                            |                                  |
| Tenant identification method? | Subdomain / Header / JWT claim                               | don't know, open for suggestions |

### 13. Observability Backend (Required for Phase 4)

| Question | Options | Your Answer                                                             |
|----------|---------|-------------------------------------------------------------------------|
| Tracing backend? | Jaeger / Zipkin / Tempo / None for dev | None, later perhaps Jaeger                                              |
| Metrics storage? | Prometheus / VictoriaMetrics / CloudWatch / Other | prometheus                                                              |
| Log aggregation? | ELK / Loki / CloudWatch / Other / None for dev | None, later ELK                                                         |
| Dashboard tool? | Grafana / Built-in / Other | Build-in, later Grafana (pehaps a userstory should be created for that) |

---

## Quick Reference: Questions by Priority

### Must Answer Before Any Code (Blocking)
1. Maven GAV coordinates (Q1)
2. Java package prefix (Q1)
3. CI/CD platform choice (Q2)
4. Service ports (Q7)
5. Test coverage thresholds (Q8)

### Should Answer Before Phase 1 Complete
6. Docker registry details (Q3)
7. Database migration tool (Q5)
8. Event schema format (Q6)
9. Branching strategy (Q2)

### Can Answer Before Phase 2 Starts
10. Telegram API credentials (Q4)
11. LLM provider strategy (Q10)
12. Kafka configuration details (Q9)

### Can Answer Before Phase 4/5 Starts
13. Security approach (Q11)
14. Observability backend (Q13)
15. Multi-tenancy strategy (Q12)

---

## How to Use This Document

1. Fill in your answers in the "Your Answer" column
2. Return this document when requesting Phase 1 implementation
3. Any unanswered questions will be decided by the implementer with sensible defaults
4. Changes to answers after implementation starts may require refactoring

---

## Additional Context Questions (Nice to Have)

### Team & Process
- How many developers will work on this?
- What is the expected development velocity (sprints/weeks)?
- Are there any specific compliance requirements (GDPR, SOC2, etc.)? No
- Do you have existing development conventions to follow? No
### Infrastructure
- Will this run on-premises, cloud, or hybrid? On-premises first,cloud or hybrid later
- Preferred cloud provider (if any)? cheapest
- Existing Kafka/PostgreSQL infrastructure to integrate with? Used before, but new docker containers would be fine
- Kubernetes now or later? later 

### Integration
- Any existing systems ECIP needs to integrate with? No
- Slack/Teams notifications required? No
- Email integration for alerts? No

---

*Document generated for ECIP Phase 1 preparation*
