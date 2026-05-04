# GitHub Actions Image Publishing Design

**Date:** 2026-05-03
**Status:** Approved
**Scope:** Automated Docker image builds and pushes to `ghcr.io` on every push to `main` and on version tags

---

## Context

EMCIP is a public GitHub repo (`theyellow/ecip`). Images were previously built and tagged locally using `scripts/build-images.sh` with bare `emcip/<service>` names. This spec introduces a GitHub Actions workflow that builds all images automatically and pushes them to the GitHub Container Registry (`ghcr.io/theyellow/ecip/<service>`), with no external secrets required (uses `GITHUB_TOKEN`). The Helm chart image references and the build script are updated to match.

---

## Image Tag Scheme

| Service group | Services | Tags |
|---|---|---|
| JVM-only | `intent-classifier`, `moderation-service`, `audit-service`, `admin-api`, `admin-ui`, `tdlib-adapter` | `:latest`, `:v1.2.3` (on version tag) |
| Native-capable (JVM variant) | `policy-engine`, `conversation-context`, `llm-orchestrator` | `:jvm-latest`, `:v1.2.3-jvm` (on version tag) |
| Native-capable (GraalVM native) | `policy-engine`, `conversation-context`, `llm-orchestrator` | `:native-amd64`, `:v1.2.3-native-amd64` (on version tag) |

`tdlib-adapter` uses `:latest` because it has no native-image variant — its `libtdjni.so` JNI binding is compiled amd64-only during the JVM Docker build.

---

## GitHub Actions Workflow

**File:** `.github/workflows/build-images.yml`

**Triggers:**
- `push` to `main` branch
- `push` to tags matching `v*.*.*`

**Jobs:** Three parallel jobs, all on `ubuntu-latest` (amd64 GitHub-hosted runner).

### Job 1: `build-jvm-only`

Builds the six services that have no GraalVM native variant.

**Services:** `intent-classifier`, `moderation-service`, `audit-service`, `admin-api`, `admin-ui`, `tdlib-adapter`

**Steps per service:**
1. Checkout
2. Log in to `ghcr.io` with `GITHUB_TOKEN`
3. `docker/metadata-action` — produces `:latest` on `main` push, `:v1.2.3` on tag push
4. `docker/build-push-action` — builds `<module>/Dockerfile`, pushes to `ghcr.io/theyellow/ecip/<service>`

**Build context:** repo root (`.`) — required by all existing Dockerfiles.

### Job 2: `build-jvm-latest`

Builds the JVM fallback images for the three GraalVM-capable services.

**Services:** `policy-engine`, `conversation-context`, `llm-orchestrator`

**Steps per service:**
1. Checkout
2. Log in to `ghcr.io` with `GITHUB_TOKEN`
3. `docker/metadata-action` — produces `:jvm-latest` on `main` push, `:v1.2.3-jvm` on tag push
4. `docker/build-push-action` — builds `<module>/Dockerfile`, pushes to `ghcr.io/theyellow/ecip/<service>`

### Job 3: `build-native-amd64`

Builds GraalVM native images for the three native-capable services. This is the slow job (~15-20 min per service due to AOT compilation).

**Services:** `policy-engine`, `conversation-context`, `llm-orchestrator`

**Steps per service:**
1. Checkout
2. `graalvm/setup-graalvm` action — installs GraalVM 25 + `native-image` on the runner
3. Log in to `ghcr.io` with `GITHUB_TOKEN`
4. `docker/metadata-action` — produces `:native-amd64` on `main` push, `:v1.2.3-native-amd64` on tag push
5. `docker/build-push-action` — builds `<module>/Dockerfile.native`, pushes to `ghcr.io/theyellow/ecip/<service>`

**Note:** `graalvm/setup-graalvm` is needed on the runner host only if `Dockerfile.native` runs native-image outside Docker. If the Dockerfile itself installs GraalVM internally (multi-stage build), this step can be omitted. Implementation task must verify this.

---

## `scripts/build-images.sh` Changes

Add an optional `--registry <prefix>` flag. Default is empty string, preserving current local behavior.

**New interface:**
```bash
scripts/build-images.sh --native amd64                               # local: emcip/policy-engine:native-amd64
scripts/build-images.sh --jvm                                        # local: emcip/intent-classifier:latest
scripts/build-images.sh --jvm --registry ghcr.io/theyellow/ecip     # CI/remote: ghcr.io/theyellow/ecip/intent-classifier:latest
scripts/build-images.sh --all --registry ghcr.io/theyellow/ecip     # all images, ghcr.io prefix
```

When `--registry` is set, image tags become `<registry>/<service>:<tag>`. When absent, behavior is identical to current.

The JVM-only services keep `:latest`; the native-capable services use `:jvm-latest` when a registry prefix is set (to match the new tag scheme), `:latest` when no prefix (local convenience).

---

## Helm Chart Image Name Updates

### `helm/emcip/values.yaml`

All image references updated from `emcip/<service>:latest` to `ghcr.io/theyellow/ecip/<service>:<tag>`:

| Service | Old | New |
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

### `helm/emcip/values-mixed-cluster.yaml`

| Service | Old | New |
|---|---|---|
| `policyEngine` | `emcip/policy-engine:native-amd64` | `ghcr.io/theyellow/ecip/policy-engine:native-amd64` |
| `conversationContext` | `emcip/conversation-context:native-amd64` | `ghcr.io/theyellow/ecip/conversation-context:native-amd64` |
| `llmOrchestrator` | `emcip/llm-orchestrator:native-amd64` | `ghcr.io/theyellow/ecip/llm-orchestrator:native-amd64` |
| `tdlibAdapter` | `emcip/tdlib-adapter:latest` | `ghcr.io/theyellow/ecip/tdlib-adapter:latest` |

---

## Out of Scope

- `buildx` multi-arch JVM images (arm64 native — deferred)
- Separate staging/production image tags (deferred)
- Image vulnerability scanning in CI (deferred)
- Caching layer optimisation for GraalVM builds (deferred — acceptable slow build for now)
- Helm chart version bump
