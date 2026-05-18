# EMCIP Rollout Runbook

Quick reference for rolling out new images after a CI build on `main`.

---

## Which pods get a new image?

Only pods whose service had a new image pushed by CI. Check ghcr.io or the
GitHub Actions run to confirm which jobs succeeded. Do **not** restart
infrastructure pods (Kafka, PostgreSQL, Grafana, Loki, Prometheus) — they run
upstream images that CI does not touch.

| Pod | Type | Restart how | New image built by CI? |
|-----|------|-------------|------------------------|
| `emcip-admin-api` | Deployment | `rollout restart deployment` | ✅ Yes |
| `emcip-admin-ui` | Deployment | `rollout restart deployment` | ✅ Yes |
| `emcip-audit-service` | Deployment | `rollout restart deployment` | ✅ Yes |
| `emcip-intent-classifier` | Deployment | `rollout restart deployment` | ✅ Yes |
| `emcip-moderation-service` | Deployment | `rollout restart deployment` | ✅ Yes |
| `emcip-conversation-context` | Deployment | `rollout restart deployment` | ✅ Yes |
| `emcip-llm-orchestrator` | Deployment | `rollout restart deployment` | ✅ Yes |
| `emcip-policy-engine` | Deployment | `rollout restart deployment` | ✅ Yes |
| `emcip-tdlib-adapter` | **StatefulSet** | `rollout restart statefulset` | ✅ Yes |
| `emcip-postgres` | StatefulSet | ❌ Never touch | ❌ Upstream image |
| `emcip-emcip` (Kafka) | StatefulSet | ❌ Never touch | ❌ Upstream image |
| `emcip-entity-operator` | Deployment | ❌ Never touch | ❌ Strimzi-managed |
| `emcip-grafana` | Deployment | Only if chart changed | ❌ Upstream image |
| `emcip-loki` | Deployment | Only if chart changed | ❌ Upstream image |
| `emcip-prometheus` | Deployment | Only if chart changed | ❌ Upstream image |

> **Key distinction:** `emcip-tdlib-adapter` is a StatefulSet, not a Deployment.
> `kubectl rollout restart deployment` skips it. Always restart it explicitly.

---

## Full rollout after CI build

```bash
# 1. Restart all application Deployments
microk8s.kubectl rollout restart deployment \
  emcip-admin-api \
  emcip-admin-ui \
  emcip-audit-service \
  emcip-intent-classifier \
  emcip-moderation-service \
  emcip-conversation-context \
  emcip-llm-orchestrator \
  emcip-policy-engine \
  -n emcip

# 2. Restart tdlib-adapter StatefulSet separately
microk8s.kubectl rollout restart statefulset/emcip-tdlib-adapter -n emcip

# 3. Watch progress
microk8s.kubectl get pods -n emcip -w
```

All images use `pullPolicy: Always`, so new pods always pull the latest tag.

---

## Manual trigger (no code change needed)

Go to **GitHub → Actions → "Build and Push Docker Images" → Run workflow**
and check `force_rebuild_all: true`. This rebuilds all services regardless of
file changes.

---

## Liquibase checksum errors

If a JPA service (policy-engine, conversation-context, llm-orchestrator,
moderation-service) crashes on startup with:

```
liquibase.exception.ValidationFailedException: Validation Failed:
  N changesets check sum
    db/changelog/changes/XXX.xml::ID::AUTHOR was: 9:... but is now: 9:...
```

A changeset file was modified after it was applied. Clear the stored checksum
so Liquibase recomputes it (safe — schema is already correct):

```bash
microk8s.kubectl exec -n emcip emcip-postgres-0 -- \
  psql -U emcip -d emcip -c \
  "UPDATE DATABASECHANGELOG SET MD5SUM = NULL WHERE ID = '<id>' AND AUTHOR = '<author>';"
```

Then restart the affected service.
