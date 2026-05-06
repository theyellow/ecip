# EMCIP Implementation Backlog

Deferred and out-of-scope items from completed specs. Each entry links to its source spec for context.

---

## Infrastructure & Deployment

### GraalVM native images for R2DBC services
**Source:** `specs/2026-04-29-graalvm-native-migration-design.md` — "Out of Scope / Deferred"

Four services use R2DBC with Netty codec registries, which require complex reflection hints that were deferred:
- `emcip-moderation-service` — R2DBC with Netty codec registries
- `emcip-audit-service` — R2DBC Netty bindings
- `emcip-admin-api` — R2DBC + JJWT reflection + Security proxies
- `emcip-intent-classifier` — R2DBC codec/netty conflict

`emcip-tdlib-adapter` and `emcip-admin-ui` are permanently excluded (JNI native lib / SPA wrapper — no benefit).

**Prerequisite:** Dedicated R2DBC + GraalVM investigation epic.

---

### Kubernetes: HA / multi-replica configuration
**Source:** `specs/2026-04-29-kubernetes-helm-deployment-design.md` — "Out of Scope"

All services currently deploy with `replicas: 1`. When horizontal scaling is needed:
- Add `HorizontalPodAutoscaler` templates to the Helm chart
- Tune `replicas` defaults per service tier
- Add pod disruption budgets for critical services

---

### Kubernetes: TLS via cert-manager
**Source:** `specs/2026-04-29-kubernetes-helm-deployment-design.md` — "Out of Scope"

Currently Ingress has no TLS. Add:
- cert-manager ClusterIssuer (Let's Encrypt or self-signed)
- TLS stanza in `helm/emcip/templates/ingress.yaml`
- `values.yaml` `ingress.tls` section

---

### Mixed-cluster: `buildx` arm64 native images
**Source:** `specs/2026-05-02-mixed-cluster-helm-values-design.md` — "Out of Scope"

Cross-compiling GraalVM native images for `linux/arm64` (Pi 4). Requires:
- `buildx` setup with QEMU emulation or an arm64 build runner
- A third tag variant (e.g., `:native-arm64`)
- `values-mixed-cluster.yaml` updated to use arm64 images on Pi nodes

---

### Mixed-cluster: node taints and tolerations
**Source:** `specs/2026-05-02-mixed-cluster-helm-values-design.md` — "Out of Scope"

`nodeSelector` is sufficient for the current cluster size. If more fine-grained scheduling is needed (e.g., dedicated node pools), add `tolerations` and `taints` support to the Helm chart alongside the existing `nodeSelector` fields.

---

### CI/CD: multi-arch JVM images (`buildx` linux/arm64)
**Source:** `specs/2026-05-03-github-actions-image-publishing-design.md` — "Out of Scope"

JVM images currently build as amd64 only. `eclipse-temurin:21-jdk` is multi-arch, so a `docker buildx build --platform linux/amd64,linux/arm64` step in the workflow would produce true multi-arch manifests for the JVM-only services.

---

### CI/CD: image vulnerability scanning
**Source:** `specs/2026-05-03-github-actions-image-publishing-design.md` — "Out of Scope"

Add a scan step (e.g., `anchore/scan-action` or `aquasecurity/trivy-action`) to the `build-images.yml` workflow after each `build-push-action`.

---

### CI/CD: GraalVM build caching
**Source:** `specs/2026-05-03-github-actions-image-publishing-design.md` — "Out of Scope"

Native image builds take ~15-20 min each. Add Docker layer caching (`cache-from`/`cache-to` in `docker/build-push-action`) using `ghcr.io` or GitHub Actions cache backend to speed up repeat builds.

---

### CI/CD: staging vs. production image tags
**Source:** `specs/2026-05-03-github-actions-image-publishing-design.md` — "Out of Scope"

Currently all builds go to the same tags. A future environments strategy might push `:staging` on merge to `main` and promote to `:latest` / `:native-amd64` only on approval or release tag.

---

## Application Features

### Telegram: concurrent multi-account sessions
**Source:** `specs/2026-04-26-telegram-multi-account-auth-design.md` — "Out of Scope"

Currently only one Telegram account can be active at a time. True concurrent sessions (multiple accounts active simultaneously) requires architectural work in `tdlib-adapter`.

---

### Telegram: self-service account connection by end-users
**Source:** `specs/2026-04-26-telegram-multi-account-auth-design.md` — "Out of Scope"

Platform admins currently manage all Telegram accounts. Allow end-users to connect their own accounts via a self-service UI flow (phone → OTP → session).

---

### Admin UI: LLM cost analytics dashboard
**Source:** `specs/2026-04-24-admin-ui-phase2-design.md` — "Out of Scope"

Usage/cost dashboard showing per-tenant LLM call counts and token expenditure. Separate phase.

---

