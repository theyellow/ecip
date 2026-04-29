# Kubernetes/Helm Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a single Helm chart (`helm/emcip/`) that deploys the full EMCIP stack — PostgreSQL, Strimzi-managed Kafka, Loki, Grafana, and all 8 application services — on microk8s with NFS-backed PVCs and secrets referenced by name only.

**Architecture:** One umbrella chart. Infrastructure (Postgres StatefulSet, Strimzi Kafka/KafkaTopic CRs, Loki/Grafana Deployments) in `templates/infra/`. Application services in `templates/apps/` using a standard Deployment+Service pattern; tdlib-adapter uses StatefulSet for persistent TDLib session state. All secrets referenced via `secretKeyRef` pointing to a `emcip-secrets` Secret created out-of-band. NFS `StorageClass` name is a single `values.yaml` parameter. External access via microk8s ingress (nginx addon).

**Tech Stack:** Helm 3, Kubernetes 1.28+, Strimzi 0.41+ (KRaft mode), microk8s, NFS subdir external provisioner.

**Branch:** `feature/kubernetes-helm` (branch from `main`)

---

## Prerequisites (human operator, before implementation)

These are one-time cluster setup steps not managed by the chart:

```bash
# 1. Enable microk8s addons
microk8s enable dns ingress storage helm3

# 2. Install nfs-subdir-external-provisioner (points to your NAS)
helm repo add nfs-subdir-external-provisioner \
  https://kubernetes-sigs.github.io/nfs-subdir-external-provisioner/
helm install nfs-provisioner nfs-subdir-external-provisioner/nfs-subdir-external-provisioner \
  --set nfs.server=<NAS_IP> \
  --set nfs.path=/exports/emcip \
  --set storageClass.name=nfs-client

# 3. Install Strimzi operator
helm repo add strimzi https://strimzi.io/charts/
helm install strimzi-operator strimzi/strimzi-kafka-operator \
  -n strimzi-system --create-namespace

# 4. Create namespace
kubectl create namespace emcip

# 5. Create secrets (before helm install)
kubectl create secret generic emcip-secrets \
  --from-literal=postgres-password=<password> \
  --from-literal=postgres-user=emcip \
  --from-literal=anthropic-api-key=<key> \
  --from-literal=admin-jwt-secret=<min-32-char-secret> \
  --from-literal=admin-service-token=<token> \
  --from-literal=telegram-api-id=<id> \
  --from-literal=telegram-api-hash=<hash> \
  --from-literal=telegram-phone-number=<+4912345> \
  -n emcip
```

---

## File Map

| Action | File |
|--------|------|
| Create | `helm/emcip/Chart.yaml` |
| Create | `helm/emcip/values.yaml` |
| Create | `helm/emcip/templates/_helpers.tpl` |
| Create | `helm/emcip/templates/infra/postgres-pvc.yaml` |
| Create | `helm/emcip/templates/infra/postgres-statefulset.yaml` |
| Create | `helm/emcip/templates/infra/postgres-service.yaml` |
| Create | `helm/emcip/templates/infra/kafka.yaml` |
| Create | `helm/emcip/templates/infra/kafka-nodepool.yaml` |
| Create | `helm/emcip/templates/infra/kafka-topics.yaml` |
| Create | `helm/emcip/templates/infra/loki-configmap.yaml` |
| Create | `helm/emcip/templates/infra/loki-pvc.yaml` |
| Create | `helm/emcip/templates/infra/loki-deployment.yaml` |
| Create | `helm/emcip/templates/infra/loki-service.yaml` |
| Create | `helm/emcip/templates/infra/grafana-pvc.yaml` |
| Create | `helm/emcip/templates/infra/grafana-configmap.yaml` |
| Create | `helm/emcip/templates/infra/grafana-deployment.yaml` |
| Create | `helm/emcip/templates/infra/grafana-service.yaml` |
| Create | `helm/emcip/templates/apps/standard-deployments.yaml` |
| Create | `helm/emcip/templates/apps/tdlib-statefulset.yaml` |
| Create | `helm/emcip/templates/apps/tdlib-pvc.yaml` |
| Create | `helm/emcip/templates/apps/tdlib-service.yaml` |
| Create | `helm/emcip/templates/ingress.yaml` |
| Modify | `documentation/operations-guide.adoc` |
| Modify | `documentation/developer-guide.adoc` |

---

## Task 1: Chart Scaffold — Chart.yaml, values.yaml, _helpers.tpl

**Files:**
- Create: `helm/emcip/Chart.yaml`
- Create: `helm/emcip/values.yaml`
- Create: `helm/emcip/templates/_helpers.tpl`

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p helm/emcip/templates/infra helm/emcip/templates/apps
```

- [ ] **Step 2: Create Chart.yaml**

Create `helm/emcip/Chart.yaml`:

```yaml
apiVersion: v2
name: emcip
description: EMCIP - Community Intelligence Platform
type: application
version: 0.1.0
appVersion: "0.1.0-SNAPSHOT"
keywords:
  - emcip
  - telegram
  - intelligence
  - kafka
  - spring-boot
```

- [ ] **Step 3: Create values.yaml**

Create `helm/emcip/values.yaml`:

```yaml
# ============================================================
# EMCIP Helm Chart values
# ============================================================
# IMPORTANT: Never put secrets here.
# All secrets live in a Kubernetes Secret named 'emcip-secrets'.
# See documentation/operations-guide.adoc for creation instructions.

# Global image settings
image:
  registry: ""          # e.g. "registry.example.com/" — leave empty for Docker Hub
  pullPolicy: IfNotPresent

# Storage — all PVCs use this StorageClass (points to NAS NFS provisioner)
storage:
  storageClassName: nfs-client
  postgres:
    size: 10Gi
  kafka:
    size: 20Gi
  loki:
    size: 10Gi
  grafana:
    size: 1Gi
  tdlibDb:
    size: 1Gi
  tdlibFiles:
    size: 5Gi

# Secret reference — name of the pre-created Kubernetes Secret
secretName: emcip-secrets

# Ingress
ingress:
  enabled: true
  className: nginx
  host: emcip.local
  # Paths map to admin-ui (catch-all) and admin-api
  adminApiPath: /api
  adminUiPath: /

# ============================================================
# Application services
# Services listed here get a standard Deployment + ClusterIP Service.
# tdlib-adapter is separate (StatefulSet).
# ============================================================
services:
  conversationContext:
    name: conversation-context
    image: emcip/conversation-context:latest
    port: 9081
    replicas: 1
    env:
      SPRING_DATASOURCE_URL: "jdbc:postgresql://emcip-postgres:5432/emcip"
      KAFKA_BOOTSTRAP_SERVERS: "emcip-kafka-bootstrap:9092"
    resources:
      requests:
        cpu: 100m
        memory: 64Mi
      limits:
        cpu: 500m
        memory: 256Mi

  intentClassifier:
    name: intent-classifier
    image: emcip/intent-classifier:latest
    port: 9082
    replicas: 1
    env:
      KAFKA_BOOTSTRAP_SERVERS: "emcip-kafka-bootstrap:9092"
    resources:
      requests:
        cpu: 100m
        memory: 64Mi
      limits:
        cpu: 500m
        memory: 256Mi

  policyEngine:
    name: policy-engine
    image: emcip/policy-engine:latest
    port: 9083
    replicas: 1
    env:
      SPRING_DATASOURCE_URL: "jdbc:postgresql://emcip-postgres:5432/emcip"
      KAFKA_BOOTSTRAP_SERVERS: "emcip-kafka-bootstrap:9092"
    resources:
      requests:
        cpu: 100m
        memory: 64Mi
      limits:
        cpu: 500m
        memory: 256Mi

  llmOrchestrator:
    name: llm-orchestrator
    image: emcip/llm-orchestrator:latest
    port: 9084
    replicas: 1
    env:
      SPRING_DATASOURCE_URL: "jdbc:postgresql://emcip-postgres:5432/emcip"
      KAFKA_BOOTSTRAP_SERVERS: "emcip-kafka-bootstrap:9092"
    secrets:
      ANTHROPIC_API_KEY: anthropic-api-key
    resources:
      requests:
        cpu: 100m
        memory: 128Mi
      limits:
        cpu: 1000m
        memory: 512Mi

  moderationService:
    name: moderation-service
    image: emcip/moderation-service:latest
    port: 9085
    replicas: 1
    env:
      SPRING_R2DBC_URL: "r2dbc:postgresql://emcip-postgres:5432/emcip"
      SPRING_LIQUIBASE_URL: "jdbc:postgresql://emcip-postgres:5432/emcip"
      KAFKA_BOOTSTRAP_SERVERS: "emcip-kafka-bootstrap:9092"
    resources:
      requests:
        cpu: 100m
        memory: 128Mi
      limits:
        cpu: 500m
        memory: 512Mi

  auditService:
    name: audit-service
    image: emcip/audit-service:latest
    port: 9086
    replicas: 1
    env:
      SPRING_R2DBC_URL: "r2dbc:postgresql://emcip-postgres:5432/emcip"
      SPRING_LIQUIBASE_URL: "jdbc:postgresql://emcip-postgres:5432/emcip"
      KAFKA_BOOTSTRAP_SERVERS: "emcip-kafka-bootstrap:9092"
    resources:
      requests:
        cpu: 100m
        memory: 128Mi
      limits:
        cpu: 500m
        memory: 512Mi

  adminApi:
    name: admin-api
    image: emcip/admin-api:latest
    port: 9087
    replicas: 1
    env:
      SPRING_R2DBC_URL: "r2dbc:postgresql://emcip-postgres:5432/emcip"
      SPRING_LIQUIBASE_URL: "jdbc:postgresql://emcip-postgres:5432/emcip"
    secrets:
      ADMIN_JWT_SECRET: admin-jwt-secret
      ADMIN_SERVICE_TOKEN: admin-service-token
    resources:
      requests:
        cpu: 100m
        memory: 128Mi
      limits:
        cpu: 500m
        memory: 512Mi

  adminUi:
    name: admin-ui
    image: emcip/admin-ui:latest
    port: 14009
    replicas: 1
    env: {}
    resources:
      requests:
        cpu: 50m
        memory: 64Mi
      limits:
        cpu: 200m
        memory: 128Mi

# tdlib-adapter is a StatefulSet (separate section — needs PVC for session state)
tdlibAdapter:
  name: tdlib-adapter
  image: emcip/tdlib-adapter:latest
  port: 9080
  replicas: 1
  env:
    KAFKA_BOOTSTRAP_SERVERS: "emcip-kafka-bootstrap:9092"
  secrets:
    TELEGRAM_API_ID: telegram-api-id
    TELEGRAM_API_HASH: telegram-api-hash
    TELEGRAM_PHONE_NUMBER: telegram-phone-number
  resources:
    requests:
      cpu: 200m
      memory: 256Mi
    limits:
      cpu: 1000m
      memory: 1Gi

# Infrastructure image versions
infra:
  postgres:
    image: postgres:16-alpine
    port: 5432
  loki:
    image: grafana/loki:3.0.0
    port: 3100
  grafana:
    image: grafana/grafana-oss:11.0.0
    port: 3000
```

- [ ] **Step 4: Create _helpers.tpl**

Create `helm/emcip/templates/_helpers.tpl`:

```
{{/*
Expand the name of the chart.
*/}}
{{- define "emcip.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "emcip.fullname" -}}
{{- printf "%s" .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels applied to all resources.
*/}}
{{- define "emcip.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Selector labels for a given service name.
Usage: include "emcip.selectorLabels" (dict "name" "policy-engine")
*/}}
{{- define "emcip.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .instance }}
{{- end }}
```

- [ ] **Step 5: Validate chart structure**

```bash
helm lint helm/emcip/
```

Expected: `1 chart(s) linted, 0 chart(s) failed`. Ignore warnings about empty templates directory at this stage.

- [ ] **Step 6: Commit**

```bash
git add helm/
git commit -m "feat(k8s): scaffold Helm chart structure, Chart.yaml, values.yaml, helpers"
```

---

## Task 2: PostgreSQL StatefulSet + PVC + Service

**Files:**
- Create: `helm/emcip/templates/infra/postgres-pvc.yaml`
- Create: `helm/emcip/templates/infra/postgres-statefulset.yaml`
- Create: `helm/emcip/templates/infra/postgres-service.yaml`

- [ ] **Step 1: Create postgres-pvc.yaml**

Create `helm/emcip/templates/infra/postgres-pvc.yaml`:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: emcip-postgres-data
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
    app.kubernetes.io/component: postgres
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: {{ .Values.storage.storageClassName }}
  resources:
    requests:
      storage: {{ .Values.storage.postgres.size }}
```

- [ ] **Step 2: Create postgres-statefulset.yaml**

Create `helm/emcip/templates/infra/postgres-statefulset.yaml`:

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: emcip-postgres
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
    app.kubernetes.io/component: postgres
spec:
  serviceName: emcip-postgres
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: postgres
      app.kubernetes.io/instance: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: postgres
        app.kubernetes.io/instance: {{ .Release.Name }}
        app.kubernetes.io/component: postgres
    spec:
      containers:
        - name: postgres
          image: {{ .Values.infra.postgres.image }}
          ports:
            - containerPort: {{ .Values.infra.postgres.port }}
          env:
            - name: POSTGRES_DB
              value: emcip
            - name: POSTGRES_USER
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.secretName }}
                  key: postgres-user
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.secretName }}
                  key: postgres-password
          volumeMounts:
            - name: postgres-data
              mountPath: /var/lib/postgresql/data
          readinessProbe:
            exec:
              command: ["pg_isready", "-U", "emcip"]
            initialDelaySeconds: 5
            periodSeconds: 10
          livenessProbe:
            exec:
              command: ["pg_isready", "-U", "emcip"]
            initialDelaySeconds: 30
            periodSeconds: 10
      volumes:
        - name: postgres-data
          persistentVolumeClaim:
            claimName: emcip-postgres-data
```

- [ ] **Step 3: Create postgres-service.yaml**

Create `helm/emcip/templates/infra/postgres-service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: emcip-postgres
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
    app.kubernetes.io/component: postgres
spec:
  type: ClusterIP
  ports:
    - port: 5432
      targetPort: {{ .Values.infra.postgres.port }}
      protocol: TCP
  selector:
    app.kubernetes.io/name: postgres
    app.kubernetes.io/instance: {{ .Release.Name }}
```

- [ ] **Step 4: Validate**

```bash
helm lint helm/emcip/
helm template emcip helm/emcip/ -n emcip | grep -A5 "kind: StatefulSet"
```

Expected: lint passes, StatefulSet block appears in rendered output.

- [ ] **Step 5: Commit**

```bash
git add helm/emcip/templates/infra/
git commit -m "feat(k8s): add PostgreSQL StatefulSet, PVC, Service templates"
```

---

## Task 3: Strimzi Kafka CR + KafkaNodePool + KafkaTopic CRs

**Files:**
- Create: `helm/emcip/templates/infra/kafka.yaml`
- Create: `helm/emcip/templates/infra/kafka-nodepool.yaml`
- Create: `helm/emcip/templates/infra/kafka-topics.yaml`

- [ ] **Step 1: Create kafka-nodepool.yaml**

Create `helm/emcip/templates/infra/kafka-nodepool.yaml`:

```yaml
# Strimzi KafkaNodePool — KRaft mode (no ZooKeeper)
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaNodePool
metadata:
  name: emcip
  namespace: {{ .Release.Namespace }}
  labels:
    strimzi.io/cluster: emcip
    {{- include "emcip.labels" . | nindent 4 }}
spec:
  replicas: 1
  roles:
    - controller
    - broker
  storage:
    type: persistent-claim
    size: {{ .Values.storage.kafka.size }}
    class: {{ .Values.storage.storageClassName }}
    deleteClaim: false
  resources:
    requests:
      memory: 512Mi
      cpu: 200m
    limits:
      memory: 1Gi
      cpu: 1000m
```

- [ ] **Step 2: Create kafka.yaml**

Create `helm/emcip/templates/infra/kafka.yaml`:

```yaml
# Strimzi Kafka CR — KRaft mode, single broker
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: emcip
  namespace: {{ .Release.Namespace }}
  annotations:
    strimzi.io/node-pools: enabled
    strimzi.io/kraft: enabled
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
spec:
  kafka:
    version: 3.7.0
    listeners:
      - name: plain
        port: 9092
        type: internal
        tls: false
    config:
      offsets.topic.replication.factor: 1
      transaction.state.log.replication.factor: 1
      transaction.state.log.min.isr: 1
      default.replication.factor: 1
      min.insync.replicas: 1
      num.partitions: 3
      auto.create.topics.enable: "true"
  entityOperator:
    topicOperator: {}
```

- [ ] **Step 3: Create kafka-topics.yaml**

Create `helm/emcip/templates/infra/kafka-topics.yaml`:

```yaml
# KafkaTopic CRs for all EMCIP topics.
# Strimzi entity operator reconciles these into Kafka.
{{- $ns := .Release.Namespace }}
{{- $release := .Release.Name }}
{{- $chart := .Chart }}
{{- $topics := list
  "telegram.raw.messages"
  "telegram.raw.updates"
  "telegram.messages"
  "messages.classified"
  "policies.decisions"
  "responses.generated"
  "responses.pending"
  "moderation.flags"
  "moderation.actions"
  "commands.execute"
  "escalation.human"
  "review.pending"
}}
{{- range $topics }}
---
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaTopic
metadata:
  name: {{ . | replace "." "-" }}
  namespace: {{ $ns }}
  labels:
    strimzi.io/cluster: emcip
    helm.sh/chart: {{ $chart.Name }}-{{ $chart.Version }}
    app.kubernetes.io/instance: {{ $release }}
spec:
  partitions: 3
  replicas: 1
  config:
    retention.ms: 604800000   # 7 days
    cleanup.policy: delete
{{- end }}
```

- [ ] **Step 4: Validate**

```bash
helm lint helm/emcip/
helm template emcip helm/emcip/ -n emcip | grep "kind: Kafka"
```

Expected: `kind: Kafka`, `kind: KafkaNodePool`, and multiple `kind: KafkaTopic` blocks in output.

- [ ] **Step 5: Commit**

```bash
git add helm/emcip/templates/infra/kafka.yaml \
        helm/emcip/templates/infra/kafka-nodepool.yaml \
        helm/emcip/templates/infra/kafka-topics.yaml
git commit -m "feat(k8s): add Strimzi Kafka CR, KafkaNodePool, KafkaTopic templates"
```

---

## Task 4: Loki + Grafana Observability

**Files:**
- Create: `helm/emcip/templates/infra/loki-configmap.yaml`
- Create: `helm/emcip/templates/infra/loki-pvc.yaml`
- Create: `helm/emcip/templates/infra/loki-deployment.yaml`
- Create: `helm/emcip/templates/infra/loki-service.yaml`
- Create: `helm/emcip/templates/infra/grafana-pvc.yaml`
- Create: `helm/emcip/templates/infra/grafana-configmap.yaml`
- Create: `helm/emcip/templates/infra/grafana-deployment.yaml`
- Create: `helm/emcip/templates/infra/grafana-service.yaml`

- [ ] **Step 1: Create loki-configmap.yaml**

Create `helm/emcip/templates/infra/loki-configmap.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: emcip-loki-config
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
data:
  loki-config.yaml: |
    auth_enabled: false
    server:
      http_listen_port: 3100
    ingester:
      lifecycler:
        address: 127.0.0.1
        ring:
          kvstore:
            store: inmemory
          replication_factor: 1
        final_sleep: 0s
      chunk_idle_period: 5m
      chunk_retain_period: 30s
    schema_config:
      configs:
        - from: 2024-01-01
          store: boltdb-shipper
          object_store: filesystem
          schema: v11
          index:
            prefix: index_
            period: 24h
    storage_config:
      boltdb_shipper:
        active_index_directory: /loki/index
        cache_location: /loki/cache
        shared_store: filesystem
      filesystem:
        directory: /loki/chunks
    limits_config:
      enforce_metric_name: false
      reject_old_samples: true
      reject_old_samples_max_age: 168h
    compactor:
      working_directory: /loki/compactor
      shared_store: filesystem
```

- [ ] **Step 2: Create loki-pvc.yaml**

Create `helm/emcip/templates/infra/loki-pvc.yaml`:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: emcip-loki-data
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: {{ .Values.storage.storageClassName }}
  resources:
    requests:
      storage: {{ .Values.storage.loki.size }}
```

- [ ] **Step 3: Create loki-deployment.yaml**

Create `helm/emcip/templates/infra/loki-deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: emcip-loki
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
    app.kubernetes.io/component: loki
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: loki
      app.kubernetes.io/instance: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: loki
        app.kubernetes.io/instance: {{ .Release.Name }}
    spec:
      containers:
        - name: loki
          image: {{ .Values.infra.loki.image }}
          args: ["-config.file=/etc/loki/loki-config.yaml"]
          ports:
            - containerPort: {{ .Values.infra.loki.port }}
          readinessProbe:
            httpGet:
              path: /ready
              port: {{ .Values.infra.loki.port }}
            initialDelaySeconds: 15
            periodSeconds: 10
          volumeMounts:
            - name: config
              mountPath: /etc/loki
            - name: data
              mountPath: /loki
      volumes:
        - name: config
          configMap:
            name: emcip-loki-config
        - name: data
          persistentVolumeClaim:
            claimName: emcip-loki-data
```

- [ ] **Step 4: Create loki-service.yaml**

Create `helm/emcip/templates/infra/loki-service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: emcip-loki
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
spec:
  type: ClusterIP
  ports:
    - port: 3100
      targetPort: {{ .Values.infra.loki.port }}
  selector:
    app.kubernetes.io/name: loki
    app.kubernetes.io/instance: {{ .Release.Name }}
```

- [ ] **Step 5: Create grafana-pvc.yaml**

Create `helm/emcip/templates/infra/grafana-pvc.yaml`:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: emcip-grafana-data
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: {{ .Values.storage.storageClassName }}
  resources:
    requests:
      storage: {{ .Values.storage.grafana.size }}
```

- [ ] **Step 6: Create grafana-configmap.yaml**

Create `helm/emcip/templates/infra/grafana-configmap.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: emcip-grafana-datasources
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
data:
  datasources.yaml: |
    apiVersion: 1
    datasources:
      - name: Loki
        type: loki
        access: proxy
        url: http://emcip-loki:3100
        isDefault: true
        version: 1
        editable: false
```

- [ ] **Step 7: Create grafana-deployment.yaml**

Create `helm/emcip/templates/infra/grafana-deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: emcip-grafana
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
    app.kubernetes.io/component: grafana
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: grafana
      app.kubernetes.io/instance: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: grafana
        app.kubernetes.io/instance: {{ .Release.Name }}
    spec:
      containers:
        - name: grafana
          image: {{ .Values.infra.grafana.image }}
          ports:
            - containerPort: {{ .Values.infra.grafana.port }}
          env:
            - name: GF_SECURITY_ADMIN_USER
              value: admin
            - name: GF_SECURITY_ADMIN_PASSWORD
              value: admin
            - name: GF_USERS_ALLOW_SIGN_UP
              value: "false"
          readinessProbe:
            httpGet:
              path: /api/health
              port: {{ .Values.infra.grafana.port }}
            initialDelaySeconds: 10
            periodSeconds: 10
          volumeMounts:
            - name: data
              mountPath: /var/lib/grafana
            - name: datasources
              mountPath: /etc/grafana/provisioning/datasources
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: emcip-grafana-data
        - name: datasources
          configMap:
            name: emcip-grafana-datasources
```

- [ ] **Step 8: Create grafana-service.yaml**

Create `helm/emcip/templates/infra/grafana-service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: emcip-grafana
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
spec:
  type: ClusterIP
  ports:
    - port: 3000
      targetPort: {{ .Values.infra.grafana.port }}
  selector:
    app.kubernetes.io/name: grafana
    app.kubernetes.io/instance: {{ .Release.Name }}
```

- [ ] **Step 9: Validate and commit**

```bash
helm lint helm/emcip/
git add helm/emcip/templates/infra/loki-*.yaml helm/emcip/templates/infra/grafana-*.yaml
git commit -m "feat(k8s): add Loki and Grafana Deployment, Service, PVC, ConfigMap templates"
```

---

## Task 5: Standard Application Service Deployments

**Files:**
- Create: `helm/emcip/templates/apps/standard-deployments.yaml`

All 8 standard services (all except tdlib-adapter) follow the same pattern. A single template file iterates over `values.services` using a Helm range loop.

- [ ] **Step 1: Create standard-deployments.yaml**

Create `helm/emcip/templates/apps/standard-deployments.yaml`:

```yaml
{{- range $key, $svc := .Values.services }}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: emcip-{{ $svc.name }}
  namespace: {{ $.Release.Namespace }}
  labels:
    helm.sh/chart: {{ $.Chart.Name }}-{{ $.Chart.Version }}
    app.kubernetes.io/managed-by: {{ $.Release.Service }}
    app.kubernetes.io/instance: {{ $.Release.Name }}
    app.kubernetes.io/name: {{ $svc.name }}
    app.kubernetes.io/component: application
spec:
  replicas: {{ $svc.replicas }}
  selector:
    matchLabels:
      app.kubernetes.io/name: {{ $svc.name }}
      app.kubernetes.io/instance: {{ $.Release.Name }}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: {{ $svc.name }}
        app.kubernetes.io/instance: {{ $.Release.Name }}
    spec:
      containers:
        - name: {{ $svc.name }}
          image: {{ $.Values.image.registry }}{{ $svc.image }}
          imagePullPolicy: {{ $.Values.image.pullPolicy }}
          ports:
            - containerPort: {{ $svc.port }}
          env:
            {{- range $envKey, $envVal := $svc.env }}
            - name: {{ $envKey }}
              value: {{ $envVal | quote }}
            {{- end }}
            - name: SPRING_DATASOURCE_USERNAME
              valueFrom:
                secretKeyRef:
                  name: {{ $.Values.secretName }}
                  key: postgres-user
                  optional: true
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ $.Values.secretName }}
                  key: postgres-password
                  optional: true
            - name: SPRING_R2DBC_USERNAME
              valueFrom:
                secretKeyRef:
                  name: {{ $.Values.secretName }}
                  key: postgres-user
                  optional: true
            - name: SPRING_R2DBC_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ $.Values.secretName }}
                  key: postgres-password
                  optional: true
            {{- range $secretKey, $secretRef := $svc.secrets }}
            - name: {{ $secretKey }}
              valueFrom:
                secretKeyRef:
                  name: {{ $.Values.secretName }}
                  key: {{ $secretRef }}
            {{- end }}
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: {{ $svc.port }}
            initialDelaySeconds: 15
            periodSeconds: 10
            failureThreshold: 3
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: {{ $svc.port }}
            initialDelaySeconds: 30
            periodSeconds: 15
            failureThreshold: 3
          resources:
            {{- toYaml $svc.resources | nindent 12 }}
---
apiVersion: v1
kind: Service
metadata:
  name: emcip-{{ $svc.name }}
  namespace: {{ $.Release.Namespace }}
  labels:
    helm.sh/chart: {{ $.Chart.Name }}-{{ $.Chart.Version }}
    app.kubernetes.io/instance: {{ $.Release.Name }}
    app.kubernetes.io/name: {{ $svc.name }}
spec:
  type: ClusterIP
  ports:
    - port: {{ $svc.port }}
      targetPort: {{ $svc.port }}
      protocol: TCP
  selector:
    app.kubernetes.io/name: {{ $svc.name }}
    app.kubernetes.io/instance: {{ $.Release.Name }}
{{- end }}
```

Note: `optional: true` on the postgres credential env vars means services that don't use JPA/R2DBC (e.g. intent-classifier) won't fail if the key is missing in the secret — but it's safe to include it regardless.

- [ ] **Step 2: Validate**

```bash
helm lint helm/emcip/
helm template emcip helm/emcip/ -n emcip | grep "kind: Deployment" | wc -l
```

Expected: 8 Deployment blocks (one per service in `.Values.services`).

```bash
helm template emcip helm/emcip/ -n emcip | grep "kind: Service" | grep -v "kind: ServiceAccount" | wc -l
```

Expected: at least 8 Service blocks (plus postgres/loki/grafana).

- [ ] **Step 3: Commit**

```bash
git add helm/emcip/templates/apps/standard-deployments.yaml
git commit -m "feat(k8s): add standard application service Deployment+Service templates (range loop)"
```

---

## Task 6: tdlib-adapter StatefulSet + PVCs

**Files:**
- Create: `helm/emcip/templates/apps/tdlib-pvc.yaml`
- Create: `helm/emcip/templates/apps/tdlib-statefulset.yaml`
- Create: `helm/emcip/templates/apps/tdlib-service.yaml`

- [ ] **Step 1: Create tdlib-pvc.yaml**

Create `helm/emcip/templates/apps/tdlib-pvc.yaml`:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: emcip-tdlib-db
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: {{ .Values.storage.storageClassName }}
  resources:
    requests:
      storage: {{ .Values.storage.tdlibDb.size }}
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: emcip-tdlib-files
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: {{ .Values.storage.storageClassName }}
  resources:
    requests:
      storage: {{ .Values.storage.tdlibFiles.size }}
```

- [ ] **Step 2: Create tdlib-statefulset.yaml**

Create `helm/emcip/templates/apps/tdlib-statefulset.yaml`:

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: emcip-tdlib-adapter
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
    app.kubernetes.io/name: {{ .Values.tdlibAdapter.name }}
    app.kubernetes.io/component: application
spec:
  serviceName: emcip-tdlib-adapter
  replicas: {{ .Values.tdlibAdapter.replicas }}
  selector:
    matchLabels:
      app.kubernetes.io/name: {{ .Values.tdlibAdapter.name }}
      app.kubernetes.io/instance: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: {{ .Values.tdlibAdapter.name }}
        app.kubernetes.io/instance: {{ .Release.Name }}
    spec:
      containers:
        - name: tdlib-adapter
          image: {{ .Values.image.registry }}{{ .Values.tdlibAdapter.image }}
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - containerPort: {{ .Values.tdlibAdapter.port }}
          env:
            {{- range $k, $v := .Values.tdlibAdapter.env }}
            - name: {{ $k }}
              value: {{ $v | quote }}
            {{- end }}
            {{- range $k, $ref := .Values.tdlibAdapter.secrets }}
            - name: {{ $k }}
              valueFrom:
                secretKeyRef:
                  name: {{ $.Values.secretName }}
                  key: {{ $ref }}
            {{- end }}
            - name: TDLIB_DB_DIR
              value: /app/tdlib-db
            - name: TDLIB_FILES_DIR
              value: /app/tdlib-files
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: {{ .Values.tdlibAdapter.port }}
            initialDelaySeconds: 30
            periodSeconds: 15
          volumeMounts:
            - name: tdlib-db
              mountPath: /app/tdlib-db
            - name: tdlib-files
              mountPath: /app/tdlib-files
          resources:
            {{- toYaml .Values.tdlibAdapter.resources | nindent 12 }}
      volumes:
        - name: tdlib-db
          persistentVolumeClaim:
            claimName: emcip-tdlib-db
        - name: tdlib-files
          persistentVolumeClaim:
            claimName: emcip-tdlib-files
```

- [ ] **Step 3: Create tdlib-service.yaml**

Create `helm/emcip/templates/apps/tdlib-service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: emcip-tdlib-adapter
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
spec:
  type: ClusterIP
  ports:
    - port: {{ .Values.tdlibAdapter.port }}
      targetPort: {{ .Values.tdlibAdapter.port }}
      protocol: TCP
  selector:
    app.kubernetes.io/name: {{ .Values.tdlibAdapter.name }}
    app.kubernetes.io/instance: {{ .Release.Name }}
```

- [ ] **Step 4: Validate and commit**

```bash
helm lint helm/emcip/
git add helm/emcip/templates/apps/tdlib-*.yaml
git commit -m "feat(k8s): add tdlib-adapter StatefulSet, PVCs, Service templates"
```

---

## Task 7: Ingress

**Files:**
- Create: `helm/emcip/templates/ingress.yaml`

- [ ] **Step 1: Create ingress.yaml**

Create `helm/emcip/templates/ingress.yaml`:

```yaml
{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: emcip-ingress
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /$2
spec:
  ingressClassName: {{ .Values.ingress.className }}
  rules:
    - host: {{ .Values.ingress.host }}
      http:
        paths:
          - path: /api(/|$)(.*)
            pathType: ImplementationSpecific
            backend:
              service:
                name: emcip-admin-api
                port:
                  number: {{ (index .Values.services "adminApi").port }}
          - path: /grafana(/|$)(.*)
            pathType: ImplementationSpecific
            backend:
              service:
                name: emcip-grafana
                port:
                  number: 3000
          - path: /()(.*)
            pathType: ImplementationSpecific
            backend:
              service:
                name: emcip-admin-ui
                port:
                  number: {{ (index .Values.services "adminUi").port }}
{{- end }}
```

- [ ] **Step 2: Validate**

```bash
helm lint helm/emcip/
helm template emcip helm/emcip/ -n emcip | grep -A20 "kind: Ingress"
```

Expected: Ingress block with 3 path rules.

- [ ] **Step 3: Commit**

```bash
git add helm/emcip/templates/ingress.yaml
git commit -m "feat(k8s): add Ingress template with admin-api, admin-ui, Grafana paths"
```

---

## Task 8: Operations Guide — Kubernetes Section

**Files:**
- Modify: `documentation/operations-guide.adoc`
- Modify: `documentation/developer-guide.adoc`

- [ ] **Step 1: Add Kubernetes section to operations-guide.adoc**

Open `documentation/operations-guide.adoc`. Find the end of the existing `== Docker Compose Quickstart` section. Add a new top-level section after it:

```asciidoc
== Kubernetes Deployment

EMCIP ships a Helm chart at `helm/emcip/`. Docker Compose remains the recommended local development environment; Kubernetes is for staging and production.

=== Prerequisites

The following must be installed and configured on the cluster **before** running `helm install`:

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

=== Secrets — Create Before Installing

Secrets are never stored in the chart. Create them manually once:

[source,bash]
----
kubectl create secret generic emcip-secrets \
  --from-literal=postgres-password=<password> \           # <1>
  --from-literal=postgres-user=emcip \
  --from-literal=anthropic-api-key=<key> \               # <2>
  --from-literal=admin-jwt-secret=<min-32-char-secret> \ # <3>
  --from-literal=admin-service-token=<token> \
  --from-literal=telegram-api-id=<id> \                  # <4>
  --from-literal=telegram-api-hash=<hash> \
  --from-literal=telegram-phone-number=<+4912345> \
  -n emcip
----
<1> PostgreSQL password — choose a strong password
<2> Anthropic API key — required for LLM Orchestrator; omit key if not using LLM profile
<3> Admin API JWT signing secret — minimum 32 characters
<4> Telegram credentials — required for tdlib-adapter; omit if not using Telegram profile

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

=== Access Services

With microk8s ingress enabled and `emcip.local` pointing to the cluster IP (add to `/etc/hosts`):

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
|admin / admin (change on first login)
|===

=== Namespace Strategy

All EMCIP resources deploy to the `emcip` namespace. The Strimzi operator runs in `strimzi-system` (managed separately).
```

- [ ] **Step 2: Add note to developer-guide.adoc**

Open `documentation/developer-guide.adoc`. Find the `== Quick Start` section. Add a note after the quick start block:

```asciidoc
NOTE: Docker Compose is the recommended local development environment (see above).
Kubernetes deployment via Helm is for staging and production environments.
See the _Operations Guide_ for the full Kubernetes setup instructions.
```

- [ ] **Step 3: Commit**

```bash
git add documentation/operations-guide.adoc documentation/developer-guide.adoc
git commit -m "docs(k8s): add Kubernetes deployment section to operations guide"
```

---

## Task 9: Final Validation and PR

- [ ] **Step 1: Full helm lint**

```bash
helm lint helm/emcip/
```

Expected: `1 chart(s) linted, 0 chart(s) failed`.

- [ ] **Step 2: Full template render — check for obvious errors**

```bash
helm template emcip helm/emcip/ -n emcip > /tmp/emcip-rendered.yaml
echo "Deployments: $(grep 'kind: Deployment' /tmp/emcip-rendered.yaml | wc -l)"
echo "StatefulSets: $(grep 'kind: StatefulSet' /tmp/emcip-rendered.yaml | wc -l)"
echo "Services: $(grep 'kind: Service' /tmp/emcip-rendered.yaml | wc -l)"
echo "PVCs: $(grep 'kind: PersistentVolumeClaim' /tmp/emcip-rendered.yaml | wc -l)"
echo "KafkaTopics: $(grep 'kind: KafkaTopic' /tmp/emcip-rendered.yaml | wc -l)"
```

Expected counts:
- Deployments: 10 (8 app services + Loki + Grafana)
- StatefulSets: 2 (Postgres + tdlib-adapter)
- Services: ≥12 (8 app + tdlib + postgres + loki + grafana)
- PVCs: 6 (postgres + kafka managed by Strimzi + loki + grafana + tdlib-db + tdlib-files)
- KafkaTopics: 12

- [ ] **Step 3: Dry-run against cluster (optional but recommended)**

```bash
helm install emcip helm/emcip/ -n emcip --dry-run
```

Expected: no errors. Warnings about CRDs (Strimzi) not being present are acceptable if Strimzi isn't installed yet.

- [ ] **Step 4: Push and open PR**

```bash
git push -u origin feature/kubernetes-helm
gh pr create \
  --title "feat(k8s): Helm chart for full EMCIP stack on microk8s" \
  --body "Single umbrella Helm chart deploying all EMCIP services, PostgreSQL StatefulSet, Strimzi Kafka (KRaft), Loki, and Grafana. NFS-backed PVCs, secrets by reference only. Operations guide updated with full pre-install and deployment instructions."
```
