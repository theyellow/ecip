# Multi-stage build for emcip-llm-orchestrator
#
# Build context must be the project root so Maven can resolve the parent POM.
# Usage (from project root):
#   docker compose build llm-orchestrator

# Stage 1: Build the Spring Boot JAR
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

RUN apt-get update && apt-get install -y maven --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

# Copy all module POMs first for dependency layer caching.
# Maven validates all <modules> in the root POM, so every sibling pom.xml is required.
COPY pom.xml ./pom.xml
COPY emcip-core/pom.xml ./emcip-core/pom.xml
COPY emcip-core/src ./emcip-core/src
COPY emcip-llm-orchestrator/pom.xml ./emcip-llm-orchestrator/pom.xml
COPY emcip-tdlib-adapter/pom.xml ./emcip-tdlib-adapter/pom.xml
COPY emcip-conversation-context/pom.xml ./emcip-conversation-context/pom.xml
COPY emcip-intent-classifier/pom.xml ./emcip-intent-classifier/pom.xml
COPY emcip-policy-engine/pom.xml ./emcip-policy-engine/pom.xml
COPY emcip-moderation-service/pom.xml ./emcip-moderation-service/pom.xml
COPY emcip-audit-service/pom.xml ./emcip-audit-service/pom.xml
COPY emcip-admin-api/pom.xml ./emcip-admin-api/pom.xml
COPY emcip-admin-ui/pom.xml ./emcip-admin-ui/pom.xml
COPY gatling-tests/pom.xml ./gatling-tests/pom.xml

# Install parent POM and emcip-core into local Maven repo
RUN mvn install -N -q && \
    mvn install -pl emcip-core -DskipTests -q

# Copy module source and build
COPY emcip-llm-orchestrator/src ./emcip-llm-orchestrator/src

RUN mvn clean package -DskipTests -q -pl emcip-llm-orchestrator -am

# Stage 2: Minimal JRE runtime
FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y curl --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN groupadd -r emcip && useradd -r -g emcip emcip

COPY --from=builder /build/emcip-llm-orchestrator/target/*-exec.jar app.jar

RUN chown -R emcip:emcip /app

USER emcip

EXPOSE 9084

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -sf http://localhost:9084/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
