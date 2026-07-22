# EMCIP Roadmap — Path to 1.0.0 and Beyond

> **Created**: 2026-07-22
> **Owner**: solo developer + Claude Code (single-implementer sequencing — no parallel streams)
> **Authoritative meta-plan.** Supersedes `documentation/REMEDIATION_PLAN_2026-07-18.md` (absorbed for tactical detail).
> **Live task status** lives in `docs/superpowers/BACKLOG.md`. This doc owns *sequence + rationale*; the backlog owns *status*.

## How to read this

- Phases are executed **in order**. Within a phase, work is grouped into **PR-sized batches** sized for one implementer.
- Each item keeps its **canonical finding ID** so it cross-links to the source analysis:
  - `RT2-xxx` → `documentation/RED_TEAM_REPORT_2026-07-18.md`
  - `S-NEW-x` / `S-OPEN-x` / `B1` / `KE-x` / `Ix` → `documentation/REVIEW-2026-07-18.md`
  - `INFx` / `RT-Fx` / plain `#` → `docs/superpowers/BACKLOG.md`
- **Verify-first principle**: the 2026-07-18 reviews already retracted 3 false findings (RT2-001, RT2-010, I1). Before implementing each batch, confirm the finding still holds against current `main` — code moves.
- Each phase (from P1 on) enters the normal cycle: **brainstorm/spec → writing-plans → implement → verify → PR**. This roadmap is the meta-plan that decides *what batch is next*.

**1.0.0 ships at the end of P3.** P4–P6 are post-release.

---

## Phase skeleton

| Phase | Theme | Size | Gate |
|-------|-------|------|------|
| **P0** | Reconcile & baseline | XS | — |
| **P1** | Critical security quick-wins | ~1–2 days | dangerous + cheap |
| **P2** | Security structural hardening | ~1–2 weeks | — |
| **P3** | Pre-1.0.0 release-readiness | ~3–5 weeks | **→ 1.0.0** |
| **P4** | 1.0.0 polish + cheap wins | interleave | — |
| **P5** | Post-1.0.0 features | large | — |
| **P6** | Long horizon | ongoing | — |

---

## P0 — Reconcile & baseline (this work)

- [x] Write this `ROADMAP.md`.
- [ ] Sync `BACKLOG.md`: absorb the 17 new 2026-07-18 findings as tracked rows.
- [ ] Add superseded header to `REMEDIATION_PLAN_2026-07-18.md`.
- [ ] Commit the three docs together.

---

## P1 — Critical security quick-wins

The dangerous-but-cheap batch. Every item here is a live privilege-escalation, tenant-isolation, or audit-integrity hole with a ≤2h fix. Ship these first, as tight per-module PRs. **Opportunistic folds** (RT-F3, RT-F4) touch the same files and should be done while we're in there.

### PR 1.1 — admin-api auth enforcement *(CRITICAL)*
- **RT2-003** — JWT revocation filter must return **401** on a revoked JTI, not `chain.filter(exchange)` passthrough. `JwtAuthenticationFilter.java`.
- **RT2-003 (triggers)** — fire revocation on password change, role change, user deletion. `AuthService` / `UserManagementService` / `JwtRevocationService`.
- **RT2-004** — add `@PreAuthorize` WRITE perms: `TelegramAccountController` (13 endpoints, zero today), `TenantController` writes (currently READ-only class annotation), `AIProxyController` writes + `warmUp()`. Permissions already exist: `TELEGRAM_WRITE`, `TENANTS_WRITE`, `AI_CONFIG_WRITE`.
- *Fold:* **RT-F3** (parse JWT `Claims` once instead of 4×) and **RT-F4** (single `save()` on login) — same files.
- Effort: ~½ day.

### PR 1.2 — audit integrity *(HIGH)*
- **RT2-002** — `AuditEventConsumer` call `saveWithChain()` not `save()`. Activates the hash chain (columns exist, always NULL today).
- **RT2-016** — add DELETE-prevention Liquibase trigger on `audit_events` (UPDATE trigger already exists).
- **B1** — remove `.block()` from the Kafka listener; use reactive subscription / async listener.
- *Optional:* schedule `verifyChain()` as a periodic integrity check.
- Effort: ~½ day. `emcip-audit-service`.

### PR 1.3 — Kafka tenant isolation *(HIGH)*
- **RT2-008** — `ManualEnrichmentConsumer`: add `TenantAwareKafkaSupport.validateTenantHeader(record)` at consumer boundary. `emcip-knowledge-engine`.
- **RT2-009** — `PolicyDecisionConsumer`: capture the returned UUID and call `TenantContext.setTenantId(...)`. `emcip-llm-orchestrator`.
- Effort: ~30 min.

### PR 1.4 — config & CI quick fixes *(trivial, high-leverage)*
- **RT2-013 / S-NEW-1** — admin-ui actuator `show-details: always` → `never`.
- **S-OPEN-2** — add Java **CodeQL** SAST to CI (currently only Trivy SARIF uploads use codeql-action).
- **I2 / RT-034** — pin Docker base images to patch (`eclipse-temurin:21.0.x-jre`).
- **I4** — make Checkstyle/PMD `failOnViolation: true` (blocking).
- Effort: ~2h.

**P1 exit criteria:** no VIEWER can perform writes; revoked/demoted tokens are rejected; audit trail is append-only + hash-chained; both Kafka consumers fail-closed on tenant; CI has Java SAST + blocking quality gates.

---

## P2 — Security structural hardening

Multi-day items. Roughly ordered by risk. Each is its own spec → plan → PR.

| Order | Item | ID | Module | Size |
|-------|------|----|--------|------|
| 2.1 | **SSRF protection** on `DocumentIngestionService.fetchWithTimeout()` — https/http scheme whitelist, RFC-1918 + loopback + link-local + metadata-IP blocklist, DNS-resolution recheck | RT2-005 / RT-F2 / S-NEW-3 | knowledge-engine | M |
| 2.2 | **admin-ui Spring Security** — `SecurityConfig` with CSP, HSTS, X-Frame-Options, X-Content-Type-Options + CSP meta tag in `index.html` | RT2-007 / RT-029 | admin-ui | M |
| 2.3 | **DOMPurify** on LLM/Markdown rendering — `Flags.jsx`, `ReportViewer.jsx` | RT2-011 / RT2-012 | admin-ui | S |
| 2.4 | **Knowledge→LLM escaping** — escape boundary markers in knowledge/ontology/web-search content; move to structured role messages; expand injection patterns | RT2-006 / RT-009 | knowledge-engine + llm-orchestrator | L |
| 2.5 | **Telegram `session_string` encryption** — pgcrypto or app-level AES-256-GCM (open since Round 1) | S5 / S-OPEN-1 | admin-api | M |
| 2.6 | **API-key encryption at rest** — `vendor_api_keys.api_key`, `llm_provider_configs.api_key`; decide strategy (app AES / pgcrypto / Vault) first | RT-013 / S-NEW-2 | admin-api + knowledge-engine | M |
| 2.7 | **ROLE_SERVICE path restriction** — limit service token to `/api/internal/**` + `/actuator/**`; add to RBAC matrix | RT2-014 / RT-020 | admin-api | M |
| 2.8 | **UI hygiene batch** — replace 7× `console.error/warn` with toasts (U-NEW-1), replace `key={i}` in 8+ lists (U-NEW-2), fix 3× silent `.catch(() => {})` (U-NEW-3), `npm audit fix` (RT2-015) | admin-ui | S |
| 2.9 | **Failed-login audit** — publish `LOGIN_FAILURE` on `BadCredentialsException` | S-OPEN-3 / RT-017 | admin-api | S |

**Note (2.6):** needs a one-paragraph strategy decision before implementation — carry the same approach chosen here into a future secrets-management ADR.

---

## P3 — Pre-1.0.0 release-readiness

Dependency-ordered. The release cannot ship until `helm install` on a blank cluster produces a working system with no manual steps.

| Order | Item | ID | Size | Needs |
|-------|------|----|------|-------|
| 3.1 | **Liquibase migration consolidation** — squash to `001-initial-schema.xml` per service (removes `md5sum='manual'` fragility) | INF1 | M | — |
| 3.2 | **Fresh-install smoke test** — drop DB, `helm install`, verify all 18 pages; document in `docs/operations/fresh-install.md` | INF2 | S | 3.1 |
| 3.3 | **Tenant provisioning endpoint** — admin-api endpoint + Liquibase-safe seed (no direct DB access) | #21 | M | — |
| 3.4 | **Telegram test-account seeding via Helm** | INF3 | S | 3.3 |
| 3.5 | **K8s pod hardening** — `securityContext` (readOnlyRootFilesystem, runAsNonRoot, drop caps), ServiceAccounts, NetworkPolicy | RT-012 | M | — |
| 3.6 | **Prometheus alerting rules** — CPU, memory, error rates, Kafka lag | I5 | S | — |
| 3.7 | **ADRs 009–011** — multi-tenancy strategy, auth/authz architecture, API versioning (decide `/v1` before 1.0.0) | S6 | S | — |
| 3.8 | **Test coverage to 80%** — JaCoCo gate; weakest: intent-classifier, tdlib-adapter, moderation-service | #4 | L | — |
| 3.9 | **Gatling load tests in CI** — 3 sims exist, add gate | #14 | S | — |
| 3.10 | **K8s HA / multi-replica** — HPA, PDB, tuned replicas | #12 | M | 3.5 |
| 3.11 | **Backup/restore runbook** — scripts exist, document | I7 / M6 | S | — |

**1.0.0 release gate:** P1 + P2 + P3.1–3.7 complete; P3.8–3.11 strongly recommended.

---

## P4 — 1.0.0 polish + cheap wins (interleave-able)

- **RT-F1** — per-user/IP rate limiting (custom `RateLimiterConfig` key resolver; global counters today). S
- **RT-F5** — `BackfillService` `PARTIAL` status on `MAX_ITERATIONS` truncation. XS
- **#45** — language detection (Lingua) → populate `language` in `IntentClassifiedEvent`; unblocks `MESSAGE_LANGUAGE` rules. S
- **#46** — Unicode-aware regex case folding (`Pattern.UNICODE_CASE`) for German umlauts. XS
- **Toast migration** — replace per-page `alertBanner`/`errorBanner` with `useToast()` across ~13 pages (POSSIBLE_DEVELOPMENT). S
- **Knowledge-enrichment follow-ons** — per-template enrichment flag, task-type-aware search type, enrichment health indicator, context re-ranking (POSSIBLE_DEVELOPMENT). M

---

## P5 — Post-1.0.0 features

- **#8 ML toxicity detection** *(XL)* — needs an architecture decision (ADR) first: OpenNLP vs Perspective API vs local LiteLLM scorer.
- **Category-based moderation** — ML-scored categories (harassment, hate_speech, sexual_content, self_harm, spam, misinformation) with per-category thresholds/actions. Requires new data model + scoring pipeline.
- **LLM routing & multi-model** — intent-based / cost-based / load-balanced routing; direct API clients (MiniMax, Anthropic, OpenAI, Ollama); LLM client factory.
- **LLM quality & safety** — response cache (Caffeine+Redis, semantic match), response validator, retry-with-fallback-model, per-tenant budget enforcement (80%/95% alerts).
- **Prompt-template follow-ons** — `resolveTemplateConfig` dedup, merge `flag_analysis`/`flag_analyse`, wire custom templates to policy actions, FlagService.chat() test coverage.

---

## P6 — Long horizon

- **Kafka SASL/ACLs** (RT-005) — deferred; revisit on multi-tenant cluster / external exposure / compliance audit.
- **mTLS between services**, **API gateway** (Spring Cloud Gateway — centralized auth/rate-limit, G4).
- **Persistent JWT revocation (Redis)** — replace `ConcurrentHashMap`, survive pod restarts.
- **SSO / OAuth2 / OIDC** admin login; **public self-service portal** (needs tenant provisioning first).
- **Audit/compliance** — WORM immutability, retention tiers, CSV/JSON/PDF export, SIEM integration, backup encryption (RT-030).
- **Rule engine** — Drools/easy-rules, escalation manager, decision cache.
- **Kafka infra** — `RetryableKafkaConsumer`, DLQ replay, per-consumer metrics.
- **PostgreSQL RLS** — defence-in-depth beyond Hibernate `@Filter`.
- **Operator reply** — media replies, edit/delete sent, bulk replies.
- **GraalVM native for R2DBC services** (#13), arm64 native (#19), node taints/tolerations (#20).

---

## Cross-references

- Findings & evidence: `documentation/REVIEW-2026-07-18.md`, `documentation/RED_TEAM_REPORT_2026-07-18.md`
- Tactical per-finding detail (absorbed): `documentation/REMEDIATION_PLAN_2026-07-18.md`
- Live task status: `docs/superpowers/BACKLOG.md`
- Raw future ideas: `documentation/POSSIBLE_DEVELOPMENT.md`

*Maintained by: solo + Claude Code. Update BACKLOG on every PR; update this file only when the sequence or scope changes.*
