# Mixed-Cluster Helm Values Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the EMCIP Helm chart with optional `nodeSelector` support so that native-image services and `tdlib-adapter` can be pinned to amd64 nodes in a mixed x86_64 + arm64 (Raspberry Pi 4) microk8s cluster, delivered via a `values-mixed-cluster.yaml` overlay without touching the base chart's defaults.

**Architecture:** The base `values.yaml` gains an optional `nodeSelector: {}` field on every service and on `tdlibAdapter`; empty map = no constraint. Two Helm templates (`standard-deployments.yaml`, `tdlib-statefulset.yaml`) gain a conditional `nodeSelector:` block inside the pod spec. A new `values-mixed-cluster.yaml` overlay overrides image tags and sets `kubernetes.io/arch: amd64` only for the four amd64-only workloads. A standalone `scripts/build-images.sh` covers manual/CI image builds. An AsciiDoc subsection documents the mixed-cluster deploy procedure.

**Tech Stack:** Helm 3, Go templates (Helm), Bash, AsciiDoc

---

## File Map

| Action | File |
|--------|------|
| Modify | `helm/emcip/values.yaml` |
| Modify | `helm/emcip/templates/apps/standard-deployments.yaml` |
| Modify | `helm/emcip/templates/apps/tdlib-statefulset.yaml` |
| Create | `helm/emcip/values-mixed-cluster.yaml` |
| Create | `scripts/build-images.sh` |
| Modify | `documentation/operations-guide.adoc` |

---

### Task 1: Add `nodeSelector` field to `values.yaml`

**Files:**
- Modify: `helm/emcip/values.yaml`

- [ ] **Step 1: Capture baseline render (no nodeSelector expected)**

Run from project root:
```bash
helm template emcip helm/emcip/ -n emcip | grep "nodeSelector" && echo "UNEXPECTED" || echo "PASS: no nodeSelector in base"
```
Expected: `PASS: no nodeSelector in base`

- [ ] **Step 2: Add `nodeSelector: {}` to each service entry in `values.yaml`**

Open `helm/emcip/values.yaml`. For each of the 8 service entries (`conversationContext`, `intentClassifier`, `policyEngine`, `llmOrchestrator`, `moderationService`, `auditService`, `adminApi`, `adminUi`), add `nodeSelector: {}` as a top-level field immediately after `replicas`. Example for `conversationContext` (lines 44–58 in the current file):

```yaml
  conversationContext:
    name: conversation-context
    image: emcip/conversation-context:latest
    port: 9081
    replicas: 1
    nodeSelector: {}
    env:
```

Repeat this exact insertion for: `intentClassifier` (after its `replicas: 1`), `policyEngine` (after its `replicas: 1`), `llmOrchestrator` (after its `replicas: 1`), `moderationService` (after its `replicas: 1`), `auditService` (after its `replicas: 1`), `adminApi` (after its `replicas: 1`), `adminUi` (after its `replicas: 1`).

- [ ] **Step 3: Add `nodeSelector: {}` to `tdlibAdapter` section**

In the `tdlibAdapter:` block (currently after the `services:` map), add `nodeSelector: {}` after `replicas: 1`:

```yaml
tdlibAdapter:
  name: tdlib-adapter
  image: emcip/tdlib-adapter:latest
  port: 9080
  replicas: 1
  nodeSelector: {}
  env:
```

- [ ] **Step 4: Lint and verify no nodeSelector still renders**

```bash
helm lint helm/emcip/
helm template emcip helm/emcip/ -n emcip | grep "nodeSelector" && echo "UNEXPECTED" || echo "PASS: nodeSelector absent with empty map"
```
Expected: `helm lint` passes (0 errors); `PASS: nodeSelector absent with empty map`

- [ ] **Step 5: Commit**

```bash
git add helm/emcip/values.yaml
git commit -m "feat(helm): add optional nodeSelector field to all services and tdlibAdapter"
```

---

### Task 2: Add conditional `nodeSelector` to `standard-deployments.yaml`

**Files:**
- Modify: `helm/emcip/templates/apps/standard-deployments.yaml`

- [ ] **Step 1: Add conditional block to pod spec**

In `helm/emcip/templates/apps/standard-deployments.yaml`, the `spec:` block of the pod template currently starts at line 23 with `containers:` directly under `spec:`. Replace:

```yaml
    spec:
      containers:
```

with:

```yaml
    spec:
      {{- if $svc.nodeSelector }}
      nodeSelector:
        {{- toYaml $svc.nodeSelector | nindent 8 }}
      {{- end }}
      containers:
```

- [ ] **Step 2: Lint**

```bash
helm lint helm/emcip/
```
Expected: `0 chart(s) failed`

- [ ] **Step 3: Render with base values — nodeSelector must be absent**

```bash
helm template emcip helm/emcip/ -n emcip | grep "nodeSelector" && echo "UNEXPECTED" || echo "PASS"
```
Expected: `PASS`

- [ ] **Step 4: Render with a one-off test to confirm the block fires**

```bash
helm template emcip helm/emcip/ -n emcip \
  --set 'services.policyEngine.nodeSelector.kubernetes\.io/arch=amd64' \
  | grep -A2 "nodeSelector"
```
Expected output contains:
```
      nodeSelector:
        kubernetes.io/arch: amd64
```

- [ ] **Step 5: Commit**

```bash
git add helm/emcip/templates/apps/standard-deployments.yaml
git commit -m "feat(helm): render nodeSelector in Deployment pod spec when set"
```

---

### Task 3: Add conditional `nodeSelector` to `tdlib-statefulset.yaml`

**Files:**
- Modify: `helm/emcip/templates/apps/tdlib-statefulset.yaml`

- [ ] **Step 1: Add conditional block to StatefulSet pod spec**

In `helm/emcip/templates/apps/tdlib-statefulset.yaml`, the `spec:` block of the pod template currently has `containers:` as its first child (line 23). Replace:

```yaml
    spec:
      containers:
```

with:

```yaml
    spec:
      {{- if .Values.tdlibAdapter.nodeSelector }}
      nodeSelector:
        {{- toYaml .Values.tdlibAdapter.nodeSelector | nindent 8 }}
      {{- end }}
      containers:
```

- [ ] **Step 2: Lint**

```bash
helm lint helm/emcip/
```
Expected: `0 chart(s) failed`

- [ ] **Step 3: Render with base values — nodeSelector absent for tdlib StatefulSet**

```bash
helm template emcip helm/emcip/ -n emcip | grep -B5 "nodeSelector" && echo "UNEXPECTED" || echo "PASS"
```
Expected: `PASS`

- [ ] **Step 4: Render with override — nodeSelector fires for tdlib**

```bash
helm template emcip helm/emcip/ -n emcip \
  --set 'tdlibAdapter.nodeSelector.kubernetes\.io/arch=amd64' \
  | grep -A5 "emcip-tdlib-adapter" | grep -A2 "nodeSelector"
```
Expected:
```
      nodeSelector:
        kubernetes.io/arch: amd64
```

- [ ] **Step 5: Commit**

```bash
git add helm/emcip/templates/apps/tdlib-statefulset.yaml
git commit -m "feat(helm): render nodeSelector in StatefulSet pod spec when set"
```

---

### Task 4: Create `values-mixed-cluster.yaml` overlay

**Files:**
- Create: `helm/emcip/values-mixed-cluster.yaml`

- [ ] **Step 1: Create the overlay file**

Create `helm/emcip/values-mixed-cluster.yaml` with this exact content:

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
  image: emcip/tdlib-adapter:latest   # libtdjni.so is amd64-only — must stay on amd64 nodes
  nodeSelector:
    kubernetes.io/arch: amd64
```

- [ ] **Step 2: Lint with the overlay**

```bash
helm lint helm/emcip/ -f helm/emcip/values-mixed-cluster.yaml
```
Expected: `0 chart(s) failed`

- [ ] **Step 3: Render and verify amd64 pods get nodeSelector**

```bash
helm template emcip helm/emcip/ -n emcip \
  -f helm/emcip/values-mixed-cluster.yaml \
  | grep -A2 "nodeSelector"
```
Expected: four occurrences of:
```
      nodeSelector:
        kubernetes.io/arch: amd64
```
One each for: `emcip-policy-engine`, `emcip-conversation-context`, `emcip-llm-orchestrator`, `emcip-tdlib-adapter`.

- [ ] **Step 4: Verify JVM-only services have NO nodeSelector in overlay render**

```bash
helm template emcip helm/emcip/ -n emcip \
  -f helm/emcip/values-mixed-cluster.yaml \
  | grep -B10 "nodeSelector" | grep "app.kubernetes.io/name"
```
The names appearing before each `nodeSelector:` block must be only:
`conversation-context`, `policy-engine`, `llm-orchestrator`, `tdlib-adapter`.
Services `intent-classifier`, `moderation-service`, `audit-service`, `admin-api`, `admin-ui` must NOT appear.

- [ ] **Step 5: Commit**

```bash
git add helm/emcip/values-mixed-cluster.yaml
git commit -m "feat(helm): add values-mixed-cluster.yaml overlay for x86_64+arm64 clusters"
```

---

### Task 5: Create `scripts/build-images.sh`

**Files:**
- Create: `scripts/build-images.sh`

- [ ] **Step 1: Create the script**

Create `scripts/build-images.sh` with this exact content:

```bash
#!/usr/bin/env bash
# build-images.sh — Build and push EMCIP Docker images.
# Must be run from the project root (Dockerfiles use '.' as build context).
#
# Usage:
#   scripts/build-images.sh --native amd64   # native-amd64 for GraalVM services + tdlib-adapter:latest
#   scripts/build-images.sh --jvm            # :latest (JVM) for all services
#   scripts/build-images.sh --all            # both of the above
set -euo pipefail

# module/image-name pairs for all services
SERVICES_ALL=(
  "emcip-conversation-context/conversation-context"
  "emcip-intent-classifier/intent-classifier"
  "emcip-policy-engine/policy-engine"
  "emcip-llm-orchestrator/llm-orchestrator"
  "emcip-moderation-service/moderation-service"
  "emcip-audit-service/audit-service"
  "emcip-admin-api/admin-api"
  "emcip-admin-ui/admin-ui"
  "emcip-tdlib-adapter/tdlib-adapter"
)

# Services that have a Dockerfile.native (GraalVM native image)
SERVICES_NATIVE=(
  "emcip-conversation-context/conversation-context"
  "emcip-policy-engine/policy-engine"
  "emcip-llm-orchestrator/llm-orchestrator"
)

build_native_amd64() {
  echo "=== Building :native-amd64 images (GraalVM) ==="
  for entry in "${SERVICES_NATIVE[@]}"; do
    module="${entry%%/*}"
    svc="${entry##*/}"
    echo "--> emcip/${svc}:native-amd64  (${module}/Dockerfile.native)"
    docker build -f "${module}/Dockerfile.native" -t "emcip/${svc}:native-amd64" .
    docker push "emcip/${svc}:native-amd64"
    echo "    Pushed emcip/${svc}:native-amd64"
  done
  echo "--> emcip/tdlib-adapter:latest  (emcip-tdlib-adapter/Dockerfile, libtdjni.so compiled amd64)"
  docker build -f "emcip-tdlib-adapter/Dockerfile" -t "emcip/tdlib-adapter:latest" .
  docker push "emcip/tdlib-adapter:latest"
  echo "    Pushed emcip/tdlib-adapter:latest"
}

build_jvm() {
  echo "=== Building :latest (JVM) images ==="
  for entry in "${SERVICES_ALL[@]}"; do
    module="${entry%%/*}"
    svc="${entry##*/}"
    echo "--> emcip/${svc}:latest  (${module}/Dockerfile)"
    docker build -f "${module}/Dockerfile" -t "emcip/${svc}:latest" .
    docker push "emcip/${svc}:latest"
    echo "    Pushed emcip/${svc}:latest"
  done
}

usage() {
  echo "Usage: $0 --native amd64 | --jvm | --all" >&2
  exit 1
}

[[ $# -eq 0 ]] && usage

case "$1" in
  --native)
    [[ "${2:-}" == "amd64" ]] || { echo "Error: --native requires 'amd64' argument" >&2; usage; }
    build_native_amd64
    ;;
  --jvm)
    build_jvm
    ;;
  --all)
    build_native_amd64
    build_jvm
    ;;
  *)
    echo "Unknown argument: $1" >&2
    usage
    ;;
esac
```

- [ ] **Step 2: Make executable**

```bash
chmod +x scripts/build-images.sh
```

- [ ] **Step 3: Validate bash syntax**

```bash
bash -n scripts/build-images.sh && echo "PASS: syntax OK"
```
Expected: `PASS: syntax OK`

- [ ] **Step 4: Verify usage message on no args**

```bash
scripts/build-images.sh 2>&1 | head -1
```
Expected: `Usage: scripts/build-images.sh --native amd64 | --jvm | --all`

- [ ] **Step 5: Verify unknown arg exits non-zero**

```bash
scripts/build-images.sh --foo 2>&1; echo "exit $?"
```
Expected: lines containing `Unknown argument: --foo` and `exit 1`

- [ ] **Step 6: Commit**

```bash
git add scripts/build-images.sh
git commit -m "feat(scripts): add build-images.sh for native-amd64 and JVM image builds"
```

---

### Task 6: Document mixed-cluster deployment in operations guide

**Files:**
- Modify: `documentation/operations-guide.adoc`

- [ ] **Step 1: Append Mixed-Cluster subsection**

In `documentation/operations-guide.adoc`, at the very end of the file (after the `=== Namespace Strategy` section, currently line 709), append the following block:

```asciidoc

=== Mixed-Cluster Deployment (x86_64 + arm64)

Use this when your microk8s cluster contains both x86_64 and arm64 (Raspberry Pi 4) nodes.
The base `values.yaml` works unchanged for homogeneous x86_64 clusters; the overlay only changes
what differs.

==== Why some services must run on amd64

[cols="2,3"]
|===
|Service |Reason

|`policy-engine`
|GraalVM native image (`native-amd64` tag) — x86_64 binary

|`conversation-context`
|GraalVM native image (`native-amd64` tag) — x86_64 binary

|`llm-orchestrator`
|GraalVM native image (`native-amd64` tag) — x86_64 binary

|`tdlib-adapter`
|`libtdjni.so` compiled from source during Docker build (cmake, amd64-only)
|===

All other services use `eclipse-temurin:21-jdk` which publishes multi-arch manifests — they run
on Pi 4 without changes.

==== Build images

Run from the project root on an x86_64 machine:

[source,bash]
----
# Build native-amd64 images for the three GraalVM services + tdlib-adapter:latest
scripts/build-images.sh --native amd64

# Build JVM :latest images for all services (multi-arch via eclipse-temurin base)
scripts/build-images.sh --jvm
----

==== Deploy

[source,bash]
----
helm upgrade --install emcip helm/emcip/ \
  -f helm/emcip/values-mixed-cluster.yaml \
  -n emcip
----

==== Node placement

After deployment, verify pods landed on the correct nodes:

[source,bash]
----
microk8s kubectl get pods -n emcip -o wide
----

`policy-engine`, `conversation-context`, `llm-orchestrator`, and `tdlib-adapter` pods should show
an x86_64 node in the `NODE` column. All other pods may run on any node.
```

- [ ] **Step 2: Verify the section renders cleanly (no trailing whitespace / broken syntax)**

```bash
grep -n "Mixed-Cluster\|==== Build\|==== Deploy\|==== Node\|==== Why" documentation/operations-guide.adoc
```
Expected: 5 lines printed, one for each of the new headings.

- [ ] **Step 3: Commit**

```bash
git add documentation/operations-guide.adoc
git commit -m "docs(operations): add Mixed-Cluster Deployment subsection for x86_64+arm64"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Covered by |
|-----------------|------------|
| `nodeSelector: {}` on all `services` entries | Task 1 |
| `nodeSelector: {}` on `tdlibAdapter` | Task 1 |
| Conditional `nodeSelector` in `standard-deployments.yaml` | Task 2 |
| Conditional `nodeSelector` in `tdlib-statefulset.yaml` | Task 3 |
| `values-mixed-cluster.yaml` with native-amd64 images + nodeSelector | Task 4 |
| `scripts/build-images.sh` with `--native amd64`, `--jvm`, `--all` | Task 5 |
| Ops guide `=== Mixed-Cluster Deployment` subsection | Task 6 |

All spec requirements covered. No TBDs or placeholders.
