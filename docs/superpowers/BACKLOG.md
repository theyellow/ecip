# EMCIP Backlog

> Last updated: 2026-07-31 (P2.3 admin-ui security headers delivered, PR #217 — Spring Security CSP/HSTS/X-Frame-Options/X-Content-Type-Options/Referrer-Policy/Permissions-Policy filter chain; follow-ups RT2-007-F1/F2 logged in §0b)
> Single source of truth for all open work **status**. Sequencing & rationale live in `documentation/ROADMAP.md`.
> Completed items are in §5.
> Size guide: **XS** < 2h · **S** ½ day · **M** 1–2 days · **L** 3–5 days · **XL** > 1 week
> Dependency key: items are ordered so prerequisites appear before dependents. "Needs" column lists hard blockers.
> **Phase** column maps each item to its `ROADMAP.md` phase.

**At a glance:** P1 ✅ · P2.0 ✅ · P2.1 ✅ · P2.2 ✅ · P2.3 ✅ · P2.4 ✅ · P2.5 ✅ · **P2.6 = next** · P2.7–P2.8 open · then P3 (release-readiness).

---

## 0. Security Remediation — 2026-07-18 Reviews

> New findings from `REVIEW-2026-07-18.md` + `RED_TEAM_REPORT_2026-07-18.md`. Sequenced in `ROADMAP.md` P1–P2.
> Verify each still holds against current `main` before implementing (3 findings were already retracted: RT2-001, RT2-010, I1).
>
> **Verified corrections from P1 execution (2026-07-22) — do not re-implement these:**
> - RT2-003's *"revocation not triggered on password/role/user change"* — **FALSE**. Already implemented at
>   `UserManagementService.java:143` (role), `:177` (delete), `:242` (password), `AuthController.java:72` (logout).
>   Only the filter 401 bug was real.
> - RT2-004's *"`warmUp()` unauthenticated"* — **FALSE**. It inherited class-level `AI_CONFIG_READ`. Raised to
>   `AI_CONFIG_WRITE` because it triggers LLM work.
> - RT2-002's *"schedule `verifyChain()`"* follow-up — **ALREADY DONE** (`AuditChainVerificationJob`).
> - TelegramAccountController has **13** endpoints (9 write + 4 read), not 11.

### 0a. Remediation items (phase-ordered)

| ID | Item | Sev | Phase | Size | Status |
|----|------|-----|-------|------|--------|
| RT2-003 | JWT revocation filter returns 401 (not passthrough) | CRITICAL | P1.1 | S | ✅ PR #206 |
| RT2-004 | `@PreAuthorize` WRITE perms on TelegramAccount/Tenant/AIProxy controllers (22 endpoints) | CRITICAL | P1.1 | S | ✅ PR #206 |
| RT-F3 | JWT single-parse optimization (folded into P1.1) | LOW | P1.1 | XS | ✅ PR #206 |
| RT-F4 | Combine double save on login (folded into P1.1) | LOW | P1.1 | XS | ✅ PR #206 |
| RT2-008 | `ManualEnrichmentConsumer` explicit Kafka tenant-header validation | HIGH | P1.3 | XS | ✅ PR #207 |
| RT2-009 | `PolicyDecisionConsumer` capture + set tenant UUID | HIGH | P1.3 | XS | ✅ PR #207 |
| RT2-013 / S-NEW-1 | admin-ui actuator `show-details: never` | HIGH | P1.4 | XS | ✅ PR #208 |
| S-OPEN-2 | Java CodeQL SAST in CI | HIGH | P1.4 | XS | ✅ PR #208 |
| I2 / RT-034 | Pin Docker base images to patch version (`21.0.11_10`) | MEDIUM | P1.4 | XS | ✅ PR #208 |
| I4 | PMD `failOnViolation: true` — genuinely blocking | MEDIUM | P1.4 | XS | ✅ PR #208 |
| I4b | **Checkstyle removed** — google_checks.xml is mostly formatting (Spotless already owns that, and its AOSP 4-space directly contradicts google_checks' 2-space), so the gate was inert and unfixable without fighting the formatter. Plugin + CI steps deleted. Static analysis = Spotless (format) + PMD (smells, blocking) + CodeQL (security). | — | P1.4 | XS | ✅ PR #208 |
| S5 / S-OPEN-1 / RT-013 / S-NEW-2 | **Secrets encryption at rest** — `telegram_accounts.session_string` + `api_hash`, `ke_vendor_api_keys.api_key`, `llm_provider_configs.api_key`; AES-256-GCM `SecretCipher` in `emcip-core`, strict fail-closed reads, rows migrated by hand via `SecretCipherCli` + runbook. Merges the former P2.5 + P2.6. Spec: `specs/2026-07-23-secrets-encryption-at-rest-design.md`; plan: `plans/2026-07-23-secrets-encryption-at-rest.md`; runbook: `docs/operations/secrets-encryption.md` | CRITICAL | P2.0 | L | ✅ PR #209 |
| RT2-002 | Wire `saveWithChain()` into `AuditEventConsumer` (activate hash chain) | HIGH | P2.1 | L | ✅ PR #210 |
| RT2-016 | DELETE-prevention trigger on `audit_events` | HIGH | P2.1 | L | ✅ PR #210 |
| B1 | Remove `.block()` from `AuditEventConsumer` Kafka listener | HIGH | P2.1 | L | ✅ PR #210 — retained a single `.block()` bridging a reactive `saveWithChain()` at the Kafka consumer thread; the risky part (silent loss under `MANUAL_IMMEDIATE`) is fixed via `DefaultErrorHandler`→DLQ, not by removing `.block()` |
| RT2-005 | SSRF protection on `DocumentIngestionService` (scheme whitelist + private-IP blocklist + DNS recheck) | HIGH | P2.2 | M | ✅ PR #215 — SSRF guard (pin validated IP via OkHttp `Dns` + pre-connect literal-IP interceptor) on URL ingestion; configurable allow-list; reingest path covered. Follow-ups SSRF-F1…F4 in §0b |
| RT2-007 | admin-ui Spring Security (CSP/HSTS/X-Frame-Options + Referrer-Policy/Permissions-Policy; header-only, no meta tag) | HIGH | P2.3 | M | ✅ PR #217 |
| RT2-011 / RT2-012 | DOMPurify on LLM/Markdown rendering (Flags, ReportViewer) | HIGH | P2.4 | S | ✅ PR #218 — Unicode-hygiene sanitizer (`sanitizeText`, strips bidi/zero-width/control) on both renders + `.md` download + Copy; DOMPurify retained behind a reserved `sanitizeHtml()` for a future HTML sink. No HTML sink existed. Spec: `specs/2026-07-31-p2.4-llm-render-sanitization-design.md` |
| RT2-006 | Knowledge/ontology/web-search content escaping in LLM prompts | HIGH | P2.5 | L | ✅ PR #219 — per-call nonce fence + "treat fenced content as data" convention via shared io.emcip.common.prompt.PromptFence; applied to USER_CONTENT, knowledge enrichment, extraction document text, research web evidence. Ontology types neutralized-not-fenced; evidence fenced at prompt-build. Ingestion regex scanner is a fail-closed gate (match → FLAGGED_INJECTION_RISK, doc rejected) whose detector was deliberately not expanded (fencing is the control; deny-list filtering is the wrong tool). Residual data-poisoning risk → §0b KE-TRUST. Spec: specs/2026-07-31-p2.5-prompt-injection-fencing-design.md |
| RT2-014 / RT-020 | `ROLE_SERVICE` path restriction + add to RBAC matrix | MEDIUM | P2.6 | M | ⏳ **next** |
| U-NEW-1/2/3 | UI hygiene: console leaks → toasts, `key={i}` → data IDs, silent `.catch(()=>{})` | MEDIUM | P2.7 | S | ⏳ |
| RT2-015 | `npm audit fix` (esbuild/vite/vitest) | MEDIUM | P2.7 | XS | ⏳ |
| S-OPEN-3 | `LOGIN_FAILURE` audit event on `BadCredentialsException` | MEDIUM | P2.8 | S | ⏳ |

### 0b. Follow-ups raised during remediation → deferred to P3/P4

> Non-blocking hardening + test-debt spawned while delivering the items above. None gates 1.0.0.

| ID | Item | Sev | Phase | Size | Status |
|----|------|-----|-------|------|--------|
| INF-CI-IT | Integration tests (`*IT`) run in CI repo-wide. Today CI runs `mvn test` (Surefire only) and no module activates `maven-failsafe`, so all `*IT` classes across the repo are CI-invisible except the four audit ITs P2.1 wired in directly. Generalize: activate failsafe + `mvn verify` repo-wide, and audit the latent failures this surfaces (e.g. `AuditEventPersistenceIT` was silently red on `main` before P2.1). | MEDIUM | P3 | M | ⏳ |
| INF-CI-FE | **Frontend vitest suite not run in CI.** `emcip-admin-ui`'s `frontend-maven-plugin` runs `npm run build` (vite) at `generate-resources` but never `npm test`, so every admin-ui vitest spec is CI-invisible — three had silently rotted red on `main` (Flags placeholder `...`→`…`; AuditLog `list()` arity + combobox-order drift) before P2.4/PR #218 fixed them. Add an `npm` execution running `test` (= `vitest run`) bound to the `test` phase (or a CI step), gated to fail the build. Sibling to INF-CI-IT (Java `*IT`); do the two together. Ref: `emcip-admin-ui/pom.xml`, `.github/workflows/maven.yml`. | LOW | P3 | XS | ⏳ |
| P1-M1 | No end-to-end test that `@PreAuthorize` is enforced by the live filter chain — existing controller tests use `WebTestClient.bindToController(...)`, which bypasses Spring Security. `ControllerAuthorizationTest` is reflection-only (now inverted to catch unannotated write methods). Needs a `@WebFluxTest` + `@WithMockUser` suite. | MEDIUM | P3 | S | ⏳ |
| P1-M3 | Base-image pinning is Temurin-only — `docker/postgres-knowledge/Dockerfile` (`postgres:16`) and the three `Dockerfile.native` runtimes (`debian:12-slim`) still float. | LOW | P3 | XS | ⏳ |
| P2.0-F1 | Flip reads from strict-fail-closed to a stricter startup self-check once every environment reports zero plaintext (the design's planned hardening). Also revisit key rotation (the `v1:` prefix is the hook) with the P6 secrets ADR. | LOW | P3/P4 | S | ⏳ |
| P1-M2 | JWT revocation is **per-replica** — `JwtRevocationService` uses an in-process `ConcurrentHashMap`. Correct at `replicas: 1` (current Helm default) but silently degrades on scale-out. Bounds the RT2-003 fix. | MEDIUM | P4 | M | ⏳ |
| P1-M4 | `ManualEnrichmentConsumerTest` hardcodes the global sentinel string instead of referencing `TenantAwareKafkaSupport.GLOBAL_TENANT_SENTINEL`; no test asserts the sentinel cannot bypass a *tenant-scoped* source. | LOW | P4 | XS | ⏳ |
| P2.0-M1 | llm-orchestrator's `LlmProviderApiKeyCipherConverter` Hibernate-injection is not covered by an integration test (only a hand-constructed unit test). KE proves the identical mechanism via Testcontainers; llm-orchestrator has no Testcontainers harness. Fails loud if it regresses (no no-arg ctor). Add a `@DataJpaTest` round-trip. | LOW | P4 | XS | ⏳ |
| P2.0-M2 | `VendorApiKeyEncryptionIT` asserts the decrypt `rootCause` doesn't leak plaintext, but not that the JPA wrapper messages (`JpaSystemException`/`PersistenceException`) don't — safe today (fixed Hibernate string) but unpinned by a test. | LOW | P4 | XS | ⏳ |
| P2.1-F1 | DLQ publish in the shared `DeadLetterTopicHandler` is fire-and-forget and swallows exceptions on send — a hard broker outage past `delivery.timeout.ms` can lose a DLQ-routed record after the consumer offset has already committed. Harden by awaiting the send result (or a transactional outbox). Affects all EMCIP consumers, not just audit. | LOW | P4 | S | ⏳ |
| SSRF-F1 | `SsrfGuard.unwrapIpv4Mapped()` unwraps only IPv4-*mapped* addresses (`::ffff:127.0.0.1`); it does not unwrap deprecated IPv4-*compatible* addresses (`::127.0.0.1` / `::7f00:1`). A crafted `::7f00:1` literal or resolution could bypass the loopback/private deny-set. Extend the unwrap (and add a test) to cover the `::/96` IPv4-compatible form. Ref: `emcip-core/.../SsrfGuard.java`. | LOW | P4 | XS | ⏳ |
| SSRF-F2 | The SSRF deny-set omits the NAT64 well-known prefix `64:ff9b::/96` (RFC 6052). Where a NAT64 gateway is present, `64:ff9b::a00:1` translates to private `10.0.0.1`, reaching internal hosts. Add `64:ff9b::/96` to `SsrfGuard.DENY` with a `nat64` label + test. Ref: `emcip-core/.../SsrfGuard.java`. | LOW | P4 | XS | ⏳ |
| SSRF-F3 | `SsrfProperties` is a mutable `@ConfigurationProperties` bean with getters/setters; convert to an immutable record (constructor-bound) for consistency with the project's config style and to prevent post-bind mutation. Ref: `emcip-knowledge-engine/.../config/SsrfProperties.java`. | LOW | P4 | XS | ⏳ |
| SSRF-F4 | **Consolidate OkHttp / MockWebServer on 5.x.** The reactor straddles two OkHttp majors: knowledge-engine uses `mockwebserver` 4.12.0 (okhttp 4.x) while llm-orchestrator + admin-api use `mockwebserver3` 5.2.1 (okhttp 5.x). emcip-core's okhttp compile dep is marked `optional` (and knowledge-engine re-declares it) purely to keep okhttp 4.x from leaking onto the 5.x modules and breaking MockWebServer 5.x (`TaskRunner` ABI — this bit PR #215 on the reactor build). Migrate knowledge-engine tests (`okhttp3.mockwebserver.*` → `mockwebserver3.*`, ~13 files, `MockResponse` builder API) and the SSRF client to okhttp 5.x so a single okhttp version governs the reactor and `optional` can be dropped. Ref: root `pom.xml`, `emcip-core/pom.xml`, `emcip-knowledge-engine/pom.xml`. | LOW | P4 | M | ⏳ |
| RT2-007-F1 | Remove admin-ui inline styles (68 occurrences / 22 files → CSS Modules; PipelineTrace enum/binary classes; Costs bar chart → inline SVG) and drop 'unsafe-inline' from CSP style-src to reach strict style-src 'self'. Ref: emcip-admin-ui/src/main/frontend. | LOW | P4 | M | ⏳ |
| RT2-007-F2 | `ApiProxyController` forwards admin-api's own security headers (CSP/HSTS/X-Frame-Options/X-Content-Type-Options/Referrer-Policy/Permissions-Policy), so a 200 `/api/**` response carries duplicate (and divergent) copies alongside admin-ui's. Harmless today (proxied responses are JSON consumed by `fetch()`, not rendered documents; document responses are served locally by admin-ui), but the BFF should be the single header authority — add those names to `HOP_BY_HOP_HEADERS`/a strip-list and cover with a fixture that returns downstream headers. Ref: `emcip-admin-ui/src/main/java/io/emcip/adminui/ApiProxyController.java`. | LOW | P4 | XS | ⏳ |
| KE-TRUST | **Data-poisoning / retrieval trust for the knowledge base.** P2.5 fencing (RT2-006) contains *instruction* injection, but not false *facts* in retrieved content skewing summaries/reports/moderation — a different problem the regex ingestion gate cannot solve either. Not a 1.0 blocker (ingestion is operator-driven, not open crawling; the fencing closes the named finding). Post-1.0 hardening: source provenance/trust scoring, and an operator quarantine-review + re-ingest/override workflow for `FLAGGED_INJECTION_RISK` docs (today they are silently rejected — `KnowledgeQueryService.findAllByStatus` + Knowledge UI can surface them, but there is no review action). Do NOT "fix" this by expanding the injection regexes (false confidence). Ref: `DocumentIngestionService.INJECTION_PATTERNS`, `IngestionJob.IngestionStatus.FLAGGED_INJECTION_RISK`. | LOW | P4 | M | ⏳ |

---

## 1. Review-Driven Structural Changes

> All SC items complete. Full findings in `documentation/REVIEW-2026-05-18.md §8.2`.

| # | Item | Size | Done |
|---|------|------|------|
| SC1 | **Extract service layer** from controllers | S | ✅ PR #58 |
| SC2 | **Input validation** — `@Valid` on all request bodies | S | ✅ PR #62 |
| SC3 | **Replace ThreadLocal tenant** with Reactor `Context` | S | ✅ PR #60 |
| SC4 | **Multi-tenancy enforcement** — Hibernate @Filter, ReactorTenantContext | L | ✅ |
| SC5 | **Refactor `AuditEventConsumer`** — extract generic handler | S | ✅ PR #63 |
| SC6 | **Pagination enforcement** — `PageResponse<T>`, size cap 200 | M | ✅ PR #73 |
| SC7 | **Refresh token** — 1 h JWT, `/api/auth/refresh` | M | ✅ PR #73 |
| SC8 | **Circuit breakers** on all WebClient downstream calls | M | ✅ PR #73 |
| SC9 | **Network segmentation** in docker-compose | S | ✅ PR #63 |

---

## 2. Open — Feature Work

> Non-security feature work, ordered by dependency: items are ready to pick up unless "Needs" says otherwise.
> Security remediation lives in §0; its follow-ups in §0b. Sequenced against `ROADMAP.md` P4/P5.

| # | Item | Size | Needs | Notes |
|---|------|------|-------|-------|
| RT-F1 | **Per-user/IP rate limiting** | S | — | Current Resilience4j rate limiters (auth 10/min, llm-trigger 20/min, admin-crud 100/min) are global counters shared across all users. A single user can exhaust the quota for everyone. Implement per-IP bucketing on auth endpoints and per-user on authenticated endpoints using a custom `RateLimiterConfig` key resolver. Ref: RT-014, `SecurityConfig.java`, `AuthController.java`. Roadmap: P4. |
| RT-F5 | **BackfillService partial completion status** | XS | — | When backfill hits `MAX_ITERATIONS` (5000), status is set to `COMPLETED` despite truncation. Add `PARTIAL` status or include a warning in status metadata so the caller knows not all messages were processed. Ref: `BackfillService.java`. Roadmap: P4. |
| 45 | **Language detection for MESSAGE_LANGUAGE condition** | S | — | `MESSAGE_LANGUAGE` policy condition is a dead placeholder — the intent classifier never populates a `language` field. Add a lightweight language detection library (e.g. [Lingua](https://github.com/pemistahl/lingua)) to `IntentClassificationService`, populate `language` in the `IntentClassifiedEvent` params map. Enables rules like "flag messages not in DE or EN". Ref: `MessageLanguageEvaluator.java`, `IntentClassificationService.java`. Roadmap: P4. |
| 46 | **Unicode-aware REGEX case folding** | XS | — | Intent classifier compiles REGEX rules with `Pattern.CASE_INSENSITIVE` but not `Pattern.UNICODE_CASE`. German umlauts are not case-folded in regex patterns (e.g. `(?i)\bÄrger\b` won't match `ärger`). Fix: add `Pattern.UNICODE_CASE` flag alongside `CASE_INSENSITIVE` in `IntentClassificationService.refreshRules()`. Ref: `IntentClassificationService.java:81`. Roadmap: P4. |
| 8 | **ML toxicity detection** | XL | Architecture decision | Replace keyword/regex with model-based scorer (OpenNLP, Perspective API, or local LiteLLM). Architecture decision (ADR) needed first. Roadmap: P5. |

---

## 3. Infrastructure / Pre-1.0.0 Requirements

> All items below must be complete before any public release.
> Goal: `helm install` on a blank cluster must produce a fully working system with no manual steps.

| # | Item | Size | Notes |
|---|------|------|-------|
| INF1 | **Liquibase migration consolidation** | M | Each service has 6–10 incremental migrations, several with `md5sum='manual'` (root cause of 2026-05-20 AI Config 500). Squash to single `001-initial-schema.xml` per service before 1.0.0. |
| INF2 | **Fresh install smoke test** | S | After INF1: drop test DB, `helm install`, verify all pages. Document in `docs/operations/fresh-install.md`. |
| INF3 | **Telegram test account seeding via Helm values** | S | `testing.telegram.enabled` + account params → post-deploy Job inserts row into `telegram_accounts`. Removes manual DB access during dev. |
| 21 | **Tenant provisioning / onboarding flow** | M | No way to create a tenant without direct DB access. Needs admin-api endpoint + Liquibase-safe seed flow. Blocks 1.0.0. |
| 4 | **Test coverage to 80% (JaCoCo gate)** | L | Weakest: `moderation-service`, `audit-service`, `llm-orchestrator`. Phase 4 DoD requirement. |
| 14 | **Gatling load tests in CI** | S | 3 simulations exist (`IntentClassifierSimulation`, `AdminApiSimulation`, `PolicyEngineSimulation`). No CI gate yet. |
| 12 | **Kubernetes HA / multi-replica** | M | HPA templates, tuned `replicas`, PodDisruptionBudgets. Ref: `specs/2026-04-29-kubernetes-helm-deployment-design.md`. |

---

## 4. Deferred

> Not needed before 1.0.0. Revisit when cluster grows or a concrete use case arises.

| # | Item | Size | Notes |
|---|------|------|-------|
| PolicyRules-paging | **Paginate `PolicyRuleController.listActive()`** | XS | SC6 adds `.take(200)` safety cap. Full `PageResponse<T>` not needed — policy rules are config data and will stay small. Revisit if a tenant exceeds ~100 rules. |
| 20 | **Mixed-cluster: node taints + tolerations** | S | Fine-grained pod scheduling. `nodeSelector` is sufficient today. Ref: `specs/2026-05-02-mixed-cluster-helm-values-design.md`. |
| 19 | **Mixed-cluster: arm64 native images** | L | Cross-compile GraalVM native for Pi 4 nodes. Needs QEMU or dedicated arm64 runner. Ref: `specs/2026-05-02-mixed-cluster-helm-values-design.md`. |
| 13 | **GraalVM native — R2DBC services** | XL | 4 services JVM-only (`moderation-service`, `audit-service`, `admin-api`, `intent-classifier`). Blocked on R2DBC + GraalVM reflection hints. Ref: `specs/2026-04-29-graalvm-native-migration-design.md`. |
| RT-005 | **Kafka SASL authentication + topic ACLs** | M | Red Team finding RT-005 / LC-2. All services connect to Kafka without authentication; any pod in the cluster can produce/consume any topic. Currently acceptable because Kafka runs on a trusted internal network with no external exposure. Revisit when: multi-tenant cluster, external Kafka access, or compliance audit requires transport-level auth. Implementation: enable `SASL_PLAINTEXT` or `SASL_SSL`, per-service credentials, topic ACLs restricting produce/consume to owning services. Ref: `documentation/RED_TEAM_REPORT.md`. |
| RT-012 | **Kubernetes pod security hardening** — *promoted to P3.5 in `ROADMAP.md`.* | S | Pods run without `securityContext` restrictions — no `readOnlyRootFilesystem`, no `runAsNonRoot`, no `allowPrivilegeEscalation: false`, no dropped capabilities. Infrastructure-level hardening (not application code). Add `securityContext` to all Deployment templates in `helm/emcip/templates/apps/standard-deployments.yaml` and the StatefulSet. Ref: `documentation/RED_TEAM_REPORT.md`. |
| RT-013 | **Encrypt API keys at rest in database** — *no longer deferred; delivered as P2.0 (§0).* | — | Strategy decided 2026-07-23: application-level AES-256-GCM, key from a K8s Secret, never sent to Postgres. `pgcrypto` rejected (key would appear in SQL text and leak into `pg_stat_statements`/query logs); Vault deferred to P6, to swap in behind the same cipher boundary. Delivered under S5 / S-OPEN-1 / S-NEW-2, PR #209. Spec: `specs/2026-07-23-secrets-encryption-at-rest-design.md`. |

---

## 5. Completed

> One line per item. PR number + date is enough to find the full context in git / specs.

| # | Item | Done |
|---|------|------|
| INF4 | Refresh token cleanup job | ✅ PR #73 — 2026-05-21 |
| 9 | Telegram: self-service account connection | ✅ PR #80 — 2026-05-21. Spec: `specs/2026-05-21-telegram-self-service-rbac-design.md` |
| 10 | Telegram: multi-account scaling foundation | ✅ PR #85 — 2026-05-27. Spec: `specs/2026-05-26-tdlib-multi-account-scaling-design.md` |
| 25 | Group name + full sender info on flags and audits | ✅ PR #85 — 2026-05-27 |
| 23 | Flag-detail: operator reply panel (Phase 1) | ✅ PR #89 — 2026-05-27. Spec: `specs/2026-05-27-flag-detail-operator-reply-design.md` |
| 28 | Admin UI v2: design system + Groups page | ✅ PR #90 — 2026-05-28. Spec: `specs/2026-05-28-admin-ui-v2-design-system-design.md` |
| 29 | Admin UI v2: all page redesigns | ✅ PR #91/#94 — 2026-05-28/29 |
| 22 | Admin UI: cross-tenant views (ADMIN dropdown) | ✅ 2026-06-05 |
| 30 | Admin UI v2: remove v1 compat aliases | ✅ 2026-06-05 |
| 31 | Admin UI v2: delete design handoff directory | ✅ 2026-06-05 |
| 32 | Admin UI v2: SpaceBackground v3 (Otherland Sky) | ✅ 2026-06-05 |
| 33 | Policy rule: warn on live-effect actions in UI | ✅ 2026-06-05 |
| 34 | Architecture: rewire moderation-service off `telegram.raw.messages` | ✅ 2026-06-05. Spec: `specs/2026-06-05-moderation-service-rewire-design.md` |
| 35 | Self-host Inter Variable font | ✅ 2026-06-05 |
| 36 | Signal detectors: 9 structural/script abuse detectors | ✅ PR #115 — 2026-06-08. Spec: `specs/2026-06-08-signal-detectors-design.md` |
| 39 | Decisions page: filters, pagination, rename from Flags | ✅ PR #117 — 2026-06-09 |
| 26 | Knowledge Foundation — US-26.1 extensions, US-26.2 service bootstrap, US-26.3 ontology model | ✅ PR #122/#123 — 2026-06-13. US-26.4–26.10 all complete (see below). Spec: `specs/2026-06-13-knowledge-management-platform-design.md` |
| SC6b | Audit-log page: filters + pagination | ✅ 2026-06-14. Spec: `specs/2026-06-14-audit-log-filters-pagination-design.md` |
| 40 | SC8 resilience follow-ons: retry + read fallbacks | ✅ 2026-06-14. Spec: `specs/2026-06-14-sc8-resilience-follow-ons-design.md` |
| 24 | Flag-detail: AI analysis end-to-end fix | ✅ 2026-06-15. Spec: `specs/2026-06-14-ai-analysis-e2e-design.md` |
| 23 | Flag-detail: AI Research chat (Phase 2) | ✅ 2026-06-15. Spec: `specs/2026-06-15-ai-research-chat-design.md` |
| 7 | LLM cost analytics dashboard | ✅ 2026-06-15. Spec: `specs/2026-06-15-llm-cost-analytics-design.md` |
| 41b | Decisions reply composer v2 — 4-mode SegmentedControl, chip-row, char counter, NOTE backend | ✅ PR #130 — 2026-06-16. Spec: `specs/2026-06-15-reply-composer-v2-design.md` |
| 26.4 | Knowledge extraction pipeline — DLQ, metadata preservation, ontology-driven prompt, result validation | ✅ PR #132 — 2026-06-16. Spec: `specs/2026-06-16-knowledge-extraction-pipeline-design.md` |
| 26.5 | Entity resolution — embedding similarity (merge ≥ 0.92, flag ≥ 0.80), `ke_resolution_flags` queue | ✅ PR #133 — 2026-06-17. Spec: `specs/2026-06-16-entity-resolution-design.md` |
| 43 | Entity resolution review UI — Resolution Queue page, merge/dismiss with ConfirmDialog | ✅ PR #134 — 2026-06-17. Spec: `specs/2026-06-17-entity-resolution-review-ui-design.md` |
| 41a | Simulate page: two-column pipeline trace + correlationId fix in AuditEventConsumer | ✅ PR #135 — 2026-06-18. Spec: `specs/2026-06-17-simulate-pipeline-trace-design.md` |
| 26.6 | Live message fork — per-group `knowledgeForkEnabled` flag, conditional knowledge.raw.messages publish | ✅ PR #137 — 2026-06-18. Plan: `plans/2026-06-18-live-message-fork.md` |
| 26.7 | Bulk backfill — operator-triggered historical backfill, BackfillModal, per-group progress polling | ✅ PR #139 — 2026-06-18. Spec: `specs/2026-06-18-bulk-backfill-design.md` |
| 26.8 | Document ingestion (factual knowledge) — URL fetch + file upload (Tika), async jobs, Admin-UI Knowledge page + IngestionModal | ✅ PR #140 — 2026-06-19. Spec: `specs/2026-06-18-document-ingestion-design.md` |
| 26.9 | Knowledge query API — semantic search, graph exploration (topics, persons, neighbors), hybrid search, KnowledgePage Search tab | ✅ 2026-06-20 — implemented within PR #142 (Epic 42 branch). |
| 42 | Structured feed connectors — 13 connectors (Wikipedia, arXiv, PubMed, Wikidata, OpenAlex, SemanticScholar, CORE, DOAJ, Zenodo, Unpaywall, BioRxiv, Brave, Exa), EnrichmentConnectorRegistry, EnrichmentScheduler | ✅ PR #142 — 2026-06-20. |
| 26.10 | Knowledge enrichment for LLM responses — KnowledgeEngineClient, KnowledgeContextEnricherService wired into LlmCallService; feature-flagged via KNOWLEDGE_ENRICHMENT_ENABLED | ✅ PR #143 — 2026-06-20. Plan: `plans/2026-06-20-knowledge-enrichment-llm.md` |
| 41c | Users: expanded roles (MODERATOR, ANALYST, VIEWER) + lastLogin column + tenant selector for all non-ADMIN roles | ✅ 2026-06-22. Plan: `plans/2026-06-22-users-expanded-roles.md` |
| 27B | Deep Research Agent — web search (SearXNG connector, Brave fallback, US-27.3) + structured report generation (LLM synthesis, ke_research_reports, US-27.5) | ✅ PR branch — 2026-06-22. Plan: `plans/2026-06-22-deep-research-agent-plan-b.md` |
| 27C | Deep Research Agent — Admin UI: session list, Start Research modal, live polling, evidence table, report viewer (Markdown renderer + download), comparison view (US-27.6, 27.7, 27.8) | ✅ PR branch — 2026-06-22. Plan: `plans/2026-06-22-deep-research-agent-plan-c.md` |
| 27A | Deep Research Agent — backend: session lifecycle, strategy engine, execution loop, evidence with provenance, cost/depth guardrails (US-27.1, 27.2, 27.4, 27.9) | ✅ PR #145 — 2026-06-21. Plan: `plans/2026-06-20-deep-research-agent-plan-a.md` |
| 6 | Policy rule versioning — condition groups (AND/OR), 7 evaluator types, rule history snapshots, dry-run endpoint, admin-api proxy | ✅ 2026-06-23. Branch: `feat/42-knowledge-enrichment-connectors`. |
| 44 | Intent Rules management — CRUD endpoints, signal config (thresholds, toxicity words), Liquibase migrations, admin-api proxy, Admin UI page + Signal Config sub-page, intent dropdown in policy rules | ✅ PRs #155/#159 — 2026-06-25. |
| — | CI: knowledge-engine Docker build, custom postgres image (pgvector + AGE), GitHub Actions Node 24 update | ✅ PRs #149–#154, #161 — 2026-06-24/26. |
| — | Bugfixes: policy evaluator string params, costs NPE, research 400, knowledge search 500, LLM provider self-deactivation, intent-classifier datasource/Liquibase config, signal config JSONB default, knowledge-engine backfill auth | ✅ PRs #156–#167 — 2026-06-24/29. |
| — | Admin UI polish: Watched Groups rename, sticky columns, themed scrollbar, dot legend on Pipeline Trace, Global badge on tenant-less policy rules, research page error states | ✅ 2026-06-25/28. |
| — | Knowledge-engine safeguards — fetch timeout (30s), content size limit (10 MB), chunk cap (500), backfill iteration limit (5000), stuck-pagination detection, batch delay, frontend polling timeouts | ✅ 2026-06-30. Branch: `fix/knowledge-engine-backfill-auth`. |
| — | **Red Team remediation** — 16 quick wins + 10 structural changes. Wave 1: Kafka tenant fail-closed (RT-007), per-service DB users (RT-006), DB SSL (RT-016), ingress TLS/cert-manager (RT-032), audit publishing (RT-017), audit tamper resistance with hash chaining (RT-027). Wave 2: LLM prompt injection defense — boundary markers + output validation + ingestion scanning (RT-002/003/009), JWT revocation with jti tracking (RT-010). Standalone: rate limiting (RT-014), KE↔LLM-O circuit breakers (RT-025). Deferred: RT-005 Kafka SASL, RT-012 pod hardening, RT-013 encrypted API keys. Follow-ups: RT-F1–F5. | ✅ 2026-07-01. Branch: `fix/knowledge-engine-backfill-auth`. Spec: `specs/2026-06-30-red-team-remediation-design.md`. |
| P0 | Unified ROADMAP + absorb 2026-07-18 review findings into backlog | ✅ PR #205 — 2026-07-22. |
| RT-F2 | SSRF prevention on document ingestion (delivered as P2.2 / RT2-005) | ✅ PR #215 — 2026-07-29. Follow-ups SSRF-F1…F4 in §0b. |

---

## Documentation Audit (2026-05-16)

> **New diagram (2026-06-02):** `documentation/diagrams/kafka-topic-flow.puml` — topic-centric Kafka flow, all 10 topics, producer/consumer mapping.
> **New diagram (2026-06-13):** `documentation/diagrams/c3-knowledge-engine.puml` — Knowledge Engine component view.

Diagrams confirmed current during LiteLLM integration pass:

| Diagram | Covers |
|---|---|
| `c3-policy-engine.puml` | Policy Engine component view |
| `c3-tdlib-adapter.puml` | TDLib Adapter component view |
| `c4-policy-domain.puml` | Policy domain model |
| `sequence-error-handling.puml` | Retry, DLQ, and circuit breaker flow |
| `sequence-admin-auth.puml` | Admin UI JWT authentication flow |
| `sequence-policy-evaluation.puml` | Policy evaluation detail flow |
| `dataflow-context-enrichment.puml` | Conversation context enrichment data flow |
