# Multi-stage build for emcip-llm-orchestrator
# Stage 1: Build
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

# Create non-root user
RUN groupadd -r emcip && useradd -r -g emcip emcip

# Copy executable JAR from builder
COPY --from=builder /build/target/*-exec.jar app.jar

# Set ownership
RUN chown -R emcip:emcip /app

# Switch to non-root user
USER emcip

# Expose service port
EXPOSE 9084

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:9084/actuator/health || exit 1

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]
