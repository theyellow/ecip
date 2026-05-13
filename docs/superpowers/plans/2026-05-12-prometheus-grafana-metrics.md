# Prometheus + Grafana Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Prometheus scraping to all 8 EMCIP services and wire it to Grafana in both docker-compose and Helm (microk8s), with a provisioned JVM + HTTP metrics dashboard.

**Architecture:** Each Spring Boot service exposes `/actuator/prometheus` via Micrometer. A Prometheus container scrapes all 8 services by internal hostname. Grafana (already running) gains a Prometheus datasource and one new dashboard; existing Loki dashboards are untouched.

**Tech Stack:** Micrometer `micrometer-registry-prometheus`, Spring Boot Actuator, Prometheus `prom/prometheus:v2.52.0`, Grafana provisioning (JSON dashboards + YAML datasources), Helm ConfigMap/Deployment/Service/PVC.

---

## File Map

| Action | Path |
|--------|------|
| Modify | `pom.xml` — add `micrometer-registry-prometheus` to `dependencyManagement` |
| Modify | `emcip-conversation-context/pom.xml` — add dep |
| Modify | `emcip-intent-classifier/pom.xml` — add dep |
| Modify | `emcip-policy-engine/pom.xml` — add dep |
| Modify | `emcip-llm-orchestrator/pom.xml` — add dep |
| Modify | `emcip-tdlib-adapter/pom.xml` — add dep |
| Modify | `emcip-conversation-context/src/main/resources/application.yml` — expose prometheus endpoint |
| Modify | `emcip-intent-classifier/src/main/resources/application.yml` — expose prometheus endpoint |
| Modify | `emcip-policy-engine/src/main/resources/application.yml` — expose prometheus endpoint |
| Modify | `emcip-llm-orchestrator/src/main/resources/application.yml` — expose prometheus endpoint |
| Modify | `emcip-tdlib-adapter/src/main/resources/application.yml` — expose prometheus endpoint |
| Create | `config/prometheus.yml` — scrape config for docker-compose |
| Modify | `docker-compose.yml` — add prometheus service, add prometheus-data volume |
| Modify | `config/grafana/provisioning/datasources/datasources.yml` — fix Prometheus URL to `http://prometheus:9090` |
| Create | `config/grafana/provisioning/dashboards/jvm-http-metrics.json` — JVM + HTTP dashboard |
| Create | `helm/emcip/templates/infra/prometheus-configmap.yaml` |
| Create | `helm/emcip/templates/infra/prometheus-deployment.yaml` |
| Create | `helm/emcip/templates/infra/prometheus-service.yaml` |
| Create | `helm/emcip/templates/infra/prometheus-pvc.yaml` |
| Modify | `helm/emcip/templates/infra/grafana-configmap.yaml` — add Prometheus datasource + dashboard ConfigMap |
| Modify | `helm/emcip/templates/infra/grafana-deployment.yaml` — mount dashboards ConfigMap |
| Modify | `helm/emcip/values.yaml` — add `infra.prometheus` image/port, `storage.prometheus` size |

---

## Task 1: Add `micrometer-registry-prometheus` to parent pom dependencyManagement

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Open `pom.xml` and locate the `<dependencyManagement><dependencies>` block**

Find the Spring Boot BOM import. The managed section starts around line 30. Add the Prometheus registry entry after the BOM import:

```xml
      <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
        <version>${micrometer.version}</version>
      </dependency>
```

> Note: Spring Boot BOM already manages `micrometer.version`, so the version tag will be resolved automatically. If your parent pom uses `<version>${spring-boot.version}</version>` on the BOM import (not a separate `micrometer.version` property), omit the `<version>` tag entirely — the BOM manages it.

- [ ] **Step 2: Verify the build still compiles**

```bash
cd /home/ben/Development/ecip
mvn validate -q | cat
```

Expected: no output (success).

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "chore(deps): add micrometer-registry-prometheus to parent dependencyManagement"
```

---

## Task 2: Add prometheus dep to the 5 missing services

**Files:**
- Modify: `emcip-conversation-context/pom.xml`
- Modify: `emcip-intent-classifier/pom.xml`
- Modify: `emcip-policy-engine/pom.xml`
- Modify: `emcip-llm-orchestrator/pom.xml`
- Modify: `emcip-tdlib-adapter/pom.xml`

Each pom already has `spring-boot-starter-actuator`. Add the prometheus registry dep directly after actuator in each pom's `<dependencies>` block (no version needed — managed by parent):

```xml
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
```

- [ ] **Step 1: Add to `emcip-conversation-context/pom.xml`**

Locate the line `<artifactId>spring-boot-starter-actuator</artifactId>` and insert the dep block immediately after its closing `</dependency>` tag.

- [ ] **Step 2: Add to `emcip-intent-classifier/pom.xml`** (same insertion point)

- [ ] **Step 3: Add to `emcip-policy-engine/pom.xml`** (same insertion point)

- [ ] **Step 4: Add to `emcip-llm-orchestrator/pom.xml`** (same insertion point)

- [ ] **Step 5: Add to `emcip-tdlib-adapter/pom.xml`** (same insertion point)

- [ ] **Step 6: Verify compilation**

```bash
cd /home/ben/Development/ecip
mvn compile -pl emcip-conversation-context,emcip-intent-classifier,emcip-policy-engine,emcip-llm-orchestrator,emcip-tdlib-adapter -am -q | cat
```

Expected: BUILD SUCCESS, no errors.

- [ ] **Step 7: Commit**

```bash
git add emcip-conversation-context/pom.xml emcip-intent-classifier/pom.xml emcip-policy-engine/pom.xml emcip-llm-orchestrator/pom.xml emcip-tdlib-adapter/pom.xml
git commit -m "feat(metrics): add micrometer-registry-prometheus to 5 services"
```

---

## Task 3: Expose /actuator/prometheus in the 5 missing services

**Files:**
- Modify: `emcip-conversation-context/src/main/resources/application.yml`
- Modify: `emcip-intent-classifier/src/main/resources/application.yml`
- Modify: `emcip-policy-engine/src/main/resources/application.yml`
- Modify: `emcip-llm-orchestrator/src/main/resources/application.yml`
- Modify: `emcip-tdlib-adapter/src/main/resources/application.yml`

Each file currently has:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always
```

Change `include: health,info,metrics` to `include: health,info,metrics,prometheus` in each file. Do NOT add `management.metrics.export.prometheus.enabled: true` — Spring Boot 3+ auto-configures this when the registry dep is on the classpath.

- [ ] **Step 1: Edit `emcip-conversation-context/src/main/resources/application.yml`**

Replace:
```yaml
        include: health,info,metrics
```
With:
```yaml
        include: health,info,metrics,prometheus
```

- [ ] **Step 2: Edit `emcip-intent-classifier/src/main/resources/application.yml`** (same change)

- [ ] **Step 3: Edit `emcip-policy-engine/src/main/resources/application.yml`** (same change)

- [ ] **Step 4: Edit `emcip-llm-orchestrator/src/main/resources/application.yml`** (same change)

- [ ] **Step 5: Edit `emcip-tdlib-adapter/src/main/resources/application.yml`** (same change)

- [ ] **Step 6: Verify with a quick unit test check**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-policy-engine -q 2>&1 | tail -5 | cat
```

Expected: BUILD SUCCESS (existing tests pass).

- [ ] **Step 7: Commit**

```bash
git add emcip-conversation-context/src/main/resources/application.yml \
        emcip-intent-classifier/src/main/resources/application.yml \
        emcip-policy-engine/src/main/resources/application.yml \
        emcip-llm-orchestrator/src/main/resources/application.yml \
        emcip-tdlib-adapter/src/main/resources/application.yml
git commit -m "feat(metrics): expose /actuator/prometheus in 5 remaining services"
```

---

## Task 4: Create Prometheus scrape config for docker-compose

**Files:**
- Create: `config/prometheus.yml`

- [ ] **Step 1: Create `config/prometheus.yml`**

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'emcip-services'
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - 'emcip-tdlib-adapter:9080'
          - 'emcip-conversation-context:9081'
          - 'emcip-intent-classifier:9082'
          - 'emcip-policy-engine:9083'
          - 'emcip-llm-orchestrator:9084'
          - 'emcip-moderation-service:9085'
          - 'emcip-audit-service:9086'
          - 'emcip-admin-api:9087'
    relabel_configs:
      - source_labels: [__address__]
        regex: '([^:]+):\d+'
        target_label: service
        replacement: '${1}'
```

The `relabel_configs` block extracts the hostname part of each target (e.g. `emcip-policy-engine`) and sets it as a `service` label on every metric — this is what lets Grafana filter by service.

- [ ] **Step 2: Commit**

```bash
git add config/prometheus.yml
git commit -m "feat(metrics): add Prometheus scrape config for docker-compose"
```

---

## Task 5: Add Prometheus container to docker-compose

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Add the prometheus service block**

In `docker-compose.yml`, add the following service after the `promtail` service and before `# Admin UI`:

```yaml
  # Prometheus — metrics scraping (http://localhost:14010)
  prometheus:
    image: prom/prometheus:v2.52.0
    container_name: ecip-prometheus
    ports:
      - "14010:9090"
    volumes:
      - ./config/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus-data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--storage.tsdb.retention.time=15d'
    networks:
      - ecip-network
    healthcheck:
      test: ["CMD-SHELL", "wget --no-verbose --tries=1 --spider http://localhost:9090/-/healthy || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 5
```

- [ ] **Step 2: Add `prometheus-data` volume**

In the `volumes:` section at the bottom of `docker-compose.yml`, add:

```yaml
  prometheus-data:
```

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(metrics): add Prometheus container to docker-compose"
```

---

## Task 6: Fix Grafana Prometheus datasource URL (docker-compose)

**Files:**
- Modify: `config/grafana/provisioning/datasources/datasources.yml`

The current config has `url: http://host.docker.internal:9090` which points nowhere. Change it to the Prometheus container hostname.

- [ ] **Step 1: Edit `config/grafana/provisioning/datasources/datasources.yml`**

Replace the entire file with:

```yaml
apiVersion: 1

datasources:
  - name: Loki
    type: loki
    access: proxy
    url: http://loki:3100
    isDefault: false
    jsonData:
      maxLines: 1000

  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    jsonData:
      timeInterval: 15s
```

Key changes: `url` for Prometheus changed from `http://host.docker.internal:9090` to `http://prometheus:9090`; Prometheus is now `isDefault: true`, Loki is `isDefault: false`.

- [ ] **Step 2: Commit**

```bash
git add config/grafana/provisioning/datasources/datasources.yml
git commit -m "fix(metrics): point Grafana Prometheus datasource to prometheus container"
```

---

## Task 7: Add JVM + HTTP metrics Grafana dashboard (docker-compose)

**Files:**
- Create: `config/grafana/provisioning/dashboards/jvm-http-metrics.json`

- [ ] **Step 1: Create the dashboard JSON**

```json
{
  "annotations": { "list": [] },
  "description": "EMCIP services — JVM heap, GC, HTTP request rate and error rate",
  "editable": true,
  "id": null,
  "uid": "emcip-jvm-http",
  "title": "EMCIP JVM & HTTP Metrics",
  "tags": ["emcip", "prometheus"],
  "timezone": "browser",
  "refresh": "30s",
  "schemaVersion": 38,
  "panels": [
    {
      "id": 1,
      "title": "JVM Heap Used (bytes)",
      "type": "timeseries",
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
      "targets": [{
        "datasource": { "type": "prometheus", "uid": "Prometheus" },
        "expr": "jvm_memory_used_bytes{area=\"heap\"}",
        "legendFormat": "{{service}} {{id}}",
        "refId": "A"
      }],
      "fieldConfig": {
        "defaults": { "unit": "bytes" }
      }
    },
    {
      "id": 2,
      "title": "HTTP Request Rate (req/s)",
      "type": "timeseries",
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
      "targets": [{
        "datasource": { "type": "prometheus", "uid": "Prometheus" },
        "expr": "sum by (service) (rate(http_server_requests_seconds_count[1m]))",
        "legendFormat": "{{service}}",
        "refId": "A"
      }],
      "fieldConfig": {
        "defaults": { "unit": "reqps" }
      }
    },
    {
      "id": 3,
      "title": "HTTP Error Rate (5xx req/s)",
      "type": "timeseries",
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
      "targets": [{
        "datasource": { "type": "prometheus", "uid": "Prometheus" },
        "expr": "sum by (service) (rate(http_server_requests_seconds_count{status=~\"5..\"}[1m]))",
        "legendFormat": "{{service}}",
        "refId": "A"
      }],
      "fieldConfig": {
        "defaults": { "unit": "reqps", "color": { "fixedColor": "red", "mode": "fixed" } }
      }
    },
    {
      "id": 4,
      "title": "GC Pause Time (seconds/s)",
      "type": "timeseries",
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 8 },
      "targets": [{
        "datasource": { "type": "prometheus", "uid": "Prometheus" },
        "expr": "sum by (service) (rate(jvm_gc_pause_seconds_sum[1m]))",
        "legendFormat": "{{service}}",
        "refId": "A"
      }],
      "fieldConfig": {
        "defaults": { "unit": "s" }
      }
    },
    {
      "id": 5,
      "title": "Kafka Consumer Lag",
      "type": "timeseries",
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "gridPos": { "h": 8, "w": 24, "x": 0, "y": 16 },
      "targets": [{
        "datasource": { "type": "prometheus", "uid": "Prometheus" },
        "expr": "kafka_consumer_fetch_manager_records_lag{service=~\"emcip-.*\"}",
        "legendFormat": "{{service}} topic={{topic}} partition={{partition}}",
        "refId": "A"
      }],
      "fieldConfig": {
        "defaults": { "unit": "short" }
      }
    }
  ],
  "time": { "from": "now-1h", "to": "now" }
}
```

- [ ] **Step 2: Commit**

```bash
git add config/grafana/provisioning/dashboards/jvm-http-metrics.json
git commit -m "feat(metrics): add provisioned JVM+HTTP Grafana dashboard"
```

---

## Task 8: Add Prometheus to Helm — ConfigMap

**Files:**
- Create: `helm/emcip/templates/infra/prometheus-configmap.yaml`

In Helm/microk8s, services are reachable by in-cluster DNS: `emcip-<name>.<namespace>.svc.cluster.local`, but within the same namespace the short form `emcip-<name>` works.

- [ ] **Step 1: Create `helm/emcip/templates/infra/prometheus-configmap.yaml`**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: emcip-prometheus-config
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
data:
  prometheus.yml: |
    global:
      scrape_interval: 15s
      evaluation_interval: 15s

    scrape_configs:
      - job_name: 'emcip-services'
        metrics_path: /actuator/prometheus
        static_configs:
          - targets:
              - 'emcip-tdlib-adapter:9080'
              - 'emcip-conversation-context:9081'
              - 'emcip-intent-classifier:9082'
              - 'emcip-policy-engine:9083'
              - 'emcip-llm-orchestrator:9084'
              - 'emcip-moderation-service:9085'
              - 'emcip-audit-service:9086'
              - 'emcip-admin-api:9087'
        relabel_configs:
          - source_labels: [__address__]
            regex: '([^:]+):\d+'
            target_label: service
            replacement: '${1}'
```

- [ ] **Step 2: Commit**

```bash
git add helm/emcip/templates/infra/prometheus-configmap.yaml
git commit -m "feat(metrics): add Prometheus ConfigMap for Helm"
```

---

## Task 9: Add Prometheus to Helm — Deployment, Service, PVC

**Files:**
- Create: `helm/emcip/templates/infra/prometheus-deployment.yaml`
- Create: `helm/emcip/templates/infra/prometheus-service.yaml`
- Create: `helm/emcip/templates/infra/prometheus-pvc.yaml`

- [ ] **Step 1: Create `helm/emcip/templates/infra/prometheus-deployment.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: emcip-prometheus
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
    app.kubernetes.io/component: prometheus
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: prometheus
      app.kubernetes.io/instance: {{ .Release.Name }}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: prometheus
        app.kubernetes.io/instance: {{ .Release.Name }}
    spec:
      containers:
        - name: prometheus
          image: {{ .Values.infra.prometheus.image }}
          args:
            - '--config.file=/etc/prometheus/prometheus.yml'
            - '--storage.tsdb.path=/prometheus'
            - '--storage.tsdb.retention.time=15d'
          ports:
            - containerPort: {{ .Values.infra.prometheus.port }}
          readinessProbe:
            httpGet:
              path: /-/ready
              port: {{ .Values.infra.prometheus.port }}
            initialDelaySeconds: 10
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /-/healthy
              port: {{ .Values.infra.prometheus.port }}
            initialDelaySeconds: 30
            periodSeconds: 15
            failureThreshold: 3
          volumeMounts:
            - name: config
              mountPath: /etc/prometheus
            - name: data
              mountPath: /prometheus
      volumes:
        - name: config
          configMap:
            name: emcip-prometheus-config
        - name: data
          persistentVolumeClaim:
            claimName: emcip-prometheus-data
```

- [ ] **Step 2: Create `helm/emcip/templates/infra/prometheus-service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: emcip-prometheus
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
spec:
  type: ClusterIP
  ports:
    - port: {{ .Values.infra.prometheus.port }}
      targetPort: {{ .Values.infra.prometheus.port }}
  selector:
    app.kubernetes.io/name: prometheus
    app.kubernetes.io/instance: {{ .Release.Name }}
```

- [ ] **Step 3: Create `helm/emcip/templates/infra/prometheus-pvc.yaml`**

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: emcip-prometheus-data
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: {{ .Values.storage.storageClassName }}
  resources:
    requests:
      storage: {{ .Values.storage.prometheus.size }}
```

- [ ] **Step 4: Commit**

```bash
git add helm/emcip/templates/infra/prometheus-deployment.yaml \
        helm/emcip/templates/infra/prometheus-service.yaml \
        helm/emcip/templates/infra/prometheus-pvc.yaml
git commit -m "feat(metrics): add Prometheus Deployment/Service/PVC to Helm"
```

---

## Task 10: Update Helm values.yaml for Prometheus

**Files:**
- Modify: `helm/emcip/values.yaml`

- [ ] **Step 1: Add `prometheus` to the `infra:` section**

In `values.yaml`, find:
```yaml
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

Add `prometheus` entry:
```yaml
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
  prometheus:
    image: prom/prometheus:v2.52.0
    port: 9090
```

- [ ] **Step 2: Add `prometheus` to the `storage:` section**

Find:
```yaml
storage:
  storageClassName: microk8s-hostpath
  postgres:
    size: 10Gi
  kafka:
    size: 20Gi
  loki:
    size: 10Gi
  grafana:
    size: 1Gi
```

Add:
```yaml
  prometheus:
    size: 10Gi
```

- [ ] **Step 3: Commit**

```bash
git add helm/emcip/values.yaml
git commit -m "feat(metrics): add Prometheus image/port/storage to Helm values"
```

---

## Task 11: Update Helm Grafana ConfigMap — add Prometheus datasource

**Files:**
- Modify: `helm/emcip/templates/infra/grafana-configmap.yaml`

Currently the configmap only has Loki. Add Prometheus datasource pointing to `http://emcip-prometheus:9090` (in-cluster DNS).

- [ ] **Step 1: Replace `helm/emcip/templates/infra/grafana-configmap.yaml`**

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
        isDefault: false
        version: 1
        editable: false
      - name: Prometheus
        type: prometheus
        access: proxy
        url: http://emcip-prometheus:{{ .Values.infra.prometheus.port }}
        isDefault: true
        version: 1
        editable: false
        jsonData:
          timeInterval: 15s
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: emcip-grafana-dashboards
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
data:
  provider.yml: |
    apiVersion: 1
    providers:
      - name: EMCIP Dashboards
        orgId: 1
        folder: EMCIP
        type: file
        disableDeletion: true
        editable: true
        options:
          path: /etc/grafana/provisioning/dashboards
  jvm-http-metrics.json: |
    {
      "annotations": { "list": [] },
      "description": "EMCIP services — JVM heap, GC, HTTP request rate and error rate",
      "editable": true,
      "id": null,
      "uid": "emcip-jvm-http",
      "title": "EMCIP JVM & HTTP Metrics",
      "tags": ["emcip", "prometheus"],
      "timezone": "browser",
      "refresh": "30s",
      "schemaVersion": 38,
      "panels": [
        {
          "id": 1,
          "title": "JVM Heap Used (bytes)",
          "type": "timeseries",
          "datasource": { "type": "prometheus", "uid": "Prometheus" },
          "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
          "targets": [{
            "datasource": { "type": "prometheus", "uid": "Prometheus" },
            "expr": "jvm_memory_used_bytes{area=\"heap\"}",
            "legendFormat": "{{`{{service}}`}} {{`{{id}}`}}",
            "refId": "A"
          }],
          "fieldConfig": { "defaults": { "unit": "bytes" } }
        },
        {
          "id": 2,
          "title": "HTTP Request Rate (req/s)",
          "type": "timeseries",
          "datasource": { "type": "prometheus", "uid": "Prometheus" },
          "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
          "targets": [{
            "datasource": { "type": "prometheus", "uid": "Prometheus" },
            "expr": "sum by (service) (rate(http_server_requests_seconds_count[1m]))",
            "legendFormat": "{{`{{service}}`}}",
            "refId": "A"
          }],
          "fieldConfig": { "defaults": { "unit": "reqps" } }
        },
        {
          "id": 3,
          "title": "HTTP Error Rate (5xx req/s)",
          "type": "timeseries",
          "datasource": { "type": "prometheus", "uid": "Prometheus" },
          "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
          "targets": [{
            "datasource": { "type": "prometheus", "uid": "Prometheus" },
            "expr": "sum by (service) (rate(http_server_requests_seconds_count{status=~\"5..\"}[1m]))",
            "legendFormat": "{{`{{service}}`}}",
            "refId": "A"
          }],
          "fieldConfig": {
            "defaults": { "unit": "reqps", "color": { "fixedColor": "red", "mode": "fixed" } }
          }
        },
        {
          "id": 4,
          "title": "GC Pause Time (seconds/s)",
          "type": "timeseries",
          "datasource": { "type": "prometheus", "uid": "Prometheus" },
          "gridPos": { "h": 8, "w": 12, "x": 12, "y": 8 },
          "targets": [{
            "datasource": { "type": "prometheus", "uid": "Prometheus" },
            "expr": "sum by (service) (rate(jvm_gc_pause_seconds_sum[1m]))",
            "legendFormat": "{{`{{service}}`}}",
            "refId": "A"
          }],
          "fieldConfig": { "defaults": { "unit": "s" } }
        },
        {
          "id": 5,
          "title": "Kafka Consumer Lag",
          "type": "timeseries",
          "datasource": { "type": "prometheus", "uid": "Prometheus" },
          "gridPos": { "h": 8, "w": 24, "x": 0, "y": 16 },
          "targets": [{
            "datasource": { "type": "prometheus", "uid": "Prometheus" },
            "expr": "kafka_consumer_fetch_manager_records_lag",
            "legendFormat": "{{`{{service}}`}} topic={{`{{topic}}`}} partition={{`{{partition}}`}}",
            "refId": "A"
          }],
          "fieldConfig": { "defaults": { "unit": "short" } }
        }
      ],
      "time": { "from": "now-1h", "to": "now" }
    }
```

> **Helm template note:** Inside the `data:` block, Go template delimiters `{{ }}` are active. Any Grafana `legendFormat` strings using `{{label}}` must be escaped as `{{` `{{` `` `{{label}}` `` `}}` `}}` to prevent Helm from trying to evaluate them. The plan above already shows the correct `{{` \` `{{service}}` \` `}}` escaping.

- [ ] **Step 2: Commit**

```bash
git add helm/emcip/templates/infra/grafana-configmap.yaml
git commit -m "feat(metrics): add Prometheus datasource and JVM dashboard to Helm Grafana configmap"
```

---

## Task 12: Mount dashboards ConfigMap in Helm Grafana Deployment

**Files:**
- Modify: `helm/emcip/templates/infra/grafana-deployment.yaml`

Currently the Grafana deployment only mounts the `datasources` ConfigMap. Add the dashboards ConfigMap mount.

- [ ] **Step 1: Add `dashboards` volume and volumeMount**

In `grafana-deployment.yaml`, find the `volumeMounts:` section and add:

```yaml
            - name: dashboards-provider
              mountPath: /etc/grafana/provisioning/dashboards
```

In the `volumes:` section, add:

```yaml
        - name: dashboards-provider
          configMap:
            name: emcip-grafana-dashboards
```

The full `volumeMounts` and `volumes` blocks should look like:

```yaml
          volumeMounts:
            - name: data
              mountPath: /var/lib/grafana
            - name: datasources
              mountPath: /etc/grafana/provisioning/datasources
            - name: dashboards-provider
              mountPath: /etc/grafana/provisioning/dashboards
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: emcip-grafana-data
        - name: datasources
          configMap:
            name: emcip-grafana-datasources
        - name: dashboards-provider
          configMap:
            name: emcip-grafana-dashboards
```

- [ ] **Step 2: Commit**

```bash
git add helm/emcip/templates/infra/grafana-deployment.yaml
git commit -m "feat(metrics): mount dashboard ConfigMap into Grafana pod"
```

---

## Task 13: Helm template validation

**Files:** none (validation only)

- [ ] **Step 1: Lint the Helm chart**

```bash
cd /home/ben/Development/ecip
helm lint helm/emcip | cat
```

Expected: `1 chart(s) linted, 0 chart(s) failed`

- [ ] **Step 2: Dry-run template render and check prometheus resources appear**

```bash
helm template emcip helm/emcip --namespace emcip-dev | grep -E "name: emcip-prometheus|kind: (Deployment|Service|PersistentVolumeClaim|ConfigMap)" | cat
```

Expected output includes:
```
kind: ConfigMap
  name: emcip-prometheus-config
kind: Deployment
  name: emcip-prometheus
kind: Service
  name: emcip-prometheus
kind: PersistentVolumeClaim
  name: emcip-prometheus-data
```

- [ ] **Step 3: Check Grafana deployment template has both volume mounts**

```bash
helm template emcip helm/emcip --namespace emcip-dev | grep -A60 "name: emcip-grafana" | grep -E "mountPath|claimName|configMap" | cat
```

Expected: shows `datasources`, `dashboards-provider`, and `emcip-grafana-data`.

- [ ] **Step 4: Commit**

Nothing to commit (validation step). If lint errors were found and fixed, commit those fixes:

```bash
git add helm/
git commit -m "fix(helm): fix lint errors in prometheus/grafana templates"
```

---

## Task 14: Smoke test docker-compose

**Files:** none (testing only)

- [ ] **Step 1: Start the stack**

```bash
cd /home/ben/Development/ecip
docker compose up -d prometheus grafana | cat
```

- [ ] **Step 2: Wait for Prometheus to be healthy**

```bash
sleep 10
curl -s http://localhost:14010/-/healthy | cat
```

Expected: `Prometheus Server is Healthy.`

- [ ] **Step 3: Verify targets are being scraped**

```bash
curl -s "http://localhost:14010/api/v1/targets" | python3 -c "import sys,json; d=json.load(sys.stdin); [print(t['labels']['service'], t['health']) for t in d['data']['activeTargets']]" | cat
```

Expected: 8 lines, one per service, each showing `up` (services must be running) or `down` (if not started — this is acceptable, the target list itself proves scraping is configured correctly).

- [ ] **Step 4: Verify Grafana loads the dashboard**

Open `http://localhost:14007` (admin/admin), navigate to Dashboards → EMCIP → "EMCIP JVM & HTTP Metrics". The dashboard should load. If services are running, panels will show data.

- [ ] **Step 5: Final commit if any fixups were needed**

```bash
git add -A
git commit -m "fix(metrics): smoke test fixups"
```

---

## Self-Review

**Spec coverage check:**
- ✅ Section 1 (service-side, 5 services) — Tasks 1–3
- ✅ Section 2 (docker-compose Prometheus + datasource fix) — Tasks 4–6
- ✅ Section 3 (Helm Prometheus resources) — Tasks 8–13
- ✅ Section 4 (Grafana dashboard) — Tasks 7 + 11 + 12

**Placeholder scan:** No TBD/TODO present.

**Type consistency:**
- `emcip-prometheus` used consistently as the service name in docker-compose, Helm service name, and Grafana datasource URLs.
- `emcip-grafana-dashboards` ConfigMap name used consistently between Task 11 (creation) and Task 12 (mount reference).
- Port `9090` referenced via `.Values.infra.prometheus.port` in Helm templates, defined in Task 10.
- Relabel `service` label applied in both docker-compose `prometheus.yml` (Task 4) and Helm ConfigMap (Task 8) — Grafana PromQL queries use `{{service}}` accordingly.

**Helm Go template escaping:** Grafana `legendFormat` strings in the Helm ConfigMap (Task 11) use `` {{` `{{label}}` `}} `` escaping to prevent Helm from interpreting them as Go template calls.
