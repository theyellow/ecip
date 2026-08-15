# Secrets Encryption at Rest — Design Spec

**Date:** 2026-07-23
**Roadmap phase:** P2.5 + P2.6 (combined) — `documentation/ROADMAP.md`
**Findings closed:** S5 / S-OPEN-1 (CRITICAL), RT-013 / S-NEW-2 (MEDIUM)
**Modules:** `emcip-core`, `emcip-admin-api`, `emcip-knowledge-engine`, `emcip-llm-orchestrator`
**Delivery:** one PR

> **Follow-up delivered:** the "planned hardening" referenced throughout this spec (flipping from
> lenient reads to a stricter startup check) is delivered by
> `2026-08-15-secrets-startup-self-check-design.md` (ROADMAP P3.7, BACKLOG P2.0-F1). That later spec
> also corrects a premise error in how this hardening was described: reads were never lenient —
> `SecretCipher.decrypt()` has thrown on any unprefixed value since this spec shipped — the actual gap
> was *when* plaintext is discovered (lazily, on first read) rather than how strictly it was handled.

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
| `api_hash`, `session_string` | **`telegram_config`** | *nobody* | **no — already dropped by changelog 007** |

- **`telegram_accounts.api_hash` is in scope.** Together with `api_id` it is the Telegram application
  credential. It sits in the same row we are already rewriting; encrypting the session but not the
  hash beside it is indefensible and costs nothing extra.
- **`telegram_config` needs nothing — already gone.** *(Corrected 2026-07-24 during implementation.)*
  It was created in changelog `006` and this design assumed it lingered as an orphan holding plaintext
  credentials. On verification, changeset `007-drop-telegram-config`
  (`changes/007-telegram-accounts.xml`) **already drops it** as the first step of the migration to
  `telegram_accounts` — so it does not exist in any environment past changelog 007, and there is
  nothing to encrypt or drop. The originally-planned `DROP TABLE telegram_config` was removed: dropping
  a nonexistent table fails Liquibase. This is a verify-first miss — the `006` `createTable` was seen
  during design, but the `007` drop was not checked.

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
| `String decrypt(String stored)` | `v1:` prefix → decrypt. **No prefix → throw `IllegalStateException`** — fail closed, see §4. `null` in → `null` out. |
| `boolean isEncrypted(String value)` | `v1:` prefix test, used by the migration CLI and by tests. |

The `IllegalStateException` message identifies the logical location (e.g. `plaintext secret in
ke_vendor_api_keys.api_key — run the migration runbook`) and **never** contains the value, the
plaintext, or any prefix of either.

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
- ~~`DROP TABLE telegram_config`~~ — removed; changelog `007` already dropped it (see the scope note
  above).

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

## 4. Strict reads, hand-run migration

**Reads are fail-closed.** A stored value without the `v1:` prefix throws; no code path can silently
use a plaintext secret, and there is no tolerant mode to remove later. **There is no backfill runner** —
the existing rows are migrated **by hand** against the cluster database, once, before the new pods
start.

This is the deliberate opposite of the P1 tenant-check lesson, so the trade-off is recorded explicitly:

| Risk it accepts | Why it is acceptable here |
|-----------------|---------------------------|
| The deploy becomes **order-sensitive** — a service reading an unmigrated row hard-fails | The affected tables hold **dozens of rows**, all under the operator's hand, and every service runs at `replicas: 1` in the current Helm values. This is a single-operator maintenance window, not a rolling multi-replica upgrade. |
| A missed row breaks a feature at runtime | Failure is **loud and immediate** — an exception naming the exact table and column — not a silent plaintext read. That is the correct failure direction for a secret. |
| No code enforces convergence | §5 makes "no plaintext remains" a verified acceptance criterion via raw SQL, not an assumption. |

The distinction from P1: there, a fail-closed check was added to a path whose *producer* nobody had
read, so failure was **unbounded and unknown**. Here the entire population of affected rows is
enumerable with four `SELECT`s and is migrated by the same person running the deploy.

### `SecretCipherCli` — why a tool is required

AES-256-GCM ciphertext **cannot be produced with standard shell tooling** — `openssl enc` does not
support AEAD modes, so there is no `psql`-and-`openssl` one-liner that yields a valid `v1:` value. The
migration therefore ships a minimal main class in `emcip-core`:

```
java -cp emcip-core.jar io.emcip.common.crypto.SecretCipherCli encrypt '<plaintext>'
→ v1:9f2a...==
```

It reads `EMCIP_SECRET_KEY` from the environment exactly as the services do, takes the plaintext as an
argument, and prints the encrypted value to stdout. It has an `isEncrypted` subcommand for checking a
value already in the database. This is not scope creep — without it the chosen migration strategy has
no execution path, and the same tool is what a future key rotation will be built on.

**Operational caution to document:** the plaintext appears in the shell command, so it lands in shell
history. The runbook instructs the operator to clear it afterwards, or to pipe from stdin.

### Runbook (documented in `docs/operations/`)

Liquibase runs **at service startup** in all three services, so the columns cannot be widened while
they are scaled to zero. The order below reflects that: deploy first, migrate second, and accept a
short window in which secret-reading features fail loudly.

1. Create the `emcip-secret-key` K8s Secret.
2. Deploy the new images. Pods start normally — decryption happens on demand, not at boot — and
   Liquibase widens the columns (§3). **From here until step 4, any feature reading one of the four
   secrets fails with the strict-mode exception.** This is the maintenance window.
3. For each of the four columns: read the current plaintext, generate ciphertext with
   `SecretCipherCli`, and `UPDATE` each row **by primary key**. There is no `psql` on the workstation,
   so this runs through the Postgres pod:
   `microk8s.kubectl exec -it <postgres-pod> -- psql -U emcip -d emcip`
4. Verify: every row in all four columns starts with `v1:` — one `SELECT` per column.
5. Restart the three services, so that anything holding a decrypted secret in memory re-reads it.

**Window length is bounded by step 3**, which is a handful of `UPDATE`s against dozens of rows. If it
must be zero instead, run the widening migration ahead of the deploy with the Liquibase Maven plugin
pointed at the cluster database, migrate the values, and only then deploy — at the cost of applying
schema changes outside the normal service-startup path.

**Fallback requiring no CLI:** set the columns to `NULL` and re-enter the secrets through the Admin UI
after deploy, which writes them encrypted. Cheap for the vendor and LLM-provider keys; for
`telegram_accounts` it means re-authenticating each Telegram account (phone + login code), so the CLI
path is preferred there.

---

## 5. Testing

**Unit — `SecretCipher`:**
- round-trip: `decrypt(encrypt(x)) == x`
- encrypting the same plaintext twice yields different ciphertext (fresh IV per call)
- tampered ciphertext → `AEADBadTagException`
- **unprefixed input throws `IllegalStateException`, and the message contains neither the value nor
  any prefix of it** — the core strict-mode guarantee
- `null` in → `null` out, both directions
- `SecretCipherConfig` rejects a missing key, a non-Base64 key, and a key that is not 32 bytes
- `SecretCipherCli encrypt` output round-trips through `SecretCipher.decrypt`, so what the operator
  pastes into an `UPDATE` is exactly what the service can read back

**Integration (Testcontainers):**
- **cross-stack contract:** write a vendor key through admin-api's R2DBC path, read it through
  knowledge-engine's JPA path, assert the plaintext matches. This is the test the shared
  `ke_vendor_api_keys` table demands and the one most likely to catch a real regression.
- raw SQL `SELECT api_key FROM ke_vendor_api_keys` asserts the stored value starts with `v1:` and does
  **not** contain the plaintext
- `EncryptedStringConverter` receives an injected `SecretCipher` in a real application context
  (proves the `SpringBeanContainer` assumption from §2)
- `TelegramAccountService` round-trips `sessionString` and `apiHash`

**Strict-mode failure path:**
- a row seeded with a plaintext value causes the reading service to fail with an
  `IllegalStateException` naming the table and column — proving the migration cannot be skipped
  unnoticed. This test **is** the safety net that replaces the deleted backfill runner, so it is not
  optional.

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
and the §4 migration runbook.

**Deploy ordering is significant and must be followed** — the three services stay scaled to zero until
the hand-run migration in §4 is complete and verified. A service started against unmigrated rows will
fail on read, by design.

**Local dev / docker-compose:** a default key is supplied via `docker-compose.yml` env so the stack
still comes up without manual setup. `application.yml` carries **no** default value — a missing key
fails startup, matching how `ADMIN_JWT_SECRET` would be treated in production and preventing a shipped
default from becoming the production key.

---

## 7. Documentation updates

Per the project documentation checklist, this change also updates:

- `docs/operations/` — key generation, the §4 migration runbook, rotation-is-not-yet-supported note,
  key-loss recovery
- `docs/superpowers/BACKLOG.md` — S5/S-OPEN-1 and RT-013/S-NEW-2 → done
- `documentation/ROADMAP.md` — P2.5 and P2.6 marked delivered as one PR
- any architecture `.adoc` / PlantUML that depicts these tables or the secret-handling flow

---

## Out of scope

| Item | Where it belongs |
|------|------------------|
| Key rotation mechanics (multi-key decrypt, re-encrypt command) | P6 secrets ADR — built on `SecretCipherCli` |
| KMS / Vault backend | P6 — swaps in behind this spec's cipher boundary |
| Automated backfill of legacy rows | **not built** — migration is hand-run per §4 |
| Encrypting `users.password_hash`, `refresh_tokens.token_hash` | never — already one-way hashed |
| Transport encryption of `sessionString` to tdlib-adapter | separate concern; this is encryption **at rest** |
| Database-level TLS (`DB_SSL_MODE` currently `disable`) | P3 K8s hardening |

---

## Acceptance criteria

1. No plaintext secret remains in `telegram_accounts.session_string`, `telegram_accounts.api_hash`,
   `ke_vendor_api_keys.api_key` or `llm_provider_configs.api_key` — verified by raw SQL against the
   cluster database after the §4 runbook, every value starting with `v1:`.
2. `telegram_config` no longer exists — already guaranteed by changelog `007`; this change adds no
   drop.
3. A vendor key written by admin-api is readable by knowledge-engine, and vice versa.
4. The Admin UI still shows the last 4 characters of the real key.
5. All three services fail to start without a valid `EMCIP_SECRET_KEY`; no other service is affected.
6. A plaintext value in any of the four columns causes a loud `IllegalStateException` naming that
   column — never a silent plaintext read.
7. `SecretCipherCli` output pasted into an `UPDATE` is readable by the running service.
8. `mvn spotless:apply` reports `0 were changed to be clean`; PMD passes; full build green.
