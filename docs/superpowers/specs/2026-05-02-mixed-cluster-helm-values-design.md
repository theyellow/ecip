# Mixed-Cluster Helm Values Design

**Date:** 2026-05-02
**Status:** Approved
**Scope:** Multi-architecture Kubernetes deployment — x86_64 + arm64 (Raspberry Pi 4) nodes in a single microk8s cluster

---

## Context

EMCIP runs on a mixed microk8s cluster with x86_64 and arm64 (Pi 4) nodes. Three services have GraalVM native images (`policy-engine`, `conversation-context`, `llm-orchestrator`) which are x86_64-only binaries. All other services use JVM images built on `eclipse-temurin:21-jdk`, which already publishes multi-arch manifests and runs on Pi 4 without changes.

The base `values.yaml` (from the kubernetes-helm branch) continues to work for homogeneous x86_64 clusters. A new `values-mixed-cluster.yaml` overlay handles the mixed case without touching the base chart.

---

## Image Tagging Convention

### Native-capable services (policy-engine, conversation-context, llm-orchestrator)

| Tag | Built from | Runs on |
|---|---|---|
| `emcip/<service>:native-amd64` | `Dockerfile.native` on x86_64 | x86_64 nodes only |
| `emcip/<service>:latest` | `Dockerfile` (JVM) | any node (dev / fallback) |

### JVM-only services (all others)

| Tag | Built from | Runs on |
|---|---|---|
| `emcip/<service>:latest` | `Dockerfile` (JVM, eclipse-temurin multi-arch) | any node |

`eclipse-temurin:21-jdk` already publishes `linux/amd64` and `linux/arm64` manifests — no `docker buildx` cross-compilation needed for JVM services. A single `docker build` + `docker push` on an x86_64 machine produces an image that runs on Pi 4.

**tdlib-adapter** uses a native TDLib binary (amd64). It is pinned to amd64 nodes via `nodeSelector` regardless of image tag.

---

## Helm Template Changes

### `helm/emcip/values.yaml`

Add an optional `nodeSelector: {}` field to every service entry in the `services` map and to `tdlibAdapter`. Empty map = no constraint (any node). The base values file keeps all services unconstrained.

```yaml
services:
  policyEngine:
    name: policy-engine
    image: emcip/policy-engine:latest
    nodeSelector: {}      # <-- added; empty = any node
    # ... rest unchanged
```

Same addition to `tdlibAdapter`:

```yaml
tdlibAdapter:
  name: tdlib-adapter
  image: emcip/tdlib-adapter:latest
  nodeSelector: {}        # <-- added
  # ... rest unchanged
```

### `helm/emcip/templates/apps/standard-deployments.yaml`

Add a conditional `nodeSelector` block inside the pod spec, inside the range loop:

```yaml
    spec:
      {{- if $svc.nodeSelector }}
      nodeSelector:
        {{- toYaml $svc.nodeSelector | nindent 8 }}
      {{- end }}
      containers:
```

### `helm/emcip/templates/apps/tdlib-statefulset.yaml`

Same pattern in the StatefulSet pod spec:

```yaml
    spec:
      {{- if .Values.tdlibAdapter.nodeSelector }}
      nodeSelector:
        {{- toYaml .Values.tdlibAdapter.nodeSelector | nindent 8 }}
      {{- end }}
      containers:
```

---

## New File: `helm/emcip/values-mixed-cluster.yaml`

Overrides only what differs from the base. The base chart's JVM-only services, infra (PostgreSQL, Kafka, Loki, Grafana), and Ingress are unchanged.

```yaml
# Mixed cluster values overlay: x86_64 + arm64 (Raspberry Pi 4)
#
# Deploy:
#   helm upgrade --install emcip helm/emcip/ \
#     -f helm/emcip/values-mixed-cluster.yaml -n emcip
#
# Prerequisites:
#   - native-amd64 images built and pushed (see scripts/build-images.sh --native amd64)
#   - JVM images built and pushed (see scripts/build-images.sh --jvm)

services:
  policyEngine:
    image: emcip/policy-engine:native-amd64
    nodeSelector:
      kubernetes.io/arch: amd64

  conversationContext:
    image: emcip/conversation-context:native-amd64
    nodeSelector:
      kubernetes.io/arch: amd64

  llmOrchestrator:
    image: emcip/llm-orchestrator:native-amd64
    nodeSelector:
      kubernetes.io/arch: amd64

tdlibAdapter:
  nodeSelector:
    kubernetes.io/arch: amd64   # TDLib native binary is amd64-only
```

Infrastructure components (PostgreSQL StatefulSet, Kafka, Loki, Grafana) receive no nodeSelector — they can run on any node. If you want to pin infra to amd64 for reliability, add `nodeSelector` overrides to the infra section in this file.

---

## New File: `scripts/build-images.sh`

Shell script for manual builds, structured for CI/CD reuse.

**Interface:**

```bash
scripts/build-images.sh --native amd64   # build :native-amd64 for policy-engine, conversation-context, llm-orchestrator
scripts/build-images.sh --jvm            # build :latest (JVM) for all services
scripts/build-images.sh --all            # both of the above
```

**Behaviour:**
- `--native amd64`: runs `docker build -f <service>/Dockerfile.native -t emcip/<service>:native-amd64 .` for each of the three native-capable services, then `docker push`
- `--jvm`: runs `docker build -f <service>/Dockerfile -t emcip/<service>:latest .` for all services (including native-capable ones, using their JVM Dockerfile), then `docker push`
- Must be run from the project root (build context requirement of existing Dockerfiles)
- Exits non-zero on first build failure
- Prints which images were built and pushed

**CI/CD path:** A GitHub Actions workflow would call `scripts/build-images.sh --native amd64` on an `ubuntu-latest` (amd64) runner and `--jvm` on any runner. No tag scheme changes required.

---

## Documentation Changes

### `documentation/operations-guide.adoc` — new subsection under Kubernetes Deployment

New `=== Mixed-Cluster Deployment (x86_64 + arm64)` subsection covering:
- When to use the overlay vs. the base values
- Build images command (`scripts/build-images.sh`)
- Deploy command with `-f values-mixed-cluster.yaml`
- Note on which services run on which nodes

---

## Out of Scope

- `buildx` cross-compilation for native arm64 images (deferred — requires build infrastructure)
- Node taints / tolerations (nodeSelector is sufficient for this cluster size)
- Separate `values-amd64.yaml` or `values-arm64.yaml` single-arch files (not needed for the mixed case)
- Helm chart version bump (operational change only)
