# OTel Tracing + Phase 4 Integration Tests — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire Grafana Tempo as the distributed trace backend for all 8 EMCIP services, add observability and integration tests for moderation and audit services, and update documentation.

**Architecture:** Services already have `micrometer-tracing-bridge-otel` — swap `opentelemetry-exporter-logging` for `opentelemetry-exporter-otlp`, add `management.otlp.tracing.endpoint` to all service configs. Add Tempo to docker-compose and Helm. Provision Tempo datasource in Grafana with trace-log correlation via Loki derived fields.

**Tech Stack:** Grafana Tempo 2.4.0, Spring Boot 4.0.5 OTLP autoconfiguration (`management.otlp.tracing.*`), Testcontainers 1.19.7 (PostgreSQL + Kafka), Micrometer Tracing OTel bridge, tools.jackson (Jackson 3), Awaitility (included in spring-boot-starter-test).

**Spec:** `docs/superpowers/specs/2026-05-15-otel-tracing-phase4-design.md`

---

## File Map

**Created:**
- `config/tempo-config.yml`
- `helm/emcip/templates/infra/tempo-pvc.yaml`
- `helm/emcip/templates/infra/tempo-configmap.yaml`
- `helm/emcip/templates/infra/tempo-deployment.yaml`
- `helm/emcip/templates/infra/tempo-service.yaml`
- `emcip-moderation-service/src/test/java/io/emcip/moderation/service/AbstractModerationIntegrationTest.java`
- `emcip-moderation-service/src/test/java/io/emcip/moderation/service/PrometheusScrapingIT.java`
- `emcip-moderation-service/src/test/java/io/emcip/moderation/service/TraceContextPropagationIT.java`
- `emcip-moderation-service/src/test/java/io/emcip/moderation/service/ModerationFlowIT.java`
- `emcip-audit-service/src/test/java/io/emcip/audit/service/AbstractAuditIntegrationTest.java`
- `emcip-audit-service/src/test/java/io/emcip/audit/service/AuditEventPersistenceIT.java`
- `emcip-audit-service/src/test/java/io/emcip/audit/service/RetentionServiceIT.java`
- `docs/operations/observability-verification.md`

**Modified:**
- `config/grafana/provisioning/datasources/datasources.yml`
- `docker-compose.yml`
- `helm/emcip/values.yaml`
- `helm/emcip/templates/infra/grafana-configmap.yaml`
- `*/pom.xml` (8 services: conversation-context, intent-classifier, policy-engine, llm-orchestrator, moderation-service, audit-service, admin-api, tdlib-adapter)
- `*/src/main/resources/application.yml` (same 8 services)
- `documentation/docker-compose-guide.adoc`
- `documentation/operations-guide.adoc`
- `documentation/architecture-guide.adoc`
- `documentation/developer-guide.adoc`

---

## Task 1: Tempo config + docker-compose infrastructure

**Files:**
- Create: `config/tempo-config.yml`
- Modify: `config/grafana/provisioning/datasources/datasources.yml`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Create `config/tempo-config.yml`**

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
    block_retention: 336h
```

- [ ] **Step 2: Update `config/grafana/provisioning/datasources/datasources.yml`**

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
      derivedFields:
        - name: TraceID
          matcherRegex: '"traceId":"(\w+)"'
          url: '${__value.raw}'
          datasourceUid: Tempo

  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    jsonData:
      timeInterval: 15s

  - name: Tempo
    type: tempo
    uid: Tempo
    access: proxy
    url: http://tempo:3200
    isDefault: false
```

- [ ] **Step 3: Add Tempo service to `docker-compose.yml`**

Add this block before the `volumes:` section (after the `admin-ui` service):

```yaml
  # Tempo — distributed trace backend (http://localhost:14011)
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
    healthcheck:
      test: ["CMD-SHELL", "wget --no-verbose --tries=1 --spider http://localhost:3200/ready || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5
```

Add `tempo-data:` to the `volumes:` block.

- [ ] **Step 4: Add `tempo: condition: service_healthy` to Grafana's `depends_on` in `docker-compose.yml`**

The Grafana service block currently has:
```yaml
    depends_on:
      loki:
        condition: service_healthy
      prometheus:
        condition: service_healthy
```

Add:
```yaml
      tempo:
        condition: service_healthy
```

- [ ] **Step 5: Add `OTEL_EXPORTER_OTLP_ENDPOINT` to each of the 8 instrumented app services in `docker-compose.yml`**

For each of: `conversation-context`, `intent-classifier`, `policy-engine`, `llm-orchestrator`, `moderation-service`, `audit-service`, `admin-api`, `tdlib-adapter` — add to their `environment:` block:

```yaml
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://ecip-tempo:4318
```

Note: `admin-ui` does NOT get this env var — it has no OTel dependencies.

- [ ] **Step 6: Verify Tempo starts**

```bash
docker compose up -d tempo
sleep 10
curl -s http://localhost:14011/ready
```

Expected output: `ready`

- [ ] **Step 7: Commit**

```bash
git add config/tempo-config.yml config/grafana/provisioning/datasources/datasources.yml docker-compose.yml
git commit -m "feat(observability): add Grafana Tempo to docker-compose stack

Add Tempo 2.4.0 with OTLP/HTTP ingestion on port 4318, query API on
14011. Update Grafana datasources with Tempo + Loki trace-log
correlation via derivedFields. Wire OTEL_EXPORTER_OTLP_ENDPOINT to
all 8 instrumented services."
```

---

## Task 2: Swap OTel exporter dep in all 8 service pom.xml files

**Files:**
- Modify: `emcip-conversation-context/pom.xml`
- Modify: `emcip-intent-classifier/pom.xml`
- Modify: `emcip-policy-engine/pom.xml`
- Modify: `emcip-llm-orchestrator/pom.xml`
- Modify: `emcip-moderation-service/pom.xml`
- Modify: `emcip-audit-service/pom.xml`
- Modify: `emcip-admin-api/pom.xml`
- Modify: `emcip-tdlib-adapter/pom.xml`

In every one of the 8 service pom.xml files, the change is identical: remove `opentelemetry-exporter-logging`, add `opentelemetry-exporter-otlp`. No version needed — managed by Spring Boot BOM.

- [ ] **Step 1: In each of the 8 pom.xml files, replace**

```xml
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-exporter-logging</artifactId>
    </dependency>
```

with:

```xml
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>
```

Apply to all 8 services: `emcip-conversation-context`, `emcip-intent-classifier`, `emcip-policy-engine`, `emcip-llm-orchestrator`, `emcip-moderation-service`, `emcip-audit-service`, `emcip-admin-api`, `emcip-tdlib-adapter`.

- [ ] **Step 2: Verify compilation**

```bash
mvn clean compile -q
```

Expected: `BUILD SUCCESS` with no errors.

- [ ] **Step 3: Apply Spotless**

```bash
mvn spotless:apply
```

Expected: `0 were changed to be clean`

- [ ] **Step 4: Commit**

```bash
git add '*/pom.xml'
git commit -m "feat(observability): swap opentelemetry-exporter-logging for otlp in all 8 services

Enables OTLP/HTTP trace export to Tempo. No code changes — the
OTLP exporter is autoconfigured by Spring Boot when the dep is present
and management.otlp.tracing.endpoint is set."
```

---

## Task 3: Add OTLP endpoint config to all 8 application.yml files

**Files:**
- Modify: `emcip-conversation-context/src/main/resources/application.yml`
- Modify: `emcip-intent-classifier/src/main/resources/application.yml`
- Modify: `emcip-policy-engine/src/main/resources/application.yml`
- Modify: `emcip-llm-orchestrator/src/main/resources/application.yml`
- Modify: `emcip-moderation-service/src/main/resources/application.yml`
- Modify: `emcip-audit-service/src/main/resources/application.yml`
- Modify: `emcip-admin-api/src/main/resources/application.yml`
- Modify: `emcip-tdlib-adapter/src/main/resources/application.yml`

**Services that already have `management.tracing.sampling.probability: 1.0`** (moderation-service, admin-api, audit-service): merge only the `otlp` block into their existing `management:` section.

**Services with no tracing config** (conversation-context, intent-classifier, policy-engine, llm-orchestrator, tdlib-adapter): add the full block.

- [ ] **Step 1: For the 5 services without tracing config, add to their `management:` block**

Find the `management:` section (which has `endpoints.web.exposure.include: health,info,metrics,prometheus`) and add `tracing` and `otlp` as siblings to `endpoints`:

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/traces
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
```

Services: `emcip-conversation-context`, `emcip-intent-classifier`, `emcip-policy-engine`, `emcip-llm-orchestrator`, `emcip-tdlib-adapter`.

- [ ] **Step 2: For the 3 services with existing tracing config, add only the `otlp` block**

In `emcip-moderation-service/src/main/resources/application.yml`, `emcip-admin-api/src/main/resources/application.yml`, `emcip-audit-service/src/main/resources/application.yml` — add `otlp` block as a sibling to `tracing` under `management:`:

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/traces
  endpoints:
    ...
```

- [ ] **Step 3: Verify packaging**

```bash
mvn clean package -DskipTests -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add '*/src/main/resources/application.yml'
git commit -m "feat(observability): wire OTLP trace endpoint in all 8 service configs

All services now export traces via OTLP/HTTP to
\${OTEL_EXPORTER_OTLP_ENDPOINT}/v1/traces (defaults to localhost:4318).
Added sampling.probability=1.0 to the 5 services that were missing it."
```

---

## Task 4: Helm Tempo templates + values + Grafana ConfigMap

**Files:**
- Create: `helm/emcip/templates/infra/tempo-pvc.yaml`
- Create: `helm/emcip/templates/infra/tempo-configmap.yaml`
- Create: `helm/emcip/templates/infra/tempo-deployment.yaml`
- Create: `helm/emcip/templates/infra/tempo-service.yaml`
- Modify: `helm/emcip/values.yaml`
- Modify: `helm/emcip/templates/infra/grafana-configmap.yaml`

- [ ] **Step 1: Create `helm/emcip/templates/infra/tempo-pvc.yaml`**

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: emcip-tempo-data
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: {{ .Values.storage.storageClassName }}
  resources:
    requests:
      storage: {{ .Values.storage.tempo.size }}
```

- [ ] **Step 2: Create `helm/emcip/templates/infra/tempo-configmap.yaml`**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: emcip-tempo-config
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
data:
  tempo.yaml: |
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
        block_retention: 336h
```

- [ ] **Step 3: Create `helm/emcip/templates/infra/tempo-deployment.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: emcip-tempo
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: emcip-tempo
  template:
    metadata:
      labels:
        app: emcip-tempo
        {{- include "emcip.labels" . | nindent 8 }}
    spec:
      containers:
        - name: tempo
          image: {{ .Values.infra.tempo.image }}
          args:
            - -config.file=/etc/tempo/tempo.yaml
          ports:
            - containerPort: 3200
              name: http
            - containerPort: 4318
              name: otlp-http
          volumeMounts:
            - name: config
              mountPath: /etc/tempo
            - name: data
              mountPath: /var/tempo
          readinessProbe:
            httpGet:
              path: /ready
              port: 3200
            initialDelaySeconds: 10
            periodSeconds: 10
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
      volumes:
        - name: config
          configMap:
            name: emcip-tempo-config
        - name: data
          persistentVolumeClaim:
            claimName: emcip-tempo-data
```

- [ ] **Step 4: Create `helm/emcip/templates/infra/tempo-service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: emcip-tempo
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "emcip.labels" . | nindent 4 }}
spec:
  type: ClusterIP
  selector:
    app: emcip-tempo
  ports:
    - name: http
      port: {{ .Values.infra.tempo.port }}
      targetPort: 3200
    - name: otlp-http
      port: 4318
      targetPort: 4318
```

- [ ] **Step 5: Update `helm/emcip/values.yaml` — add Tempo entries**

Add `tempo` to the `infra:` block (after the `prometheus:` entry):

```yaml
  tempo:
    image: grafana/tempo:2.4.0
    port: 3200
```

Add `tempo` to the `storage:` block (after `prometheus:`):

```yaml
  tempo:
    size: 10Gi
```

Add `OTEL_EXPORTER_OTLP_ENDPOINT: "http://emcip-tempo:4318"` to the `env:` section of each of the 8 instrumented services under `services:`. Example for `conversationContext`:

```yaml
  conversationContext:
    ...
    env:
      SPRING_DATASOURCE_URL: "jdbc:postgresql://emcip-postgres:5432/emcip"
      KAFKA_BOOTSTRAP_SERVERS: "emcip-kafka-bootstrap:9092"
      OTEL_EXPORTER_OTLP_ENDPOINT: "http://emcip-tempo:4318"
```

Apply to all 8 services: `conversationContext`, `intentClassifier`, `policyEngine`, `llmOrchestrator`, `moderationService`, `auditService`, `adminApi`, `tdlibAdapter`. Skip `adminUi`.

- [ ] **Step 6: Update `helm/emcip/templates/infra/grafana-configmap.yaml` — add Tempo datasource**

In the `datasources.yaml:` key inside the ConfigMap data, replace the existing content with one that adds Tempo and the Loki `derivedFields`. The existing section looks like:

```yaml
    datasources:
      - name: Loki
        ...
      - name: Prometheus
        ...
```

Replace it with:

```yaml
    datasources:
      - name: Loki
        type: loki
        access: proxy
        url: http://emcip-loki:3100
        isDefault: false
        version: 1
        editable: false
        jsonData:
          derivedFields:
            - name: TraceID
              matcherRegex: '"traceId":"(\w+)"'
              url: '${__value.raw}'
              datasourceUid: Tempo
      - name: Prometheus
        type: prometheus
        access: proxy
        url: http://emcip-prometheus:{{ .Values.infra.prometheus.port }}
        isDefault: true
        version: 1
        editable: false
        jsonData:
          timeInterval: 15s
      - name: Tempo
        type: tempo
        uid: Tempo
        access: proxy
        url: http://emcip-tempo:{{ .Values.infra.tempo.port }}
        isDefault: false
        version: 1
        editable: false
```

- [ ] **Step 7: Verify Helm templates render cleanly**

```bash
helm template helm/emcip | grep -A3 "emcip-tempo" | head -30
```

Expected: output shows the Tempo Service, Deployment, PVC, and ConfigMap metadata without errors.

- [ ] **Step 8: Commit**

```bash
git add helm/
git commit -m "feat(helm): add Grafana Tempo to Helm chart

Add PVC, ConfigMap, Deployment, and Service for Tempo 2.4.0.
Wire OTEL_EXPORTER_OTLP_ENDPOINT to all 8 services in values.yaml.
Add Tempo datasource and Loki derivedFields to Grafana ConfigMap."
```

---

## Task 5: Moderation service — add test deps + AbstractModerationIntegrationTest

**Files:**
- Modify: `emcip-moderation-service/pom.xml`
- Create: `emcip-moderation-service/src/test/java/io/emcip/moderation/service/AbstractModerationIntegrationTest.java`

- [ ] **Step 1: Add Testcontainers deps to `emcip-moderation-service/pom.xml`**

Add after the `reactor-test` dependency in the `<!-- Testing -->` section:

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
```

Note: all three are version-managed by Spring Boot 4's BOM. `postgresql` is also declared in the parent BOM — either entry is fine.

- [ ] **Step 2: Create `AbstractModerationIntegrationTest.java`**

```java
package io.emcip.moderation.service;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class AbstractModerationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url",
                () -> POSTGRES.getJdbcUrl().replace("jdbc:postgresql", "r2dbc:postgresql"));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Suppress OTLP connection errors during tests — no Tempo running
        registry.add("management.otlp.tracing.endpoint",
                () -> "http://localhost:1/v1/traces");
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
mvn -pl emcip-moderation-service compile -q
mvn -pl emcip-moderation-service test-compile -q
```

Expected: `BUILD SUCCESS` for both.

- [ ] **Step 4: Apply Spotless**

```bash
mvn -pl emcip-moderation-service spotless:apply
```

- [ ] **Step 5: Commit**

```bash
git add emcip-moderation-service/pom.xml emcip-moderation-service/src/test/
git commit -m "test(moderation): add Testcontainers deps and AbstractModerationIntegrationTest base class"
```

---

## Task 6: PrometheusScrapingIT

**Files:**
- Create: `emcip-moderation-service/src/test/java/io/emcip/moderation/service/PrometheusScrapingIT.java`

- [ ] **Step 1: Write the test**

```java
package io.emcip.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class PrometheusScrapingIT extends AbstractModerationIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void actuatorPrometheus_exposesJvmAndHttpMetrics() {
        String body = webTestClient
                .get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("jvm_memory_used_bytes");
        assertThat(body).contains("http_server_requests_seconds_count");
    }
}
```

- [ ] **Step 2: Run the test**

```bash
mvn -pl emcip-moderation-service verify -Dit.test=PrometheusScrapingIT -DskipTests=false
```

Expected: `BUILD SUCCESS`, 1 test passing. First run pulls Docker images — allow up to 2 minutes.

- [ ] **Step 3: Apply Spotless and commit**

```bash
mvn -pl emcip-moderation-service spotless:apply
git add emcip-moderation-service/src/test/
git commit -m "test(observability): add PrometheusScrapingIT verifying actuator metrics"
```

---

## Task 7: TraceContextPropagationIT

**Files:**
- Create: `emcip-moderation-service/src/test/java/io/emcip/moderation/service/TraceContextPropagationIT.java`

- [ ] **Step 1: Write the test**

```java
package io.emcip.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class TraceContextPropagationIT extends AbstractModerationIntegrationTest {

    @Autowired
    private Tracer tracer;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void tracer_isOtelBridge_notNoop() {
        // A no-op tracer produces all-zero trace IDs; the OTel bridge produces real UUIDs
        Span span = tracer.nextSpan().name("test-verify-otel-bridge").start();
        String traceId;
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            traceId = span.context().traceId();
        } finally {
            span.end();
        }
        assertThat(traceId).isNotBlank().doesNotMatch("^0+$");
    }

    @Test
    void httpRequest_createsActiveSpan_withNonZeroTraceId() {
        Span span = tracer.nextSpan().name("test-http-tracing").start();
        String traceId;
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            traceId = span.context().traceId();
            webTestClient.get()
                    .uri("/actuator/health")
                    .exchange()
                    .expectStatus().isOk();
        } finally {
            span.end();
        }
        assertThat(traceId).isNotBlank().doesNotMatch("^0+$");
        assertThat(traceId).hasSize(32); // OTel trace IDs are 128-bit hex = 32 chars
    }
}
```

- [ ] **Step 2: Run the test**

```bash
mvn -pl emcip-moderation-service verify -Dit.test=TraceContextPropagationIT -DskipTests=false
```

Expected: `BUILD SUCCESS`, 2 tests passing.

- [ ] **Step 3: Apply Spotless and commit**

```bash
mvn -pl emcip-moderation-service spotless:apply
git add emcip-moderation-service/src/test/
git commit -m "test(observability): add TraceContextPropagationIT verifying OTel bridge is active"
```

---

## Task 8: ModerationFlowIT

**Files:**
- Create: `emcip-moderation-service/src/test/java/io/emcip/moderation/service/ModerationFlowIT.java`

- [ ] **Step 1: Write the test**

```java
package io.emcip.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.emcip.common.events.EventSchemas.TelegramMessageEvent;
import io.emcip.moderation.service.entity.ModerationRule;
import io.emcip.moderation.service.repository.ModerationRuleRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

class ModerationFlowIT extends AbstractModerationIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ModerationRuleRepository ruleRepository;

    @Test
    void telegramMessage_matchingKeywordRule_producesModerationFlagEvent() throws Exception {
        // Arrange: insert an enabled keyword rule
        ModerationRule rule = ModerationRule.builder()
                .name("spam-detection-it")
                .ruleType("KEYWORD")
                .pattern("spam_it_test_keyword_99")
                .severity("HIGH")
                .action("FLAG")
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        ruleRepository.save(rule).block();

        // Arrange: build input event
        TelegramMessageEvent event = new TelegramMessageEvent(
                "evt-mod-flow-001", Instant.now().toString(), null, null,
                100L, 200L, "user-mod-1", "USER",
                "this message contains spam_it_test_keyword_99",
                0, null, false, null, null, Map.of(), null);
        String json = new ObjectMapper().writeValueAsString(event);

        // Arrange: subscribe to output topic with a unique group to capture from the start
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG,
                "test-mod-flow-" + System.currentTimeMillis());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        Consumer<String, String> testConsumer =
                new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer();
        testConsumer.subscribe(Collections.singletonList("moderation.flags"));

        // Act: publish input event
        kafkaTemplate.send("telegram.raw.messages", "evt-mod-flow-001", json).get();

        // Assert: ModerationFlagEvent appears on output topic within 15 seconds
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofMillis(500));
            assertThat(records.count()).isGreaterThan(0);
            String value = records.iterator().next().value();
            assertThat(value).contains("ModerationFlag");
            assertThat(value).contains("evt-mod-flow-001");
        });

        testConsumer.close();
    }
}
```

- [ ] **Step 2: Run the test**

```bash
mvn -pl emcip-moderation-service verify -Dit.test=ModerationFlowIT -DskipTests=false
```

Expected: `BUILD SUCCESS`, 1 test passing (may take up to 30 seconds — container startup + Kafka consumer sync).

- [ ] **Step 3: Run all moderation ITs together to confirm no interference**

```bash
mvn -pl emcip-moderation-service verify -DskipTests=false
```

Expected: all existing unit tests pass + 4 new IT tests pass.

- [ ] **Step 4: Apply Spotless and commit**

```bash
mvn -pl emcip-moderation-service spotless:apply
git add emcip-moderation-service/src/test/ emcip-moderation-service/pom.xml
git commit -m "test(moderation): add ModerationFlowIT — end-to-end Kafka → rule evaluation → flag event"
```

---

## Task 9: Audit service — add test deps + AbstractAuditIntegrationTest

**Files:**
- Modify: `emcip-audit-service/pom.xml`
- Create: `emcip-audit-service/src/test/java/io/emcip/audit/service/AbstractAuditIntegrationTest.java`

- [ ] **Step 1: Add Testcontainers deps to `emcip-audit-service/pom.xml`**

Add after the `reactor-test` dependency in the `<!-- Testing -->` section — same three deps as Task 5:

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
```

- [ ] **Step 2: Create `AbstractAuditIntegrationTest.java`**

```java
package io.emcip.audit.service;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class AbstractAuditIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url",
                () -> POSTGRES.getJdbcUrl().replace("jdbc:postgresql", "r2dbc:postgresql"));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("management.otlp.tracing.endpoint",
                () -> "http://localhost:1/v1/traces");
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
mvn -pl emcip-audit-service compile -q && mvn -pl emcip-audit-service test-compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Apply Spotless and commit**

```bash
mvn -pl emcip-audit-service spotless:apply
git add emcip-audit-service/pom.xml emcip-audit-service/src/test/
git commit -m "test(audit): add Testcontainers deps and AbstractAuditIntegrationTest base class"
```

---

## Task 10: AuditEventPersistenceIT

**Files:**
- Create: `emcip-audit-service/src/test/java/io/emcip/audit/service/AuditEventPersistenceIT.java`

- [ ] **Step 1: Write the test**

```java
package io.emcip.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.common.events.EventSchemas.TelegramMessageEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

class AuditEventPersistenceIT extends AbstractAuditIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void telegramMessageEvent_consumedFromKafka_isPersistedAsAuditRecord() throws Exception {
        TelegramMessageEvent event = new TelegramMessageEvent(
                "audit-persist-001", Instant.now().toString(), null, null,
                300L, 400L, "user-audit-persist-1", "USER",
                "hello from audit persistence test",
                0, null, false, null, null, Map.of(), null);
        String json = new ObjectMapper().writeValueAsString(event);

        kafkaTemplate.send("telegram.raw.messages", "audit-persist-001", json).get();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            AuditEventEntity saved =
                    auditEventRepository.findByEventId("audit-persist-001").block();
            assertThat(saved).isNotNull();
            assertThat(saved.getEventType()).isEqualTo("TelegramMessage");
            assertThat(saved.getSourceService()).isEqualTo("emcip-tdlib-adapter");
            assertThat(saved.getOutcome()).isEqualTo("PROCESSED");
        });
    }
}
```

- [ ] **Step 2: Run the test**

```bash
mvn -pl emcip-audit-service verify -Dit.test=AuditEventPersistenceIT -DskipTests=false
```

Expected: `BUILD SUCCESS`, 1 test passing.

- [ ] **Step 3: Apply Spotless and commit**

```bash
mvn -pl emcip-audit-service spotless:apply
git add emcip-audit-service/src/test/
git commit -m "test(audit): add AuditEventPersistenceIT verifying Kafka event → DB persistence"
```

---

## Task 11: RetentionServiceIT

**Files:**
- Create: `emcip-audit-service/src/test/java/io/emcip/audit/service/RetentionServiceIT.java`

- [ ] **Step 1: Write the test**

```java
package io.emcip.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.emcip.audit.service.entity.AuditEventEntity;
import io.emcip.audit.service.repository.AuditEventRepository;
import io.emcip.audit.service.service.RetentionService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

class RetentionServiceIT extends AbstractAuditIntegrationTest {

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @BeforeEach
    void cleanDatabase() {
        auditEventRepository.deleteAll().block();
    }

    @Test
    void purgeOldEvents_deletesRecordsOlderThan90Days_retainsRecentOnes() {
        AuditEventEntity old = AuditEventEntity.builder()
                .eventId("retention-old-001")
                .eventType("TEST")
                .correlationId("retention-old-001")
                .sourceService("test")
                .action("TEST")
                .actorType("SYSTEM")
                .outcome("PROCESSED")
                .createdAt(Instant.now().minus(100, ChronoUnit.DAYS))
                .build();

        AuditEventEntity recent = AuditEventEntity.builder()
                .eventId("retention-recent-001")
                .eventType("TEST")
                .correlationId("retention-recent-001")
                .sourceService("test")
                .action("TEST")
                .actorType("SYSTEM")
                .outcome("PROCESSED")
                .createdAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .build();

        Flux.concat(
                auditEventRepository.save(old),
                auditEventRepository.save(recent))
                .blockLast();

        // Act: purgeOldEvents() uses .subscribe() internally (fire-and-forget)
        retentionService.purgeOldEvents();

        // Assert: only the recent record remains
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Long count = auditEventRepository.count().block();
            assertThat(count).isEqualTo(1L);
        });

        AuditEventEntity remaining = auditEventRepository.findAll().blockFirst();
        assertThat(remaining).isNotNull();
        assertThat(remaining.getEventId()).isEqualTo("retention-recent-001");
    }
}
```

- [ ] **Step 2: Run the test**

```bash
mvn -pl emcip-audit-service verify -Dit.test=RetentionServiceIT -DskipTests=false
```

Expected: `BUILD SUCCESS`, 1 test passing.

- [ ] **Step 3: Run all audit ITs together**

```bash
mvn -pl emcip-audit-service verify -DskipTests=false
```

Expected: all existing unit tests pass + 2 new IT tests pass.

- [ ] **Step 4: Apply Spotless and commit**

```bash
mvn -pl emcip-audit-service spotless:apply
git add emcip-audit-service/src/test/ emcip-audit-service/pom.xml
git commit -m "test(audit): add RetentionServiceIT verifying purge deletes old records and retains recent"
```

---

## Task 12: Observability runbook

**Files:**
- Create: `docs/operations/observability-verification.md`

- [ ] **Step 1: Create the runbook**

```markdown
# Observability Verification Runbook

After deploying or updating the EMCIP stack, follow these steps to verify the full
observability pipeline: metrics, logs, and traces.

---

## Prerequisites

- Grafana accessible at `http://localhost:14007` (docker-compose) or `http://emcip.local/grafana` (k8s)
- All EMCIP services running and healthy
- At least one message has been processed (trigger via Admin UI or send a test Kafka event)

---

## 1. Verify Prometheus scrape targets

1. Open Grafana → Explore → datasource: **Prometheus**
2. Run: `up{job=~"emcip-.*"}`
3. Expected: all 8 services show value `1`

If any show `0`: check `docker compose logs <service>` or `microk8s.kubectl logs -n emcip deploy/emcip-<service>`.

---

## 2. Verify traces arrive in Tempo

1. Open Grafana → Explore → datasource: **Tempo**
2. In the search form, set **Service Name** = `emcip-moderation-service`
3. Click **Run query**
4. Expected: trace results appear in the list

If empty: check `docker compose logs tempo` for OTLP ingestion errors. Verify
`OTEL_EXPORTER_OTLP_ENDPOINT` is set on the service container.

---

## 3. Verify trace span tree

1. Click any trace from step 2
2. Expand the span tree
3. Expected: at least one span visible with operation name and duration

---

## 4. Verify trace-log correlation (Loki → Tempo)

1. Open Grafana → Explore → datasource: **Loki**
2. Run: `{job="emcip"} | json | traceId != ""`
3. In any log line that shows a `traceId` field, click the **TraceID** link
4. Expected: browser jumps to Grafana Explore → Tempo, showing the matching trace

If the link is missing: the Loki `derivedFields` configuration is not applied.
Restart Grafana after updating `datasources.yml`.

---

## 5. Verify cross-service trace propagation

1. In Grafana → Explore → Tempo, search for a trace from `emcip-tdlib-adapter`
2. Look at the span tree — if a message was moderated, you should see child spans
   in `emcip-moderation-service` within the same trace
3. Expected: W3C `traceparent` propagated the trace ID across service boundaries via Kafka headers

---

## Quick Tempo API check (without Grafana)

```bash
# docker-compose
curl -s "http://localhost:14011/api/search?service.name=emcip-moderation-service" | jq .

# microk8s (port-forward first)
microk8s.kubectl port-forward -n emcip svc/emcip-tempo 14011:3200
curl -s "http://localhost:14011/api/search?service.name=emcip-moderation-service" | jq .
```
```

- [ ] **Step 2: Commit**

```bash
git add docs/operations/
git commit -m "docs: add observability verification runbook (Prometheus + Tempo + Loki correlation)"
```

---

## Task 13: Update four documentation guides

**Files:**
- Modify: `documentation/docker-compose-guide.adoc`
- Modify: `documentation/operations-guide.adoc`
- Modify: `documentation/architecture-guide.adoc`
- Modify: `documentation/developer-guide.adoc`

- [ ] **Step 1: Update `documentation/docker-compose-guide.adoc` — port table**

Find the port reference table (the one listing Grafana at 14007, Loki at 14008, Admin UI at 14009). Add a Tempo row after Prometheus (14010):

```asciidoc
|Tempo
|14011
|Distributed trace query API (via Grafana Explore → Tempo)
```

- [ ] **Step 2: Update `documentation/docker-compose-guide.adoc` — port conflict check script**

Find the `for port in` loop and add `14011` to the list alongside `14010`.

Before:
```bash
for port in 9080 9081 9082 9083 9084 9085 9086 9087 \
            14001 14002 14003 14004 14005 14006 14007 14008 14009; do
```

After:
```bash
for port in 9080 9081 9082 9083 9084 9085 9086 9087 \
            14001 14002 14003 14004 14005 14006 14007 14008 14009 14010 14011; do
```

Note: 14010 (Prometheus) was also missing from this list — add both.

- [ ] **Step 3: Update `documentation/docker-compose-guide.adoc` — Observability section**

Find the `=== Observability` section (which has `=== Grafana Dashboards` and `=== Loki Log Queries`). Add a new subsection after Loki:

```asciidoc
=== Distributed Tracing

Open http://localhost:14007 → Explore → select **Tempo** datasource.

Search by service name to find traces:

* *Service Name* = `emcip-moderation-service` → shows all spans from the moderation service
* Click a trace to see the span tree
* In any Loki log line containing a `traceId` field, click the **TraceID** derived field link to jump directly to the matching Tempo trace

Direct Tempo API (for scripting):

[source,bash]
----
curl -s "http://localhost:14011/api/search?service.name=emcip-moderation-service" | jq .
----
```

- [ ] **Step 4: Update `documentation/operations-guide.adoc` — Observability section**

Find the `== Observability` section (has `=== Grafana Dashboards`, `=== Loki Log Queries`, `=== Prometheus Metrics`). Add a new section after Prometheus Metrics:

```asciidoc
=== Distributed Tracing

Traces are collected by Grafana Tempo. The Tempo query API is exposed as `emcip-tempo:3200` (ClusterIP).

Open Grafana → Explore → **Tempo** datasource (http://emcip.local/grafana).

[cols="1,3"]
|===
|Query type |Usage

|Service name search
|Set *Service Name* = `emcip-<service>` → lists recent traces

|Trace ID lookup
|Paste a trace ID directly to open the span tree

|Loki link
|Click the *TraceID* field in any Loki log line → jumps to the matching trace
|===

.Useful LogQL to find traces:
[source]
----
# All log lines with a traceId (structured JSON logs)
{job="emcip"} | json | traceId != ""

# Find logs for a specific trace
{job="emcip"} | json | traceId="<paste-trace-id-here>"
----
```

- [ ] **Step 5: Update `documentation/architecture-guide.adoc` — observability**

Find the section that describes the observability stack (likely near where Loki and Prometheus are mentioned). Extend it to include distributed tracing:

```asciidoc
=== Distributed Tracing

All 8 services use *Micrometer Tracing* with the OpenTelemetry bridge (`micrometer-tracing-bridge-otel`).
Traces are exported via OTLP/HTTP to *Grafana Tempo* on port 4318. Tempo stores spans with a 14-day
retention window.

W3C `traceparent` headers propagate trace context across HTTP calls and Kafka message headers,
enabling end-to-end trace correlation across service boundaries.

Trace IDs appear in structured JSON logs as the `traceId` field. Grafana's Loki datasource
is configured with a derived field that links any `traceId` value directly to the matching Tempo trace.
```

- [ ] **Step 6: Update `documentation/developer-guide.adoc` — local dev observability**

Find the section about local development or observability (search for "Grafana" or "14007"). Add a note about tracing:

```asciidoc
=== Distributed Tracing in Local Dev

When Tempo is running (`docker compose up -d tempo`), all services export traces to
`http://ecip-tempo:4318`. Open http://localhost:14007 → Explore → Tempo to browse spans.

Trace IDs appear in application logs as `"traceId":"<32-hex-chars>"`. Use the Loki derived
field to jump from a log line directly to its trace.

If Tempo is *not* running, OTLP export fails silently — services start and function normally.
There is no fallback: traces are simply not stored.
```

- [ ] **Step 7: Commit**

```bash
git add documentation/
git commit -m "docs: update four guides with Tempo/tracing port, Observability sections, and dev workflow"
```

---

## Final verification

- [ ] **Run full build**

```bash
mvn clean verify -q
```

Expected: `BUILD SUCCESS`. All unit tests pass; all `*IT` tests pass.

- [ ] **Smoke test docker-compose stack**

```bash
docker compose up -d
sleep 30
curl -s http://localhost:14011/ready
curl -s http://localhost:14007/api/health | jq .database.message
```

Expected: `ready` from Tempo; `"Database Connection is OK"` from Grafana.
