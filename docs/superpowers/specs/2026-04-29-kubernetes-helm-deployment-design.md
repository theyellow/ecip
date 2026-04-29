# Kubernetes/Helm Deployment Design

**Date:** 2026-04-29
**Status:** Approved
**Scope:** Single Helm chart covering full EMCIP stack on microk8s, portable to cloud via NFS-backed PVCs

---

## Context

EMCIP currently runs on Docker Compose. This design adds a Kubernetes deployment path using a single Helm chart. Docker Compose remains the local development standard; Kubernetes targets staging and production environments (initially: local microk8s cluster).

---

## Architecture

### Chart Structure

One chart at `helm/emcip/`:

```
helm/emcip/
├── Chart.yaml
├── values.yaml                    # all tunables — no secrets ever
├── templates/
│   ├── _helpers.tpl
│   ├── infra/
│   │   ├── postgres-pvc.yaml
│   │   ├── postgres-statefulset.yaml
│   │   ├── postgres-service.yaml
│   │   ├── kafka.yaml             # Strimzi Kafka CR
│   │   ├── kafka-nodepool.yaml    # Strimzi KafkaNodePool CR (KRaft mode)
│   │   ├── kafka-topics.yaml      # KafkaTopic CRs for all EMCIP topics
│   │   ├── loki-pvc.yaml
│   │   ├── loki-deployment.yaml
│   │   ├── loki-service.yaml
│   │   ├── loki-configmap.yaml
│   │   ├── grafana-pvc.yaml
│   │   ├── grafana-deployment.yaml
│   │   ├── grafana-service.yaml
│   │   └── grafana-configmap.yaml
│   ├── apps/
│   │   ├── tdlib-adapter-deployment.yaml
│   │   ├── tdlib-adapter-service.yaml
│   │   ├── conversation-context-deployment.yaml
│   │   ├── conversation-context-service.yaml
│   │   ├── intent-classifier-deployment.yaml
│   │   ├── intent-classifier-service.yaml
│   │   ├── policy-engine-deployment.yaml
│   │   ├── policy-engine-service.yaml
│   │   ├── llm-orchestrator-deployment.yaml
│   │   ├── llm-orchestrator-service.yaml
│   │   ├── moderation-service-deployment.yaml
│   │   ├── moderation-service-service.yaml
│   │   ├── audit-service-deployment.yaml
│   │   ├── audit-service-service.yaml
│   │   ├── admin-api-deployment.yaml
│   │   ├── admin-api-service.yaml
│   │   ├── admin-ui-deployment.yaml
│   │   └── admin-ui-service.yaml
│   └── ingress.yaml               # optional, toggled via values.yaml
```

### Approach

Single umbrella chart — no sub-charts. For a single-environment deployment this is the right complexity level. Sub-charts would be appropriate only if infrastructure and applications needed independent versioning.

---

## Infrastructure Components

### PostgreSQL

- Deployed as a `StatefulSet` with one replica
- `PersistentVolumeClaim` using `values.yaml`-configurable `storageClassName` (default: `nfs-client`)
- `ClusterIP` service, internal DNS: `emcip-postgres:5432`
- Credentials injected from a Kubernetes Secret (see Secrets section)

### Kafka (Strimzi)

- **Strimzi operator installed separately**, not managed by this chart
  ```bash
  helm install strimzi-operator strimzi/strimzi-kafka-operator \
    -n strimzi-system --create-namespace
  ```
- Chart creates:
  - `Kafka` CR: single broker, KRaft mode (no Zookeeper dependency)
  - `KafkaNodePool` CR for broker nodes
  - `KafkaTopic` CRs for all EMCIP topics
- Kafka storage: `PersistentVolumeClaim` via Strimzi's storage config, same `storageClassName`
- Internal bootstrap service name (Strimzi convention): `<kafka-cr-name>-kafka-bootstrap:9092` — e.g. `emcip-kafka-bootstrap:9092` if the `Kafka` CR is named `emcip`

### Observability (Loki + Grafana)

- Loki and Grafana deployed as simple `Deployment` resources (single replica)
- PVCs for persistent storage on NFS
- Promtail replaced by Grafana Alloy or direct pod log collection (no Docker socket dependency in k8s)
- Grafana provisioning ConfigMaps for datasources and dashboards

---

## Application Services

Each service gets a `Deployment` and a `ClusterIP` `Service`. Key parameters in `values.yaml`:

```yaml
services:
  policyEngine:
    image: emcip/policy-engine:latest
    replicas: 1
    port: 9083
    resources:
      requests: { cpu: 100m, memory: 128Mi }
      limits:   { cpu: 500m, memory: 512Mi }
```

Environment variables referencing secrets use `secretKeyRef`, not plain values.

**tdlib-adapter** runs as a `StatefulSet` (not `Deployment`) due to TDLib session state in persistent volumes — `PVC` for `tdlib-db` and `tdlib-files`.

---

## Storage

All PVCs use a `storageClassName` configured once in `values.yaml`:

```yaml
storage:
  storageClassName: nfs-client   # change to standard / gp2 / etc. for cloud
  postgres:    { size: 10Gi }
  kafka:       { size: 20Gi }
  loki:        { size: 10Gi }
  grafana:     { size: 1Gi }
  tdlibDb:     { size: 1Gi }
  tdlibFiles:  { size: 5Gi }
```

The `StorageClass` pointing to the NAS NFS server is provisioned once outside this chart (e.g. using `nfs-subdir-external-provisioner`).

---

## Secrets Management

**Secrets are never in the chart or in `values.yaml`.** The chart references secret keys only.

All application secrets are stored in a single Kubernetes Secret that the operator creates manually before `helm install`:

```bash
kubectl create secret generic emcip-secrets \
  --from-literal=postgres-password=<...> \
  --from-literal=anthropic-api-key=<...> \
  --from-literal=admin-jwt-secret=<...> \
  --from-literal=admin-service-token=<...> \
  --from-literal=telegram-api-id=<...> \
  --from-literal=telegram-api-hash=<...> \
  --from-literal=telegram-phone-number=<...> \
  -n emcip
```

Template usage example:
```yaml
env:
  - name: ANTHROPIC_API_KEY
    valueFrom:
      secretKeyRef:
        name: emcip-secrets
        key: anthropic-api-key
```

Full pre-install instructions documented in `documentation/operations-guide.adoc` under a new "Kubernetes Deployment" section.

---

## Networking

- All inter-service communication via `ClusterIP` services (internal DNS)
- External access via microk8s nginx ingress addon (`microk8s enable ingress`)
- `values.yaml` controls ingress:
  ```yaml
  ingress:
    enabled: true
    host: emcip.local
  ```
- NodePort as fallback for environments without ingress controller
- Kafka accessible only internally; no external listener needed for local deployment

---

## Documentation Changes

### `documentation/operations-guide.adoc`
New section: **Kubernetes Deployment**
- Prerequisites (microk8s, Strimzi, NFS StorageClass)
- Strimzi operator install command
- `emcip-secrets` creation command with all keys
- `helm install` command
- Upgrade and rollback instructions
- Namespace strategy

### `documentation/developer-guide.adoc`
Short note: Docker Compose remains the local development path. Kubernetes is for staging/production. No changes to the build or dev workflow.

---

## Out of Scope

- CI/CD pipeline integration (separate concern)
- Multi-replica / HA configuration (single replica for now)
- Horizontal Pod Autoscaler
- TLS/cert-manager (can be added later via ingress annotations)
