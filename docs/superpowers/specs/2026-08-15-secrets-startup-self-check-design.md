# Secrets startup self-check — design

> **Roadmap**: P3 3.7 (Tier ② Security) · **Backlog ID**: P2.0-F1
> **Created**: 2026-08-15 · **Status**: approved, pending implementation plan
> **Follows**: `2026-07-23-secrets-encryption-at-rest-design.md` (P2.0, PR #209)

---

## 0. Premise correction

ROADMAP 3.7 and BACKLOG P2.0-F1 both describe this work as *"flip encryption reads from
lenient-fail-closed to a startup self-check."*

**Reads are not lenient.** `SecretCipher.decrypt()` has thrown `PlaintextSecretException` on any
value lacking the `v1:` prefix since P2.0 (`SecretCipher.java:93-95`), and both admin-api
(`GlobalExceptionHandler:121`) and llm-orchestrator (`SecretExceptionHandler:29`) already translate
it into a clean 409 rather than a 500.

The real gap is **when** a plaintext row is discovered: lazily, on the first operator action that
touches it, rather than at boot. This spec closes that gap. It does not change read strictness, and
the roadmap/backlog wording is corrected as part of the delivery.

---

## 1. Constraint that shapes the whole design

PRs #241 (llm-orchestrator) and #243 (TG-REENTER, telegram) built **in-product repair paths** for
exactly this failure: a legacy plaintext row now returns a 409 that opens a Credentials dialog, and
the operator re-enters the secret without the service ever decrypting the old value.

**Those paths require the service to be running.** A self-check that hard-fails the pod on any
plaintext row would deadlock precisely the scenario those two PRs were built to fix — the operator
cannot reach the repair UI because the service will not boot, and recovery drops back to direct DB
access, which P2.0's runbook was written to avoid.

Therefore: **discovery moves to boot; enforcement stays at read.** Hard-fail is available but
opt-in, per environment, and only after that environment has reported clean.

---

## 2. Component

`io.emcip.common.crypto.SecretsSelfCheck` in **emcip-core**, registered as a Spring
`ApplicationRunner`.

`ApplicationRunner` rather than `@EventListener(ApplicationReadyEvent)` for two reasons: an
exception thrown from a runner fails `SpringApplication.run()` cleanly (which is the `fail` mode
semantics), and runners execute *before* the application reports ready, so a failing check never
briefly serves traffic.

It consumes a `List<SecretColumn>`:

```java
public record SecretColumn(String table, String column, String pkColumn) {}
```

Each affected service contributes its own list as a `@Bean`, alongside its existing `CryptoConfig`:

| Service | Columns |
|---------|---------|
| admin-api | `telegram_accounts.api_hash`, `telegram_accounts.session_string` |
| knowledge-engine | `ke_vendor_api_keys.api_key` |
| llm-orchestrator | `llm_provider_configs.api_key` |

Services that do not `@Import(SecretCipherConfig.class)` contribute no list and are entirely
unaffected — audit-service, policy-engine, moderation-service, intent-classifier, tdlib-adapter and
conversation-context. This is the same isolation property P2.0 relied on for key-absence fail-fast.

### Persistence access

The check runs over **plain JDBC** in all three services, uniformly.

This is possible because all three already declare a blocking `spring.datasource` — admin-api's
exists for Liquibase (`emcip-admin-api/src/main/resources/application.yml:20-24`) even though its
runtime path is R2DBC. A startup-only blocking query therefore never touches admin-api's reactive
request path, and uses the same connection style Liquibase already opens there at boot.

**Accepted trade-off:** admin-api's `spring.datasource` carries the *Liquibase* credential, which
RT-006 made distinct from the runtime user. The check consequently does not prove the runtime R2DBC
user can read those rows. It is a read-only count at boot and both findings (plaintext present, key
works) are user-independent, so this is logged as a follow-up rather than designed around. An
R2DBC scanner adapter is the fix if that ever matters.

---

## 3. What the check does per column

```sql
-- Finding PLAINTEXT: tally, no decryption
SELECT count(*) FROM <table>
 WHERE <column> IS NOT NULL AND <column> NOT LIKE 'v1:%';

-- Finding PLAINTEXT: identify for the operator — primary keys only, never values
SELECT <pk> FROM <table>
 WHERE <column> IS NOT NULL AND <column> NOT LIKE 'v1:%' LIMIT 20;

-- Finding KEY_MISMATCH: prove the mounted key decrypts real stored data
SELECT <column> FROM <table> WHERE <column> LIKE 'v1:%' LIMIT 1;
-- then SecretCipher.decrypt(...) and discard the result
```

Four outcomes per column, never conflated:

| Outcome | Meaning |
|---------|---------|
| `OK` | zero plaintext rows, and one encrypted row decrypted successfully |
| `PLAINTEXT` | n rows lack the `v1:` prefix — repairable via the Admin UI Credentials dialog |
| `KEY_MISMATCH` | a `v1:` row exists but the mounted `EMCIP_SECRET_KEY` cannot decrypt it |
| `UNVERIFIED` | no encrypted rows exist, so the key could not be proven against real data |

`KEY_MISMATCH` is the finding a prefix-only scan is structurally blind to: with the wrong key
mounted, every row still starts with `v1:` and a naive scan reports a clean bill of health while
every secret in the system is unreadable. This is the "check that cannot fail" shape P3.4 caught
three times, and avoiding it is why the key proof is in scope.

`UNVERIFIED` is deliberately **not** folded into `OK`: an empty column must not be able to
masquerade as a passing check.

---

## 4. Modes

Config key `emcip.secrets.self-check`, declared in each affected service's `application.yml`.
Default **`warn`**.

| Mode | Behaviour |
|------|-----------|
| `warn` *(default)* | Log `ERROR` with the full inventory, set metrics, **boot normally.** Keeps the #241/#243 repair UI reachable. |
| `fail` | Log the same, then throw — the pod refuses to start. Opt-in per environment, only once that environment has reported clean. |
| `off` | Skip entirely. Local dev and tests that do not exercise this. |

`warn` is the shipping default everywhere. Promotion to `fail` is an operator action documented in
the runbook, not something this change does.

---

## 5. Surfacing

**Log** — one `ERROR` block at startup listing `table.column`, outcome, row counts and offending
primary keys. It must never contain a secret value, a ciphertext, or any prefix of either, matching
the discipline `PlaintextSecretException` already enforces.

```
ERROR  SECRET SELF-CHECK  mode=warn
  telegram_accounts.api_hash          12 encrypted,    1 plaintext  [PLAINTEXT]
    offending id: [...]
  telegram_accounts.session_string    12 encrypted,    0 plaintext  [OK]
  ke_vendor_api_keys.api_key           4 encrypted,    0 plaintext  [OK]
  llm_provider_configs.api_key         0 encrypted,    3 plaintext  [PLAINTEXT]
  Repair plaintext values via Admin UI -> Credentials. See docs/operations/secrets-encryption.md
```

**Metrics** — the alertable surface, consumed by P3.22:

- `emcip.secrets.plaintext_count{column="<table>.<column>"}` — gauge, 0 when clean
- `emcip.secrets.key_status{column="<table>.<column>"}` — gauge, `0` OK / `1` mismatch / `2` unverified

**Health** — dropped before merge. The original design here had `SecretsHealthIndicator` report
`UP` always, carrying `mode`, per-column outcome and counts in `details`. That indicator was deleted
during the pre-merge review: every one of the three wiring services sets
`management.endpoint.health.show-details: never` (a deliberate P1.4 hardening decision, unrelated to
this change and not being revisited here), so Spring renders only `{"status":"UP"}` at
`/actuator/health` and omits the `details`/`components` block entirely. Every `withDetail(...)` the
indicator built was therefore unreachable — it was a no-op from the day it shipped. The metrics above
are the only surface that was ever actually going to alert; see
`docs/operations/secrets-encryption.md` for how to read them.

### Re-scan

The check re-runs on a schedule in addition to startup — **hourly at an offset time** (e.g.
`17m 23s` past, per CLAUDE.md rule 6, never a round `:00.000`).

Startup-only scanning would leave the gauge pinned at `1` after an operator repairs a row, until
the next pod restart. An alert that cannot clear is close to as harmful as a check that cannot
fire. The cost is four `COUNT(*)`s against tables holding dozens of rows.

The scheduled re-scan **never** fails the application, in any mode — `fail` governs startup only.
Killing a healthy running pod because a new plaintext row appeared would be a self-inflicted
outage, and the metric plus log already carry the signal.

---

## 6. Testing

Per the P3.4 verification lesson — *a check that has never been observed failing is not evidence* —
every assertion below must be seen failing against a deliberately broken fixture at least once, and
that is a review requirement, not an aspiration.

**Integration (Testcontainers, one per persistence style — JPA and the admin-api/R2DBC service):**

- Insert a genuinely plaintext row → `warn` boots, reports `PLAINTEXT`, count 1.
- Same fixture, `fail` → `SpringApplication.run()` throws and the context does not start.
- Repair the row to a `v1:` value → the next scan reports count 0. Proves the gauge can clear.
- `off` → no queries issued at all.

**Wrong-key:**

- Encrypt with key A, boot with key B → `KEY_MISMATCH`, and assert it is **not** reported as
  `PLAINTEXT`. The two findings must not be conflatable.

**Empty column:**

- No rows at all → `UNVERIFIED`, explicitly asserted not to be `OK`.

**Leak assertions:**

- Captured log output and the health `details` payload contain neither the plaintext value, the
  ciphertext, nor any prefix of either — asserted against a fixture whose secret is a known
  sentinel string.

---

## 7. Live verification

P3.6's transferable lesson was that the value no test could check was the one that was wrong
(`trusted-proxy-hops` was 2, silently, while the correct value was 1). This change is exposed to
the same class of error: the whole feature is a claim about **live data**, which no test can
observe.

So the delivery includes a live step, not just a green build:

1. Deploy the three services in `warn`.
2. Read `emcip.secrets.plaintext_count` and `emcip.secrets.key_status` off the live cluster.
3. Record the actual per-column state in `docs/operations/secrets-encryption.md`.

The expected result is **not** "clean". PR #243 fixed a legacy plaintext `api_hash` lockout on
2026-08-10; assuming the cluster now reports zero plaintext is exactly the kind of assumption this
check exists to replace. Whatever it reports is the finding.

Promotion of any environment to `fail` is a **separate, later** decision, taken on the evidence
this step produces. It is out of scope for this change.

---

## 8. Documentation

Per `documentation-checklist`:

- `docs/operations/secrets-encryption.md` — new self-check section: what each outcome means, how to
  read the metric, how to repair a `PLAINTEXT` row via the Admin UI, and the procedure for
  promoting an environment to `fail`.
- `docs/superpowers/specs/2026-07-23-secrets-encryption-at-rest-design.md` — note that the planned
  hardening is delivered here.
- `docs/superpowers/BACKLOG.md` — P2.0-F1 → ✅ with a delivered-note; file the admin-api
  Liquibase-credential gap (§2) as a new follow-up.
- `documentation/ROADMAP.md` — 3.7 → ✅, including the §0 premise correction.

---

## 9. Deliberately excluded

- **Automatic re-encryption of plaintext rows.** The check reports; it never writes. Repair goes
  through the #241/#243 operator paths, which re-enter the secret rather than decrypting a value
  the service by definition cannot read.
- **Surfacing offending rows in the Admin UI.** The existing feature pages already 409 into the
  Credentials dialog. A second inventory screen is scope this change does not need.
- **Promoting any environment to `fail`.** Ships as `warn` everywhere; promotion is an operator
  decision on live evidence (§7).
- **An R2DBC scanner for admin-api.** See §2 — logged as a follow-up.
