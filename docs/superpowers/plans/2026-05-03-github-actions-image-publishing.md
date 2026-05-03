# GitHub Actions Image Publishing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automate Docker image builds and pushes to `ghcr.io/theyellow/ecip` on every push to `main` and on `v*.*.*` version tags, and update all image references in the Helm chart and build script to match.

**Architecture:** A single GitHub Actions workflow file with three parallel matrix jobs (`build-jvm-only`, `build-jvm-latest`, `build-native-amd64`) authenticates via `GITHUB_TOKEN` (no external secrets) and uses `docker/build-push-action` with `docker/metadata-action` for tag management. GraalVM is already inside `Dockerfile.native` (multi-stage build from `ghcr.io/graalvm/native-image-community:25`) — no runner-level GraalVM installation needed. The Helm chart image names and the build script are updated to use the new `ghcr.io/theyellow/ecip/<service>` paths.

**Tech Stack:** GitHub Actions, `docker/login-action@v3`, `docker/metadata-action@v5`, `docker/build-push-action@v5`, Helm 3, Bash

---

## File Map

| Action | File |
|--------|------|
| Create | `.github/workflows/build-images.yml` |
| Modify | `scripts/build-images.sh` |
| Modify | `helm/emcip/values.yaml` |
| Modify | `helm/emcip/values-mixed-cluster.yaml` |

---

### Task 1: Create `.github/workflows/build-images.yml`

**Files:**
- Create: `.github/workflows/build-images.yml`

- [ ] **Step 1: Create the `.github/workflows/` directory if needed and write the workflow file**

```bash
mkdir -p .github/workflows
```

Create `.github/workflows/build-images.yml` with this exact content:

```yaml
name: Build and Push Docker Images

on:
  push:
    branches:
      - main
    tags:
      - 'v*.*.*'

env:
  IMAGE_PREFIX: ghcr.io/theyellow/ecip

jobs:

  build-jvm-only:
    name: Build JVM-only (${{ matrix.service }})
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    strategy:
      fail-fast: false
      matrix:
        include:
          - service: intent-classifier
            module: emcip-intent-classifier
          - service: moderation-service
            module: emcip-moderation-service
          - service: audit-service
            module: emcip-audit-service
          - service: admin-api
            module: emcip-admin-api
          - service: admin-ui
            module: emcip-admin-ui
          - service: tdlib-adapter
            module: emcip-tdlib-adapter
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Log in to ghcr.io
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Extract metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.IMAGE_PREFIX }}/${{ matrix.service }}
          tags: |
            type=raw,value=latest,enable={{is_default_branch}}
            type=semver,pattern={{version}}

      - name: Build and push
        uses: docker/build-push-action@v5
        with:
          context: .
          file: ${{ matrix.module }}/Dockerfile
          push: true
          tags: ${{ steps.meta.outputs.tags }}

  build-jvm-latest:
    name: Build JVM-latest (${{ matrix.service }})
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    strategy:
      fail-fast: false
      matrix:
        include:
          - service: policy-engine
            module: emcip-policy-engine
          - service: conversation-context
            module: emcip-conversation-context
          - service: llm-orchestrator
            module: emcip-llm-orchestrator
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Log in to ghcr.io
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Extract metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.IMAGE_PREFIX }}/${{ matrix.service }}
          tags: |
            type=raw,value=jvm-latest,enable={{is_default_branch}}
            type=semver,pattern={{version}}-jvm

      - name: Build and push
        uses: docker/build-push-action@v5
        with:
          context: .
          file: ${{ matrix.module }}/Dockerfile
          push: true
          tags: ${{ steps.meta.outputs.tags }}

  build-native-amd64:
    name: Build native-amd64 (${{ matrix.service }})
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    strategy:
      fail-fast: false
      matrix:
        include:
          - service: policy-engine
            module: emcip-policy-engine
          - service: conversation-context
            module: emcip-conversation-context
          - service: llm-orchestrator
            module: emcip-llm-orchestrator
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Log in to ghcr.io
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Extract metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.IMAGE_PREFIX }}/${{ matrix.service }}
          tags: |
            type=raw,value=native-amd64,enable={{is_default_branch}}
            type=semver,pattern={{version}}-native-amd64

      - name: Build and push
        uses: docker/build-push-action@v5
        with:
          context: .
          file: ${{ matrix.module }}/Dockerfile.native
          push: true
          tags: ${{ steps.meta.outputs.tags }}
```

- [ ] **Step 2: Validate YAML syntax**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/build-images.yml')); print('PASS: valid YAML')"
```
Expected: `PASS: valid YAML`

- [ ] **Step 3: Verify job and trigger structure**

```bash
python3 -c "
import yaml
w = yaml.safe_load(open('.github/workflows/build-images.yml'))
jobs = list(w['jobs'].keys())
triggers = list(w['on'].keys())
print('Jobs:', jobs)
print('Triggers:', triggers)
assert 'build-jvm-only' in jobs
assert 'build-jvm-latest' in jobs
assert 'build-native-amd64' in jobs
assert 'push' in triggers
print('PASS')
"
```
Expected:
```
Jobs: ['build-jvm-only', 'build-jvm-latest', 'build-native-amd64']
Triggers: ['push']
PASS
```

- [ ] **Step 4: Verify matrix entries**

```bash
python3 -c "
import yaml
w = yaml.safe_load(open('.github/workflows/build-images.yml'))
jvm_only = [m['service'] for m in w['jobs']['build-jvm-only']['strategy']['matrix']['include']]
jvm_latest = [m['service'] for m in w['jobs']['build-jvm-latest']['strategy']['matrix']['include']]
native = [m['service'] for m in w['jobs']['build-native-amd64']['strategy']['matrix']['include']]
print('JVM-only:', sorted(jvm_only))
print('JVM-latest:', sorted(jvm_latest))
print('Native:', sorted(native))
assert sorted(jvm_only) == ['admin-api', 'admin-ui', 'audit-service', 'intent-classifier', 'moderation-service', 'tdlib-adapter']
assert sorted(jvm_latest) == ['conversation-context', 'llm-orchestrator', 'policy-engine']
assert sorted(native) == ['conversation-context', 'llm-orchestrator', 'policy-engine']
print('PASS')
"
```
Expected: all three assertions pass, `PASS` printed.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/build-images.yml
git commit -m "feat(ci): add GitHub Actions workflow to build and push images to ghcr.io"
```

---

### Task 2: Add `--registry` flag to `scripts/build-images.sh`

**Files:**
- Modify: `scripts/build-images.sh`

- [ ] **Step 1: Replace the full script content**

Overwrite `scripts/build-images.sh` with this exact content:

```bash
#!/usr/bin/env bash
# build-images.sh — Build and push EMCIP Docker images.
# Must be run from the project root (Dockerfiles use '.' as build context).
#
# Usage:
#   scripts/build-images.sh [--registry <prefix>] --native amd64 | --jvm | --all
#
# Examples:
#   scripts/build-images.sh --native amd64                             # local: emcip/<svc>:native-amd64
#   scripts/build-images.sh --jvm                                      # local: emcip/<svc>:latest
#   scripts/build-images.sh --jvm --registry ghcr.io/theyellow/ecip   # CI: ghcr.io/theyellow/ecip/<svc>:latest
#   scripts/build-images.sh --all --registry ghcr.io/theyellow/ecip   # all images with ghcr.io prefix
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

REGISTRY=""

is_native_capable() {
  local svc="$1"
  for entry in "${SERVICES_NATIVE[@]}"; do
    [[ "${entry##*/}" == "$svc" ]] && return 0
  done
  return 1
}

image_tag() {
  local svc="$1" tag="$2"
  if [[ -n "$REGISTRY" ]]; then
    echo "${REGISTRY}/${svc}:${tag}"
  else
    echo "emcip/${svc}:${tag}"
  fi
}

build_native_amd64() {
  echo "=== Building :native-amd64 images (GraalVM) ==="
  for entry in "${SERVICES_NATIVE[@]}"; do
    module="${entry%%/*}"
    svc="${entry##*/}"
    tag="$(image_tag "$svc" "native-amd64")"
    echo "--> ${tag}  (${module}/Dockerfile.native)"
    docker build -f "${module}/Dockerfile.native" -t "${tag}" .
    docker push "${tag}"
    echo "    Pushed ${tag}"
  done
  tdlib_tag="$(image_tag "tdlib-adapter" "latest")"
  echo "--> ${tdlib_tag}  (emcip-tdlib-adapter/Dockerfile, libtdjni.so compiled amd64)"
  docker build -f "emcip-tdlib-adapter/Dockerfile" -t "${tdlib_tag}" .
  docker push "${tdlib_tag}"
  echo "    Pushed ${tdlib_tag}"
}

build_jvm() {
  echo "=== Building JVM images ==="
  for entry in "${SERVICES_ALL[@]}"; do
    module="${entry%%/*}"
    svc="${entry##*/}"
    if [[ -n "$REGISTRY" ]] && is_native_capable "$svc"; then
      tag="$(image_tag "$svc" "jvm-latest")"
    else
      tag="$(image_tag "$svc" "latest")"
    fi
    echo "--> ${tag}  (${module}/Dockerfile)"
    docker build -f "${module}/Dockerfile" -t "${tag}" .
    docker push "${tag}"
    echo "    Pushed ${tag}"
  done
}

usage() {
  echo "Usage: $0 [--registry <prefix>] --native amd64 | --jvm | --all" >&2
  exit 1
}

# Parse optional --registry flag
if [[ "${1:-}" == "--registry" ]]; then
  [[ -n "${2:-}" ]] || { echo "Error: --registry requires a value" >&2; usage; }
  REGISTRY="${2%/}"
  shift 2
fi

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

- [ ] **Step 2: Make executable and validate syntax**

```bash
chmod +x scripts/build-images.sh
bash -n scripts/build-images.sh && echo "PASS: syntax OK"
```
Expected: `PASS: syntax OK`

- [ ] **Step 3: Verify usage message (no args)**

```bash
scripts/build-images.sh 2>&1 | head -1
```
Expected: `Usage: scripts/build-images.sh [--registry <prefix>] --native amd64 | --jvm | --all`

- [ ] **Step 4: Verify `--registry` missing value exits non-zero**

```bash
scripts/build-images.sh --registry 2>&1; echo "exit $?"
```
Expected output contains `Error: --registry requires a value` and `exit 1`.

- [ ] **Step 5: Verify `--registry` strips trailing slash**

```bash
bash -c '
source scripts/build-images.sh 2>/dev/null || true
REGISTRY=""
is_native_capable() { return 1; }
image_tag() { local svc="$1" tag="$2"; if [[ -n "$REGISTRY" ]]; then echo "${REGISTRY}/${svc}:${tag}"; else echo "emcip/${svc}:${tag}"; fi; }
REGISTRY="ghcr.io/theyellow/ecip/"   # trailing slash
REGISTRY="${REGISTRY%/}"             # strip it
result=$(image_tag "intent-classifier" "latest")
echo "$result"
[[ "$result" == "ghcr.io/theyellow/ecip/intent-classifier:latest" ]] && echo "PASS" || echo "FAIL"
'
```
Expected: `ghcr.io/theyellow/ecip/intent-classifier:latest` then `PASS`.

Actually this sourcing approach is fragile. Use a simpler direct test instead:

```bash
# Verify trailing slash is stripped
bash -c 'REGISTRY="ghcr.io/theyellow/ecip/"; REGISTRY="${REGISTRY%/}"; echo "${REGISTRY}/policy-engine:native-amd64"'
```
Expected: `ghcr.io/theyellow/ecip/policy-engine:native-amd64`

- [ ] **Step 6: Commit**

```bash
git add scripts/build-images.sh
git commit -m "feat(scripts): add --registry flag to build-images.sh for CI/remote registries"
```

---

### Task 3: Update image names in `helm/emcip/values.yaml`

**Files:**
- Modify: `helm/emcip/values.yaml`

- [ ] **Step 1: Replace all 9 image references**

In `helm/emcip/values.yaml`, update the `image:` field for each service:

| Service key | Old value | New value |
|---|---|---|
| `conversationContext` | `emcip/conversation-context:latest` | `ghcr.io/theyellow/ecip/conversation-context:jvm-latest` |
| `intentClassifier` | `emcip/intent-classifier:latest` | `ghcr.io/theyellow/ecip/intent-classifier:latest` |
| `policyEngine` | `emcip/policy-engine:latest` | `ghcr.io/theyellow/ecip/policy-engine:jvm-latest` |
| `llmOrchestrator` | `emcip/llm-orchestrator:latest` | `ghcr.io/theyellow/ecip/llm-orchestrator:jvm-latest` |
| `moderationService` | `emcip/moderation-service:latest` | `ghcr.io/theyellow/ecip/moderation-service:latest` |
| `auditService` | `emcip/audit-service:latest` | `ghcr.io/theyellow/ecip/audit-service:latest` |
| `adminApi` | `emcip/admin-api:latest` | `ghcr.io/theyellow/ecip/admin-api:latest` |
| `adminUi` | `emcip/admin-ui:latest` | `ghcr.io/theyellow/ecip/admin-ui:latest` |
| `tdlibAdapter` | `emcip/tdlib-adapter:latest` | `ghcr.io/theyellow/ecip/tdlib-adapter:latest` |

Make these 9 edits to `helm/emcip/values.yaml`. No other fields change.

- [ ] **Step 2: Verify no old `emcip/` prefix remains**

```bash
grep "image: emcip/" helm/emcip/values.yaml && echo "FAIL: old names remain" || echo "PASS: no old names"
```
Expected: `PASS: no old names`

- [ ] **Step 3: Verify all 9 new image names are present**

```bash
grep "image: ghcr.io/theyellow/ecip/" helm/emcip/values.yaml | wc -l
```
Expected: `9`

- [ ] **Step 4: Verify jvm-latest appears for exactly the 3 native-capable services**

```bash
grep "jvm-latest" helm/emcip/values.yaml
```
Expected: exactly 3 lines containing `conversation-context:jvm-latest`, `policy-engine:jvm-latest`, `llm-orchestrator:jvm-latest`.

- [ ] **Step 5: Helm lint**

```bash
helm lint helm/emcip/
```
Expected: `0 chart(s) failed`

- [ ] **Step 6: Commit**

```bash
git add helm/emcip/values.yaml
git commit -m "feat(helm): update image references to ghcr.io/theyellow/ecip"
```

---

### Task 4: Update image names in `helm/emcip/values-mixed-cluster.yaml`

**Files:**
- Modify: `helm/emcip/values-mixed-cluster.yaml`

- [ ] **Step 1: Replace all 4 image references**

In `helm/emcip/values-mixed-cluster.yaml`, update the `image:` fields:

| Entry | Old value | New value |
|---|---|---|
| `services.policyEngine` | `emcip/policy-engine:native-amd64` | `ghcr.io/theyellow/ecip/policy-engine:native-amd64` |
| `services.conversationContext` | `emcip/conversation-context:native-amd64` | `ghcr.io/theyellow/ecip/conversation-context:native-amd64` |
| `services.llmOrchestrator` | `emcip/llm-orchestrator:native-amd64` | `ghcr.io/theyellow/ecip/llm-orchestrator:native-amd64` |
| `tdlibAdapter` | `emcip/tdlib-adapter:latest` | `ghcr.io/theyellow/ecip/tdlib-adapter:latest` |

- [ ] **Step 2: Verify no old `emcip/` prefix remains**

```bash
grep "image: emcip/" helm/emcip/values-mixed-cluster.yaml && echo "FAIL: old names remain" || echo "PASS: no old names"
```
Expected: `PASS: no old names`

- [ ] **Step 3: Verify all 4 new image names are present**

```bash
grep "image: ghcr.io/theyellow/ecip/" helm/emcip/values-mixed-cluster.yaml | wc -l
```
Expected: `4`

- [ ] **Step 4: Lint and render overlay — 4 nodeSelectors, correct image names**

```bash
helm lint helm/emcip/ -f helm/emcip/values-mixed-cluster.yaml
helm template emcip helm/emcip/ -n emcip -f helm/emcip/values-mixed-cluster.yaml \
  | grep "image: ghcr.io" | sort -u
```
Expected lint: `0 chart(s) failed`
Expected images in render: lines containing:
```
ghcr.io/theyellow/ecip/conversation-context:native-amd64
ghcr.io/theyellow/ecip/llm-orchestrator:native-amd64
ghcr.io/theyellow/ecip/policy-engine:native-amd64
ghcr.io/theyellow/ecip/tdlib-adapter:latest
```
(plus the unchanged services using their base values)

- [ ] **Step 5: Commit**

```bash
git add helm/emcip/values-mixed-cluster.yaml
git commit -m "feat(helm): update mixed-cluster overlay to ghcr.io/theyellow/ecip image names"
```

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task |
|---|---|
| `.github/workflows/build-images.yml` with 3 parallel jobs | Task 1 |
| Triggers: push to `main` and `v*.*.*` tags | Task 1 |
| `build-jvm-only`: 6 services, `:latest` + `:v1.2.3` | Task 1 |
| `build-jvm-latest`: 3 services, `:jvm-latest` + `:v1.2.3-jvm` | Task 1 |
| `build-native-amd64`: 3 services, `:native-amd64` + `:v1.2.3-native-amd64` | Task 1 |
| Auth via `GITHUB_TOKEN` only | Task 1 |
| No `graalvm/setup-graalvm` (GraalVM is inside Dockerfile.native) | Task 1 ✅ confirmed |
| `--registry` flag in `scripts/build-images.sh` | Task 2 |
| `:jvm-latest` tag when `--registry` set for native-capable services | Task 2 |
| `helm/emcip/values.yaml` image names → `ghcr.io/theyellow/ecip` | Task 3 |
| Native-capable services use `:jvm-latest` in base chart | Task 3 |
| `helm/emcip/values-mixed-cluster.yaml` image names → `ghcr.io/theyellow/ecip` | Task 4 |

All spec requirements covered. No placeholders.
