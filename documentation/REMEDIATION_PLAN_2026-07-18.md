# EMCIP Remediation Plan — 2026-07-18

> **⚠️ SUPERSEDED (2026-07-22)** by `documentation/ROADMAP.md`, which absorbs this plan into a
> solo-optimized, dependency-ordered sequence (P0–P6). The 5-parallel-stream structure below assumes
> a 5-developer team and no longer reflects execution reality. Kept for its per-finding tactical detail;
> live task status is tracked in `docs/superpowers/BACKLOG.md §0`.
>
> Based on verified findings from REVIEW Round 2 and Red Team Report Round 2.
> Findings RT2-001, RT2-010, and I1 were removed after codebase verification proved them false.

---

## Corrected Finding Summary

| Severity | Count | Notes |
|----------|-------|-------|
| **Critical** | 2 | RT2-003 (JWT filter), RT2-004 (@PreAuthorize) |
| **High** | 5 | RT2-005 (SSRF), RT2-006 (prompt injection), RT2-007 (admin-ui security), RT2-009 (tenant leak), B1 (.block()) |
| **Medium** | 10 | See full list below |
| **Low** | 5 | See full list below |

---

## Phase 1 — Critical & Quick Wins (Week 1)

All items ≤ 2h effort. Can be done as individual PRs.

| # | Fix | Module | Effort | Assignable Independently? |
|---|-----|--------|--------|---------------------------|
| 1 | **Fix JWT revocation filter** — return 401 on revoked JTI instead of `chain.filter(exchange)` | admin-api | 15 min | Yes — Security team |
| 2 | **Add `@PreAuthorize`** to TelegramAccountController (0/11 methods), fix TenantController + AIProxyController writes to use `_WRITE` | admin-api | 2h | Yes — Security team |
| 3 | **Replace `save()` with `saveWithChain()`** in AuditEventConsumer + add DELETE trigger on `audit_events` | audit-service | 30 min | Yes — Backend team |
| 4 | **Fix admin-ui `show-details: always`** → `never` | admin-ui | 5 min | Yes — Anyone |
| 5 | **Fix PolicyDecisionConsumer** — capture tenant UUID return value, call `TenantContext.setTenantId()` | llm-orchestrator | 15 min | Yes — Backend team |
| 6 | **Add tenant header validation** to ManualEnrichmentConsumer via `TenantAwareKafkaSupport.validateTenantHeader()` | knowledge-engine | 15 min | Yes — Backend team |
| 7 | **Trigger JWT revocation** on password change, role change, user deletion | admin-api | 2h | Yes — Security team |

**Phase 1 total: ~5.5h — can be split across 3 people in 1 day**

---

## Phase 2 — High Priority (Week 2)

| # | Fix | Module | Effort | Assignable Independently? |
|---|-----|--------|--------|---------------------------|
| 8 | **SSRF protection** on `DocumentIngestionService.fetchWithTimeout()` — URL scheme whitelist (https only), private IP blocklist, DNS resolution check | knowledge-engine | 1 day | Yes — Backend team |
| 9 | **Spring Security config for admin-ui** — CSP, HSTS, X-Frame-Options headers + CSP meta tag in index.html | admin-ui | 1 day | Yes — Frontend team |
| 10 | **DOMPurify** for LLM/Markdown rendering in Flags.jsx + ReportViewer.jsx | admin-ui | 4h | Yes — Frontend team |
| 11 | **Knowledge content escaping** in LLM prompts — escape boundary markers, expand injection patterns | llm-orchestrator + knowledge-engine | 2 days | Yes — Backend team (needs LLM domain knowledge) |
| 12 | **Remove `.block()`** from AuditEventConsumer — use reactive subscription or async listener | audit-service | 2h | Yes — Backend team |
| 13 | **Fix UI console leaks** — replace 7x `console.error`/`console.warn` with toast notifications | admin-ui | 30 min | Yes — Frontend team |
| 14 | **Fix index-based React keys** — replace `key={i}` with data IDs in 8+ components | admin-ui | 1h | Yes — Frontend team |
| 15 | **Fix silent `.catch(() => {})`** — add error handling in AuthContext, Sidebar, Groups | admin-ui | 30 min | Yes — Frontend team |

**Phase 2 total: ~5 days — can be split across 3 teams in parallel**

---

## Phase 3 — Short-term (Weeks 3–4)

| # | Fix | Module | Effort | Assignable Independently? |
|---|-----|--------|--------|---------------------------|
| 16 | **Add CodeQL SAST** to CI pipeline (Java source analysis, not just Trivy container) | CI/CD | 30 min | Yes — DevOps |
| 17 | **Encrypt Telegram session_string** — pgcrypto or AES-256-GCM (open since Round 1!) | admin-api | 4h | Yes — Backend team |
| 18 | **Pin Docker base images** to patch version (`21.0.x-jre`) | All Dockerfiles | 1h | Yes — DevOps |
| 19 | **Add Prometheus alerting rules** — CPU, memory, error rates, Kafka lag | config | 2h | Yes — DevOps |
| 20 | **Intent-classifier tests** — pattern compilation, cache refresh, concurrent access | intent-classifier | 6h | Yes — Test team |
| 21 | **New UI page tests** — Costs, IntegrationsPage, ResolutionQueue, Knowledge search | admin-ui | 4h | Yes — Frontend team |
| 22 | **Write missing ADRs** — multi-tenancy (009), auth/authz (010), API versioning (011) | documentation | 4h | Yes — Architect |
| 23 | **Add failed login audit event** — publish `LOGIN_FAILURE` on `BadCredentialsException` | admin-api | 1h | Yes — Backend team |
| 24 | **ROLE_SERVICE path restriction** — limit to `/api/internal/**` | admin-api | 1 day | Yes — Security team |
| 25 | **npm dependency update** — esbuild critical vuln, vite/vitest | admin-ui | 30 min | Yes — Frontend team |

---

## Phase 4 — Pre-1.0.0 (Weeks 5–8)

| # | Fix | Effort | Notes |
|---|-----|--------|-------|
| 26 | Liquibase migration consolidation (squash per service) | M | Blocks fresh install |
| 27 | Fresh install smoke test | S | Depends on #26 |
| 28 | Kubernetes pod hardening (securityContext, ServiceAccounts, NetworkPolicy) | 2–3 days | DevOps |
| 29 | Vendor API key encryption at rest | 2–3 days | Needs design decision first |
| 30 | Persistent JWT revocation (Redis) | 2–3 days | Replaces ConcurrentHashMap |
| 31 | Test coverage to 80% (JaCoCo gate) | L | Weakest: intent-classifier, tdlib-adapter |
| 32 | Backup/restore documentation | S | Scripts exist, need runbook |

## Deferred (Post-1.0.0)

| Fix | Findings |
|-----|----------|
| Kafka SASL/ACLs | RT-005 |
| mTLS between services | Architecture |
| API gateway (Spring Cloud Gateway) | G4 |
| Async research execution | Epic 27B |
| React code splitting | U-NEW-6 |
| Backup encryption | RT-030 |

---

## Parallel Work Streams

These streams are **fully independent** — different modules, no merge conflicts:

```
Stream A (Security/Backend — admin-api)     Stream B (Frontend — admin-ui)     Stream C (DevOps/Infra)
─────────────────────────────────────────   ─────────────────────────────────   ──────────────────────────
P1: #1 JWT filter fix                       P1: #4 show-details fix            P3: #16 CodeQL SAST
P1: #2 @PreAuthorize 3 controllers          P2: #9 SecurityConfig + CSP        P3: #18 Pin Docker images
P1: #7 JWT revocation triggers              P2: #10 DOMPurify                  P3: #19 Prometheus alerts
P2: #11 Knowledge content escaping          P2: #13 Console leaks              P4: #28 K8s pod hardening
P3: #17 Session string encryption           P2: #14 Index-based keys
P3: #23 Failed login audit                  P2: #15 Silent catches
P3: #24 ROLE_SERVICE restriction            P3: #21 UI page tests
                                            P3: #25 npm update

Stream D (Backend — other services)         Stream E (Documentation)
─────────────────────────────────────────   ──────────────────────────
P1: #3 saveWithChain + DELETE trigger       P3: #22 ADRs 009-011
P1: #5 PolicyDecisionConsumer tenant fix    P4: #32 Backup runbook
P1: #6 ManualEnrichmentConsumer tenant
P2: #8 SSRF protection
P2: #12 AuditEventConsumer .block()
P3: #20 Intent-classifier tests
```

**5 fully independent streams. With 5 developers, Phase 1+2 can complete in 2 days.**

---

## Corrections to Previous Documents

The following claims in the Round 2 reports were **verified as incorrect** and should be disregarded:

| Document | Finding | Issue |
|----------|---------|-------|
| Red Team | RT2-001 (AdminAuditPublisher never called) | **FALSE** — actively used in SecurityConfig, AuthService, UserManagementService |
| Red Team | RT2-010 (Rate limiters not applied to 95% of endpoints) | **FALSE** — `llm-trigger` and `admin-crud` ARE used in 5+ controllers |
| Review | I1 (Liveness probe wrong endpoint) | **FALSE** — Helm chart correctly uses `/actuator/health/liveness` |
| Review | 23 UI pages | **Overcounted** — actual: 18 page directories |
| Review | 47 JSX files | **Undercounted** — actual: 67 JSX files |
| Review | 76 Liquibase migrations | **Undercounted** — actual: 79 |

---

*Generated 2026-07-18 by Claude Opus 4.6 — verified against codebase*
