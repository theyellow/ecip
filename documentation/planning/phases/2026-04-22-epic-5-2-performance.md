# Epic 5.2 — Performance Tuning & Load Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a `gatling-tests` Maven module with Gatling load simulations, define SLOs, and tune HikariCP/Kafka settings to meet them.

**Architecture:** A new Maven module `gatling-tests` contains Gatling simulations using the Gatling Java DSL (Gatling 3.11+, no Scala required). The module is excluded from the parent `mvn test` run and triggered via `mvn gatling:test -pl gatling-tests`. Tuning changes go in each service's `application.yml`.

**Tech Stack:** Gatling 3.11 (Java DSL), Maven, Java 21, HikariCP, Kafka.

**SLOs:**
| Metric | Target |
|--------|--------|
| p95 intent classifier actuator | < 200ms |
| p95 policy engine actuator | < 100ms |
| p95 admin api (login + list) | < 200ms |
| Kafka throughput | 500 msg/s sustained |

---

### Task 1: gatling-tests Maven module scaffold

**Files:**
- Create: `gatling-tests/pom.xml`
- Modify: `pom.xml` (root) — add `gatling-tests` to `<modules>`

- [ ] **Step 1: Add module to root pom.xml**

In the root `pom.xml`, in the `<modules>` section, add:
```xml
<module>gatling-tests</module>
```

- [ ] **Step 2: Create gatling-tests/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.emcip</groupId>
        <artifactId>community-intelligence-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>gatling-tests</artifactId>
    <name>EMCIP Gatling Load Tests</name>
    <description>Performance load tests — run separately, not part of mvn test</description>

    <properties>
        <gatling.version>3.11.5</gatling.version>
        <gatling-maven-plugin.version>4.9.6</gatling-maven-plugin.version>
        <maven.test.skip>true</maven.test.skip>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.gatling.highcharts</groupId>
            <artifactId>gatling-charts-highcharts</artifactId>
            <version>${gatling.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <testSourceDirectory>src/test/java</testSourceDirectory>
        <plugins>
            <plugin>
                <groupId>io.gatling</groupId>
                <artifactId>gatling-maven-plugin</artifactId>
                <version>${gatling-maven-plugin.version}</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Verify root build still compiles**

```bash
mvn compile -pl . --non-recursive
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add gatling-tests/pom.xml pom.xml
git commit -m "feat(5.2): add gatling-tests Maven module scaffold"
```

---

### Task 2: Intent Classifier simulation

**Files:**
- Create: `gatling-tests/src/test/java/io/emcip/perf/IntentClassifierSimulation.java`

- [ ] **Step 1: Create IntentClassifierSimulation**

```java
package io.emcip.perf;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import java.time.Duration;

public class IntentClassifierSimulation extends Simulation {

    private static final String BASE_URL =
            System.getProperty("intentClassifierUrl", "http://localhost:9082");

    HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json");

    ScenarioBuilder healthScenario = scenario("Intent Classifier Health")
            .exec(
                http("actuator health")
                    .get("/actuator/health")
                    .check(status().is(200))
                    .check(responseTimeInMillis().lte(200))
            );

    {
        setUp(
            healthScenario.injectOpen(
                rampUsers(50).during(Duration.ofSeconds(30)),
                constantUsersPerSec(20).during(Duration.ofSeconds(60))
            )
        )
        .protocols(httpProtocol)
        .assertions(
            global().responseTime().percentile(95).lte(200),
            global().successfulRequests().percent().gte(99.0)
        );
    }
}
```

- [ ] **Step 2: Compile check**

```bash
mvn test-compile -pl gatling-tests
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add gatling-tests/src/test/java/io/emcip/perf/IntentClassifierSimulation.java
git commit -m "feat(5.2): add IntentClassifier Gatling simulation"
```

---

### Task 3: Policy Engine simulation

**Files:**
- Create: `gatling-tests/src/test/java/io/emcip/perf/PolicyEngineSimulation.java`

- [ ] **Step 1: Create PolicyEngineSimulation**

```java
package io.emcip.perf;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import java.time.Duration;

public class PolicyEngineSimulation extends Simulation {

    private static final String BASE_URL =
            System.getProperty("policyEngineUrl", "http://localhost:9083");

    HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json");

    ScenarioBuilder healthScenario = scenario("Policy Engine Health")
            .exec(
                http("actuator health")
                    .get("/actuator/health")
                    .check(status().is(200))
                    .check(responseTimeInMillis().lte(100))
            );

    {
        setUp(
            healthScenario.injectOpen(
                rampUsers(100).during(Duration.ofSeconds(30)),
                constantUsersPerSec(50).during(Duration.ofSeconds(60))
            )
        )
        .protocols(httpProtocol)
        .assertions(
            global().responseTime().percentile(95).lte(100),
            global().successfulRequests().percent().gte(99.0)
        );
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add gatling-tests/src/test/java/io/emcip/perf/PolicyEngineSimulation.java
git commit -m "feat(5.2): add PolicyEngine Gatling simulation"
```

---

### Task 4: Admin API simulation

**Files:**
- Create: `gatling-tests/src/test/java/io/emcip/perf/AdminApiSimulation.java`

- [ ] **Step 1: Create AdminApiSimulation with JWT auth flow**

```java
package io.emcip.perf;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import java.time.Duration;

public class AdminApiSimulation extends Simulation {

    private static final String BASE_URL =
            System.getProperty("adminApiUrl", "http://localhost:9087");
    private static final String ADMIN_USER = System.getProperty("adminUser", "admin");
    private static final String ADMIN_PASS = System.getProperty("adminPass", "admin");

    HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    ScenarioBuilder loginAndListGroups = scenario("Admin API — Login + List Groups")
            .exec(
                http("login")
                    .post("/api/auth/token")
                    .body(StringBody(
                        "{\"username\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASS + "\"}"))
                    .check(status().is(200))
                    .check(jsonPath("$.token").saveAs("jwtToken"))
            )
            .exec(
                http("list groups")
                    .get("/api/groups")
                    .header("Authorization", session -> "Bearer " + session.getString("jwtToken"))
                    .check(status().is(200))
                    .check(responseTimeInMillis().lte(200))
            );

    {
        setUp(
            loginAndListGroups.injectOpen(
                rampUsers(20).during(Duration.ofSeconds(20)),
                constantUsersPerSec(10).during(Duration.ofSeconds(60))
            )
        )
        .protocols(httpProtocol)
        .assertions(
            global().responseTime().percentile(95).lte(200),
            global().successfulRequests().percent().gte(99.0)
        );
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add gatling-tests/src/test/java/io/emcip/perf/AdminApiSimulation.java
git commit -m "feat(5.2): add AdminApi Gatling simulation with JWT auth"
```

---

### Task 5: Run baseline and document results

**Files:**
- Create: `documentation/developer/performance-benchmarks.md`

- [ ] **Step 1: Start services**

```bash
docker compose up -d postgres kafka
# In separate terminals or background:
mvn spring-boot:run -pl emcip-intent-classifier
mvn spring-boot:run -pl emcip-policy-engine
mvn spring-boot:run -pl emcip-admin-api
sleep 30
```

- [ ] **Step 2: Run all simulations**

```bash
mvn gatling:test -pl gatling-tests -DsimulationClass=io.emcip.perf.IntentClassifierSimulation
mvn gatling:test -pl gatling-tests -DsimulationClass=io.emcip.perf.PolicyEngineSimulation
mvn gatling:test -pl gatling-tests -DsimulationClass=io.emcip.perf.AdminApiSimulation
```

Expected: HTML reports in `gatling-tests/target/gatling/`

- [ ] **Step 3: Create benchmark doc with actual baseline numbers**

```markdown
# EMCIP Performance Benchmarks

## Baseline (run after Phase 5 complete)

Run: `mvn gatling:test -pl gatling-tests`

| Simulation | p95 | p99 | SLO | Status |
|------------|-----|-----|-----|--------|
| Intent Classifier health | Xms | Xms | 200ms | fill in |
| Policy Engine health | Xms | Xms | 100ms | fill in |
| Admin API (login + list) | Xms | Xms | 200ms | fill in |

## How to run

```bash
docker compose up -d postgres kafka
# Start required services, then:
mvn gatling:test -pl gatling-tests -DsimulationClass=io.emcip.perf.IntentClassifierSimulation
```

## Tuning history

| Date | Change | Before | After |
|------|--------|--------|-------|
| 2026-04-22 | Initial baseline | - | - |
```

- [ ] **Step 4: Commit**

```bash
git add documentation/developer/performance-benchmarks.md
git commit -m "docs(5.2): add performance benchmarks doc"
```

---

### Task 6: HikariCP and Kafka consumer tuning

**Files:**
- Modify: `emcip-policy-engine/src/main/resources/application.yml`
- Modify: `emcip-intent-classifier/src/main/resources/application.yml`

Apply after baseline profiling. Tune only if p95 exceeds SLO.

- [ ] **Step 1: Tune HikariCP in policy-engine (if p95 > 100ms)**

In `emcip-policy-engine/src/main/resources/application.yml`, under `spring.datasource`:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

- [ ] **Step 2: Tune Kafka consumer in intent-classifier (if throughput < 500 msg/s)**

In `emcip-intent-classifier/src/main/resources/application.yml`:
```yaml
spring:
  kafka:
    consumer:
      max-poll-records: 500
      fetch-min-size: 1
      fetch-max-wait: 500ms
```

- [ ] **Step 3: Re-run simulations and verify SLOs**

```bash
mvn gatling:test -pl gatling-tests -DsimulationClass=io.emcip.perf.PolicyEngineSimulation
```

Expected: p95 <= 100ms.

- [ ] **Step 4: Commit tuning**

```bash
git add emcip-policy-engine/src/main/resources/application.yml \
        emcip-intent-classifier/src/main/resources/application.yml
git commit -m "feat(5.2): tune HikariCP and Kafka consumer settings for SLOs"
```

---

### Verification

```bash
# Compile check (no services needed)
mvn test-compile -pl gatling-tests

# Full run (requires running services)
docker compose up -d postgres kafka
mvn gatling:test -pl gatling-tests -DsimulationClass=io.emcip.perf.AdminApiSimulation
open gatling-tests/target/gatling/$(ls -t gatling-tests/target/gatling/ | head -1)/index.html
```
