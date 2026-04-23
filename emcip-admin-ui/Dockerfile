# Multi-stage build for emcip-admin-ui
# Build context must be the project root (context: .)

# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
RUN apk add --no-cache maven

# Copy root POM and all module POMs so Maven can validate the <modules> list
COPY pom.xml ./pom.xml
COPY emcip-core/pom.xml ./emcip-core/pom.xml
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

# Install parent POM into local Maven repo (non-recursive)
RUN mvn install -N -DskipTests -q

# Copy module source and build
COPY emcip-admin-ui/src ./emcip-admin-ui/src
RUN mvn package -pl emcip-admin-ui -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create non-root user
RUN addgroup -S emcip && adduser -S -G emcip emcip

# Copy executable JAR from builder
COPY --from=builder /build/emcip-admin-ui/target/*-exec.jar app.jar

# Set ownership
RUN chown -R emcip:emcip /app

# Switch to non-root user
USER emcip

# Expose service port
EXPOSE 14009

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:14009/actuator/health || exit 1

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]
