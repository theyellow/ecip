# EMCIP Red Team Report — Full Security Assessment

**Date:** 2026-06-27
**Scope:** Whole system
**Mode:** Read-only architectural and code review (no runtime exploitation)
**Authorization:** Explicit owner authorization for security review

---

## 1. Executive Summary

EMCIP is a well-architected event-driven microservices platform with **strong fundamentals** — clean pipeline decomposition, proper JWT authentication, tenant context propagation, DLQ handling, and CI/CD security scanning. However, the review uncovered **systemic security gaps** that collectively create a high-risk posture if deployed to production with untrusted users.

### Top 10 Risks (in order of severity)

1. **Cypher injection in Apache AGE** — `escapeString()` has a flawed replacement order; `sanitizeLabel()` is insufficient. User-reachable via Knowledge Engine REST endpoints. Allows arbitrary graph data exfiltration or deletion.

2. **10+ admin-api controllers missing `@PreAuthorize`** — Any authenticated user (including VIEWER) can create/delete policy rules, moderation rules, intent rules, flags, and trigger backfills. The RBAC system is defined but not enforced on most endpoints.

3. **Unauthenticated Kafka** — No SASL, no ACLs. Any pod can produce fake `policies.decisions` to approve content, or inject `responses.generated` to send arbitrary text to Telegram users.

4. **Prompt injection -> unvalidated LLM output in security path** — User text flows unescaped into LLM prompts. LLM responses drive RESPOND/ESCALATE/EXECUTE decisions with zero output validation.

5. **Single shared database identity** — All 8 services connect as user `emcip`. SQL injection or compromise of any service exposes all tables including `admin_users` and plaintext API keys in `llm_provider_config`.

6. **Inconsistent tenant isolation on Kafka consumers** — Missing `tenant_id` header causes some consumers to proceed with null tenant (unfiltered queries), while only moderation-service correctly rejects.

7. **No Kubernetes pod hardening** — No securityContext, no ServiceAccount scoping, no NetworkPolicy. Default SA token mounted in every pod.

8. **Native SQL queries bypass Hibernate tenant filter** — `PolicyDecisionRepository.updateSignalStatus()` and paginated queries use native SQL that doesn't include `WHERE tenant_id = ?`.

9. **No rate limiting anywhere** — No Bucket4j, no Resilience4j RateLimiter. LLM calls, admin CRUD, and knowledge search are all unbounded.

10. **Audit trail gaps** — Login failures, password changes, config modifications, user CRUD, and authorization failures are not audited. Audit records are mutable (no append-only constraint, no hash chaining).

### Overall Risk Posture: **HIGH** for production deployment. **ACCEPTABLE** for controlled development/staging with trusted users.

### Finding Summary: 37 findings total — 4 Critical, 13 High, 15 Medium, 5 Low/Info

---

## 2. Architecture Assessment (Phase 1)

### System Model

EMCIP is an event-driven microservices platform for Telegram community intelligence. 9 services + 1 React SPA share a single PostgreSQL 16 instance (with Apache AGE + pgvector extensions), communicate asynchronously via Kafka (10+ topics), and synchronously via REST (admin-api as gateway). LLM capabilities are provided via an internal LiteLLM proxy speaking the OpenAI-compatible API.

```
Trust Boundaries:

TB1: Browser <-> Ingress (TLS optional, JWT)
TB2: Ingress <-> Admin API (in-cluster HTTP, JWT validated)
TB3: Admin API <-> Backend Services (HTTP + X-Service-Token, same network)
TB4: Services <-> Kafka (unauthenticated, same network)
TB5: Services <-> PostgreSQL (password auth, single user, same network)
TB6: LLM Orchestrator <-> LiteLLM Proxy (HTTP Bearer or open, potentially cross-network)
TB7: Knowledge Engine <-> SearXNG (HTTP, external content)
TB8: TDLib Adapter <-> Telegram API (TDLib encrypted protocol, external)
```

### 2.1 Separation of Concerns

**Verdict: Generally well-designed, with two structural concerns**

**Strengths:**
- Clean pipeline decomposition: ingest -> classify -> decide -> respond -> audit. Each stage is a separate service consuming from the previous stage's Kafka topic.
- `emcip-core` provides shared primitives (events, tenant context, Kafka config) without leaking domain logic.
- Service data ownership is respected at the code level — no cross-service table reads in JPA repositories.

**Findings:**

| # | Finding | Evidence | Severity |
|---|---------|----------|----------|
| SoC-1 | **Admin API is a "god gateway"** — it proxies to all 7 other services, owns user/tenant/group data, handles JWT auth, simulates events, and manages AI config. It has 15+ controllers. Any compromise of admin-api gives full-system access. | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/` — 15 controller classes | MEDIUM |
| SoC-2 | **Shared database anti-pattern** — all 8 services read/write the same PostgreSQL instance (`emcip`) with the same user (`emcip`). Service isolation is only enforced by code convention (separate tables), not by DB-level access controls. A SQL injection in *any* service could read *all* tables. | `standard-deployments.yaml:40-63` — same `postgres-user`/`postgres-password` secret for every pod | HIGH |
| SoC-3 | **Bidirectional REST coupling** — Knowledge Engine calls LLM Orchestrator for extraction (`POST /api/analyse`), while LLM Orchestrator calls Knowledge Engine for enrichment (`POST /api/knowledge/search`). This creates a circular dependency and deadlock risk under load. | `emcip-knowledge-engine/.../LlmOrchestratorClient.java`, `emcip-llm-orchestrator/.../KnowledgeEngineClient.java` | MEDIUM |

### 2.2 Loose Coupling & Contracts

**Verdict: Solid Kafka fundamentals, but no schema registry and partial REST hardening**

**Strengths:**
- Events are defined as Java records in `emcip-core/EventSchemas.java` with semantic versioning (v1.0.0).
- `EventValidator` enforces required fields and version compatibility.
- DLQ with monitoring (`DeadLetterQueueConsumer` listening on `^.*\.dlq$`).
- Idempotent producers (`ENABLE_IDEMPOTENCE=true`, `ACKS=all`).
- Manual acknowledgment (`ENABLE_AUTO_COMMIT=false`) on all consumers.
- Resilience4j circuit breakers on all admin-api->service REST calls (50% failure threshold, 30s open, 10-call sliding window).

**Findings:**

| # | Finding | Evidence | Severity |
|---|---------|----------|----------|
| LC-1 | **No schema registry** — event contracts are Java records compiled into `emcip-core.jar`. Schema evolution requires recompiling and redeploying all consumers simultaneously. No runtime contract validation. A producer on v2 sending to a consumer still on v1 will throw `JacksonException` -> DLQ. | `EventSchemas.java` — records only, no Avro/Protobuf/JSON Schema | MEDIUM |
| LC-2 | **Kafka has no authentication or ACLs** — any pod on the internal network can produce to any topic or consume from any consumer group. A compromised service could inject fake `policies.decisions` events to approve malicious content. | Helm `kafka-topics.yaml` — no ACL config; `CommonKafkaConfig.java` — no `SASL_CONFIG` or `SECURITY_PROTOCOL` | HIGH |
| LC-3 | **Five "action" topics have no consumers** — `moderation.actions`, `responses.pending`, `escalation.human`, `commands.execute`, `review.pending` are defined in Helm but have zero `@KafkaListener` subscribers. Messages published to these topics accumulate silently. | `kafka-topic-flow.puml` labels them "No Consumers Yet"; grep confirms no listeners | LOW |
| LC-4 | **No timeout on Kafka consumer processing** — `MAX_POLL_RECORDS=500` with no `max.poll.interval.ms` override. If an LLM call inside `PolicyDecisionConsumer` hangs (LiteLLM proxy down), the consumer won't heartbeat and will be evicted from the group -> rebalance storm. | `CommonKafkaConfig.java:75` — only `MAX_POLL_RECORDS` set; LLM call in consumer thread is synchronous with no timeout | HIGH |
| LC-5 | **REST circuit breakers protect admin-api only** — pipeline services (intent-classifier, policy-engine, llm-orchestrator) call each other via Kafka and REST without Resilience4j. The LLM Orchestrator<->Knowledge Engine REST calls have no circuit breaker. | No `CircuitBreakerRegistry` in knowledge-engine or llm-orchestrator | MEDIUM |

### 2.3 Security Architecture Patterns

#### Defense in Depth — PARTIAL

| Layer | Status | Evidence |
|-------|--------|----------|
| Edge (Ingress) | Present | nginx ingress with optional TLS (disabled by default: `values.yaml:47`) |
| Application (JWT) | Present | HS256 JWT, 1h expiry, refresh rotation, bcrypt passwords, secret validated at startup (`JwtService.java:26-31`) |
| Service-to-service | **Weak** | Single shared `X-Service-Token` — all services use the same static token. Compromising one service = full access to all others. No mTLS. |
| Data tier | **Absent** | Single DB user, no row-level security, no column-level encryption. LLM API keys stored in plaintext in DB |
| Network | **Absent** | No NetworkPolicy, no ServiceAccount, no pod securityContext |

#### Zero Trust — NOT IMPLEMENTED

| Aspect | Status | Evidence |
|--------|--------|----------|
| Service identity | **Absent** | No mTLS, no SPIFFE/SPIRE, no per-service credentials. All services share one `X-Service-Token`. |
| Kafka auth | **Absent** | No SASL, no ACLs. Any pod can produce/consume any topic. |
| DB auth | **Absent** | Single `emcip` user for all services. No per-service DB users. |
| LiteLLM proxy | **Absent** | Bearer token or open access. No network restriction on who can call it. |

#### Least Privilege — PARTIALLY IMPLEMENTED

| Aspect | Status | Evidence |
|--------|--------|----------|
| RBAC (user-facing) | **Good** | 5 roles (ADMIN -> VIEWER) with 20+ granular permissions, `@PreAuthorize` enforcement (`RolePermissions.java`) |
| K8s RBAC | **Absent** | No ServiceAccount, no Role/RoleBinding in Helm templates. Pods use default SA. |
| DB privileges | **Absent** | All services use single superuser-equivalent account |
| Kafka ACLs | **Absent** | |

#### Secure by Default / Fail Closed — MIXED

| Aspect | Status | Evidence |
|--------|--------|----------|
| Default JWT secret | **Good** | `JwtService.java:26-31` — application refuses to start with default secret |
| Missing tenant header (HTTP) | **Good** | `TenantContextFilter.java:18-19` returns 400 if missing |
| Missing tenant header (Kafka) | **BAD** | Audit service saves with `null` tenant; intent-classifier and policy-engine proceed with `null` context; only moderation-service correctly skips |
| Actuator endpoints | **Open** | `SecurityConfig.java:44-45` — `/actuator/**` is `permitAll` |
| TDLib Adapter | **Open** | `permitAll` on all endpoints including `/auth/phone`, `/auth/code`, `/auth/password` |

#### Secrets Management — PARTIALLY IMPLEMENTED

| Secret | Location | Risk |
|--------|----------|------|
| JWT signing key | K8s Secret -> env var | **Good** (with startup validation) |
| Service token | K8s Secret -> env var | Acceptable (single shared token is the weakness, not storage) |
| DB password | K8s Secret -> env var | Acceptable for deployment; single-user is the real issue |
| LLM API key | **Plaintext in PostgreSQL** (`llm_provider_config.api_key VARCHAR(512)`) | **HIGH** — DB compromise exposes key |
| Vendor API keys | **Plaintext in PostgreSQL** (`vendor_api_keys` table) | **HIGH** — same concern |
| Telegram creds | K8s Secret -> env var | Acceptable |
| TruffleHog | CI/CD secret scanning active | **Good** |

### 2.4 Trust Boundaries & STRIDE Threat Model

#### TB3: Admin API <-> Backend Services (HIGHEST RISK)

| Threat | Assessment |
|--------|------------|
| **Spoofing** | **HIGH** — Single static `X-Service-Token` shared by all services. If leaked or extracted from any pod env, attacker impersonates any service. |
| **Tampering** | **HIGH** — HTTP (not HTTPS) between services. Man-in-the-middle on cluster network can modify requests. |
| **Repudiation** | MEDIUM — Audit service captures events but doesn't audit admin API proxy calls or config changes. |
| **Info Disclosure** | **HIGH** — All services share one DB user. Compromise of any service exposes all tables. API keys in plaintext in DB. |
| **DoS** | MEDIUM — No per-service rate limiting. A flood to admin-api cascades to all backends. |
| **Elevation** | **HIGH** — Service token grants `ROLE_SERVICE` which bypasses tenant context. |

#### TB4: Services <-> Kafka (HIGH RISK)

| Threat | Assessment |
|--------|------------|
| **Spoofing** | **CRITICAL** — No Kafka auth. Any pod can produce to `policies.decisions` with fabricated ALLOW decisions. |
| **Tampering** | **HIGH** — Messages are unsigned JSON. A compromised pod can modify and re-produce events. |
| **Info Disclosure** | **HIGH** — Any pod can consume from any topic (including `telegram.raw.messages` with user content). |
| **Elevation** | **CRITICAL** — Producing to `responses.generated` sends arbitrary text to Telegram users via TDLib. |

#### TB6: LLM Orchestrator <-> LiteLLM Proxy (HIGH RISK)

| Threat | Assessment |
|--------|------------|
| **Tampering** | **CRITICAL** — User content flows directly into LLM prompts without escaping. Knowledge base content also injected unescaped. |
| **Info Disclosure** | **HIGH** — User messages (potential PII) sent to LLM without redaction. |
| **DoS** | **HIGH** — No rate limiting on LLM calls. Cost tracking is reactive, not preventive. |
| **Elevation** | **HIGH** — LLM responses used for security decisions without output validation. |

### Architecture Drift (Docs vs Code)

| Documented Claim | Actual Implementation | Impact |
|-----------------|----------------------|--------|
| Resilience4j on admin-api with "retry -> circuit breaker -> fallback" | Confirmed in admin-api. **Not present in pipeline services** (llm-orchestrator, knowledge-engine REST clients) | Pipeline REST calls have no resilience |
| `IntegrityHash` entity in audit trail (hash-chaining for tamper detection) | **Not implemented.** No `IntegrityHash` entity in code. | Audit integrity is asserted but not enforced |
| "Service isolation: no cross-service database access" | Correct at code level, but **all services use the same DB credentials** | Isolation is convention, not enforcement |
| "escalation.human" and "moderation.actions" as active topics | **No consumers exist** for these topics | Dead code paths |
| BCrypt rounds=12 | `new BCryptPasswordEncoder()` — default is 10 rounds | Minor discrepancy |

---

## 3. Findings Table (Phase 2 — Deep Application Review)

### CRITICAL

| ID | Title | Category | Location | Evidence | Attack Scenario | Impact | Remediation | Confidence |
|----|-------|----------|----------|----------|-----------------|--------|-------------|------------|
| RT-001 | Cypher injection via flawed `escapeString()` | OWASP A03 Injection | `emcip-knowledge-engine/.../AgeGraphRepository.java:95-106` | `escapeString()` replaces `'` before `\`, so `\'` becomes `\\'` which breaks out of the string literal. `String.format()` interpolates result directly into Cypher. | 1. Attacker calls `GET /api/knowledge/graph/node/{id}/neighbors?relationshipType=PAYLOAD` via admin-api proxy. 2. For `findByLabelAndType()`, label goes through broken `escapeString()`. 3. Injected Cypher executes via `ag_catalog.cypher()`. | Arbitrary graph data read/write/delete. Potential to escalate to SQL via `$$` quoting bypass. | Fix replacement order: escape `\` first, then `'`. Better: use parameterized Cypher via AGE stored procedures. Add `@Pattern` validation on all graph inputs. | Confirmed |
| RT-002 | Unescaped user content in LLM prompts | LLM01 Prompt Injection | `emcip-llm-orchestrator/.../LlmCallService.java:113` | `renderedUser.replace("{{content}}", enrichedContent)` — no escaping, no boundary markers, no input sanitization. | 1. User sends Telegram message containing prompt injection payload. 2. Message flows through pipeline to `PolicyDecisionConsumer`. 3. `extractUserContent()` pulls raw text. 4. Text substituted into prompt template verbatim. 5. LLM follows injected instructions. | Bypass moderation, generate harmful responses, exfiltrate system prompt, manipulate escalation decisions. | Wrap user content in clear delimiters. Validate LLM output against expected schema. Consider separate safety-classification LLM call. | Confirmed |
| RT-003 | LLM output used in security decisions without validation | LLM02 Insecure Output Handling | `emcip-llm-orchestrator/.../PolicyDecisionConsumer.java:134-157` | `result.content()` published directly to `responses.generated` Kafka topic. No length check, format validation, toxicity re-check, or schema enforcement. | 1. Prompt injection causes LLM to generate malicious response. 2. Response published to `responses.generated`. 3. TDLib adapter sends response text to Telegram user. 4. For EXECUTE task type, LLM output validates commands. | Arbitrary text sent to Telegram users. Command validation bypass. XSS if rendered in admin UI. | Add response validator: max length, format check, toxicity re-scan. Never use LLM output for command execution without human approval. | Confirmed |
| RT-004 | 10+ controllers missing `@PreAuthorize` | API5 BFLA | `emcip-admin-api/.../controller/` — GroupProfileController, ModerationRuleController, PolicyRuleController, IntentRuleController, IntentSignalConfigController, FlagController, AuditController, BackfillProxyController, DocumentIngestionProxyController, KnowledgeSearchProxyController, ResearchProxyController, SimulateController | These controllers have no `@PreAuthorize` annotations despite `RolePermissions.java` defining granular permissions. Contrast with `UserManagementController` and `TenantController` which do have them. | 1. Authenticate as VIEWER (lowest role). 2. `POST /api/policy-rules` — create rule that ALLOWs all messages. 3. `DELETE /api/moderation-rules/{id}` — delete toxicity filters. 4. `POST /api/flags/{id}/reply` — send Telegram messages as operator. | Complete bypass of RBAC. Any authenticated user can modify security-critical configuration. | Add `@PreAuthorize("hasAuthority('..._WRITE')")` to all mutating endpoints, `@PreAuthorize("hasAuthority('..._READ')")` to all read endpoints. | Confirmed |

### HIGH

| ID | Title | Category | Location | Evidence | Attack Scenario | Impact | Remediation | Confidence |
|----|-------|----------|----------|----------|-----------------|--------|-------------|------------|
| RT-005 | Unauthenticated Kafka — no SASL/ACLs | Architecture | `helm/emcip/templates/infra/kafka.yaml` — `tls: false`, no SASL; `CommonKafkaConfig.java` — no `SECURITY_PROTOCOL` | Kafka listener is `type: internal, tls: false`. No `SASL_JAAS_CONFIG`. | 1. Compromise any pod. 2. Connect to Kafka on port 9092. 3. Produce to `policies.decisions` with `decision: ALLOW`. 4. Produce to `responses.generated` to send arbitrary text to Telegram. | Full pipeline bypass. Arbitrary message injection. Content moderation defeat. | Enable Kafka SASL/SCRAM + per-service credentials. Add ACLs. Enable TLS. | Confirmed |
| RT-006 | Shared database user for all services | Architecture | `standard-deployments.yaml:40-63` — same `postgres-user` secret for all pods | All services connect as `emcip`. No `GRANT`/`REVOKE` in any migration. | 1. Exploit Cypher injection (RT-001) to pivot to SQL. 2. `SELECT * FROM admin_users`. 3. `SELECT api_key FROM llm_provider_config`. | Full database compromise from any single service vulnerability. | Create per-service PostgreSQL roles with `GRANT` restricted to owned tables. Encrypt sensitive columns. | Confirmed |
| RT-007 | Inconsistent tenant fail-open on Kafka consumers | Multi-tenancy | `AuditEventConsumer.java:173-178` (saves null), `TelegramMessageConsumer.java:50-52` (proceeds null), vs `PolicyDecisionConsumer.java:48-50` in moderation-service (correctly skips) | When `tenant_id` Kafka header is missing, most consumers proceed with null tenant context. Hibernate `@Filter` is not activated. | 1. Produce Kafka message without `tenant_id` header. 2. Intent classifier processes without tenant filter. 3. Policy engine evaluates against ALL tenants' rules. | Cross-tenant data leakage. Policy rules from wrong tenant applied. | Enforce fail-closed: reject messages without `tenant_id` header. Add validation in `TenantAwareKafkaSupport.bindTenantFromRecord()`. | Confirmed |
| RT-008 | Native SQL bypasses Hibernate tenant filter | API1 BOLA | `PolicyDecisionRepository.java` — `updateSignalStatus()` native query; `PolicyDecisionController.java:38-48` — `getById()` without tenant check | `@Query(nativeQuery=true)` doesn't trigger Hibernate `@Filter`. `findById()` from Spring Data also bypasses filters. | 1. Authenticate as TENANT_ADMIN for Tenant A. 2. `GET /api/policy-decisions/{tenantB_decision_id}`. 3. `PUT /api/policy-decisions/{tenantB_decision_id}`. | Cross-tenant data access and modification. | Add `AND tenant_id = ?` to all native queries. Use `findByIdAndTenantId()`. | Confirmed |
| RT-009 | Indirect prompt injection via knowledge base | LLM01 Prompt Injection | `KnowledgeContextEnricherService.java:42-44` | Retrieved documents appended to LLM context without escaping: `sb.append(result.document().content())`. | 1. Ingest document containing injection payload. 2. User query matches by cosine similarity. 3. Document content injected into LLM context. | LLM manipulation via poisoned knowledge base. Moderation bypass. | Mark knowledge content with clear source boundaries. Scan ingested documents for injection patterns. | Confirmed |
| RT-010 | No JWT revocation mechanism | OWASP A07 | `JwtService.java` — no blacklist, no JTI tracking | JWT valid for 60 minutes. Only refresh tokens can be revoked. No `jti` claim checked. | 1. Admin changes user role from ADMIN to VIEWER. 2. User still has up to 60 min of valid ADMIN JWT. | Delayed revocation. Compromised or demoted users retain access for up to 1 hour. | Add `jti` claim. Maintain invalidation list (Redis with TTL). Check in `JwtAuthenticationFilter`. | Confirmed |
| RT-011 | `TelegramAccountService.getById()` — no tenant check (IDOR) | API1 BOLA | `emcip-admin-api/.../TelegramAccountService.java:75-82` | `findAll()` respects tenant context, but `getById(UUID id)` calls `repository.findById(id)` without tenant filtering. | 1. TENANT_ADMIN of Tenant A guesses UUID of Tenant B's account. 2. `GET /api/telegram/accounts/{id}/status` returns Tenant B's info. | Cross-tenant information disclosure of Telegram account details. | Use `findByIdAndTenantId()`. | Confirmed |
| RT-012 | No Kubernetes pod hardening | Platform | `standard-deployments.yaml:23-95` | No `securityContext` block. No `runAsNonRoot`, `readOnlyRootFilesystem`, `capabilities.drop: [ALL]`. Default ServiceAccount used. | 1. Exploit any RCE. 2. Container runs as root. 3. Read SA token. 4. K8s API access to read Secrets, spawn pods. | Full cluster compromise from single pod RCE. | Add securityContext. Create per-service ServiceAccounts with minimal RBAC. Add default-deny NetworkPolicy. | Confirmed |
| RT-013 | Plaintext API keys in database | OWASP A02 | `LlmProviderConfig.java:48-49`, `007-create-llm-provider-config.xml:21` | `api_key VARCHAR(512)` — no encryption. Controller masks in responses but entity stores plaintext. | 1. Exploit RT-006 or RT-001 to access DB. 2. `SELECT api_key FROM llm_provider_config`. | LLM/vendor API key theft. Financial impact from unauthorized usage. | Encrypt at column level (pgcrypto) or use external secrets manager. | Confirmed |
| RT-014 | No rate limiting on any endpoint | API4 | All controllers across all services | Grep for `@RateLimit`, `RateLimiter`, `Bucket4j` returns zero results. | 1. Script sends 10,000 requests/sec to `/api/policy-rules` POST. 2. Or trigger unlimited LLM calls via pipeline. | DoS, cost explosion (LLM calls), data enumeration. | Add Resilience4j RateLimiter or Bucket4j. Per-tenant + per-user limits. | Confirmed |
| RT-015 | Actuator endpoints open to all | OWASP A01 | `SecurityConfig.java:44-45` | `.pathMatchers("/actuator/**").permitAll()` | 1. No authentication needed. 2. `GET /actuator/metrics` — internal data. 3. If `env`/`heapdump` enabled, full secret disclosure. | Information disclosure. Potential secret leakage. | Restrict to health+readiness for probes. Move others behind `ROLE_ADMIN`. | Confirmed |
| RT-016 | No SSL/TLS on database connections | OWASP A02 | All `application.yml` — JDBC/R2DBC URLs without `sslmode=require` | All connections: `jdbc:postgresql://localhost:14005/emcip` — no SSL params. | Network sniffing exposes credentials and query data. | Credential interception, data exfiltration. | Add `?sslmode=require` to all JDBC URLs. Configure R2DBC SSL. | Confirmed |
| RT-017 | Unaudited security operations | OWASP A09 | `AuthService.java:27-71`, all admin-api controllers | Login failures, password changes, user CRUD, role changes, config modifications, tenant operations — none publish audit events. | Insider modifies policy rules, changes LLM provider, creates admin user — no audit trail. | No forensic evidence. Compliance failure. | Publish audit events for all state-changing admin operations. Log failed login attempts with IP. | Confirmed |

### MEDIUM

| ID | Title | Category | Location | Evidence | Remediation | Confidence |
|----|-------|----------|----------|----------|-------------|------------|
| RT-018 | Service token timing attack | OWASP A07 | `ServiceTokenAuthenticationFilter.java:38` | `configuredServiceToken.equals(serviceToken)` — not constant-time. | Use `MessageDigest.isEqual()`. | Confirmed |
| RT-019 | Service token not validated non-default in admin-api | OWASP A07 | `ServiceTokenAuthenticationFilter.java:27` | Default `internal-service-token`. TDLib has `@PostConstruct` validation; admin-api does not. | Add `@PostConstruct` validation. | Confirmed |
| RT-020 | `ROLE_SERVICE` bypasses all permission checks | API5 BFLA | `ServiceTokenAuthenticationFilter.java:44-46` | Service token grants `ROLE_SERVICE` not in `RolePermissions.java`. No path restriction. | Restrict service token to specific paths (`/api/internal/**`). | Confirmed |
| RT-021 | Unvalidated `relationshipType` param enables Cypher injection | OWASP A03 | `KnowledgeSearchController.java:54-60` | `@RequestParam String relationshipType` — no `@Pattern`, `@Size`, or `@NotBlank`. | Add `@Pattern(regexp = "[a-zA-Z_]{1,100}")` validation. | Confirmed |
| RT-022 | Property key injection in graph node creation | OWASP A03 | `AgeGraphRepository.java:333-373` | `buildPropertiesJson()` uses `entry.getKey()` without sanitization. | Apply `sanitizeLabel()` to property keys. | Confirmed |
| RT-023 | Mass assignment — no `@JsonIgnore` on sensitive fields | API6 | `PolicyRuleConfig.java`, `PolicyDecision.java`, `LlmProviderConfig.java` | `tenantId`, `id`, `createdAt`, `version` all mass-assignable. Controllers defensively overwrite some but not all. | Add `@JsonIgnore` to sensitive fields. Use separate request DTOs. | Confirmed |
| RT-024 | Missing security headers | OWASP A05 | `SecurityConfig.java` | Only CORS configured. No CSP, HSTS, X-Content-Type-Options, X-Frame-Options. | Add `ServerHttpSecurity.headers()` config or WebFilter. | Confirmed |
| RT-025 | Bidirectional REST coupling — deadlock risk | Architecture | `KnowledgeEngineClient.java`, `LlmOrchestratorClient.java` | KE calls LLM-O for extraction; LLM-O calls KE for enrichment. Under load, both exhaust connection pools. | Break cycle: use Kafka for one direction, or add circuit breaker + timeout on both. | Confirmed |
| RT-026 | `UserManagementService.update()` — no cross-tenant check | API1 BOLA | `UserManagementService.java:52-79` | `findById(id)` without tenant validation. TENANT_ADMIN can modify users of other tenants. Can also set `req.getTenantId()` to reassign. | Validate `user.getTenantId() == caller.getTenantId()`. Restrict tenant reassignment to ADMIN only. | Confirmed |
| RT-027 | Audit trail not tamper-resistant | OWASP A09 | `AuditService.java`, `AuditEventEntity.java` | Standard PostgreSQL table. No append-only constraint, no hash chaining. Docs claim `IntegrityHash` entity but it doesn't exist. | Add database trigger preventing UPDATE/DELETE. Implement hash chaining. | Confirmed |
| RT-028 | 10 npm vulnerabilities (1 critical, 3 high) | Supply Chain | `emcip-admin-ui/src/main/frontend/package.json` | `npm audit` reports vulnerabilities in axios (critical), esbuild, form-data, ws (high), react-router (moderate). | Run `npm audit fix`. Update react-router-dom, vite, vitest. | Confirmed |
| RT-029 | No CSP on admin-ui | OWASP A05 | `emcip-admin-ui` — no CSP meta tag in `index.html`, no Spring Security CSP header | If XSS were found, no CSP to limit damage. | Add CSP header: `default-src 'self'; script-src 'self'; style-src 'self'`. | Confirmed |
| RT-030 | Backup files unencrypted | OWASP A02 | `scripts/db/backup.sh:1-24` | `pg_dump` output is gzip-compressed but not encrypted. Password via `PGPASSWORD` env var. | Encrypt backups with GPG or age. Use `.pgpass` file. | Confirmed |
| RT-031 | BCrypt uses default 10 rounds (docs claim 12) | OWASP A02 | `SecurityConfig.java:75` | `new BCryptPasswordEncoder()` — default is 10. | Use `new BCryptPasswordEncoder(12)`. | Confirmed |
| RT-032 | Ingress TLS disabled by default | OWASP A02 | `helm/emcip/values.yaml:47` | `tls.enabled: false`. Self-signed issuer even when enabled. | Enable TLS in production with real CA (Let's Encrypt). | Confirmed |

### LOW / INFO

| ID | Title | Category | Location | Remediation | Confidence |
|----|-------|----------|----------|-------------|------------|
| RT-033 | 5 Kafka topics with no consumers | Architecture | `kafka-topic-flow.puml` — moderation.actions, responses.pending, escalation.human, commands.execute, review.pending | Remove unused topics or document as roadmap items. | Confirmed |
| RT-034 | Docker base images not pinned by digest | Supply Chain | All Dockerfiles — `eclipse-temurin:21-jre` (tag, not digest) | Pin by digest: `eclipse-temurin:21-jre@sha256:...` | Confirmed |
| RT-035 | Swagger UI exposed in all services | Info Disclosure | All `application.yml` | Disable in production or gate behind auth. | Confirmed |
| RT-036 | 100% trace sampling includes auth operations | OWASP A09 | All `application.yml` — `sampling.probability: 1.0` | Reduce to 10-50% in production. | Confirmed |
| RT-037 | `intent-classifier` hardcodes DB credentials | OWASP A07 | `emcip-intent-classifier/application.yml:9-10` | Use `${SPRING_DATASOURCE_PASSWORD:emcip}` pattern. | Confirmed |

---

## 4. Quick Wins vs Structural Changes

### Quick Wins (< 1 day each, no architectural change)

| Fix | Findings Addressed | Effort |
|-----|-------------------|--------|
| Add `@PreAuthorize` to all 12 controllers missing them | RT-004 | 2 hours |
| Fix `escapeString()` replacement order (escape `\` before `'`) | RT-001 (partial) | 15 min |
| Add `@Pattern`/`@Size` validation on graph query params | RT-021 | 30 min |
| Sanitize property keys in `buildPropertiesJson()` | RT-022 | 15 min |
| Add `@PostConstruct` service token validation to admin-api | RT-019 | 15 min |
| Use `MessageDigest.isEqual()` for token comparison | RT-018 | 30 min |
| Add `@JsonIgnore` to sensitive entity fields | RT-023 | 1 hour |
| Restrict actuator to health+readiness only | RT-015 | 15 min |
| Fix BCrypt rounds to 12 | RT-031 | 5 min |
| Add security headers (CSP, HSTS, X-Frame-Options) | RT-024, RT-029 | 1 hour |
| Fix `intent-classifier` to use env vars for DB creds | RT-037 | 10 min |
| Update npm dependencies (`npm audit fix`) | RT-028 | 30 min |
| Disable Swagger UI in production profile | RT-035 | 15 min |
| Add `AND tenant_id = ?` to native SQL queries | RT-008 (partial) | 1 hour |
| Use `findByIdAndTenantId()` in `TelegramAccountService` | RT-011 | 30 min |
| Add tenant validation in `UserManagementService.update()` | RT-026 | 30 min |

### Structural Changes (require design decisions, multi-day)

| Change | Findings Addressed | Effort | Notes |
|--------|-------------------|--------|-------|
| Parameterized Cypher queries (replace `String.format`) | RT-001, RT-021, RT-022 | 3-5 days | Requires AGE stored procedure approach or OGM |
| Kafka SASL/ACLs + per-service credentials | RT-005, RT-007 | 3-5 days | Strimzi supports SASL/SCRAM; need credential per service |
| Per-service PostgreSQL users + GRANT restrictions | RT-006 | 2-3 days | New Liquibase migrations for role creation |
| LLM prompt injection defense (boundary markers + output validation) | RT-002, RT-003, RT-009 | 3-5 days | Requires prompt engineering + response validator service |
| JWT revocation (Redis blacklist with TTL) | RT-010 | 2-3 days | New Redis dependency or in-memory cache |
| Tenant fail-closed enforcement in Kafka consumers | RT-007 | 1-2 days | Update `TenantAwareKafkaSupport` + all consumers |
| Kubernetes pod hardening (securityContext, ServiceAccount, NetworkPolicy) | RT-012 | 2-3 days | Helm template changes + testing |
| Column-level encryption for API keys | RT-013 | 2-3 days | pgcrypto or application-level encryption |
| Rate limiting framework | RT-014 | 2-3 days | Bucket4j or Resilience4j RateLimiter |
| Audit event publishing for admin operations | RT-017 | 3-5 days | New audit events + Kafka producer in admin-api |
| Audit trail tamper resistance (hash chaining + write-once) | RT-027 | 2-3 days | DB trigger + IntegrityHash entity |
| Break KE<->LLM-O bidirectional coupling | RT-025 | 2-3 days | Use Kafka for one direction |
| Database SSL/TLS | RT-016 | 1 day | Certificate setup + connection string changes |
| Ingress TLS with real CA | RT-032 | 1 day | cert-manager + Let's Encrypt issuer |

---

## 5. Coverage Notes

### What was reviewed
- All `.adoc` documentation (excluding `archive/` and `planning/`)
- All 25 PlantUML diagrams
- All 13 Maven modules (pom.xml, application.yml, source code)
- All REST controllers across all services
- All Kafka consumers and producers
- All security filters and configuration classes
- All entity classes and repositories
- All Liquibase migration files
- React frontend (components, auth, API client, dependencies)
- Helm chart templates and values
- All Dockerfiles
- CI/CD workflows (GitHub Actions)
- Docker Compose configuration

### What could not be fully verified (needs manual check)
- **Runtime behavior of Hibernate `@Filter`** — confirmed via code review that `TenantFilterAspect` enables the filter, but could not verify firing on every query path without integration tests
- **Actual actuator endpoints enabled** — `management.endpoints.web.exposure.include: health,info,metrics,prometheus` is configured, but Spring Boot 4 default inclusions may differ
- **LiteLLM proxy configuration** — proxy runs externally; its logging, rate limiting, and authentication config not in this repo
- **Kubernetes runtime state** — only Helm templates reviewed, not actual deployed state (RBAC may exist via other tooling)
- **Policy Engine decision cache** — diagrams show Caffeine cache but presence not confirmed in code
- **Frontend XSS** — no `dangerouslySetInnerHTML` found, but LLM response rendering should be verified with actual payloads
- **SearXNG integration security** — Knowledge Engine calls SearXNG for web search; URL validation and SSRF protection not deeply audited
- **Telegram session security** — TDLib session files on disk; persistence and access control not verified

### Areas not in scope
- `documentation/archive/` and `documentation/planning/` (per instructions)
- Penetration testing / runtime exploitation
- Load/performance testing
- Third-party dependency deep audit (only surface-level version check)

### Positive Findings (things done well)
- Clean event-driven pipeline with well-defined topic contracts
- Idempotent Kafka producers with DLQ monitoring
- JWT secret validated non-default at startup
- Refresh token rotation with SHA-256 hashing
- Granular RBAC with 5 roles and `@PreAuthorize` enforcement (where applied)
- TenantContextFilter correctly rejects missing tenant on HTTP (servlet path)
- Resilience4j circuit breakers on admin-api gateway calls
- Secret scanning (TruffleHog) in CI/CD
- Trivy image scanning in CI/CD
- Parameterized SQL queries throughout — no SQL injection vectors found
- Non-root user in all Dockerfiles
- Multi-stage Docker builds (build tools not in runtime images)
- No `dangerouslySetInnerHTML` in React frontend
- JWT stored in sessionStorage (not localStorage)
- Structured JSON logging (logstash format)
- No sensitive data (tokens, passwords, prompts) found in log statements
