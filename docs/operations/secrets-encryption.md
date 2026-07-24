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

```bash
openssl rand -base64 32
```

Store it as the `emcip-secret-key` Kubernetes Secret. **Never commit it.** admin-api,
knowledge-engine and llm-orchestrator all read it as `EMCIP_SECRET_KEY` and will not start without it.
No other service needs it.

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

**Fallback without the CLI:** set the columns to NULL and re-enter the secrets through the Admin UI
after deploy, which writes them encrypted.

## Key rotation

Not supported yet. The `v1:` prefix is the version marker that will let a second key be introduced
without touching stored data. Tracked for P6 alongside the secrets-management ADR.
