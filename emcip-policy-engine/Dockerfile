# Multi-stage build for emcip-policy-engine
# Build context must be the project root (context: .)

# Stage 1: Build
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

RUN apt-get update && apt-get install -y maven --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

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
COPY emcip-knowledge-engine/pom.xml ./emcip-knowledge-engine/pom.xml
COPY gatling-tests/pom.xml ./gatling-tests/pom.xml

RUN mvn install -N -q && \
    mvn install -pl emcip-core -DskipTests -q

COPY emcip-policy-engine/src ./emcip-policy-engine/src

RUN mvn clean package -DskipTests -q -pl emcip-policy-engine -am

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update && apt-get install -y curl --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r emcip && useradd -r -g emcip emcip

COPY --from=builder /build/emcip-policy-engine/target/*-exec.jar app.jar

RUN chown -R emcip:emcip /app

USER emcip

EXPOSE 9083

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:9083/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
