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
- **Verify-first principle**: the 2026-07-18 reviews already retracted 3 false findings (RT2-001, RT2-010, I1), and P1 execution corrected **6 more** (see the P1 corrections table). Before implementing each batch, confirm the finding still holds against current `main` — and check the *premises* the plan states about the code, not just the finding itself. P1 batch 1.2 had to be discarded because the plan asserted the audit Kafka listener was single-threaded without ever opening `KafkaConsumerConfig`.
- Each phase (from P1 on) enters the normal cycle: **brainstorm/spec → writing-plans → implement → verify → PR**. This roadmap is the meta-plan that decides *what batch is next*.

**1.0.0 ships at the end of P3.** P4–P6 are post-release.

---

## Phase skeleton

| Phase | Theme | Size | Gate |
|-------|-------|------|------|
| **P0** | Reconcile & baseline | XS | ✅ done (PR #205) |
| **P1** | Critical security quick-wins | ~1–2 days | ✅ done (#206/#207/#208) |
| **P2** | Security structural hardening | ~1–2 weeks | 🔄 2.0–2.3 ✅ · **2.4 next** |
| **P3** | Pre-1.0.0 release-readiness | ~3–5 weeks | **→ 1.0.0** |
| **P4** | 1.0.0 polish + cheap wins | interleave | — |
| **P5** | Post-1.0.0 features | large | — |
| **P6** | Long horizon | ongoing | — |

---

## P0 — Reconcile & baseline (this work)

- [x] Write this `ROADMAP.md`.
- [x] Sync `BACKLOG.md`: absorb the 2026-07-18 findings as tracked rows (§0).
- [x] Add superseded header to `REMEDIATION_PLAN_2026-07-18.md`.
- [x] Commit the docs together — PR #205.

---

## P1 — Critical security quick-wins

The dangerous-but-cheap batch. Every item here is a live privilege-escalation, tenant-isolation, or audit-integrity hole with a ≤2h fix. Ship these first, as tight per-module PRs. **Opportunistic folds** (RT-F3, RT-F4) touch the same files and should be done while we're in there.

### PR 1.1 — admin-api auth enforcement *(CRITICAL)*
- **RT2-003** — JWT revocation filter must return **401** on a revoked JTI, not `chain.filter(exchange)` passthrough. `JwtAuthenticationFilter.java`.
- **RT2-003 (triggers)** — fire revocation on password change, role change, user deletion. `AuthService` / `UserManagementService` / `JwtRevocationService`.
- **RT2-004** — add `@PreAuthorize` WRITE perms: `TelegramAccountController` (13 endpoints, zero today), `TenantController` writes (currently READ-only class annotation), `AIProxyController` writes + `warmUp()`. Permissions already exist: `TELEGRAM_WRITE`, `TENANTS_WRITE`, `AI_CONFIG_WRITE`.
- *Fold:* **RT-F3** (parse JWT `Claims` once instead of 4×) and **RT-F4** (single `save()` on login) — same files.
- Effort: ~½ day.

### ~~PR 1.2 — audit integrity~~ → **DEFERRED TO P2.1** (2026-07-22)

RT2-002, RT2-016 and B1 turned out **not** to be quick wins. Deferred after implementation was
attempted, reviewed, and discarded. Reason:

`saveWithChain()` (`AuditService.java:166-177`) is an **unsynchronized read-modify-write** —
it reads the latest row to compute `prev_hash`, then writes. But
`KafkaConsumerConfig.java:44` sets `factory.setConcurrency(3)`: the audit listener has
**always been 3-way concurrent**. Simply swapping `save()` → `saveWithChain()` therefore
forks the chain — two rows claiming the same predecessor — which would make
`AuditChainVerificationJob` report tamper evidence on untampered data, the exact opposite
of RT-027's intent. This is true *with or without* `.block()`.

Removing `.block()` (B1) compounds it: under `AckMode.MANUAL_IMMEDIATE`
(`KafkaConsumerConfig.java:45`) offsets are committed per record, so if record 5's save fails
and 6–8 succeed, their acks commit *past* 5 and it is never redelivered — **silent
audit-event loss**. `.block()` was quietly providing both per-thread serialization and
retry-on-failure.

**RT2-002 and B1 are in direct conflict** and must be designed together. See P2.1.

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

**P1 exit criteria:** no VIEWER can perform writes ✅; revoked/demoted tokens are rejected ✅; both
Kafka consumers fail-closed on tenant ✅; no service exposes actuator health details ✅; CI has Java
SAST ✅; **PMD** blocking ✅. Checkstyle turned out to be inert and was **removed** — Spotless already owns formatting and their indent rules directly contradict (I4b). *(Audit-trail append-only +
hash-chained moved to P2.1.)*

**The final whole-change review caught a Critical regression the three task-scoped reviews all missed.**
Batch C's new fail-closed tenant check bricked *all* manual enrichment: the sole producer set no
`tenant_id` header, and every enrichment source is seeded `tenant_id NULL`, so both the header check and
the mismatch check failed 100% — run rows stuck `RUNNING` forever. Fixed with a
`GLOBAL_TENANT_SENTINEL` in `emcip-core` (producer always sets the header; consumer matches
sentinel ⇔ null-tenant), plus the consumer test whose absence let it ship.

**Lesson for P2 onward:** batches cut from the same base cannot see each other, and a fail-closed check
is only safe if you verify *who actually produces the message*. Always run a combined-integration build
before merging parallel batches, and never add a consumer-side requirement without reading the producer.

**P1 delivered (2026-07-22):** PR #206 (batch 1.1), PR #207 (batch 1.3), PR #208 (batch 1.4).

**Findings corrected during P1** — verified against code, do not re-implement:

| Claim | Reality |
|-------|---------|
| RT2-003 "revocation not triggered on password/role/user change" | **FALSE** — already implemented: `UserManagementService.java:143` (role), `:177` (delete), `:242` (password), `AuthController.java:72` (logout). Only the filter 401 bug was real. |
| RT2-004 "`warmUp()` unauthenticated" | **FALSE** — inherited class-level `AI_CONFIG_READ`. Raised to `AI_CONFIG_WRITE` because it triggers LLM work. |
| RT2-002 "schedule `verifyChain()`" follow-up | **ALREADY DONE** — `AuditChainVerificationJob` exists. |
| RT2-004 "TelegramAccountController has 11 endpoints" | **13 endpoints** (9 write + 4 read). |
| Plan's own claim that the listener is single-threaded | **FALSE** — `setConcurrency(3)`. This is what sank batch 1.2. |
| Plan's own framing of RT-F4 as "no behaviour change" | Inaccurate — `lastLogin` is no longer persisted if tenant lookup or token generation fails. Net-positive (atomic write), but it *is* a failure-path change. |

---

## P2 — Security structural hardening

Multi-day items. Roughly ordered by risk. Each is its own spec → plan → PR.

> **Ordering revised 2026-07-23.** The crypto pair (formerly 2.5 + 2.6) moved to the front and merged
> into a single item. Reasons: `session_string` was the only **CRITICAL** left in P2 yet sat behind four
> HIGHs; and the two items are one problem — reversibly encrypting a secret column in a database shared
> by several services — so splitting them meant deciding the crypto strategy twice, or merging a
> half-encrypted system. Finding IDs are unchanged; only the **Order** column was renumbered.

| Order | Item | ID | Module | Size | Status |
|-------|------|----|--------|------|--------|
| 2.0 | **Secrets encryption at rest** *(merges the former 2.5 + 2.6)* — AES-256-GCM `SecretCipher` in `emcip-core`, `v1:`-prefixed. Covers `telegram_accounts.session_string`, `telegram_accounts.api_hash`, `ke_vendor_api_keys.api_key`, `llm_provider_configs.api_key`. **Strict fail-closed reads**; existing rows migrated by hand in the cluster, no backfill code. Spec: `docs/superpowers/specs/2026-07-23-secrets-encryption-at-rest-design.md` | S5 / S-OPEN-1 / RT-013 / S-NEW-2 | emcip-core + admin-api + knowledge-engine + llm-orchestrator | L | ✅ PR #209 |
| 2.1 | **Audit integrity redesign** *(demoted from P1 — see P1 note)*. Must solve three coupled problems together: (a) serialize chain writes so `saveWithChain` cannot fork under `setConcurrency(3)` — options: concurrency 1, a single-subscriber serializing sink, or computing `prev_hash` inside one locking SQL statement; (b) give failed saves a durable landing spot (DLT or error handler) so `MANUAL_IMMEDIATE` acks cannot commit past a lost record — note audit-service defines its **own** `KafkaConsumerConfig` with no error handler and does not use `CommonKafkaConfig`, while `emcip-core` already ships `DeadLetterTopicHandler`; (c) only then add the DELETE-prevention trigger, guarded by a sanctioned-purge session flag (`SET LOCAL emcip.audit_purge='on'`) so `AuditRetentionJob` still works. The UPDATE-prevention trigger already exists (`003-audit-tamper-resistance.xml`); only DELETE is missing. **Do not split these into separate PRs.** | RT2-002 / RT2-016 / B1 / RT-027 | audit-service | L | ✅ PR #210 |
| 2.2 | **SSRF protection** on `DocumentIngestionService.fetchWithTimeout()` — https/http scheme whitelist, RFC-1918 + loopback + link-local + metadata-IP blocklist, DNS-resolution recheck | RT2-005 / RT-F2 / S-NEW-3 | knowledge-engine | M | ✅ PR #215 |
| 2.3 | **admin-ui Spring Security** — `SecurityConfig` with CSP, HSTS, X-Frame-Options, X-Content-Type-Options, Referrer-Policy, Permissions-Policy (header-only; `index.html` unchanged) | RT2-007 / RT-029 | admin-ui | M | ✅ PR #217 |
| 2.4 | **DOMPurify** on LLM/Markdown rendering — `Flags.jsx`, `ReportViewer.jsx` | RT2-011 / RT2-012 | admin-ui | S | ✅ |
| 2.5 | **Knowledge→LLM escaping** — escape boundary markers in knowledge/ontology/web-search content; move to structured role messages; expand injection patterns | RT2-006 / RT-009 | knowledge-engine + llm-orchestrator | L | ⏳ **next** |
| 2.6 | **ROLE_SERVICE path restriction** — limit service token to `/api/internal/**` + `/actuator/**`; add to RBAC matrix | RT2-014 / RT-020 | admin-api | M | ⏳ |
| 2.7 | **UI hygiene batch** — replace 7× `console.error/warn` with toasts (U-NEW-1), replace `key={i}` in 8+ lists (U-NEW-2), fix 3× silent `.catch(() => {})` (U-NEW-3), `npm audit fix` (RT2-015) | U-NEW-1/2/3 / RT2-015 | admin-ui | S | ⏳ |
| 2.8 | **Failed-login audit** — publish `LOGIN_FAILURE` on `BadCredentialsException` | S-OPEN-3 / RT-017 | admin-api | S | ⏳ |

**Note (2.0):** the secrets-management strategy decision that RT-013 was blocked on is now **made** —
app-level AES-256-GCM with the key from a K8s Secret, key never sent to Postgres. pgcrypto was rejected
because the key would appear in SQL text and leak into `pg_stat_statements` and query logs; Vault was
deferred to P6. Carry this same approach into the future secrets-management ADR — the `v1:` prefix is
the hook that lets a KMS/Vault backend swap in behind the cipher without touching stored data.

**P2.0 delivered (2026-07-24):** branch `feat/p2-secrets-encryption-at-rest`. Four columns encrypted
(`SecretCipher` in `emcip-core`; JPA `@Convert` for knowledge-engine/llm-orchestrator, service-layer for
admin-api R2DBC), strict fail-closed reads, hand-run migration via `SecretCipherCli` +
`docs/operations/secrets-encryption.md`. Scope correction during implementation: the planned
`DROP TABLE telegram_config` was removed — changelog `007` already dropped it. Follow-ups tracked as
P2.0-M1/M2/F1 in `BACKLOG.md`.

**P2.1 delivered (2026-07-26):** branch `feat/p2-audit-integrity`. Activated the hash chain by switching
the consumer to `saveWithChain()`, serialized against concurrent listener threads/replicas by a Postgres
advisory lock (`pg_advisory_xact_lock`) held for the transaction; strengthened the tamper-evident hash to
fold `prev_hash` into `integrity_hash` so `verifyChain` recomputes content hash *and* checks linkage,
reporting `CONTENT_TAMPERED` vs `BROKEN_LINKAGE`; added the DELETE-prevention trigger (`audit_no_delete`,
migration `004`), guarded by the `emcip.audit_purge` session flag so the retention job's sanctioned purge
still works. **Design revision:** the plan's own §3.2 (`ReactiveKafkaConsumerTemplate` rewrite to drop
`.block()`) was dropped during implementation — `ReactiveKafkaConsumerTemplate` was removed from
spring-kafka 4.x and reactor-kafka (the library it wrapped) is discontinued (EOL). The delivered
consumer stays a synchronous `@KafkaListener`, hardened with a `DefaultErrorHandler` (exponential
backoff) → `DeadLetterTopicHandler` DLQ and `JacksonException` classified non-retryable — matching every
other EMCIP Kafka consumer. See the spec's "Decision revision" banner:
`docs/superpowers/specs/2026-07-25-audit-integrity-redesign-design.md`.

**P2.2 delivered (2026-07-29):** branch `feat/p2-ssrf-protection`, **PR #215**. Added a reusable SSRF guard in
`emcip-core` (`io.emcip.common.net`: `CidrBlock`, `SsrfAllowList`, `SsrfGuard`, `SsrfBlockedException`,
`PinningDns`) that classifies the *resolved* IP against a hardcoded deny set (loopback `127.0.0.0/8` +
`::1`, RFC-1918 `10/8` + `172.16/12` + `192.168/16`, link-local/metadata `169.254.0.0/16` incl.
`169.254.169.254` + `fe80::/10`, IPv6 ULA `fc00::/7`, wildcard `0.0.0.0/8` + `::`, multicast/reserved
`224/4` + `240/4` + `ff00::/8`), unwrapping IPv4-mapped IPv6 first. DNS-rebinding TOCTOU is closed by
pinning: an OkHttp `Dns` hook (`PinningDns`) validates every resolved address and returns exactly the
validated list, so OkHttp connects only to a pre-validated IP. Redirects are disabled
(`followRedirects(false)` + `followSslRedirects(false)`), with 30s connect/read/call timeouts and the
existing 10 MB `MAX_CONTENT_BYTES` cap preserved via a bounded body read. The http/https scheme check is
centralized in `submitUrlIngestion`, covering both the controller and the `reingestJob` reprocess path;
blocked URLs end the ingestion job `FAILED` with no raw internal response leaked. New config key
`emcip.ingestion.ssrf.allowed-hosts` (hostnames or CIDRs) lets operators allow specific private targets;
default empty = strict deny-private, and the blocklist always applies otherwise. A post-merge reactor
build fix scoped the new OkHttp dependency (marked `optional` in `emcip-core`) so it stops colliding with
the OkHttp-5.x `mockwebserver3` modules; four low-priority hardening follow-ups are logged as SSRF-F1…F4
in `BACKLOG.md` §0b.

**P2.3 delivered (2026-07-30, PR #217):** branch `feat/p2-admin-ui-security`. Added a `permitAll` Spring Security
filter chain in `emcip-admin-ui` (auth still lives at admin-api) that writes CSP, HSTS,
`X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and
`Permissions-Policy` on every response. `script-src` is strict `'self'`; `style-src` carries an interim
`'unsafe-inline'` because the React frontend still uses inline `style={{}}` attributes, tracked as
follow-up RT2-007-F1 (CSS Modules migration, then tighten to strict `'self'`). HSTS depends on
`server.forward-headers-strategy: framework`, since TLS terminates at the ingress and Spring must trust
the forwarded `X-Forwarded-Proto` to decide the connection is secure. CSP is header-only — `index.html`
was left unchanged; a duplicate `<meta http-equiv="Content-Security-Policy">` was considered and dropped
as redundant with (and a drift risk against) the header. Note: Spring Security's stock `HstsHeaderWriter`
emits `max-age=31536000 ; includeSubDomains` (OWS around the `;`, RFC 6797-valid, no preload). A second
follow-up RT2-007-F2 (proxy header de-duplication) is logged in `BACKLOG.md` §0b. **Next: P2.4 — DOMPurify
on LLM/Markdown rendering.**

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
