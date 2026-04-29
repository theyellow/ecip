# GraalVM Native Image Migration — Remaining Services

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate `emcip-conversation-context` and `emcip-llm-orchestrator` to GraalVM native image; add Ubuntu LTS developer setup docs; write ADR deferring R2DBC services.

**Architecture:** Follow the `emcip-policy-engine` reference: add a `native` Maven profile, create a `RuntimeHints` registrar for Liquibase resources, annotate the main class with `@ImportRuntimeHints`. Spring Boot AOT handles JPA entity reflection and Kafka wiring automatically. Each service also gets a `Dockerfile.native` using `ghcr.io/graalvm/native-image-community:21` as builder and `debian:12-slim` as runtime.

**Tech Stack:** Java 21, GraalVM native-image-community:21, Spring Boot 4 AOT, native-maven-plugin 0.10.6, Maven multi-module build.

**Branch:** `feature/graalvm-remaining-services` (branch from `main` after current branch is merged)

---

## File Map

| Action | File |
|--------|------|
| Modify | `emcip-conversation-context/pom.xml` |
| Create | `emcip-conversation-context/src/main/java/io/emcip/conversation/context/config/ConversationContextRuntimeHints.java` |
| Modify | `emcip-conversation-context/src/main/java/io/emcip/conversation/context/ConversationContextApplication.java` |
| Create | `emcip-conversation-context/Dockerfile.native` |
| Modify | `emcip-llm-orchestrator/pom.xml` |
| Create | `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/LlmOrchestratorRuntimeHints.java` |
| Modify | `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/LlmOrchestratorApplication.java` |
| Create | `emcip-llm-orchestrator/Dockerfile.native` |
| Modify | `documentation/developer-guide.adoc` |
| Create | `documentation/adrs/0007-r2dbc-graalvm-deferral.adoc` |

**Reference files (read-only):**
- `emcip-policy-engine/pom.xml` — source of the `native` profile to copy
- `emcip-policy-engine/src/main/java/io/emcip/policy/engine/config/PolicyEngineRuntimeHints.java` — reference RuntimeHints
- `emcip-policy-engine/src/main/java/io/emcip/policy/engine/PolicyEngineApplication.java` — reference `@ImportRuntimeHints` usage
- `emcip-policy-engine/Dockerfile` — reference JVM Dockerfile pattern (Dockerfile.native adapts this)

---

## Task 1: Migrate emcip-conversation-context

**Files:**
- Modify: `emcip-conversation-context/pom.xml`
- Create: `emcip-conversation-context/src/main/java/io/emcip/conversation/context/config/ConversationContextRuntimeHints.java`
- Modify: `emcip-conversation-context/src/main/java/io/emcip/conversation/context/ConversationContextApplication.java`
- Create: `emcip-conversation-context/Dockerfile.native`

- [ ] **Step 1: Add native Maven profile to pom.xml**

Open `emcip-conversation-context/pom.xml`. Find the closing `</build>` tag just before `</project>`. Insert the following profile block **after** the `<build>` section but before `</project>`:

```xml
  <profiles>
    <profile>
      <id>native</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <executions>
              <execution>
                <id>process-aot</id>
                <goals>
                  <goal>process-aot</goal>
                </goals>
              </execution>
            </executions>
          </plugin>
          <plugin>
            <groupId>org.graalvm.buildtools</groupId>
            <artifactId>native-maven-plugin</artifactId>
            <executions>
              <execution>
                <id>build-native</id>
                <goals>
                  <goal>compile-no-fork</goal>
                </goals>
                <phase>package</phase>
              </execution>
            </executions>
          </plugin>
        </plugins>
      </build>
    </profile>
  </profiles>
```

Note: `native-maven-plugin` version is managed in the parent `pom.xml` via `${native-build-tools.version}` — no version needed here.

- [ ] **Step 2: Create ConversationContextRuntimeHints**

Create `emcip-conversation-context/src/main/java/io/emcip/conversation/context/config/ConversationContextRuntimeHints.java`:

```java
package io.emcip.conversation.context.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * GraalVM native image hints for emcip-conversation-context.
 *
 * <p>Spring Boot AOT handles entity reflection, JPA repository proxies, and Kafka listener wiring.
 * We only need to register resources that Spring Boot's auto-configuration would normally register
 * but cannot because spring.liquibase.enabled=false (our custom LiquibaseConfig is used instead).
 */
public class ConversationContextRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("db/changelog/**");
    }
}
```

- [ ] **Step 3: Annotate the main class**

Open `emcip-conversation-context/src/main/java/io/emcip/conversation/context/ConversationContextApplication.java`.

Add the import and annotation:

```java
package io.emcip.conversation.context;

import io.emcip.conversation.context.config.ConversationContextRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "io.emcip.conversation.context.repository")
@ImportRuntimeHints(ConversationContextRuntimeHints.class)
public class ConversationContextApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConversationContextApplication.class, args);
    }
}
```

- [ ] **Step 4: Run Spotless**

```bash
mvn spotless:apply -pl emcip-conversation-context
```

Expected output: `0 were changed to be clean` or `N were already clean`.

- [ ] **Step 5: Build the native image**

```bash
mvn -Pnative package -pl emcip-conversation-context -am -DskipTests -q
```

This takes 3-10 minutes. Expected outcome: `BUILD SUCCESS` and a binary at `emcip-conversation-context/target/emcip-conversation-context`.

If build fails with a reflection error like `Class not found` or `Method not found`, add the missing class to the `RuntimeHints`:
```java
hints.reflection().registerType(MissingClass.class,
    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
    MemberCategory.INVOKE_DECLARED_METHODS);
```

- [ ] **Step 6: Smoke-test the native binary**

Start infrastructure if not already running:
```bash
docker compose up -d postgres kafka zookeeper
```

Then run the native binary:
```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:14005/emcip \
SPRING_DATASOURCE_USERNAME=emcip \
SPRING_DATASOURCE_PASSWORD=emcip \
KAFKA_BOOTSTRAP_SERVERS=localhost:14003 \
./emcip-conversation-context/target/emcip-conversation-context
```

Expected: service starts in under 1 second and health check returns 200:
```bash
curl -s http://localhost:9081/actuator/health | grep -q '"status":"UP"' && echo "OK"
```

Stop with Ctrl+C.

- [ ] **Step 7: Create Dockerfile.native**

Create `emcip-conversation-context/Dockerfile.native`:

```dockerfile
# Multi-stage native image build for emcip-conversation-context
# Build context must be the project root (context: .)

# Stage 1: Native image build
FROM ghcr.io/graalvm/native-image-community:21 AS builder
WORKDIR /build

RUN microdnf install -y maven findutils && microdnf clean all

COPY pom.xml ./pom.xml
COPY emcip-core/pom.xml ./emcip-core/pom.xml
COPY emcip-core/src ./emcip-core/src
COPY emcip-tdlib-adapter/pom.xml ./emcip-tdlib-adapter/pom.xml
COPY emcip-conversation-context/pom.xml ./emcip-conversation-context/pom.xml
COPY emcip-intent-classifier/pom.xml ./emcip-intent-classifier/pom.xml
COPY emcip-policy-engine/pom.xml ./emcip-policy-engine/pom.xml
COPY emcip-llm-orchestrator/pom.xml ./emcip-llm-orchestrator/pom.xml
COPY emcip-moderation-service/pom.xml ./emcip-moderation-service/pom.xml
COPY emcip-audit-service/pom.xml ./emcip-audit-service/pom.xml
COPY emcip-admin-api/pom.xml ./emcip-admin-api/pom.xml
COPY emcip-admin-ui/pom.xml ./emcip-admin-ui/pom.xml
COPY gatling-tests/pom.xml ./gatling-tests/pom.xml

RUN mvn install -N -q && \
    mvn install -pl emcip-core -DskipTests -q

COPY emcip-conversation-context/src ./emcip-conversation-context/src

RUN mvn -Pnative package -DskipTests -q -pl emcip-conversation-context -am

# Stage 2: Minimal runtime
FROM debian:12-slim
WORKDIR /app

RUN apt-get update && apt-get install -y curl --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r emcip && useradd -r -g emcip emcip

COPY --from=builder /build/emcip-conversation-context/target/emcip-conversation-context app

RUN chown -R emcip:emcip /app
USER emcip

EXPOSE 9081

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:9081/actuator/health || exit 1

ENTRYPOINT ["./app"]
```

- [ ] **Step 8: Commit**

```bash
mvn spotless:apply -pl emcip-conversation-context
git add emcip-conversation-context/pom.xml \
        emcip-conversation-context/src/main/java/io/emcip/conversation/context/config/ConversationContextRuntimeHints.java \
        emcip-conversation-context/src/main/java/io/emcip/conversation/context/ConversationContextApplication.java \
        emcip-conversation-context/Dockerfile.native
git commit -m "feat(graalvm): migrate emcip-conversation-context to native image"
```

---

## Task 2: Migrate emcip-llm-orchestrator

**Files:**
- Modify: `emcip-llm-orchestrator/pom.xml`
- Create: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/LlmOrchestratorRuntimeHints.java`
- Modify: `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/LlmOrchestratorApplication.java`
- Create: `emcip-llm-orchestrator/Dockerfile.native`

- [ ] **Step 1: Add native Maven profile to pom.xml**

Open `emcip-llm-orchestrator/pom.xml`. Insert the same `<profiles>` block as in Task 1, Step 1, before `</project>`.

```xml
  <profiles>
    <profile>
      <id>native</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <executions>
              <execution>
                <id>process-aot</id>
                <goals>
                  <goal>process-aot</goal>
                </goals>
              </execution>
            </executions>
          </plugin>
          <plugin>
            <groupId>org.graalvm.buildtools</groupId>
            <artifactId>native-maven-plugin</artifactId>
            <executions>
              <execution>
                <id>build-native</id>
                <goals>
                  <goal>compile-no-fork</goal>
                </goals>
                <phase>package</phase>
              </execution>
            </executions>
          </plugin>
        </plugins>
      </build>
    </profile>
  </profiles>
```

- [ ] **Step 2: Create LlmOrchestratorRuntimeHints**

Create `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/LlmOrchestratorRuntimeHints.java`:

```java
package io.emcip.llm.orchestrator.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * GraalVM native image hints for emcip-llm-orchestrator.
 *
 * <p>Spring Boot AOT handles entity reflection, JPA repository proxies, and Kafka listener wiring.
 * We only need to register resources that Spring Boot's auto-configuration would normally register
 * but cannot because spring.liquibase.enabled=false (our custom LiquibaseConfig is used instead).
 */
public class LlmOrchestratorRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("db/changelog/**");
    }
}
```

- [ ] **Step 3: Annotate the main class**

Open `emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/LlmOrchestratorApplication.java`.

Replace the existing class content with:

```java
package io.emcip.llm.orchestrator;

import io.emcip.llm.orchestrator.config.LlmOrchestratorRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories
@ImportRuntimeHints(LlmOrchestratorRuntimeHints.class)
public class LlmOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmOrchestratorApplication.class, args);
    }
}
```

- [ ] **Step 4: Run Spotless**

```bash
mvn spotless:apply -pl emcip-llm-orchestrator
```

Expected: `0 were changed to be clean` or `N were already clean`.

- [ ] **Step 5: Build the native image**

```bash
mvn -Pnative package -pl emcip-llm-orchestrator -am -DskipTests -q
```

Expected: `BUILD SUCCESS` and binary at `emcip-llm-orchestrator/target/emcip-llm-orchestrator`.

If build fails with reflection errors related to `OkHttp` or `MockWebServer` (test dependency), check that `mockwebserver` is scoped `<scope>test</scope>` in pom.xml — it should not appear in the native compile classpath.

- [ ] **Step 6: Smoke-test the native binary**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:14005/emcip \
SPRING_DATASOURCE_USERNAME=emcip \
SPRING_DATASOURCE_PASSWORD=emcip \
KAFKA_BOOTSTRAP_SERVERS=localhost:14003 \
ANTHROPIC_API_KEY=test-key-not-used-for-health \
./emcip-llm-orchestrator/target/emcip-llm-orchestrator
```

Expected: starts in under 1 second, health check returns 200:
```bash
curl -s http://localhost:9084/actuator/health | grep -q '"status":"UP"' && echo "OK"
```

Stop with Ctrl+C.

- [ ] **Step 7: Create Dockerfile.native**

Create `emcip-llm-orchestrator/Dockerfile.native`:

```dockerfile
# Multi-stage native image build for emcip-llm-orchestrator
# Build context must be the project root (context: .)

# Stage 1: Native image build
FROM ghcr.io/graalvm/native-image-community:21 AS builder
WORKDIR /build

RUN microdnf install -y maven findutils && microdnf clean all

COPY pom.xml ./pom.xml
COPY emcip-core/pom.xml ./emcip-core/pom.xml
COPY emcip-core/src ./emcip-core/src
COPY emcip-tdlib-adapter/pom.xml ./emcip-tdlib-adapter/pom.xml
COPY emcip-conversation-context/pom.xml ./emcip-conversation-context/pom.xml
COPY emcip-intent-classifier/pom.xml ./emcip-intent-classifier/pom.xml
COPY emcip-policy-engine/pom.xml ./emcip-policy-engine/pom.xml
COPY emcip-llm-orchestrator/pom.xml ./emcip-llm-orchestrator/pom.xml
COPY emcip-moderation-service/pom.xml ./emcip-moderation-service/pom.xml
COPY emcip-audit-service/pom.xml ./emcip-audit-service/pom.xml
COPY emcip-admin-api/pom.xml ./emcip-admin-api/pom.xml
COPY emcip-admin-ui/pom.xml ./emcip-admin-ui/pom.xml
COPY gatling-tests/pom.xml ./gatling-tests/pom.xml

RUN mvn install -N -q && \
    mvn install -pl emcip-core -DskipTests -q

COPY emcip-llm-orchestrator/src ./emcip-llm-orchestrator/src

RUN mvn -Pnative package -DskipTests -q -pl emcip-llm-orchestrator -am

# Stage 2: Minimal runtime
FROM debian:12-slim
WORKDIR /app

RUN apt-get update && apt-get install -y curl --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r emcip && useradd -r -g emcip emcip

COPY --from=builder /build/emcip-llm-orchestrator/target/emcip-llm-orchestrator app

RUN chown -R emcip:emcip /app
USER emcip

EXPOSE 9084

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:9084/actuator/health || exit 1

ENTRYPOINT ["./app"]
```

- [ ] **Step 8: Commit**

```bash
mvn spotless:apply -pl emcip-llm-orchestrator
git add emcip-llm-orchestrator/pom.xml \
        emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/config/LlmOrchestratorRuntimeHints.java \
        emcip-llm-orchestrator/src/main/java/io/emcip/llm/orchestrator/LlmOrchestratorApplication.java \
        emcip-llm-orchestrator/Dockerfile.native
git commit -m "feat(graalvm): migrate emcip-llm-orchestrator to native image"
```

---

## Task 3: Developer Setup Docs — Ubuntu LTS

**Files:**
- Modify: `documentation/developer-guide.adoc`

- [ ] **Step 1: Find the right insertion point**

Open `documentation/developer-guide.adoc`. Find the `== Quick Start` section. The new GraalVM section goes **before** Quick Start, or as a subsection of it. Alternatively, search for any existing `Prerequisites` line:

```bash
grep -n "Prerequisites\|Quick Start\|== Development" documentation/developer-guide.adoc | head -10
```

Insert the new section **after** the `== Quick Start` section, before `== Module Structure`, so it reads as an optional step for developers who need native image builds.

- [ ] **Step 2: Add the GraalVM setup section**

Insert the following block at the identified location in `documentation/developer-guide.adoc`:

```asciidoc
== GraalVM Native Image Setup

Standard development (`mvn install`, `mvn test`, `mvn spring-boot:run`) works with any Java 21 JDK.
GraalVM is only required when building native binaries via `mvn -Pnative package`.

=== Ubuntu 24.04 LTS (Noble)

[source,bash]
----
# Install GraalVM JDK via snap (official Ubuntu channel)
sudo snap install graalvm-jdk

# Add to ~/.bashrc or ~/.profile
export JAVA_HOME=/snap/graalvm-jdk/current
export PATH=$JAVA_HOME/bin:$PATH

# Reload
source ~/.bashrc

# Verify
java -version        # should show GraalVM CE 21
native-image --version
----

=== Ubuntu 22.04 LTS (Jammy)

Snap is unavailable for GraalVM on 22.04. Use BellSoft Liberica NIK (actively maintained, fully open-source).

[source,bash]
----
# 1. Download Liberica NIK 21 .deb from:
#    https://bell-sw.com/pages/downloads/native-image-kit/
#    Select: NIK 21, Linux, x86_64, .deb

# 2. Verify SHA256 checksum shown on the download page
sha256sum liberica-nik-21-full-<version>-linux-amd64.deb

# 3. Install
sudo dpkg -i liberica-nik-21-full-<version>-linux-amd64.deb

# 4. Add to ~/.bashrc or ~/.profile
export JAVA_HOME=/usr/lib/jvm/liberica-nik-21-full-amd64
export PATH=$JAVA_HOME/bin:$PATH

# Reload
source ~/.bashrc

# Verify
java -version        # should show Liberica NIK 21
native-image --version
----

=== Building a Native Image

[source,bash]
----
# Example: build policy-engine native binary (from project root)
mvn -Pnative package -pl emcip-policy-engine -am -DskipTests

# Binary produced at:
./emcip-policy-engine/target/emcip-policy-engine
----

Services with native image support: `emcip-policy-engine`, `emcip-conversation-context`, `emcip-llm-orchestrator`.

Services explicitly excluded: `emcip-tdlib-adapter` (C native library), `emcip-admin-ui` (React SPA).
R2DBC services (`emcip-moderation-service`, `emcip-audit-service`, `emcip-admin-api`, `emcip-intent-classifier`) are deferred — see ADR-0007.
```

- [ ] **Step 3: Commit**

```bash
git add documentation/developer-guide.adoc
git commit -m "docs(graalvm): add Ubuntu LTS developer setup for native image builds"
```

---

## Task 4: ADR — R2DBC Services Native Image Deferral

**Files:**
- Create: `documentation/adrs/0007-r2dbc-graalvm-deferral.adoc`

- [ ] **Step 1: Check the existing ADR numbering**

```bash
ls documentation/adrs/
```

Verify the next ADR number. If `0006-*.adoc` is the highest, use `0007`. Adjust filename if needed.

- [ ] **Step 2: Create the ADR**

Create `documentation/adrs/0007-r2dbc-graalvm-deferral.adoc`:

```asciidoc
= ADR-0007: Defer GraalVM Native Image Migration for R2DBC Services
:date: 2026-04-29
:status: Accepted

== Context

Four EMCIP services use R2DBC for reactive database access: `emcip-moderation-service`,
`emcip-audit-service`, `emcip-admin-api`, and `emcip-intent-classifier`.
The project is migrating eligible services to GraalVM native image to reduce startup time
and memory footprint in Kubernetes environments.

== Decision

R2DBC services are excluded from the current native image migration.
Three JPA-based services (`emcip-policy-engine`, `emcip-conversation-context`,
`emcip-llm-orchestrator`) are migrated instead.

== Rationale

`r2dbc-postgresql` relies on Netty's dynamic channel handler and codec registries.
Configuring these for GraalVM native image requires manual `RuntimeHints` for:

* Netty channel handler classes (`io.netty.channel.*`)
* PostgreSQL wire protocol codec classes
* Netty buffer allocator internals

`emcip-admin-api` additionally uses Spring Security (proxy generation at runtime)
and JJWT (Jackson-based reflection for JWT serialization), both of which require
further investigation to hint correctly.

This work is disproportionate to the benefit at this stage of the project.
The R2DBC services remain JVM-based and continue to function correctly.

== Consequences

* R2DBC services run as JVM containers in Kubernetes (larger memory footprint, ~3-5s startup vs <1s native).
* A dedicated spike is needed before migrating R2DBC services: prototype one service
  (recommend `emcip-intent-classifier` as simplest), document the required hints,
  then apply the pattern to the remaining three.
* `emcip-tdlib-adapter` is permanently excluded (links against `libtdjni.so` C shared library).
```

- [ ] **Step 3: Commit**

```bash
git add documentation/adrs/0007-r2dbc-graalvm-deferral.adoc
git commit -m "docs(graalvm): ADR-0007 — defer R2DBC services native image migration"
```

---

## Task 5: Final Verification and PR

- [ ] **Step 1: Run full JVM build to confirm nothing is broken**

```bash
mvn clean install -DskipTests -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Run Spotless check across all changed modules**

```bash
mvn spotless:check -pl emcip-conversation-context,emcip-llm-orchestrator
```

Expected: `0 were changed to be clean` (or all already clean).

- [ ] **Step 3: Verify all three native services build**

```bash
mvn -Pnative package -pl emcip-policy-engine -am -DskipTests -q && echo "policy-engine OK"
mvn -Pnative package -pl emcip-conversation-context -am -DskipTests -q && echo "conversation-context OK"
mvn -Pnative package -pl emcip-llm-orchestrator -am -DskipTests -q && echo "llm-orchestrator OK"
```

All three should print `OK`.

- [ ] **Step 4: Push and open PR**

```bash
git push -u origin feature/graalvm-remaining-services
gh pr create \
  --title "feat(graalvm): migrate conversation-context and llm-orchestrator to native image" \
  --body "Migrates remaining JPA services to GraalVM native image following the policy-engine reference pattern. Adds Ubuntu LTS developer setup docs and ADR-0007 deferring R2DBC services. Closes #<issue> if applicable."
```
