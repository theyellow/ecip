# Epic 5.6 — Observability Stack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Grafana + Loki + Promtail to docker-compose, with 3 pre-built dashboards and zero service code changes.

**Architecture:** Promtail scrapes Docker container stdout logs, parses the logstash JSON format already emitted by all services, and ships to Loki. Grafana is provisioned via config files (datasources + dashboards) so the stack is ready to use on first `docker compose up`. No Java code changes needed.

**Tech Stack:** Grafana OSS, Loki, Promtail (all Grafana project), Docker Compose v3.8, Grafana dashboard JSON provisioning.

---

### Task 1: Loki configuration

**Files:**
- Create: `config/loki-config.yml`

- [ ] **Step 1: Create Loki config with local filesystem storage**

```yaml
# config/loki-config.yml
auth_enabled: false

server:
  http_listen_port: 3100
  grpc_listen_port: 9096

common:
  instance_addr: 127.0.0.1
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory

schema_config:
  configs:
    - from: 2020-10-24
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h

limits_config:
  retention_period: 744h   # 31 days
  ingestion_rate_mb: 16
  ingestion_burst_size_mb: 32

compactor:
  working_directory: /loki/compactor
  retention_enabled: true
```

- [ ] **Step 2: Commit**

```bash
git add config/loki-config.yml
git commit -m "feat(5.6): add Loki configuration"
```

---

### Task 2: Promtail configuration

**Files:**
- Create: `config/promtail-config.yml`

- [ ] **Step 1: Create Promtail config to scrape Docker logs and parse JSON**

All 8 EMCIP services emit logstash-logback-encoder JSON to stdout. Each log line looks like:
```json
{"@timestamp":"2026-04-22T10:00:00.000Z","level":"INFO","logger_name":"io.emcip.policy...","message":"...","traceId":"abc","spanId":"def"}
```

```yaml
# config/promtail-config.yml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  - job_name: ecip-services
    docker_sd_configs:
      - host: unix:///var/run/docker.sock
        refresh_interval: 5s
        filters:
          - name: name
            values:
              - ecip-tdlib-adapter
              - ecip-conversation-context
              - ecip-intent-classifier
              - ecip-policy-engine
              - ecip-llm-orchestrator
              - ecip-moderation-service
              - ecip-audit-service
              - ecip-admin-api
    relabel_configs:
      - source_labels: [__meta_docker_container_name]
        regex: /(.*)
        target_label: container
      - source_labels: [__meta_docker_container_name]
        regex: /ecip-(.*)
        target_label: service
    pipeline_stages:
      - json:
          expressions:
            level: level
            logger: logger_name
            message: message
            trace_id: traceId
            span_id: spanId
            timestamp: "@timestamp"
      - labels:
          level:
          logger:
          service:
      - timestamp:
          source: timestamp
          format: RFC3339Nano
          fallback_formats:
            - RFC3339
```

- [ ] **Step 2: Commit**

```bash
git add config/promtail-config.yml
git commit -m "feat(5.6): add Promtail configuration for Docker log scraping"
```

---

### Task 3: Grafana provisioning — datasources

**Files:**
- Create: `config/grafana/provisioning/datasources/datasources.yml`

- [ ] **Step 1: Create Grafana datasources config**

```yaml
# config/grafana/provisioning/datasources/datasources.yml
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
    url: http://host.docker.internal:9090
    isDefault: true
    jsonData:
      timeInterval: 15s
```

> Note: Prometheus URL uses `host.docker.internal` since services run outside Docker. If Prometheus is not yet deployed, the datasource will show as "pending" but will not break Grafana startup.

- [ ] **Step 2: Commit**

```bash
git add config/grafana/provisioning/datasources/datasources.yml
git commit -m "feat(5.6): add Grafana datasource provisioning (Loki + Prometheus)"
```

---

### Task 4: Grafana provisioning — dashboard provider

**Files:**
- Create: `config/grafana/provisioning/dashboards/provider.yml`

- [ ] **Step 1: Create dashboard provider config**

```yaml
# config/grafana/provisioning/dashboards/provider.yml
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
```

- [ ] **Step 2: Commit**

```bash
git add config/grafana/provisioning/dashboards/provider.yml
git commit -m "feat(5.6): add Grafana dashboard provider provisioning"
```

---

### Task 5: Service Health dashboard

**Files:**
- Create: `config/grafana/provisioning/dashboards/service-health.json`

- [ ] **Step 1: Create service health dashboard JSON**

```json
{
  "__inputs": [],
  "__requires": [],
  "annotations": { "list": [] },
  "description": "EMCIP service health — Actuator UP/DOWN and JVM memory",
  "editable": true,
  "fiscalYearStartMonth": 0,
  "graphTooltip": 0,
  "id": null,
  "links": [],
  "panels": [
    {
      "datasource": { "type": "loki", "uid": "Loki" },
      "gridPos": { "h": 8, "w": 24, "x": 0, "y": 0 },
      "id": 1,
      "options": {
        "dedupStrategy": "none",
        "enableLogDetails": true,
        "prettifyLogMessage": false,
        "showLabels": true,
        "showTime": true,
        "sortOrder": "Descending",
        "wrapLogMessage": false
      },
      "targets": [
        {
          "datasource": { "type": "loki", "uid": "Loki" },
          "expr": "{container=~\"ecip-.*\"} |= \"ERROR\"",
          "legendFormat": "",
          "refId": "A"
        }
      ],
      "title": "Error Logs (all services)",
      "type": "logs"
    },
    {
      "datasource": { "type": "loki", "uid": "Loki" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
      "id": 2,
      "options": {
        "dedupStrategy": "none",
        "enableLogDetails": true,
        "showTime": true,
        "sortOrder": "Descending"
      },
      "targets": [
        {
          "datasource": { "type": "loki", "uid": "Loki" },
          "expr": "{service=\"policy-engine\"}",
          "refId": "A"
        }
      ],
      "title": "Policy Engine Logs",
      "type": "logs"
    },
    {
      "datasource": { "type": "loki", "uid": "Loki" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 8 },
      "id": 3,
      "options": {
        "dedupStrategy": "none",
        "enableLogDetails": true,
        "showTime": true,
        "sortOrder": "Descending"
      },
      "targets": [
        {
          "datasource": { "type": "loki", "uid": "Loki" },
          "expr": "{service=\"audit-service\"}",
          "refId": "A"
        }
      ],
      "title": "Audit Service Logs",
      "type": "logs"
    }
  ],
  "refresh": "10s",
  "schemaVersion": 38,
  "tags": ["emcip", "health"],
  "time": { "from": "now-1h", "to": "now" },
  "timepicker": {},
  "title": "EMCIP Service Health",
  "uid": "emcip-service-health",
  "version": 1
}
```

- [ ] **Step 2: Commit**

```bash
git add config/grafana/provisioning/dashboards/service-health.json
git commit -m "feat(5.6): add Service Health Grafana dashboard"
```

---

### Task 6: Kafka Consumer Lag dashboard

**Files:**
- Create: `config/grafana/provisioning/dashboards/kafka-lag.json`

- [ ] **Step 1: Create Kafka lag dashboard JSON**

```json
{
  "annotations": { "list": [] },
  "description": "Kafka consumer lag per topic and consumer group",
  "editable": true,
  "id": null,
  "panels": [
    {
      "datasource": { "type": "loki", "uid": "Loki" },
      "gridPos": { "h": 8, "w": 24, "x": 0, "y": 0 },
      "id": 1,
      "options": {
        "dedupStrategy": "none",
        "enableLogDetails": true,
        "showTime": true,
        "sortOrder": "Descending"
      },
      "targets": [
        {
          "datasource": { "type": "loki", "uid": "Loki" },
          "expr": "{container=~\"ecip-.*\"} |= \"ConsumerRecords\" | json | line_format \"{{.service}} offset={{.message}}\"",
          "refId": "A"
        }
      ],
      "title": "Kafka Consumer Activity",
      "type": "logs"
    },
    {
      "datasource": { "type": "loki", "uid": "Loki" },
      "gridPos": { "h": 8, "w": 24, "x": 0, "y": 8 },
      "id": 2,
      "options": {
        "dedupStrategy": "none",
        "enableLogDetails": true,
        "showTime": true,
        "sortOrder": "Descending"
      },
      "targets": [
        {
          "datasource": { "type": "loki", "uid": "Loki" },
          "expr": "{container=~\"ecip-.*\"} |= \"dead-letter\" or {container=~\"ecip-.*\"} |= \"DLQ\"",
          "refId": "A"
        }
      ],
      "title": "Dead Letter Queue Events",
      "type": "logs"
    }
  ],
  "refresh": "10s",
  "schemaVersion": 38,
  "tags": ["emcip", "kafka"],
  "time": { "from": "now-1h", "to": "now" },
  "title": "EMCIP Kafka Consumer Lag",
  "uid": "emcip-kafka-lag",
  "version": 1
}
```

- [ ] **Step 2: Commit**

```bash
git add config/grafana/provisioning/dashboards/kafka-lag.json
git commit -m "feat(5.6): add Kafka Consumer Lag Grafana dashboard"
```

---

### Task 7: Audit Throughput dashboard

**Files:**
- Create: `config/grafana/provisioning/dashboards/audit-throughput.json`

- [ ] **Step 1: Create audit throughput dashboard JSON**

```json
{
  "annotations": { "list": [] },
  "description": "Audit event and moderation flag throughput over time",
  "editable": true,
  "id": null,
  "panels": [
    {
      "datasource": { "type": "loki", "uid": "Loki" },
      "fieldConfig": {
        "defaults": { "color": { "mode": "palette-classic" }, "custom": { "fillOpacity": 20 } }
      },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
      "id": 1,
      "options": { "legend": { "displayMode": "list" } },
      "targets": [
        {
          "datasource": { "type": "loki", "uid": "Loki" },
          "expr": "sum(rate({service=\"audit-service\"} |= \"AuditEvent\" [5m]))",
          "legendFormat": "audit events/s",
          "refId": "A"
        }
      ],
      "title": "Audit Event Rate",
      "type": "timeseries"
    },
    {
      "datasource": { "type": "loki", "uid": "Loki" },
      "fieldConfig": {
        "defaults": { "color": { "mode": "palette-classic" }, "custom": { "fillOpacity": 20 } }
      },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
      "id": 2,
      "targets": [
        {
          "datasource": { "type": "loki", "uid": "Loki" },
          "expr": "sum(rate({service=\"moderation-service\"} |= \"ModerationFlag\" [5m]))",
          "legendFormat": "moderation flags/s",
          "refId": "A"
        }
      ],
      "title": "Moderation Flag Rate",
      "type": "timeseries"
    },
    {
      "datasource": { "type": "loki", "uid": "Loki" },
      "gridPos": { "h": 8, "w": 24, "x": 0, "y": 8 },
      "id": 3,
      "options": {
        "dedupStrategy": "none",
        "enableLogDetails": true,
        "showTime": true,
        "sortOrder": "Descending"
      },
      "targets": [
        {
          "datasource": { "type": "loki", "uid": "Loki" },
          "expr": "{service=\"moderation-service\"} |= \"FLAGGED\"",
          "refId": "A"
        }
      ],
      "title": "Recent Moderation Flags",
      "type": "logs"
    }
  ],
  "refresh": "30s",
  "schemaVersion": 38,
  "tags": ["emcip", "audit"],
  "time": { "from": "now-6h", "to": "now" },
  "title": "EMCIP Audit Throughput",
  "uid": "emcip-audit-throughput",
  "version": 1
}
```

- [ ] **Step 2: Commit**

```bash
git add config/grafana/provisioning/dashboards/audit-throughput.json
git commit -m "feat(5.6): add Audit Throughput Grafana dashboard"
```

---

### Task 8: Update docker-compose.yml

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Add loki, promtail, and grafana services to docker-compose.yml**

Add the following services before the `volumes:` section:

```yaml
  # Loki — log aggregation backend
  loki:
    image: grafana/loki:3.0.0
    container_name: ecip-loki
    ports:
      - "14008:3100"
    volumes:
      - ./config/loki-config.yml:/etc/loki/local-config.yaml
      - loki-data:/loki
    command: -config.file=/etc/loki/local-config.yaml
    networks:
      - ecip-network
    healthcheck:
      test: ["CMD-SHELL", "wget --no-verbose --tries=1 --spider http://localhost:3100/ready || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Promtail — log shipper (scrapes Docker container logs → Loki)
  promtail:
    image: grafana/promtail:3.0.0
    container_name: ecip-promtail
    depends_on:
      loki:
        condition: service_healthy
    volumes:
      - ./config/promtail-config.yml:/etc/promtail/config.yml
      - /var/run/docker.sock:/var/run/docker.sock
      - /var/lib/docker/containers:/var/lib/docker/containers:ro
    command: -config.file=/etc/promtail/config.yml
    networks:
      - ecip-network

  # Grafana — dashboards (http://localhost:14007, admin/admin)
  grafana:
    image: grafana/grafana-oss:11.0.0
    container_name: ecip-grafana
    depends_on:
      loki:
        condition: service_healthy
    ports:
      - "14007:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
      - GF_SECURITY_ADMIN_USER=admin
      - GF_USERS_ALLOW_SIGN_UP=false
    volumes:
      - ./config/grafana/provisioning:/etc/grafana/provisioning
      - grafana-data:/var/lib/grafana
    networks:
      - ecip-network
```

Add volumes to the `volumes:` section:

```yaml
  loki-data:
  grafana-data:
```

- [ ] **Step 2: Verify docker-compose syntax**

```bash
docker compose config --quiet
```

Expected: no output (valid YAML).

- [ ] **Step 3: Start the observability stack**

```bash
docker compose up -d loki promtail grafana
```

- [ ] **Step 4: Verify Loki is ready**

```bash
curl -s http://localhost:14008/ready
```

Expected output: `ready`

- [ ] **Step 5: Verify Grafana is accessible**

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:14007/api/health
```

Expected output: `200`

- [ ] **Step 6: Verify dashboards are provisioned**

```bash
curl -s -u admin:admin http://localhost:14007/api/search?folderTitle=EMCIP | python3 -m json.tool | grep title
```

Expected output: lines containing `"EMCIP Service Health"`, `"EMCIP Kafka Consumer Lag"`, `"EMCIP Audit Throughput"`

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(5.6): add Loki, Promtail, Grafana to docker-compose (ports 14007/14008)"
```

---

### Task 9: Update project topology documentation

**Files:**
- Modify: `.claude/skills/project-topology.md`

> Note: This file is protected — requires explicit user approval before editing. Present the proposed change and wait for approval.

Proposed addition to the Infrastructure Ports table:

```
| Loki          | 14008 | Log aggregation (http://localhost:14008) |
| Grafana       | 14007 | Dashboards (http://localhost:14007, admin/admin) |
```

- [ ] **Step 1: After approval, update project-topology.md**

Add to the Infrastructure Ports table in `.claude/skills/project-topology.md`:

```markdown
| Loki | 14008 | Log aggregation |
| Grafana | 14007 | Observability dashboards (http://localhost:14007) |
```

- [ ] **Step 2: Update PORT_CONFIGURATION.md**

Add to `PORT_CONFIGURATION.md`:

```markdown
| 14007 | Grafana | Observability dashboards — http://localhost:14007 (admin/admin) |
| 14008 | Loki    | Log aggregation backend |
```

- [ ] **Step 3: Commit**

```bash
git add PORT_CONFIGURATION.md
git commit -m "docs(5.6): document Grafana (14007) and Loki (14008) ports"
```

---

### Verification

```bash
# Full stack smoke test
docker compose up -d loki promtail grafana
sleep 15
curl -s http://localhost:14008/ready          # → ready
curl -s -u admin:admin http://localhost:14007/api/health  # → {"database":"ok","version":"11.x.x"}
curl -s -u admin:admin "http://localhost:14007/api/search" | python3 -c "import sys,json; dbs=[d['title'] for d in json.load(sys.stdin)]; print('\n'.join(dbs))"
# → EMCIP Service Health
# → EMCIP Kafka Consumer Lag
# → EMCIP Audit Throughput
```
