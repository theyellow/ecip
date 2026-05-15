# OTel Tracing Backend + Phase 4 Integration Tests Design

**Date:** 2026-05-15
**Status:** Approved
**Scope:** US-4.2.2 (OTel tracing backend), US-4.2.5 (observability tests), US-4.1.5 (moderation integration tests), US-4.4.5 (audit integration tests)

---

## Context

All 8 EMCIP services already have `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-logging` on the classpath — traces are generated but exported to stdout only. This design wires a real trace backend (Grafana Tempo) so traces are stored, queryable, and correlated with logs in Grafana.

Three services (moderation-service, admin-api, audit-service) already have `management.tracing.sampling.probability: 1.0`. The remaining five have no tracing config at all.

Existing observability stack: Grafana (port 14007), Loki (port 14008), Prometheus (port 14010). No trace datasource.

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│  8 Spring Boot services                         │
│  micrometer-tracing-bridge-otel                 │
│  opentelemetry-exporter-otlp  ──► OTLP/HTTP     │
└───────────────────────────────────┬─────────────┘
                                    │ :4318/v1/traces
                              ┌─────▼──────┐
                              │   Tempo    │ :3200 (query)
                              └─────┬──────┘
                                    │
                              ┌─────▼──────┐
                              │  Grafana   │ (existing)
                              │  + Tempo   │
                              │  datasource│
                              └────────────┘
```

**Decision: Grafana Tempo, direct OTLP/HTTP (no OTel Collector).** Tempo ingests OTLP natively on port 4318. No collector layer needed at this stack size. Spring Boot native OTLP autoconfiguration — no custom Java code, purely dependencies + config.

**Port allocation:**

| Component | Internal port | docker-compose host port |
|---|---|---|
| Tempo OTLP HTTP (ingestion) | 4318 | not exposed — internal only |
| Tempo query API | 3200 | 14011 |

---

## US-4.2.2 — OTel Tracing Backend

### 1. Service pom.xml changes (all 8 services, identical)

Remove:
```xml
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-logging</artifactId>
</dependency>
```

Add:
```xml
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

Version is managed by Spring Boot BOM — no explicit version needed.

Affected services: `emcip-conversation-context`, `emcip-intent-classifier`, `emcip-policy-engine`, `emcip-llm-orchestrator`, `emcip-moderation-service`, `emcip-audit-service`, `emcip-admin-api`, `emcip-tdlib-adapter`.

### 2. application.yml changes (all 8 services)

Add to `management:` block (merge with existing where present):

```yaml
management:
  tracing:
    sampling:
      probability: 1.0          # add only to the 5 services missing it
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/traces
```

Services missing `sampling.probability` (add it): `conversation-context`, `intent-classifier`, `policy-engine`, `llm-orchestrator`, `tdlib-adapter`.

Services already having it (add only `otlp` block): `moderation-service`, `admin-api`, `audit-service`.

When `OTEL_EXPORTER_OTLP_ENDPOINT` is not set (local dev without Tempo), OTLP export fails silently — acceptable.

### 3. docker-compose.yml changes

**New service:**

```yaml
tempo:
  image: grafana/tempo:2.4.0
  container_name: ecip-tempo
  ports:
    - "14011:3200"
  volumes:
    - ./config/tempo-config.yml:/etc/tempo/config.yml
    - tempo-data:/var/tempo
  command: -config.file=/etc/tempo/config.yml
  networks:
    - ecip-network
```

Add `tempo-data:` to the top-level `volumes:` block.

**Each of the 8 app services** gets one env var added under `environment:`:

```yaml
- OTEL_EXPORTER_OTLP_ENDPOINT=http://ecip-tempo:4318
```

### 4. config/tempo-config.yml (new file)

```yaml
stream_over_http_enabled: true
server:
  http_listen_port: 3200

distributor:
  receivers:
    otlp:
      protocols:
        http:
          endpoint: 0.0.0.0:4318

storage:
  trace:
    backend: local
    local:
      path: /var/tempo/traces
    wal:
      path: /var/tempo/wal

compactor:
  compaction:
    block_retention: 336h   # 14 days
```

### 5. Grafana datasources (docker-compose)

`config/grafana/provisioning/datasources/datasources.yml` — add Tempo datasource and `derivedFields` on Loki for trace-log correlation:

```yaml
- name: Tempo
  type: tempo
  uid: Tempo
  access: proxy
  url: http://tempo:3200
  isDefault: false

# Update existing Loki entry — add jsonData:
- name: Loki
  type: loki
  access: proxy
  url: http://loki:3100
  isDefault: false
  jsonData:
    maxLines: 1000
    derivedFields:
      - name: TraceID
        matcherRegex: '"traceId":"(\w+)"'
        url: '${__value.raw}'
        datasourceUid: Tempo
```

### 6. Helm changes

**`helm/emcip/values.yaml`** — add to `infra:` block:

```yaml
infra:
  tempo:
    image: grafana/tempo:2.4.0
    port: 3200
```

Add to `storage:` block:

```yaml
storage:
  tempo:
    size: 10Gi
  # uses the same global storageClassName: microk8s-hostpath
```

Add to each service block under `services:`:

```yaml
env:
  OTEL_EXPORTER_OTLP_ENDPOINT: "http://emcip-tempo:4318"
```

**New Helm templates** (`helm/emcip/templates/infra/`):

- `tempo-pvc.yaml` — 10Gi PVC, uses global `storageClassName`
- `tempo-configmap.yaml` — same config as `config/tempo-config.yml` above
- `tempo-deployment.yaml` — single replica, mounts configmap + PVC, port 3200
- `tempo-service.yaml` — ClusterIP on port 3200 (`emcip-tempo`)

**`helm/emcip/templates/infra/grafana-configmap.yaml`** — add Tempo datasource and Loki derived fields (same as docker-compose datasources, with Helm-templated service names: `http://emcip-tempo:3200`, `http://emcip-loki:3100`).

---

## US-4.2.5 — Observability Tests

### Automated tests

Location: `emcip-moderation-service/src/test/java/io/emcip/moderation/`

**`PrometheusScrapingIT.java`**

`@SpringBootTest(webEnvironment = RANDOM_PORT)` — starts Spring context, hits `/actuator/prometheus` via `TestRestTemplate`, asserts the response body contains:
- `jvm_memory_used_bytes`
- `http_server_requests_seconds_count`
- `kafka_consumer_fetch_manager_records_lag`

No Testcontainers needed.

**`TraceContextPropagationIT.java`**

`@SpringBootTest(webEnvironment = RANDOM_PORT)` — makes an HTTP request to `/actuator/health`, asserts the response contains a `traceparent` header (W3C trace context). Uses WireMock as a fake Tempo endpoint (stubbed on a random port), asserts it receives a POST to `/v1/traces` with a valid OTLP payload.

### Runbook

`docs/operations/observability-verification.md` — step-by-step post-deploy verification:

1. Open Grafana → Explore → datasource: **Tempo**
2. Search by service name `emcip-moderation-service` → confirm spans appear
3. Click a trace → verify span tree shows correct service name and operation
4. Open a Loki log line containing `"traceId"` → click the derived field link → confirm it opens the matching Tempo trace
5. Open Explore → datasource: **Prometheus** → run `up{job=~"emcip-.*"}` → confirm all 8 services show `1`

---

## US-4.1.5 — Moderation Integration Tests

Existing unit tests (`RuleEvaluationServiceTest`, `ModerationEventConsumerTest`) are unchanged.

**New: `AbstractModerationIntegrationTest.java`** (base class, `emcip-moderation-service`):

```java
@SpringBootTest
@Testcontainers
abstract class AbstractModerationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> toR2dbcUrl(postgres.getJdbcUrl()));
        registry.add("spring.liquibase.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
```

**New: `ModerationFlowIT.java`**

Scenario: publish a `content.moderation.requested` Kafka event → assert the consumer processes it within a timeout → assert a moderation result record exists in the DB (queried via R2DBC).

**pom.xml additions** (if not already present):

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-testcontainers</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>postgresql</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>kafka</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.wiremock</groupId>
  <artifactId>wiremock-standalone</artifactId>
  <scope>test</scope>
</dependency>
```

Note: WireMock is needed only for `TraceContextPropagationIT` (fake Tempo endpoint). Check if already present before adding.
```

---

## US-4.4.5 — Audit Integration Tests

**New: `AbstractAuditIntegrationTest.java`** (base class, `emcip-audit-service`) — identical pattern to moderation base class.

**New: `AuditEventPersistenceIT.java`**

Publish an audit event to Kafka → assert it is persisted in `audit_events` table via R2DBC within a timeout.

**New: `RetentionServiceIT.java`**

Insert records with timestamps older than the retention threshold directly into the DB → invoke `RetentionService.purge()` → assert old records are deleted and recent records remain.

**pom.xml additions** — same Testcontainers deps as moderation-service (check if already present before adding).

---

## Ports Summary

| Port | Service | Notes |
|---|---|---|
| 14007 | Grafana | existing |
| 14008 | Loki | existing |
| 14010 | Prometheus | existing |
| 14011 | Tempo query API | new |

Tempo OTLP ingestion (4318) is internal only — not exposed to the host.

---

## Files Changed / Created

| File | Action |
|---|---|
| `config/tempo-config.yml` | Create |
| `config/grafana/provisioning/datasources/datasources.yml` | Update |
| `docker-compose.yml` | Update (Tempo service + env on 8 services) |
| `helm/emcip/values.yaml` | Update |
| `helm/emcip/templates/infra/tempo-pvc.yaml` | Create |
| `helm/emcip/templates/infra/tempo-configmap.yaml` | Create |
| `helm/emcip/templates/infra/tempo-deployment.yaml` | Create |
| `helm/emcip/templates/infra/tempo-service.yaml` | Create |
| `helm/emcip/templates/infra/grafana-configmap.yaml` | Update |
| `*/pom.xml` (8 services) | Update (swap exporter dep) |
| `*/application.yml` (8 services) | Update (add otlp endpoint + sampling) |
| `emcip-moderation-service/.../PrometheusScrapingIT.java` | Create |
| `emcip-moderation-service/.../TraceContextPropagationIT.java` | Create |
| `emcip-moderation-service/.../AbstractModerationIntegrationTest.java` | Create |
| `emcip-moderation-service/.../ModerationFlowIT.java` | Create |
| `emcip-audit-service/.../AbstractAuditIntegrationTest.java` | Create |
| `emcip-audit-service/.../AuditEventPersistenceIT.java` | Create |
| `emcip-audit-service/.../RetentionServiceIT.java` | Create |
| `docs/operations/observability-verification.md` | Create |
