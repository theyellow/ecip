# Secrets Encryption at Rest — Design Spec

**Date:** 2026-07-23
**Roadmap phase:** P2.5 + P2.6 (combined) — `documentation/ROADMAP.md`
**Findings closed:** S5 / S-OPEN-1 (CRITICAL), RT-013 / S-NEW-2 (MEDIUM)
**Modules:** `emcip-core`, `emcip-admin-api`, `emcip-knowledge-engine`, `emcip-llm-orchestrator`
**Delivery:** one PR

---

## Why these two roadmap items are one spec

`ROADMAP.md` lists 2.5 (Telegram `session_string` encryption) and 2.6 (API-key encryption at rest)
separately, and flags that 2.6 "needs a one-paragraph strategy decision before implementation".

They are the same problem: *reversibly encrypt a secret string in a Postgres column, in a system where
several services share one database*. Splitting them means deciding the crypto strategy twice — or,
worse, differently — and merging a half-encrypted system. They ship together.

## Facts established against current `main`

Verified before writing this spec, per the roadmap's verify-first principle:

| Fact | Evidence |
|------|----------|
| All services share **one** database `emcip` @ 14005 | `application.yml` of admin-api (`SPRING_R2DBC_URL`), knowledge-engine + llm-orchestrator (`SPRING_DATASOURCE_URL`) |
| `ke_vendor_api_keys` is written by admin-api over **R2DBC** and read by knowledge-engine over **JPA** | `VendorApiKeyRow` (`@Table`, `org.springframework.data.relational`) vs `VendorApiKey` (`@Entity`, `jakarta.persistence`) |
| `session_string` must be **reversible in admin-api** — it is sent to tdlib-adapter over HTTP | `TelegramAccountService.java:182`, `:412` (`payload.put("sessionString", ...)`) |
| `emcip-core` beans are **not** component-scanned by services | `@SpringBootApplication` sits on `io.emcip.admin.api.AdminApiApplication` etc.; `io.emcip.common` is outside those base packages |
| llm-orchestrator has **10** read sites of `getApiKey()` | `OrchestratorController` ×6, `OpenAiCompatibleLlmClient` ×4 |
| `VendorApiKeyResponse.maskKey()` reveals the **last 4 chars** | `VendorApiKeyResponse.java:24-27` |
| Helm already has a `secrets:` env-var → K8s-secret map | `helm/emcip/values.yaml:254-258` |

### Scope corrections to the roadmap

The roadmap names three columns. A sweep of every Liquibase changelog for secret-bearing columns
found two more items:

| Column | Table | Owner | Roadmap? |
|--------|-------|-------|----------|
| `session_string` | `telegram_accounts` | admin-api (R2DBC) | yes — S5 |
| `api_key` | `ke_vendor_api_keys` | admin-api (R2DBC) writes, knowledge-engine (JPA) reads | yes — RT-013 |
| `api_key` | `llm_provider_configs` | llm-orchestrator (JPA) | yes — S-NEW-2 |
| **`api_hash`** | `telegram_accounts` | admin-api (R2DBC) | **no — added** |
| `api_hash`, `session_string` | **`telegram_config`** | *nobody* | **no — table dropped** |

- **`telegram_accounts.api_hash` is in scope.** Together with `api_id` it is the Telegram application
  credential. It sits in the same row we are already rewriting; encrypting the session but not the
  hash beside it is indefensible and costs nothing extra.
- **`telegram_config` is dropped, not encrypted.** Created in changelog `006`, superseded by
  `telegram_accounts` in `007`, and referenced by **zero** Java files. Its committed seed row is only
  `id=1`, so nothing secret is in git — but any environment that once populated it still holds
  plaintext credentials in a table no code can read. Deleting beats encrypting.

`users.password_hash` (BCrypt) and `refresh_tokens.token_hash` are already one-way hashed and are
correctly **out of scope**.

---

## 1. The cipher — `emcip-core`

New package `io.emcip.common.crypto`.

### `SecretCipher`

AES-256-GCM.

| Method | Behaviour |
|--------|-----------|
| `String encrypt(String plaintext)` | Fresh 12-byte IV from `SecureRandom`, 128-bit auth tag. Returns `"v1:" + Base64(iv ‖ ciphertext ‖ tag)`. `null` in → `null` out. |
| `String decrypt(String stored)` | `v1:` prefix → decrypt. **No prefix → log one WARN and return the input unchanged** (legacy plaintext). `null` in → `null` out. |
| `boolean isEncrypted(String value)` | `v1:` prefix test, for the backfill runners. |

The WARN identifies the logical location (e.g. `plaintext secret encountered in
ke_vendor_api_keys.api_key`) and **never** logs the value, the plaintext, or any prefix of either.

Tampered ciphertext surfaces as `AEADBadTagException` from the JCE and is allowed to propagate — a
corrupted secret must fail loudly, not silently degrade to an empty string.

### `SecretCipherConfig`

Reads `EMCIP_SECRET_KEY` — Base64 that must decode to exactly 32 bytes. **Fails fast at startup**
otherwise, with a message that does not echo the value.

Fail-fast is safe precisely because `io.emcip.common` is outside every service's component-scan base
package: only services that explicitly `@Import(SecretCipherConfig.class)` require the key.
audit-service, policy-engine, moderation-service, intent-classifier, tdlib-adapter and
conversation-context are unaffected and need no key.

### Deliberately excluded

- **GCM additional-authenticated-data** binding ciphertext to its table/column. An attacker who can
  rewrite that column can rewrite the whole row anyway, so AAD buys no real defence here.
- **Multi-key rotation.** The `v1:` prefix is the *hook* that makes rotation addable later without
  touching stored data — that is all this phase needs. Rotation mechanics belong with the P6 secrets
  ADR, and this spec's cipher boundary is exactly what a future KMS/Vault backend swaps behind.

---

## 2. Persistence wiring

### JPA — knowledge-engine, llm-orchestrator

`EncryptedStringConverter implements AttributeConverter<String, String>`, applied with `@Convert` to:

- `VendorApiKey.apiKey` (knowledge-engine)
- `LlmProviderConfig.apiKey` (llm-orchestrator)

A converter, not service-layer calls, because there are 10 `getApiKey()` read sites in llm-orchestrator
alone plus knowledge-engine's `ApiKeyResolver.java:25` and `WebSearchService.java:75` method
references. The converter makes every one of them correct with zero edits and leaves no read path that
can bypass decryption.

**Must be verified, not assumed:** Hibernate instantiates `AttributeConverter`s itself; constructor
injection of `SecretCipher` works only because Spring Boot registers a `SpringBeanContainer` as
Hibernate's bean container. The implementation plan proves this with an integration test that loads the
real application context before anything else is built on top of it.

**Fallback if that fails:** service-layer encrypt/decrypt, identical to the R2DBC approach below. This
is a known-good escape hatch, not a redesign.

### R2DBC — admin-api

Spring Data R2DBC has no `AttributeConverter` equivalent, so encryption is explicit in the service
layer:

- `TelegramAccountService` — encrypt `sessionString` and `apiHash` on write; decrypt before the two
  tdlib-adapter payload sites (`:182`, `:412`) and anywhere else the values leave the service.
- `VendorApiKeyService` — encrypt `apiKey` in `createGlobal`, `upsertForTenant` (both branches) and
  `update`; decrypt on read.

**Masking bug this would otherwise introduce:** `VendorApiKeyResponse.maskKey()` renders
`"••••••••" + last 4 chars` — users identify a key by its tail. Handed ciphertext it would show four
characters of Base64, which is meaningless and looks like corruption. `VendorApiKeyResponse.from()`
must therefore receive the **decrypted** value; masking moves behind the service boundary rather than
reading the raw row.

---

## 3. Schema migration

One Liquibase changeset per owning service. **Liquibase only — no Flyway.**

**admin-api:**
- `telegram_accounts.api_hash`: `VARCHAR(255)` → `TEXT`
- `DROP TABLE telegram_config` (with a rollback block recreating the empty table)

**knowledge-engine:**
- `ke_vendor_api_keys.api_key`: `VARCHAR(512)` → `TEXT`

**llm-orchestrator:**
- `llm_provider_configs.api_key`: `VARCHAR(512)` → `TEXT`

`telegram_accounts.session_string` is already `TEXT`.

Widening is **mandatory, not cosmetic**: a 512-character API key becomes roughly 723 characters of
`v1:`-prefixed Base64 (`3 + ceil((12 + n + 16) / 3) * 4`), which does not fit `VARCHAR(512)`. Without
this step the first encrypted write fails at the database.

Note the split: `ke_vendor_api_keys` is *written* by admin-api but its table was *created* by
knowledge-engine (`changes/011-create-vendor-api-keys.xml`), so the widening changeset goes in
knowledge-engine's changelog — schema ownership stays where the table lives, even though the encrypting
writer lives elsewhere.

---

## 4. Backfill

`SecretBackfillRunner` — an `ApplicationRunner` in each **owning** service:

- **admin-api** — `telegram_accounts.session_string`, `telegram_accounts.api_hash`,
  `ke_vendor_api_keys.api_key`
- **llm-orchestrator** — `llm_provider_configs.api_key`

For each row, any value failing `isEncrypted()` is encrypted and written back. **Idempotent**: it
converges after one run and is a no-op on every restart thereafter. Row counts here are in the dozens,
so a full scan costs milliseconds and needs no batching or paging.

Each runner logs a single summary line: rows scanned, rows encrypted. It logs no secret values.

**Why tolerant reads rather than fail-closed:** this is the P1 lesson applied. Batch C of P1 added a
fail-closed tenant check without checking who produced the message and bricked all manual enrichment.
A strict reader here would have the same shape of failure: during a rolling deploy, knowledge-engine
can restart and read `ke_vendor_api_keys` before admin-api's backfill has run. Tolerant reads make that
ordering irrelevant by construction.

**Exit criterion for strict mode:** zero plaintext-WARNs across all services over a full deployment
cycle. Flipping the reader to fail-closed is a **separate follow-up item** (new BACKLOG row, P3/P4) and
is explicitly *not* in this PR.

---

## 5. Testing

**Unit — `SecretCipher`:**
- round-trip: `decrypt(encrypt(x)) == x`
- encrypting the same plaintext twice yields different ciphertext (fresh IV per call)
- tampered ciphertext → `AEADBadTagException`
- unprefixed input passes through unchanged and emits exactly one WARN
- `null` in → `null` out, both directions
- `SecretCipherConfig` rejects a missing key, a non-Base64 key, and a key that is not 32 bytes

**Integration (Testcontainers):**
- **cross-stack contract:** write a vendor key through admin-api's R2DBC path, read it through
  knowledge-engine's JPA path, assert the plaintext matches. This is the test the shared
  `ke_vendor_api_keys` table demands and the one most likely to catch a real regression.
- raw SQL `SELECT api_key FROM ke_vendor_api_keys` asserts the stored value starts with `v1:` and does
  **not** contain the plaintext
- `EncryptedStringConverter` receives an injected `SecretCipher` in a real application context
  (proves the `SpringBeanContainer` assumption from §2)
- `TelegramAccountService` round-trips `sessionString` and `apiHash`

**Backfill:**
- a plaintext row is encrypted on first run
- a second run is a no-op — no double-encryption, `decrypt` still returns the original plaintext
- a table already fully encrypted produces zero writes

**Regression:**
- `VendorApiKeyResponse.maskKey()` shows the last 4 characters of the **plaintext** key, not of the
  ciphertext

---

## 6. Rollout

Add to the `secrets:` map in `helm/emcip/values.yaml` for admin-api, knowledge-engine and
llm-orchestrator, following the existing pattern at `values.yaml:254`:

```yaml
    secrets:
      EMCIP_SECRET_KEY: emcip-secret-key
```

Key generated with `openssl rand -base64 32` and **never committed** — created as a K8s Secret out of
band. Documented in `docs/operations/`, including: how to generate the key, that losing it makes every
stored secret unrecoverable (Telegram sessions must be re-authenticated and vendor keys re-entered),
and how to confirm the backfill ran.

Deploy ordering is race-free by construction — a reader that starts before the backfill sees plaintext,
logs a WARN, and works.

**Local dev / docker-compose:** a default key is supplied via `docker-compose.yml` env so the stack
still comes up without manual setup. `application.yml` carries **no** default value — a missing key
fails startup, matching how `ADMIN_JWT_SECRET` would be treated in production and preventing a shipped
default from becoming the production key.

---

## 7. Documentation updates

Per the project documentation checklist, this change also updates:

- `docs/operations/` — key generation, rotation-is-not-yet-supported note, key-loss recovery
- `docs/superpowers/BACKLOG.md` — S5/S-OPEN-1 and RT-013/S-NEW-2 → done; new rows for the strict-mode
  flip and for `telegram_config` removal
- `documentation/ROADMAP.md` — P2.5 and P2.6 marked delivered as one PR
- any architecture `.adoc` / PlantUML that depicts these tables or the secret-handling flow

---

## Out of scope

| Item | Where it belongs |
|------|------------------|
| Key rotation mechanics (multi-key decrypt, re-encrypt command) | P6 secrets ADR |
| KMS / Vault backend | P6 — swaps in behind this spec's cipher boundary |
| Flipping reads to fail-closed strict mode | follow-up item, P3/P4 |
| Encrypting `users.password_hash`, `refresh_tokens.token_hash` | never — already one-way hashed |
| Transport encryption of `sessionString` to tdlib-adapter | separate concern; this is encryption **at rest** |
| Database-level TLS (`DB_SSL_MODE` currently `disable`) | P3 K8s hardening |

---

## Acceptance criteria

1. No plaintext secret remains in `telegram_accounts.session_string`, `telegram_accounts.api_hash`,
   `ke_vendor_api_keys.api_key` or `llm_provider_configs.api_key` after startup — verified by raw SQL.
2. `telegram_config` no longer exists.
3. A vendor key written by admin-api is readable by knowledge-engine, and vice versa.
4. The Admin UI still shows the last 4 characters of the real key.
5. All three services fail to start without a valid `EMCIP_SECRET_KEY`; no other service is affected.
6. Running the backfill twice changes nothing the second time.
7. `mvn spotless:apply` reports `0 were changed to be clean`; PMD passes; full build green.
