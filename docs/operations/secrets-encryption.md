# Secrets Encryption at Rest — Operations

Four columns are encrypted with AES-256-GCM (`v1:`-prefixed base64 of `iv || ciphertext || tag`):

| Column | Table | Written by |
|--------|-------|------------|
| `session_string` | `telegram_accounts` | nothing today — always NULL |
| `api_hash` | `telegram_accounts` | admin-api |
| `api_key` | `ke_vendor_api_keys` | admin-api |
| `api_key` | `llm_provider_configs` | llm-orchestrator |

Reads are **fail-closed**: a value without the `v1:` prefix raises an error naming the column. There is
no automatic backfill.

## Generating the key

`emcip-secret-key` is a **key inside the existing `emcip-secrets` Secret**, not a Secret of its own.
The chart wires it as `secretKeyRef: {name: emcip-secrets, key: emcip-secret-key}`, and the ref is
**not** `optional` — if the key is absent, admin-api, knowledge-engine and llm-orchestrator fail to
start. No other service needs it.

Use the helper, which generates the key, adds it, and verifies it decodes to 32 bytes:

```bash
./scripts/add-secret-key.sh
```

Or by hand:

```bash
KEY=$(openssl rand -base64 32)
echo "$KEY"   # back this up before continuing — there is no recovery
microk8s.kubectl patch secret emcip-secrets -n emcip --type=json \
  -p="[{\"op\":\"add\",\"path\":\"/data/emcip-secret-key\",\"value\":\"$(printf %s "$KEY" | base64 -w0)\"}]"
```

Note the double encoding: Kubernetes Secret values are base64, and the key itself is already a
base64 string, so the stored value is base64-of-base64. Verify rather than assume — a truncated
paste fails later in a confusing way:

```bash
microk8s.kubectl get secret emcip-secrets -n emcip \
  -o jsonpath='{.data.emcip-secret-key}' | base64 -d | base64 -d | wc -c   # must print 32
```

**Never commit the key.**

> **Existing clusters:** a release created before this key existed will not have the env var wired
> at all, because the `secretKeyRef` came with a later chart revision. Adding the Secret key is not
> enough — run `helm upgrade` as well, or the pods keep starting without `EMCIP_SECRET_KEY` and
> crash. Add the key **first**; the ref is not optional, so upgrading first gives you
> `CreateContainerConfigError` instead.

## Key loss

There is no recovery. Every encrypted value becomes unreadable:

- vendor API keys and the LLM provider key must be re-entered through the Admin UI
- `telegram_accounts.api_hash` must be re-entered
- Telegram sessions would need re-authentication — currently moot, since nothing writes
  `session_string`

Back the key up separately from the database. A backup of both together defeats the encryption.

## Migration runbook (one-time, per environment)

Liquibase runs at service startup, so the columns cannot be widened while the services are scaled to
zero. Deploy first, migrate second, and accept a short window where secret-reading features fail
loudly.

1. Create the `emcip-secret-key` Secret.
2. Deploy the new images. Pods start normally — decryption happens on demand, not at boot — and
   Liquibase widens the columns. **From here until step 4, any feature reading one of the four
   secrets fails with the strict-mode error.** This is the maintenance window.

   > **Cross-service ordering for `ke_vendor_api_keys`:** this table is owned by knowledge-engine
   > (its schema, including the `api_key` column widening in changeset `021`, is applied by
   > knowledge-engine's Liquibase at startup) but is written with encrypted values by admin-api.
   > Ensure knowledge-engine has started and applied its migrations before admin-api accepts a new
   > vendor-key write — otherwise a very long key's ciphertext could exceed the pre-widening
   > `VARCHAR(512)`. In a normal all-services deploy this happens automatically; the caution
   > matters if the two services are started separately. **Local dev note:** in
   > `docker-compose.yml`, admin-api is in the default profile while knowledge-engine is behind
   > the `full` profile, so bring up knowledge-engine (or use `--profile full`) before exercising
   > vendor-key writes.
3. For each column, read the current plaintext, generate ciphertext, and UPDATE by primary key.
   There is no `psql` on the workstation, so work through the Postgres pod:

   ```bash
   microk8s.kubectl exec -it <postgres-pod> -- psql -U emcip -d emcip \
     -c "SELECT id, vendor_id, api_key FROM ke_vendor_api_keys;"
   ```

   Generate the ciphertext (the plaintext lands in shell history — clear it afterwards):

   ```bash
   EMCIP_SECRET_KEY='<the key>' java -cp emcip-core.jar \
     io.emcip.common.crypto.SecretCipherCli encrypt 'sk-the-plaintext-key'
   ```

   Apply it:

   ```bash
   microk8s.kubectl exec -it <postgres-pod> -- psql -U emcip -d emcip \
     -c "UPDATE ke_vendor_api_keys SET api_key = 'v1:...' WHERE id = '<uuid>';"
   ```

   Repeat for `llm_provider_configs.api_key` and `telegram_accounts.api_hash`.
   `telegram_accounts.session_string` is NULL everywhere and needs nothing.

4. Verify — each of these must return zero rows:

   ```sql
   SELECT id FROM ke_vendor_api_keys    WHERE api_key       NOT LIKE 'v1:%';
   SELECT id FROM llm_provider_configs  WHERE api_key       NOT LIKE 'v1:%' AND api_key IS NOT NULL;
   SELECT id FROM telegram_accounts     WHERE api_hash      NOT LIKE 'v1:%' AND api_hash IS NOT NULL;
   SELECT id FROM telegram_accounts     WHERE session_string NOT LIKE 'v1:%' AND session_string IS NOT NULL;
   ```

5. Restart the three services so nothing holds a stale decrypted value.

**Zero-window alternative:** run the widening changesets ahead of the deploy with the Liquibase Maven
plugin pointed at the cluster database, migrate the values, then deploy — at the cost of applying
schema changes outside the normal service-startup path.

**Fallback without the CLI:** re-enter the secrets through the Admin UI after deploy, which writes
them encrypted. Setting the columns to NULL first is not required.

### Repairing a legacy value through the Admin UI

Re-entering the secret is the supported repair where the UI offers it, and it works while the value
is still plaintext:

| Secret | Where |
|---|---|
| `llm_provider_configs.api_key` | AI Config → Provider Configs → Edit → retype the key |
| `ke_vendor_api_keys.api_key` | Integrations → Global Keys |
| `telegram_accounts.api_hash` | Telegram → the account → Credentials |

> Attempting to authenticate an account whose hash is legacy plaintext now opens the Credentials
> dialog directly, carrying the reason, and authentication resumes once the replacement is stored.
> Before `TG-REENTER` the hash was accepted only at account *creation*, so the 409 instructed
> operators to do something the product could not do.

### Repairing legacy values with the script

`scripts/encrypt-legacy-secrets.sh` performs step 3 of the runbook for every encrypted column at
once, using the key already in `emcip-secrets`. It is dry-run by default, prints no secret in either
form, and verifies afterwards that no plaintext remains:

```bash
./scripts/encrypt-legacy-secrets.sh            # report only
./scripts/encrypt-legacy-secrets.sh --apply    # encrypt in place
```

Restart the reading services afterwards, as the script's closing output states, so nothing holds a
stale fail-closed result.

Reads of these values fail closed until then, so the affected feature stays broken while the rest of
the UI works normally. The failure is reported as `409` with `code: SECRET_NOT_ENCRYPTED` and a
message naming the field to re-enter — it never names the table, column, or this runbook, because
that response reaches a browser.

> **Why the edit screens do not need the old value.** A JPA attribute converter decrypts eagerly, so
> merely *loading* a row with a legacy key throws and fails the whole query. Listing provider
> configs and saving an edit therefore both went through paths that read the key they were trying to
> replace, which made the repair impossible — the fix was to route those paths through projections
> and explicit updates that never decrypt (see `LlmProviderConfigRepository`). Keep that property in
> mind when adding admin screens over an encrypted column: **an administrative read must not decrypt
> a secret it does not use.**

## Key rotation

Not supported yet. The `v1:` prefix is the version marker that will let a second key be introduced
without touching stored data. Tracked for P6 alongside the secrets-management ADR.

## Startup self-check (P3.7)

Reads have always been fail-closed (a plaintext value throws rather than being silently returned) —
what P3.7 adds is **discovery**. Before this, a plaintext or wrong-key row was found lazily, the first
time an operator touched the feature that reads it. Now every affected service scans its columns at
boot, and hourly after that, so the state is known before anyone hits it.

Wired into admin-api (`telegram_accounts.api_hash`, `telegram_accounts.session_string`),
knowledge-engine (`ke_vendor_api_keys.api_key`) and llm-orchestrator (`llm_provider_configs.api_key`).
Config key `emcip.secrets.self-check` (env `EMCIP_SECRETS_SELF_CHECK`), default **`warn`** — logs and
boots normally. `off` skips the check entirely (local dev). `fail` refuses to start; see promotion
procedure below. **`warn` is the shipping default in every environment today** — no environment has
been promoted to `fail`.

### The four outcomes

| Outcome | Meaning | Operator action |
|---|---|---|
| `OK` | Zero plaintext rows, and one encrypted row decrypted successfully with the mounted key. | None. |
| `PLAINTEXT` | One or more rows lack the `v1:` prefix. | Repair via the Admin UI: **Credentials** on the affected Telegram account, **Integrations → Global Keys**, or **AI Config → Provider Configs → Edit**, per the table under [Repairing a legacy value through the Admin UI](#repairing-a-legacy-value-through-the-admin-ui) above (PR #241 / #243). Re-entering overwrites the plaintext with a freshly encrypted value — safe, because the old value was already readable in the clear. |
| `KEY_MISMATCH` | A `v1:`-prefixed row exists but the mounted `EMCIP_SECRET_KEY` cannot decrypt it — the wrong key is mounted. | **Do not** re-enter the secret through the Admin UI. The stored value is not plaintext; it is ciphertext under a *different* key, and may still be recoverable if the correct key is found and re-mounted. Re-entering would silently discard that possibility by overwriting it with a new value under the current (wrong) key. Instead: find the correct `EMCIP_SECRET_KEY` (see [Key loss](#key-loss) if it is genuinely gone) and fix the mounted Secret. |
| `UNVERIFIED` | No `v1:` rows exist at all for that column, so the key could not be proven against real data. | None — expected on a fresh install or an all-NULL column (e.g. `session_string` today). Not the same as `OK`: an empty column has not actually demonstrated that the key works. |

`KEY_MISMATCH` exists because a naive prefix-only scan cannot see it: under the wrong key every row
still starts with `v1:` and would report clean while nothing is actually readable. The self-check
proves the key against one real row per column rather than trusting the prefix alone.

### Metrics

- `emcip.secrets.plaintext_count{column="<table>.<column>"}` — gauge, count of un-prefixed rows, `0`
  when clean.
- `emcip.secrets.key_status{column="<table>.<column>"}` — gauge: `0` = OK, `1` = `KEY_MISMATCH`,
  `2` = `UNVERIFIED`. `NaN` if the column has never been scanned (before the first run, or always,
  under `self-check: off`) — deliberately not `0`, because `0` also means "checked and clean" and a
  disabled check must not look identical to a passing one.

### Reading the state

```bash
curl -s localhost:<port>/actuator/health | jq .components.secrets
```

`SecretsHealthIndicator` is **always `UP`, by design** — it never reports `DOWN`, however bad the
findings underneath. This indicator feeds the Kubernetes readiness probe; a `DOWN` here would pull the
pod out of rotation, which would make the Admin UI repair path (the previous section) unreachable —
exactly the outage this feature exists to avoid. Treat `/actuator/health`'s `secrets` block as a
report to read, not a signal to alert on; the **metrics** above are the alertable surface.

### Re-scan

The check re-runs hourly at `17m 23s` past the hour (offset per the project's cron-timing rule, never
a round `:00.000`), in addition to at startup. This is what lets the gauges clear after a repair
without waiting for the next pod restart — and, in `fail` mode, the scheduled re-scan never fails a
running pod even if it finds new plaintext; only the startup check can refuse to boot. A repair made
through the Admin UI is therefore reflected in the metrics within the hour, not immediately.

### Promoting an environment to `fail`

Ships as `warn` everywhere. Promoting a specific environment to hard-fail-on-plaintext is a manual,
per-environment operator decision, taken only after that environment has demonstrated it is clean:

1. Confirm `plaintext_count == 0` and `key_status == 0` for every one of the four columns.
2. Confirm this across **at least one full hourly re-scan cycle**, not just the boot-time reading —
   a value that looks clean once could still be stale from before a change.
3. Only then set `EMCIP_SECRETS_SELF_CHECK=fail` for that environment.

No environment has been promoted yet; live per-column state has not been read off the cluster as of
this writing (that is a separate, pending step — see the plan's live-verification task).
