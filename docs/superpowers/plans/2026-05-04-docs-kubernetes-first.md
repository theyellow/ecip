# Documentation Restructure: Kubernetes-First Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure all AsciiDoc documentation so Kubernetes/microk8s is the primary operator path, update the PDF color theme to a print-safe light scheme, add an HTML output generation step, and create a new Kubernetes deployment diagram.

**Architecture:** Documentation-only change. No Java code is modified. The asciidoctor-maven-plugin in `pom.xml` produces PDF (existing) and HTML (new) from the same four `.adoc` source files. A new PlantUML C4 diagram is added for the Kubernetes deployment topology.

**Tech Stack:** AsciiDoc, asciidoctor-maven-plugin 3.0.0, asciidoctorj-pdf 2.3.18, asciidoctorj-diagram 2.3.1, PlantUML C4-PlantUML, CSS

---

## File Map

| File | Action | Notes |
|---|---|---|
| `documentation/emcip-theme.yml` | Modify | Replace 7 dark-era color values |
| `documentation/emcip-screen.css` | Create | New HTML stylesheet |
| `pom.xml` | Modify | Add HTML execution block to asciidoctor plugin |
| `documentation/diagrams/deployment-kubernetes.puml` | Create | New C4 deployment diagram |
| `documentation/operations-guide.adoc` | Rewrite | Full restructure — new section order, new content |
| `documentation/developer-guide.adoc` | Modify | Three minor additions |
| `documentation/architecture-guide.adoc` | Modify | Two minor additions |
| `documentation/user-guide.adoc` | Modify | One sentence added to intro |

---

### Task 1: Update PDF Theme Colors

**Files:**
- Modify: `documentation/emcip-theme.yml`

- [ ] **Step 1: Apply color changes**

Open `documentation/emcip-theme.yml` and make these exact value replacements (do not change any other values, whitespace, structure, or comments):

```yaml
# Change link.font_color:
link:
  font_color: 2563eb     # was c9a84c

# Change heading.font_color:
heading:
  font_family: Cinzel
  font_color: 1e3a5f     # was c9a84c
  font_style: bold
  line_height: 1.2
  margin_top: 16
  margin_bottom: 8

# Change heading_h1.border_bottom_color:
heading_h1:
  font_size: 22
  border_bottom_color: 3b82f6   # was c9a84c
  border_bottom_width: 1.5
  padding_bottom: 4

# Change title_page.title.font_color:
title_page:
  align: left
  title:
    font_family: Cinzel
    font_color: 1e3a5f   # was c9a84c
    font_size: 28
    font_style: bold
    line_height: 1

# Change codespan.font_color:
codespan:
  font_color: 334155     # was c9a84c
  font_family: Source Code Pro
  background_color: f1f5f9    # was f8fafc
  border_color: e2e8f0
  border_radius: 3
  border_width: 0.5

# Change code block:
code:
  font_family: Source Code Pro
  font_color: 1e293b     # was c7d2fe
  font_size: 9
  background_color: f1f5f9   # was 1e1b4b
  border_left_color: 3b82f6  # was c9a84c
  border_left_width: 3
  border_radius: 4
  padding: [8, 12, 8, 12]
  line_height: 1.4

# Change table head:
table:
  background_color: FFFFFF
  border_color: e2e8f0
  border_width: 0.5
  head:
    background_color: e2e8f0   # was c9a84c
    font_color: 1e293b         # was 0d0b24
    font_family: Cinzel
    font_size: 8.5
    font_style: bold

# Change admonition:
admonition:
  border_left_width: 3
  border_color: 3b82f6         # was c9a84c
  background_color: f8fafc
  padding: [8, 16, 8, 16]
  label:
    font_family: Cinzel
    font_color: 1e3a5f         # was c9a84c
    font_size: 8
    font_style: bold

# Change header:
header:
  border_bottom_color: 3b82f6  # was c9a84c
  border_bottom_width: 0.5
  font_family: Cinzel
  font_color: 64748b
  font_size: 8
  height: 0.4in
  padding: [0, 0, 4, 0]
  recto:
    right:
      content: '{doctitle}'
  verso:
    left:
      content: '{doctitle}'
```

The full resulting file should be:

```yaml
extends: default-with-font-fallbacks

font:
  catalog:
    merge: true
    Cinzel:
      normal: Cinzel-Variable.ttf
      bold: Cinzel-Variable.ttf
    Source Code Pro:
      normal: SourceCodePro-Variable.ttf
      bold: SourceCodePro-Variable.ttf

page:
  background_color: FFFFFF
  size: A4
  margin: [0.6in, 0.75in, 0.75in, 0.75in]

base:
  font_family: Noto Serif
  font_color: 0f172a
  font_size: 10.5
  line_height: 1.5
  border_color: e2e8f0

link:
  font_color: 2563eb

heading:
  font_family: Cinzel
  font_color: 1e3a5f
  font_style: bold
  line_height: 1.2
  margin_top: 16
  margin_bottom: 8

heading_h1:
  font_size: 22
  border_bottom_color: 3b82f6
  border_bottom_width: 1.5
  padding_bottom: 4

heading_h2:
  font_size: 16

heading_h3:
  font_size: 13

heading_h4:
  font_size: 11

# Title page
title_page:
  align: left
  title:
    font_family: Cinzel
    font_color: 1e3a5f
    font_size: 28
    font_style: bold
    line_height: 1
  subtitle:
    font_family: Cinzel
    font_color: 64748b
    font_size: 16
    font_style: normal
    margin_top: 8
  authors:
    font_color: 64748b
    font_size: 10
    margin_top: 24

# Inline code
codespan:
  font_color: 334155
  font_family: Source Code Pro
  background_color: f1f5f9
  border_color: e2e8f0
  border_radius: 3
  border_width: 0.5

# Code blocks
code:
  font_family: Source Code Pro
  font_color: 1e293b
  font_size: 9
  background_color: f1f5f9
  border_left_color: 3b82f6
  border_left_width: 3
  border_radius: 4
  padding: [8, 12, 8, 12]
  line_height: 1.4

# Tables
table:
  background_color: FFFFFF
  border_color: e2e8f0
  border_width: 0.5
  head:
    background_color: e2e8f0
    font_color: 1e293b
    font_family: Cinzel
    font_size: 8.5
    font_style: bold
  body:
    stripe_background_color: f8fafc
  foot:
    background_color: f1f5f9

# Admonition blocks (NOTE, TIP, WARNING, etc.)
admonition:
  border_left_width: 3
  border_color: 3b82f6
  background_color: f8fafc
  padding: [8, 16, 8, 16]
  label:
    font_family: Cinzel
    font_color: 1e3a5f
    font_size: 8
    font_style: bold

# Header / footer
header:
  border_bottom_color: 3b82f6
  border_bottom_width: 0.5
  font_family: Cinzel
  font_color: 64748b
  font_size: 8
  height: 0.4in
  padding: [0, 0, 4, 0]
  recto:
    right:
      content: '{doctitle}'
  verso:
    left:
      content: '{doctitle}'

footer:
  border_top_color: e2e8f0
  border_top_width: 0.5
  font_family: Noto Serif
  font_color: 64748b
  font_size: 8
  height: 0.4in
  padding: [4, 0, 0, 0]
  recto:
    right:
      content: '{page-number}'
  verso:
    left:
      content: '{page-number}'
```

- [ ] **Step 2: Verify build succeeds**

```bash
cd /home/ben/Development/ecip
mvn generate-sources -q
```

Expected: `BUILD SUCCESS`. PDF files appear in `target/generated-docs/`.

- [ ] **Step 3: Commit**

```bash
git add documentation/emcip-theme.yml
git commit -m "docs: update PDF theme to print-safe light color scheme

Replace dark code backgrounds and gold accents with:
- Light gray code blocks (#f1f5f9 bg, #1e293b text)
- Deep navy headings (#1e3a5f)
- Steel blue accents (#3b82f6, #2563eb)"
```

---

### Task 2: Create HTML Stylesheet

**Files:**
- Create: `documentation/emcip-screen.css`

- [ ] **Step 1: Create the CSS file**

Write `documentation/emcip-screen.css` with this exact content:

```css
/* EMCIP screen stylesheet — applied by asciidoctor HTML5 backend */
/* Wired via pom.xml asciidoctor plugin attributes, not per-document headers */

body {
  font-family: Georgia, serif;
  color: #1e293b;
  background: #ffffff;
  max-width: 960px;
  margin: 0 auto;
  padding: 2rem;
  line-height: 1.6;
}

h1, h2, h3, h4, h5, h6 {
  color: #1e3a5f;
  font-family: Georgia, serif;
  margin-top: 1.5em;
}

h2 {
  border-bottom: 2px solid #e2e8f0;
  padding-bottom: 0.3em;
}

a {
  color: #2563eb;
  text-decoration: none;
}

a:hover {
  text-decoration: underline;
}

pre {
  background: #f1f5f9;
  color: #1e293b;
  border-left: 3px solid #3b82f6;
  padding: 1rem;
  overflow-x: auto;
  border-radius: 4px;
  line-height: 1.4;
  font-size: 0.9em;
}

code {
  background: #f1f5f9;
  color: #334155;
  padding: 0.1em 0.3em;
  border-radius: 3px;
  font-size: 0.9em;
}

pre code {
  background: none;
  padding: 0;
  border-radius: 0;
  color: inherit;
}

table {
  border-collapse: collapse;
  width: 100%;
  margin-bottom: 1.5em;
}

table th, table td {
  border: 1px solid #e2e8f0;
  padding: 0.5em 0.75em;
  text-align: left;
}

table thead {
  background: #e2e8f0;
  color: #1e293b;
}

table tbody tr:nth-child(even) {
  background: #f8fafc;
}

.admonitionblock {
  border-left: 3px solid #3b82f6;
  background: #f8fafc;
  padding: 0.75rem 1rem;
  margin: 1em 0;
}

.admonitionblock td.icon {
  font-weight: bold;
  color: #1e3a5f;
  padding-right: 0.75rem;
  white-space: nowrap;
}

.admonitionblock td.content {
  width: 100%;
}

/* Table of contents */
#toc {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  padding: 1rem 1.5rem;
  margin-bottom: 2rem;
  border-radius: 4px;
}

#toc ul {
  margin: 0;
  padding-left: 1.5em;
}

/* Source listing titles */
.title {
  font-style: italic;
  color: #64748b;
  margin-bottom: 0.25em;
}
```

- [ ] **Step 2: Commit**

```bash
git add documentation/emcip-screen.css
git commit -m "docs: add emcip-screen.css HTML stylesheet

Print-safe light color scheme for AsciiDoc HTML5 output.
Wired via pom.xml asciidoctor plugin — not per-document headers."
```

---

### Task 3: Add HTML Output Execution to pom.xml

**Files:**
- Modify: `pom.xml` (lines ~377–430, the asciidoctor-maven-plugin block)

- [ ] **Step 1: Add the HTML execution**

In `pom.xml`, locate the `<executions>` block inside the `asciidoctor-maven-plugin`. It currently contains one execution (`generate-pdf-docs`). Add a second execution for HTML immediately after the existing one:

```xml
        <executions>
          <execution>
            <id>generate-pdf-docs</id>
            <goals>
              <goal>process-asciidoc</goal>
            </goals>
            <phase>generate-resources</phase>
          </execution>
          <execution>
            <id>generate-html-docs</id>
            <goals>
              <goal>process-asciidoc</goal>
            </goals>
            <phase>generate-resources</phase>
            <configuration>
              <backend>html5</backend>
              <outputDirectory>${project.basedir}/target/generated-docs</outputDirectory>
              <attributes>
                <allow-uri-read>true</allow-uri-read>
                <imagesdir>${project.basedir}/documentation/diagrams</imagesdir>
                <plantumldir>${project.basedir}/documentation/diagrams</plantumldir>
                <stylesheet>emcip-screen.css</stylesheet>
                <stylesdir>${project.basedir}/documentation</stylesdir>
                <toc>left</toc>
                <toclevels>3</toclevels>
                <sectnums>true</sectnums>
                <icons>font</icons>
              </attributes>
            </configuration>
          </execution>
        </executions>
```

The `<configuration>` block at the plugin level (containing `<sourceDirectory>`, `<sourceDocumentNames>`, `<requires>`, `<backend>pdf`, etc.) applies only to the first execution because the HTML execution overrides `<backend>` and `<outputDirectory>` in its own `<configuration>` block.

- [ ] **Step 2: Verify build**

```bash
cd /home/ben/Development/ecip
mvn generate-sources -q
ls target/generated-docs/
```

Expected: both `.pdf` and `.html` files for all 4 guides appear in `target/generated-docs/`.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "docs: add HTML output execution to asciidoctor-maven-plugin

Produces .html alongside .pdf for all four guides.
emcip-screen.css is applied globally via plugin attributes."
```

---

### Task 4: Create Kubernetes Deployment Diagram

**Files:**
- Create: `documentation/diagrams/deployment-kubernetes.puml`

- [ ] **Step 1: Create the PlantUML diagram**

Write `documentation/diagrams/deployment-kubernetes.puml`:

```plantuml
@startuml Deployment_Kubernetes
!include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Deployment.puml

' Purpose: microk8s production deployment diagram
' Used in: operations-guide.adoc - Kubernetes Deployment section

LAYOUT_WITH_LEGEND()

title Production Deployment - Kubernetes (microk8s)

' External: GitHub Actions CI/CD
System_Ext(github_actions, "GitHub Actions", "Builds Docker images on push to main,\npushes to ghcr.io/theyellow/ecip")

' External: ghcr.io registry
System_Ext(ghcr, "ghcr.io/theyellow/ecip", "GitHub Container Registry\nPublic image registry")

' microk8s cluster
Deployment_Node(cluster, "microk8s Cluster") {

    ' amd64 node (required for native + tdlib)
    Deployment_Node(amd64_node, "Node: amd64 (x86_64)") {

        Deployment_Node(emcip_ns_amd64, "Namespace: emcip") {

            Deployment_Node(native_services, "Native / amd64-only Deployments") {
                Container(policy_engine, "policy-engine", "GraalVM native (native-amd64)", "Port 9083\nnodeSelector: amd64")
                Container(conv_context, "conversation-context", "GraalVM native (native-amd64)", "Port 9081\nnodeSelector: amd64")
                Container(llm_orch, "llm-orchestrator", "GraalVM native (native-amd64)", "Port 9084\nnodeSelector: amd64")
                Container(tdlib, "tdlib-adapter", "JVM (latest) + libtdjni.so", "Port 9080\nnodeSelector: amd64")
            }
        }
    }

    ' arm64 node (Pi 4 — runs JVM services)
    Deployment_Node(arm64_node, "Node: arm64 (Raspberry Pi 4)", $tags="optional") {

        Deployment_Node(emcip_ns_arm, "Namespace: emcip") {

            Deployment_Node(jvm_services, "JVM Deployments (multi-arch)") {
                Container(intent_classifier, "intent-classifier", "JVM (latest)", "Port 9082")
                Container(moderation_svc, "moderation-service", "JVM (latest)", "Port 9085")
                Container(audit_svc, "audit-service", "JVM (latest)", "Port 9086")
                Container(admin_api, "admin-api", "JVM (latest)", "Port 9087")
            }
        }
    }

    ' Shared namespace resources
    Deployment_Node(emcip_shared, "Namespace: emcip — Shared") {

        ContainerDb(postgres, "PostgreSQL", "postgres:16", "Port 5432\nNFS PVC: emcip-postgres")

        Deployment_Node(observability, "Observability") {
            Container(grafana, "Grafana", "grafana/grafana", "Port 3000\nNFS PVC: emcip-grafana\nIngress: /grafana")
            Container(loki, "Loki", "grafana/loki", "Port 3100\nNFS PVC: emcip-loki")
            Container(promtail, "Promtail", "grafana/promtail", "Scrapes pod logs → Loki")
        }

        Container(admin_ui, "admin-ui", "nginx + React SPA (latest)", "Port 80\nIngress: /")
        Container(ingress, "nginx Ingress", "microk8s ingress addon", "emcip.local\nRoutes /, /api, /grafana")
    }

    ' Strimzi Kafka
    Deployment_Node(strimzi_ns, "Namespace: strimzi-system") {
        Container(strimzi, "Strimzi Operator", "strimzi/operator", "Manages Kafka CRs")
        Deployment_Node(kafka_cluster, "Namespace: emcip (Kafka CR)") {
            Container(kafka, "Kafka Broker", "strimzi/kafka", "Port 9092\n8 topics, 3 partitions")
        }
    }
}

' Relationships
Rel(github_actions, ghcr, "pushes images", "docker push")
Rel(ghcr, policy_engine, "pulls :native-amd64")
Rel(ghcr, conv_context, "pulls :native-amd64")
Rel(ghcr, llm_orch, "pulls :native-amd64")
Rel(ghcr, tdlib, "pulls :latest")
Rel(ghcr, intent_classifier, "pulls :latest")
Rel(ghcr, moderation_svc, "pulls :latest")
Rel(ghcr, audit_svc, "pulls :latest")
Rel(ghcr, admin_api, "pulls :latest")
Rel(ghcr, admin_ui, "pulls :latest")

Rel(ingress, admin_ui, "/ → port 80")
Rel(ingress, admin_api, "/api → port 9087")
Rel(ingress, grafana, "/grafana → port 3000")

Rel(tdlib, kafka, "publishes telegram.raw.messages", "Kafka")
Rel(conv_context, kafka, "pub/sub", "Kafka")
Rel(intent_classifier, kafka, "pub/sub", "Kafka")
Rel(policy_engine, kafka, "pub/sub", "Kafka")
Rel(llm_orch, kafka, "pub/sub", "Kafka")
Rel(moderation_svc, kafka, "pub/sub", "Kafka")
Rel(audit_svc, kafka, "subscribes", "Kafka")

Rel(conv_context, postgres, "reads/writes")
Rel(policy_engine, postgres, "reads/writes")
Rel(intent_classifier, postgres, "reads")
Rel(moderation_svc, postgres, "reads/writes")
Rel(audit_svc, postgres, "writes")
Rel(admin_api, postgres, "reads/writes")

@enduml
```

- [ ] **Step 2: Verify build renders the diagram**

```bash
cd /home/ben/Development/ecip
mvn generate-sources -q
ls target/generated-docs/
```

Expected: `BUILD SUCCESS` — the diagram renders as PNG embedded in the PDF/HTML. If PlantUML cannot reach the C4 `!include` URL, the build logs will show a warning. This is OK for local offline builds; CI has internet access.

- [ ] **Step 3: Commit**

```bash
git add documentation/diagrams/deployment-kubernetes.puml
git commit -m "docs: add deployment-kubernetes.puml C4 diagram

New C4 deployment diagram showing microk8s production environment:
- Mixed amd64 + arm64 nodes with nodeSelector placement
- Strimzi Kafka, NFS-backed PVCs, nginx Ingress
- ghcr.io image sources and GitHub Actions as external actor"
```

---

### Task 5: Restructure operations-guide.adoc

**Files:**
- Rewrite: `documentation/operations-guide.adoc`

This is the main task. Replace the entire file. The existing content is reorganised — nothing is deleted, only moved and augmented. The new section order is:

1. Deployment Paths (NEW)
2. Kubernetes Deployment (PROMOTED from bottom, + Image Overview, + diagram)
3. Observability (PROMOTED)
4. Operations Reference (GROUPED — Default Credentials, Performance Tuning, DLQ, Moderation, Troubleshooting)
5. Backup & Restore
6. Appendix A: Docker Compose (DEMOTED — all existing Docker content)

- [ ] **Step 1: Write the new operations-guide.adoc**

Replace `documentation/operations-guide.adoc` entirely with the content below. Read the existing file first to confirm what to preserve. The complete new file:

```asciidoc
= EMCIP Operations Guide
:toc:
:toclevels: 3
:sectnums:
:icons: font
:source-highlighter: rouge

NOTE: For the architectural context of these services, see the _Architecture Guide_.

[[deployment-paths]]
== Deployment Paths

EMCIP supports two deployment modes. Choose the one that fits your context:

[cols="1,2,2"]
|===
| | Kubernetes (microk8s) | Docker Compose

|Purpose
|Production, staging
|Local development, quick patch

|Images
|`ghcr.io/theyellow/ecip` (CI-built, automatic)
|Local `docker build` (manual)

|Config
|`helm/emcip/values.yaml`
|`.env` + `docker-compose.yml`

|Start command
|`helm upgrade --install emcip helm/emcip/ -n emcip`
|`docker compose up`
|===

=== Kubernetes (Production)

Production deployments run on microk8s via Helm.
Images are built and published automatically by GitHub Actions on every push to `main`.
The operator does not build images manually for production.
See <<kubernetes-deployment>> for full setup instructions.

=== Docker Compose (Local Development / Quick Patch)

Docker Compose is the recommended local development and quick-patch environment.
Use it when developing features, debugging locally, or testing configuration changes before rolling to Kubernetes.
See <<appendix-docker-compose>> for setup instructions.

=== CI/CD Responsibilities vs Operator Responsibilities

[cols="1,2,2"]
|===
| | CI/CD (GitHub Actions — automatic) | Operator (manual)

|Image builds
|Builds all images on push to `main` or version tag
|Not required for production

|Image publishing
|Pushes to `ghcr.io/theyellow/ecip`
|Not required for production

|Tests
|Runs `mvn verify` on every PR
|Not required for production

|Kubernetes secrets
|Does NOT manage (no cluster access)
|`kubectl create secret generic emcip-secrets ...` (once per environment)

|Helm deploy
|Does NOT deploy (no cluster access)
|`helm upgrade --install emcip helm/emcip/ -n emcip`

|Telegram auth
|Not applicable
|Interactive auth via `kubectl exec` into tdlib-adapter pod

|Pod monitoring
|Not applicable
|`kubectl get pods -n emcip`, Grafana dashboards

|Backup & restore
|Not applicable
|`scripts/db/backup.sh`, `scripts/db/restore.sh`
|===

[[kubernetes-deployment]]
== Kubernetes Deployment

[plantuml,deploy-kubernetes,png]
----
include::diagrams/deployment-kubernetes.puml[]
----

EMCIP ships a Helm chart at `helm/emcip/`.
Images are built by GitHub Actions on every push to `main` and pushed to `ghcr.io/theyellow/ecip`.
The Helm chart pulls these images directly — no local build required for production deployments.

=== Prerequisites

The following must be installed and configured on the cluster *before* running `helm install`:

[cols="2,3"]
|===
|Prerequisite |Setup command

|microk8s addons
|`microk8s enable dns ingress storage helm3`

|NFS StorageClass
a|
[source,bash]
----
helm repo add nfs-subdir-external-provisioner \
  https://kubernetes-sigs.github.io/nfs-subdir-external-provisioner/
helm install nfs-provisioner \
  nfs-subdir-external-provisioner/nfs-subdir-external-provisioner \
  --set nfs.server=<NAS_IP> \
  --set nfs.path=/exports/emcip \
  --set storageClass.name=nfs-client
----

|Strimzi operator
a|
[source,bash]
----
helm repo add strimzi https://strimzi.io/charts/
helm install strimzi-operator strimzi/strimzi-kafka-operator \
  -n strimzi-system --create-namespace
----

|Namespace
|`kubectl create namespace emcip`
|===

=== Image Overview

All 9 service images are published to `ghcr.io/theyellow/ecip` by GitHub Actions CI.
Tags are updated on every push to `main` (rolling) and on version tags (`v*.*.*`).

[cols="2,3,2"]
|===
|Service |Image |Tag (default values.yaml)

|`tdlib-adapter`
|`ghcr.io/theyellow/ecip/tdlib-adapter`
|`:latest` — amd64 JVM; libtdjni.so amd64-only

|`conversation-context`
|`ghcr.io/theyellow/ecip/conversation-context`
|`:jvm-latest` (default) / `:native-amd64` (mixed-cluster overlay)

|`intent-classifier`
|`ghcr.io/theyellow/ecip/intent-classifier`
|`:latest` — multi-arch JVM

|`policy-engine`
|`ghcr.io/theyellow/ecip/policy-engine`
|`:jvm-latest` (default) / `:native-amd64` (mixed-cluster overlay)

|`llm-orchestrator`
|`ghcr.io/theyellow/ecip/llm-orchestrator`
|`:jvm-latest` (default) / `:native-amd64` (mixed-cluster overlay)

|`moderation-service`
|`ghcr.io/theyellow/ecip/moderation-service`
|`:latest` — multi-arch JVM

|`audit-service`
|`ghcr.io/theyellow/ecip/audit-service`
|`:latest` — multi-arch JVM

|`admin-api`
|`ghcr.io/theyellow/ecip/admin-api`
|`:latest` — multi-arch JVM

|`admin-ui`
|`ghcr.io/theyellow/ecip/admin-ui`
|`:latest` — multi-arch JVM
|===

The `values-mixed-cluster.yaml` overlay switches policy-engine, conversation-context, and llm-orchestrator to `:native-amd64` and adds `nodeSelector: kubernetes.io/arch: amd64`.
See <<mixed-cluster-deployment>>.

=== Secrets — Create Before Installing

Secrets are never stored in the chart. Create them manually once per environment:

[source,bash]
----
kubectl create secret generic emcip-secrets \
  --from-literal=postgres-password=<password> \
  --from-literal=postgres-user=emcip \
  --from-literal=anthropic-api-key=<key> \
  --from-literal=admin-jwt-secret=<min-32-char-secret> \
  --from-literal=admin-service-token=<token> \
  --from-literal=telegram-api-id=<id> \
  --from-literal=telegram-api-hash=<hash> \
  --from-literal=telegram-phone-number=<+4912345> \
  --from-literal=grafana-admin-password=<password> \
  -n emcip
----

NOTE: Replace each `<...>` placeholder with a real value. `admin-jwt-secret` must be at least 32 characters.
`anthropic-api-key` is required only if the LLM profile is active.
`telegram-*` values are required only if the Telegram profile is active.

=== Install

[source,bash]
----
helm install emcip helm/emcip/ -n emcip
----

Watch rollout progress:

[source,bash]
----
kubectl get pods -n emcip -w
----

All pods should reach `Running` state. Kafka and PostgreSQL may take 2-3 minutes on first start.

=== Upgrade

[source,bash]
----
helm upgrade emcip helm/emcip/ -n emcip
----

=== Rollback

[source,bash]
----
helm rollback emcip -n emcip        # rolls back to previous release
helm history emcip -n emcip         # list available revisions
helm rollback emcip 2 -n emcip      # roll back to specific revision
----

[[mixed-cluster-deployment]]
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

==== Deploy with mixed-cluster overlay

[source,bash]
----
helm upgrade --install emcip helm/emcip/ \
  -f helm/emcip/values-mixed-cluster.yaml \
  -n emcip
----

==== Node placement verification

After deployment, verify pods landed on the correct nodes:

[source,bash]
----
kubectl get pods -n emcip -o wide
----

`policy-engine`, `conversation-context`, `llm-orchestrator`, and `tdlib-adapter` pods should show
an x86_64 node in the `NODE` column. All other pods may run on any node.

=== Access Services

With microk8s ingress enabled and `emcip.local` pointing to the cluster IP:

[source,bash]
----
# Add to /etc/hosts (replace with your microk8s IP)
echo "$(microk8s kubectl get nodes -o jsonpath='{.items[0].status.addresses[0].address}') emcip.local" \
  | sudo tee -a /etc/hosts
----

[cols="2,2,3"]
|===
|UI |URL |Notes

|Admin UI
|http://emcip.local/
|React SPA

|Admin API
|http://emcip.local/api
|REST endpoints

|Grafana
|http://emcip.local/grafana
|admin / <grafana-admin-password> from K8s secret
|===

=== Namespace Strategy

All EMCIP resources deploy to the `emcip` namespace. The Strimzi operator runs in `strimzi-system` (managed separately).

== Observability

=== Grafana Dashboards

In Kubernetes: open http://emcip.local/grafana (admin / <grafana-admin-password> from K8s secret). +
In Docker Compose: open http://localhost:14007 (admin / admin).

Three pre-built dashboards are provisioned automatically on startup:

[cols="1,3"]
|===
|Dashboard |Shows

|*Service Health*
|Actuator UP/DOWN status per service, JVM heap used, GC pause time.

|*Kafka Consumer Lag*
|Consumer group lag per topic. Alert threshold: > 1000 messages.

|*Audit Throughput*
|Audit events/minute and moderation flags/minute over time.
|===

=== Loki Log Queries

In Kubernetes: Grafana → Explore → Loki datasource. +
In Docker Compose: http://localhost:14008 or Grafana → Explore.

[source]
----
# All ERROR logs across services
{job="emcip"} |= "ERROR"

# Errors from policy-engine only
{job="emcip", service="emcip-policy-engine"} | json | level="ERROR"

# Messages by trace ID
{job="emcip"} | json | traceId="<trace-id>"

# Kafka consumer errors
{job="emcip"} |= "KafkaListenerErrorHandler"
----

=== Prometheus Metrics

Key metrics to watch:

[cols="2,1,2"]
|===
|Metric |Service |Meaning

|`kafka_consumer_fetch_manager_records_lag_max`
|all consumers
|Maximum consumer lag — spike indicates backpressure

|`hikaricp_connections_active`
|JPA services
|Active DB connections — saturation if near `maximum-pool-size` (20)

|`jvm_memory_used_bytes`
|all services
|Heap usage — watch for growth trend

|`http_server_requests_seconds_max`
|admin-api
|Worst-case request latency

|`spring_kafka_listener_seconds_max`
|all consumers
|Worst-case consumer processing time
|===

=== Structured Log Fields

All services emit JSON logs via Spring Boot 4 native structured logging (`logging.structured.format.console: logstash`). Key fields:

[cols="1,2"]
|===
|Field |Example value

|`@timestamp`
|`2026-04-22T10:15:30.123Z`

|`level`
|`INFO`, `WARN`, `ERROR`

|`logger_name`
|`io.emcip.policyengine.PolicyEvaluationService`

|`message`
|`Policy decision: BLOCK for intent SPAM`

|`traceId`
|`4bf92f3577b34da6` (populated by Micrometer Tracing)

|`spanId`
|`00f067aa0ba902b7`
|===

== Operations Reference

=== Default Credentials

==== Kubernetes (microk8s)

[cols="2,1,1,1,2"]
|===
|UI |URL |Username |Password |How to change

|Admin UI
|http://emcip.local/
|`admin`
|`changeme`
|Update `admin_users` table via `kubectl exec` (see below)

|Grafana
|http://emcip.local/grafana
|`admin`
|<from K8s secret `grafana-admin-password`>
|Update the K8s secret and restart Grafana pod
|===

To change the Admin UI password on a running Kubernetes instance:

[source,bash]
----
# Get the postgres pod name
PGPOD=$(kubectl get pod -l app=emcip-postgres -n emcip -o jsonpath='{.items[0].metadata.name}')

# Generate a new bcrypt hash (rounds=12)
python3 -c "import bcrypt; print(bcrypt.hashpw(b'newpassword', bcrypt.gensalt(12)).decode())"

# Apply it
kubectl exec -it "$PGPOD" -n emcip -- \
  psql -U emcip -d emcip -c \
  "UPDATE admin_users SET password_hash = '<hash>' WHERE username = 'admin';"
----

The Admin UI JWT signing secret is set via the `admin-jwt-secret` key in the `emcip-secrets` K8s secret (minimum 32 characters). Tokens expire after 8 hours.

==== Docker Compose

[cols="2,1,1,1,2"]
|===
|UI |Port |Username |Password |How to change

|Admin UI
|14009
|`admin`
|`changeme`
|Update `admin_users` table or edit `emcip-admin-api/.../db/changelog/changes/002-seed-admin-user.xml`

|pgAdmin
|14006
|`admin@ecip.io`
|`admin`
|`docker-compose.yml` — `PGADMIN_DEFAULT_EMAIL` / `PGADMIN_DEFAULT_PASSWORD`

|Grafana
|14007
|`admin`
|`admin`
|`docker-compose.yml` — `GF_SECURITY_ADMIN_USER` / `GF_SECURITY_ADMIN_PASSWORD`

|Kafka UI
|14004
|(none)
|(none)
|No auth — open access for local development
|===

To change the Admin UI password on a running Docker Compose instance:

[source,bash]
----
# Generate a new bcrypt hash (rounds=12)
python3 -c "import bcrypt; print(bcrypt.hashpw(b'newpassword', bcrypt.gensalt(12)).decode())"

# Apply it
docker exec -it $(docker compose ps -q postgres) \
  psql -U emcip -d emcip -c \
  "UPDATE admin_users SET password_hash = '<hash>' WHERE username = 'admin';"
----

=== Performance Tuning

==== SLOs

[cols="2,1"]
|===
|Metric |Target

|p95 intent classification latency
|< 200ms

|p95 policy evaluation latency
|< 100ms

|p99 end-to-end pipeline (ingest → audit)
|< 2s

|Kafka throughput (sustained)
|500 msg/s
|===

==== HikariCP Connection Pool

`emcip-policy-engine` is the most DB-intensive service. Current tuning in `application.yml`:

[source,yaml]
----
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000    # 30s
      idle-timeout: 600000         # 10min
----

Monitor `hikaricp_connections_active`. If it saturates at 20 under load, increase `maximum-pool-size` and verify PostgreSQL `max_connections` (default 100).

==== Kafka Consumer Tuning

`emcip-intent-classifier` handles the highest message volume:

[source,yaml]
----
spring:
  kafka:
    consumer:
      max-poll-records: 500
----

Higher values increase throughput but require more heap. Monitor `jvm_memory_used_bytes` when increasing.

==== Java Flight Recorder Profiling

[source,bash]
----
# Kubernetes: attach to a running pod
kubectl exec -it <pod-name> -n emcip -- \
  jcmd 1 JFR.start duration=60s filename=/tmp/profile.jfr
kubectl cp emcip/<pod-name>:/tmp/profile.jfr ./profile.jfr

# Docker Compose (replace PID)
docker exec <container> jcmd <pid> JFR.start duration=60s filename=/tmp/profile.jfr
----

Open the `.jfr` file in IntelliJ or JDK Mission Control.

==== Running Load Tests

[source,bash]
----
cd gatling-tests
mvn gatling:test
# Review: gatling-tests/target/gatling/*/index.html
----

Simulations cover: Admin API auth + CRUD, Kafka publish throughput, policy evaluation endpoint.

=== Error Handling & DLQ

[plantuml,seq-errors,png]
----
include::diagrams/sequence-error-handling.puml[]
----

==== Retry Configuration

Configured in `CommonKafkaConfig` (emcip-core):

* *Retries:* 3 attempts with exponential backoff (500ms, 1s, 2s).
* *Retryable exceptions:* `RetryableException`, `TransientDataAccessException`, network errors.
* *Non-retryable exceptions:* `DataIntegrityViolationException`, `IllegalArgumentException`, parse errors — sent directly to DLQ without retry.

==== DLQ Naming Convention

Each topic has a corresponding DLQ:

[source]
----
telegram.raw.messages  ->  telegram.raw.messages.dlq
messages.classified    ->  messages.classified.dlq
policies.decisions     ->  policies.decisions.dlq
----

==== Monitoring DLQ

[source,bash]
----
# Kubernetes — view DLQ via Kafka CLI
kubectl exec -it $(kubectl get pod -l app=emcip-kafka -n emcip \
  -o jsonpath='{.items[0].metadata.name}') -n emcip -- \
  kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic telegram.raw.messages.dlq \
    --from-beginning

# Docker Compose — view DLQ via Kafka UI (port 14004)
open http://localhost:14004

# Docker Compose — CLI
docker exec -it $(docker compose ps -q kafka) \
  kafka-console-consumer.sh \
    --bootstrap-server localhost:14002 \
    --topic telegram.raw.messages.dlq \
    --from-beginning
----

The `DeadLetterQueueConsumer` in `emcip-audit-service` writes a DLQ event to the audit log with the original payload and failure reason.

=== Moderation Rules

Rules are evaluated by `emcip-moderation-service` against every `policies.decisions` event.

==== Rule Types

[cols="1,3,2"]
|===
|Type |Behaviour |Example pattern

|`KEYWORD`
|Exact case-insensitive word match in message text
|`spam`

|`REGEX`
|Full Java `Pattern.compile()` regex applied to message text
|`\b(buy\|sell\|crypto)\b`

|`LENGTH`
|Fires when message character count exceeds the numeric pattern value
|`500`
|===

==== Configuring Rules via Admin API

[source,bash]
----
# Create a keyword rule
curl -X POST http://localhost:9087/api/admin/moderation-rules \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "ruleType": "KEYWORD",
    "pattern": "scam",
    "action": "FLAG",
    "enabled": true
  }'

# List all rules
curl http://localhost:9087/api/admin/moderation-rules \
  -H "Authorization: Bearer <token>"

# Delete a rule
curl -X DELETE http://localhost:9087/api/admin/moderation-rules/<id> \
  -H "Authorization: Bearer <token>"
----

In Kubernetes, replace `localhost:9087` with `http://emcip.local/api`.

==== Cache Refresh

The moderation-service caches active rules with a 5-minute TTL. Changes made via the Admin API take effect within 5 minutes without a service restart.

=== Troubleshooting

[cols="2,2,3"]
|===
|Symptom |Diagnosis command |Fix

|Pod stuck in `Pending`
|`kubectl describe pod <pod> -n emcip`
|Check `Events` section — usually a missing secret, PVC not bound, or node selector mismatch.

|Pod stuck in `CrashLoopBackOff`
|`kubectl logs <pod> -n emcip --previous`
|Read the previous container's last logs. Common: missing env var, wrong secret key, Kafka not ready.

|Kafka `Connection refused` (K8s)
|`kubectl get pods -n strimzi-system`
|Strimzi operator or Kafka CR may not be ready. Check `kubectl get kafka -n emcip`.

|PostgreSQL not ready (K8s)
|`kubectl logs <postgres-pod> -n emcip`
|Wait for Liquibase migration to complete. First start with NFS PVC may take 60s.

|Liquibase migration fails
|`kubectl logs <service-pod> -n emcip \| grep Liquibase`
|A changeset is locked. Connect via `kubectl exec` to postgres pod and run `SELECT * FROM databasechangeloglock;` — delete the lock row if `LOCKED=true`.

|TDLib auth fails (K8s)
|`kubectl logs -l app=emcip-tdlib-adapter -n emcip`
|Verify `telegram-api-id`, `telegram-api-hash`, `telegram-phone-number` keys in `emcip-secrets`. Interactive auth may be required via `kubectl exec`.

|Grafana shows no data (K8s)
|`kubectl logs -l app=emcip-loki -n emcip`
|Loki may not be ready. Check PVC is bound: `kubectl get pvc -n emcip`.

|Port already in use (Docker Compose)
|`lsof -i :<port>`
|Stop the process using that port, or remap in `docker-compose.yml`.

|Kafka `Connection refused` (Docker Compose)
|`docker compose ps kafka`
|Ensure `KAFKA_BOOTSTRAP_SERVERS=localhost:14003` in `.env`. Internal services use `kafka:14002`.

|Logback startup errors
|`kubectl logs <pod> -n emcip \| grep logback` or `docker compose logs <service> \| grep logback`
|Ensure `logstash-logback-encoder` is NOT on the classpath. Use `logging.structured.format.console: logstash` in `application.yml` instead.
|===

== Backup & Restore

=== Creating a Backup

==== Kubernetes

[source,bash]
----
# Get the postgres pod name
PGPOD=$(kubectl get pod -l app=emcip-postgres -n emcip -o jsonpath='{.items[0].metadata.name}')

# Run pg_dump inside the pod and copy the dump locally
kubectl exec "$PGPOD" -n emcip -- \
  pg_dump -U emcip -Fc emcip > backup_$(date +%Y%m%d_%H%M%S).dump
----

==== Docker Compose

[source,bash]
----
# Uses defaults: localhost:14005, database emcip, user emcip
./scripts/db/backup.sh

# Custom connection
DB_HOST=myhost DB_PORT=14005 DB_NAME=emcip DB_USER=emcip \
  PGPASSWORD=secret ./scripts/db/backup.sh
----

Output: `backup_YYYYMMDD_HHMMSS.dump` in the current directory.

=== Restore Procedure

==== Kubernetes

[source,bash]
----
# Step 1: Scale down application services
kubectl scale deployment -l app.kubernetes.io/instance=emcip -n emcip --replicas=0

# Step 2: Copy dump file into postgres pod
PGPOD=$(kubectl get pod -l app=emcip-postgres -n emcip -o jsonpath='{.items[0].metadata.name}')
kubectl cp backup_20260422_120000.dump emcip/"$PGPOD":/tmp/backup.dump

# Step 3: Restore
kubectl exec "$PGPOD" -n emcip -- \
  pg_restore -U emcip -d emcip --clean /tmp/backup.dump

# Step 4: Restart services
kubectl scale deployment -l app.kubernetes.io/instance=emcip -n emcip --replicas=1
----

==== Docker Compose

[source,bash]
----
# Step 1: Stop all application services
docker compose stop tdlib-adapter conversation-context intent-classifier \
  policy-engine llm-orchestrator moderation-service audit-service admin-api

# Step 2: Restore from dump file
./scripts/db/restore.sh backup_20260422_120000.dump

# Step 3: Verify row counts
docker exec -it $(docker compose ps -q postgres) \
  psql -U emcip -d emcip -c "
    SELECT schemaname, tablename, n_live_tup
    FROM pg_stat_user_tables
    ORDER BY n_live_tup DESC
    LIMIT 10;
  "

# Step 4: Restart services
docker compose --profile full up -d
----

=== Environment Variables for Backup Scripts

[cols="1,1,2"]
|===
|Variable |Default |Description

|`DB_HOST`
|`localhost`
|PostgreSQL host

|`DB_PORT`
|`14005`
|PostgreSQL port (Docker Compose); use `5432` for Kubernetes port-forward

|`DB_NAME`
|`emcip`
|Database name

|`DB_USER`
|`emcip`
|PostgreSQL username

|`PGPASSWORD`
|`emcip`
|PostgreSQL password (read by pg_dump/pg_restore)
|===

[[appendix-docker-compose]]
[appendix]
== Appendix A: Docker Compose

NOTE: Docker Compose is the local development and quick-patch environment.
For production deployments, see <<kubernetes-deployment>>.

=== Infrastructure Overview

[plantuml,deploy-local,png]
----
include::diagrams/deployment-local-docker.puml[]
----

The local Docker Compose environment runs 8 application services and 9 infrastructure services:

[cols="2,3"]
|===
|Category |Services

|Application (8)
|tdlib-adapter, conversation-context, intent-classifier, policy-engine, llm-orchestrator, moderation-service, audit-service, admin-api

|Infrastructure (9)
|Zookeeper, Kafka broker, Kafka UI, PostgreSQL, pgAdmin, Grafana, Loki, Promtail, Admin UI
|===

=== Quick Start

==== Default Startup (infrastructure only)

[source,bash]
----
docker compose up -d
----

Starts: Zookeeper, Kafka, Kafka UI, PostgreSQL, pgAdmin. Application services are not started by default — they are managed per-profile.

=== Profiles

[source,bash]
----
# All application services
docker compose --profile full up -d

# LLM Orchestrator (requires ANTHROPIC_API_KEY)
docker compose --profile llm up -d

# TDLib Adapter (requires Telegram credentials)
docker compose --profile telegram up -d

# Observability stack (Grafana, Loki, Promtail)
docker compose --profile observability up -d
----

=== .env File Setup

Create a `.env` file in the project root (never commit it):

[source,bash]
----
# Telegram (profile: telegram)
TELEGRAM_API_ID=12345678
TELEGRAM_API_HASH=abcdef1234567890abcdef1234567890
TELEGRAM_PHONE_NUMBER=+491234567890

# LLM (profile: llm)
ANTHROPIC_API_KEY=sk-ant-...

# Admin API JWT secret
ADMIN_JWT_SECRET=change-me-in-production-minimum-32-chars

# PostgreSQL (defaults work for local dev)
POSTGRES_USER=emcip
POSTGRES_PASSWORD=emcip
POSTGRES_DB=emcip
----

=== Port Reference

[cols="2,1,3"]
|===
|Service |Port |Purpose

|emcip-tdlib-adapter
|9080
|Telegram TDLib integration

|emcip-conversation-context
|9081
|Thread and message tracking

|emcip-intent-classifier
|9082
|NLP intent classification

|emcip-policy-engine
|9083
|Policy rule evaluation

|emcip-llm-orchestrator
|9084
|LLM provider routing

|emcip-moderation-service
|9085
|Content moderation rules

|emcip-audit-service
|9086
|Audit log and metrics

|emcip-admin-api
|9087
|Admin REST API

|Zookeeper
|14001
|Kafka coordination

|Kafka (internal)
|14002
|Broker — service-to-service

|Kafka (external)
|14003
|Broker — host access, `KAFKA_BOOTSTRAP_SERVERS`

|Kafka UI
|14004
|Kafka management UI

|PostgreSQL
|14005
|Primary database

|pgAdmin
|14006
|PostgreSQL admin UI (admin@emcip.io / admin)

|Grafana
|14007
|Observability dashboards (admin / admin)

|Loki
|14008
|Log aggregation backend

|Admin UI
|14009
|React SPA for platform administration
|===

==== Port Conflict Check

[source,bash]
----
for port in 9080 9081 9082 9083 9084 9085 9086 9087 \
            14001 14002 14003 14004 14005 14006 14007 14008 14009; do
  if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "CONFLICT: port $port in use"
  fi
done
----
```

- [ ] **Step 2: Verify build succeeds**

```bash
cd /home/ben/Development/ecip
mvn generate-sources -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add documentation/operations-guide.adoc
git commit -m "docs(ops): restructure operations-guide to Kubernetes-first

- Promote Kubernetes Deployment section to top (was at bottom)
- Add Deployment Paths orientation section with CI/CD vs Operator table
- Add Image Overview table (9 services, ghcr.io tags)
- Reference new deployment-kubernetes.puml diagram
- Update exec commands to kubectl-first with Docker Compose alternative
- Split Default Credentials by environment (K8s vs Docker Compose)
- Demote Docker Compose content to Appendix A with NOTE banner"
```

---

### Task 6: Update developer-guide.adoc

**Files:**
- Modify: `documentation/developer-guide.adoc`

Three changes:
1. The opening NOTE already says "Docker Compose is the recommended local development environment" (lines 32–34) — verify it's clear, no change needed if already correct.
2. `== GraalVM Native Image Development` — add a note that production native images are built by CI, developers don't need to build them for deployment.
3. Add a new `== Image Publishing` section at the end (before or after the Git workflow section).

- [ ] **Step 1: Add CI note to GraalVM section**

In `documentation/developer-guide.adoc`, locate `== GraalVM Native Image Development` (around line 484). After the introductory paragraph ("Three services support native image compilation..."), insert:

```asciidoc
NOTE: For production deployments, native images are built automatically by GitHub Actions
(`.github/workflows/build-images.yml`) and pushed to `ghcr.io/theyellow/ecip`.
You only need to build native images locally if you are developing or testing native-image-specific code.
```

The section should look like:

```asciidoc
== GraalVM Native Image Development

Three services support native image compilation: `emcip-policy-engine`, `emcip-conversation-context`, and `emcip-llm-orchestrator`.
Native images start in ~1-2 seconds and require no JVM.

NOTE: For production deployments, native images are built automatically by GitHub Actions
(`.github/workflows/build-images.yml`) and pushed to `ghcr.io/theyellow/ecip`.
You only need to build native images locally if you are developing or testing native-image-specific code.

=== Installing GraalVM (Ubuntu 22.04 / 24.04)
```

- [ ] **Step 2: Add Image Publishing section**

At the end of `documentation/developer-guide.adoc` (after the existing last section), add:

```asciidoc
== Image Publishing

Docker images are published to `ghcr.io/theyellow/ecip` automatically by GitHub Actions.

*Workflow file:* `.github/workflows/build-images.yml`

*Triggers:* push to `main` branch, or push of a tag matching `v*.*.*`.

*Jobs:*

[cols="2,3,2"]
|===
|Job |Services |Tag on `main` push

|`build-jvm-only`
|`intent-classifier`, `moderation-service`, `audit-service`, `admin-api`, `admin-ui`, `tdlib-adapter`
|`:latest`

|`build-jvm-latest`
|`policy-engine`, `conversation-context`, `llm-orchestrator`
|`:jvm-latest`

|`build-native-amd64`
|`policy-engine`, `conversation-context`, `llm-orchestrator`
|`:native-amd64`
|===

To build and push images locally with a registry prefix (for testing):

[source,bash]
----
# Requires docker login ghcr.io first
scripts/build-images.sh --jvm --registry ghcr.io/theyellow/ecip
scripts/build-images.sh --native amd64 --registry ghcr.io/theyellow/ecip
----

See `scripts/build-images.sh --help` for full options.
```

- [ ] **Step 3: Verify build**

```bash
cd /home/ben/Development/ecip
mvn generate-sources -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add documentation/developer-guide.adoc
git commit -m "docs(dev): add CI native image note and Image Publishing section

- Note that production native images are CI-built (no manual build needed)
- Add Image Publishing section explaining workflow, jobs, and tags"
```

---

### Task 7: Update architecture-guide.adoc and user-guide.adoc

**Files:**
- Modify: `documentation/architecture-guide.adoc`
- Modify: `documentation/user-guide.adoc`

- [ ] **Step 1: Update architecture-guide.adoc — Operating Modes table**

Locate the `=== Operating Modes` table (lines ~13–30 in `documentation/architecture-guide.adoc`). After the table, add a NOTE:

```asciidoc
NOTE: Production runtime is Kubernetes (microk8s) via Helm.
Local development uses Docker Compose. See the _Operations Guide_ for deployment details.
```

The NOTE currently at line 32 (`NOTE: For setup steps...`) stays unchanged — add the new NOTE before it.

- [ ] **Step 2: Update architecture-guide.adoc — System Context prose**

Locate `== System Context (C1)` (around line 34). After the `*External systems:*` bullet list, add:

```asciidoc
*Production deployment:* EMCIP runs on microk8s managed by Helm.
Images are built by GitHub Actions and pulled from `ghcr.io/theyellow/ecip`.
See the _Operations Guide_ for the full Kubernetes deployment diagram and instructions.
```

- [ ] **Step 3: Update architecture-guide.adoc — Kubernetes deployment diagram reference**

Locate the `Infrastructure services` paragraph near line 99 in architecture-guide.adoc:

```asciidoc
Infrastructure services (Kafka, PostgreSQL, Grafana, Loki, Admin UI) are defined in `docker-compose.yml`.
```

Replace with:

```asciidoc
Infrastructure services (Kafka, PostgreSQL, Grafana, Loki, Admin UI) are defined in `docker-compose.yml` for local development and in the Helm chart (`helm/emcip/`) for production.
For the production deployment topology, see the Kubernetes deployment diagram in the _Operations Guide_.
```

- [ ] **Step 4: Update user-guide.adoc — add production note to intro**

Locate the opening paragraph in `documentation/user-guide.adoc` (lines 8–13). After the existing two bullet points (`Part I — Admin UI` and `Part II — REST API`), add:

```asciidoc
NOTE: EMCIP runs on microk8s in production.
The Admin UI is available at `http://emcip.local/` and the Admin API at `http://emcip.local/api`.
For local development (Docker Compose), use `http://localhost:14009` and `http://localhost:9087` respectively.
```

- [ ] **Step 5: Verify build**

```bash
cd /home/ben/Development/ecip
mvn generate-sources -q
```

Expected: `BUILD SUCCESS`. Visually verify at least one generated HTML file looks correct by opening `target/generated-docs/operations-guide.html` in a browser.

- [ ] **Step 6: Commit**

```bash
git add documentation/architecture-guide.adoc documentation/user-guide.adoc
git commit -m "docs: add Kubernetes production context to architecture and user guides

- architecture-guide: note production runtime is microk8s, add diagram reference
- user-guide: note production URLs (emcip.local) vs dev URLs (localhost)"
```
