# GraalVM Native Image Migration Design

**Date:** 2026-04-29
**Status:** Approved
**Scope:** Migrate eligible JPA services to GraalVM native image; document R2DBC services as deferred; add Ubuntu LTS developer setup guide

---

## Context

`emcip-policy-engine` is already GraalVM native-capable (Maven `native` profile, `PolicyEngineRuntimeHints`, `native-maven-plugin` 0.10.6). This migration extends native image support to the remaining eligible services using policy-engine as the reference implementation.

Standard `mvn install` / `mvn test` are unaffected and continue to work with any Java 21 JDK. The `native` profile is opt-in and only required for native binary builds.

---

## Service Classification

### Migrate (JPA — straightforward)

| Service | Stack | Notes |
|---|---|---|
| `emcip-conversation-context` | JPA + WebFlux | Same pattern as policy-engine |
| `emcip-llm-orchestrator` | JPA + Spring Web | Uses `MockWebServer` in tests; AOT safe |

### Deferred (R2DBC — investigation required)

| Service | Reason |
|---|---|
| `emcip-moderation-service` | R2DBC: netty channel handlers, codec registries need manual native hints |
| `emcip-audit-service` | Same as above |
| `emcip-admin-api` | R2DBC + JJWT reflection + Security proxies — significant hint surface |
| `emcip-intent-classifier` | R2DBC — same netty/codec issue |

A short ADR (`docs/adrs/`) documents this deferral with the technical rationale. R2DBC + GraalVM is viable but requires significant `RuntimeHints` work per service; deferred to a dedicated follow-up.

### Out of scope (by design)

| Service | Reason |
|---|---|
| `emcip-tdlib-adapter` | Links against TDLib C shared library (`libtdjni.so`) — incompatible with native image |
| `emcip-admin-ui` | Spring Boot wrapper serving a React SPA — no benefit from native image |

---

## Migration Pattern (per service)

`emcip-policy-engine` is the reference. Each JPA service migration follows these steps:

### 1. `pom.xml` — add `native` profile

Copy the native profile from `emcip-policy-engine/pom.xml`:

```xml
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
            <goals><goal>process-aot</goal></goals>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.graalvm.buildtools</groupId>
        <artifactId>native-maven-plugin</artifactId>
        <executions>
          <execution>
            <id>build-native</id>
            <goals><goal>compile-no-fork</goal></goals>
            <phase>package</phase>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</profile>
```

### 2. `RuntimeHints` class

Create `src/main/java/io/emcip/<module>/config/<Service>RuntimeHints.java`:

```java
public class <Service>RuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("db/changelog/**");
    }
}
```

Spring Boot AOT handles entity reflection, JPA repository proxies, and Kafka listener wiring automatically. Manual hints are only needed for Liquibase changelog resources (because `spring.liquibase.enabled=false` is used alongside a custom `LiquibaseConfig`).

### 3. Main application class annotation

```java
@SpringBootApplication
@ImportRuntimeHints(<Service>RuntimeHints.class)
public class <Service>Application { ... }
```

### 4. Build verification

```bash
mvn -Pnative package -pl emcip-<service> -am -DskipTests
./emcip-<service>/target/emcip-<service>  # smoke test: should start and reach /actuator/health
```

Fix any `ReflectionException` or missing resource errors by adding entries to the `RuntimeHints` registrar.

### 5. `Dockerfile.native` (new file per service)

Each migrated service gets a `Dockerfile.native` alongside the existing JVM `Dockerfile`:

```dockerfile
# Stage 1: Native image build
FROM ghcr.io/graalvm/native-image-community:21 AS builder
WORKDIR /build

RUN microdnf install -y maven && microdnf clean all

COPY pom.xml ./pom.xml
COPY emcip-core/pom.xml ./emcip-core/pom.xml
COPY emcip-core/src ./emcip-core/src
# ... (same pom.xml COPY pattern as existing Dockerfile)
COPY emcip-<service>/pom.xml ./emcip-<service>/pom.xml

RUN mvn install -N -q && \
    mvn install -pl emcip-core -DskipTests -q

COPY emcip-<service>/src ./emcip-<service>/src

RUN mvn -Pnative package -DskipTests -q -pl emcip-<service> -am

# Stage 2: Minimal runtime
FROM debian:12-slim
WORKDIR /app

RUN apt-get update && apt-get install -y curl --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r emcip && useradd -r -g emcip emcip

COPY --from=builder /build/emcip-<service>/target/emcip-<service> app

RUN chown -R emcip:emcip /app
USER emcip

EXPOSE <port>

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:<port>/actuator/health || exit 1

ENTRYPOINT ["./app"]
```

Note the significantly faster startup and reduced `start-period` (10s vs 60s for JVM).

---

## Developer Setup — Ubuntu LTS

New section added to `documentation/developer-guide.adoc`.

Standard development (`mvn install`, `mvn test`, `mvn spring-boot:run`) works with any Java 21 JDK. GraalVM is only required to build native images via `mvn -Pnative package`.

### Ubuntu 24.04 LTS (Noble)

```bash
# Install GraalVM JDK via snap (official Ubuntu channel)
sudo snap install graalvm-jdk

# Set JAVA_HOME (add to ~/.bashrc or ~/.profile)
export JAVA_HOME=/snap/graalvm-jdk/current
export PATH=$JAVA_HOME/bin:$PATH

# Verify
java -version        # should show GraalVM CE 21
native-image --version
```

### Ubuntu 22.04 LTS (Jammy)

Snap is not available for GraalVM on 22.04. Use the official `.deb` from BellSoft Liberica NIK (actively maintained, fully open-source):

```bash
# 1. Download Liberica NIK 21 .deb from https://bell-sw.com/pages/downloads/native-image-kit/
#    Select: NIK 21, Linux, x86_64, .deb package

# 2. Verify the SHA256 checksum shown on the download page
sha256sum liberica-nik-21-full-<version>-linux-amd64.deb

# 3. Install
sudo dpkg -i liberica-nik-21-full-<version>-linux-amd64.deb

# 4. Set JAVA_HOME (add to ~/.bashrc or ~/.profile)
export JAVA_HOME=/usr/lib/jvm/liberica-nik-21-full-amd64
export PATH=$JAVA_HOME/bin:$PATH

# Verify
java -version        # should show Liberica NIK 21
native-image --version
```

### Build a native image

```bash
# From project root, example for policy-engine:
mvn -Pnative package -pl emcip-policy-engine -am -DskipTests

# The native binary is at:
./emcip-policy-engine/target/emcip-policy-engine
```

Normal JVM builds are unaffected — `mvn install` continues to work with any Java 21.

---

## Git Strategy

Single branch `feature/graalvm-remaining-services`, commits:

1. `feat(graalvm): migrate emcip-conversation-context to native image`
2. `feat(graalvm): migrate emcip-llm-orchestrator to native image`
3. `docs(graalvm): add Ubuntu LTS developer setup for native image builds`
4. `docs(graalvm): ADR — defer R2DBC services native image migration`

One PR, merged to main after all native builds pass.

---

## ADR: R2DBC Services Native Image Deferral

To be written as `documentation/adrs/0007-r2dbc-graalvm-deferral.adoc`.

**Decision:** `emcip-moderation-service`, `emcip-audit-service`, `emcip-admin-api`, and `emcip-intent-classifier` are excluded from the current native image migration.

**Rationale:** These services use R2DBC (`r2dbc-postgresql`), which relies on Netty's dynamic channel handler and codec registries. Configuring these for GraalVM native image requires extensive manual `RuntimeHints` for Netty internals (`io.netty.channel.*`, codec classes, buffer allocators) and Spring Security proxy generation (for `admin-api`). This work is disproportionate to the benefit at this stage. JJWT in `admin-api` also requires reflection hints for its Jackson-based serialization.

**Consequence:** These services continue to run as JVM-based containers. Native image support is deferred to a dedicated investigation spike.

---

## Out of Scope

- Native image CI pipeline (separate concern)
- Performance benchmarking (separate concern)
- R2DBC native image investigation (separate epic)
