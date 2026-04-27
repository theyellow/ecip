# Multi-stage build for emcip-tdlib-adapter
#
# Stage 1: Build TDLib from source with JNI bindings.
#   Produces libtdjni.so (native library) and the real Java source files
#   TdApi.java + Client.java that replace the compile-time stubs.
#   Build time: 15-30 min on first run; fully cached afterwards.
#
# Stage 2: Build the Spring Boot application JAR.
#   Replaces stub org.drinkless.tdlib classes with the real TDLib sources.
#
# Stage 3: Minimal JRE runtime.
#   Contains only the JAR and libtdjni.so.

# --- Stage 1: Build TDLib native library ---
FROM eclipse-temurin:21-jdk AS tdlib-builder
RUN apt-get update && apt-get install -y \
    git \
    cmake \
    make \
    g++ \
    zlib1g-dev \
    libssl-dev \
    gperf \
    --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

# Clone latest TDLib master.
# Note: TDLib does not publish versioned tags beyond v1.8.0.
# The master branch is the stable release branch.
RUN git clone --depth 1 https://github.com/tdlib/td /td

# Step 1: Build and install TDLib core with JNI support.
# IMPORTANT: install prefix must be example/java/td — the example/java cmake
# project hardcodes this relative path. This matches the TDLib README exactly:
#   cmake -DCMAKE_INSTALL_PREFIX:PATH=../example/java/td ..
RUN cd /td && mkdir build && cd build && \
    cmake -DCMAKE_BUILD_TYPE=Release \
          -DTD_ENABLE_JNI=ON \
          -DJAVA_HOME=/opt/java/openjdk \
          -DCMAKE_INSTALL_PREFIX=/td/example/java/td \
          .. && \
    cmake --build . --target install -- -j$(nproc)

# Step 2: Build the JNI shared library.
# The example/java cmake project finds TDLib at the ../td/ path set above.
RUN cd /td/example/java && mkdir build && cd build && \
    cmake -DCMAKE_BUILD_TYPE=Release \
          -DJAVA_HOME=/opt/java/openjdk \
          -DTd_DIR=/td/example/java/td/lib/cmake/Td \
          .. && \
    cmake --build . -- -j$(nproc)

# Collect the two Java source files needed to replace the compile-time stubs.
#
# Client.java:  hand-written JNI wrapper, taken directly from the TDLib source tree.
#
# TdApi.java:   generated from the TL schema using the installed td_generate_java_api
#               tool. The cmake example/java build puts the file in a nested
#               org/drinkless/tdlib/ subdirectory inside the build tree, making it
#               hard to locate reliably. Running the generator directly is simpler.
#               Generator args: <language> <tlo_file> <scheme_dir> <java_package>
#
# libtdjni.so:  built by the example/java cmake, found via find.
RUN mkdir -p /td-java /tmp/tdapi-gen && \
    cp /td/example/java/org/drinkless/tdlib/Client.java /td-java/ && \
    mkdir -p /tmp/tdapi-gen/org/drinkless/tdlib && \
    /td/example/java/td/bin/td_generate_java_api TdApi \
        /td/example/java/td/bin/td/generate/scheme/td_api.tlo \
        /tmp/tdapi-gen \
        org/drinkless/tdlib && \
    cp /tmp/tdapi-gen/org/drinkless/tdlib/TdApi.java /td-java/ && \
    echo "=== Java files ===" && ls -la /td-java/ && \
    TDJNI=$(find /td/example/java/build -name "libtdjni.so" | head -1) && \
    echo "Found libtdjni.so at: $TDJNI" && \
    cp "$TDJNI" /td/libtdjni.so && \
    ls -la /td/libtdjni.so

# --- Stage 2: Build Spring Boot application ---
FROM eclipse-temurin:21-jdk AS app-builder

# Mirror the multi-module project layout that Maven expects:
#   /build/pom.xml                    ← root parent POM (relativePath default: ../pom.xml)
#   /build/emcip-tdlib-adapter/pom.xml ← module POM
#   /build/emcip-tdlib-adapter/src/    ← module sources
WORKDIR /build

RUN apt-get update && apt-get install -y maven --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

# Build context is the project root (set in docker-compose).
# Maven reads the root pom.xml <modules> section and requires every listed module
# directory to exist — even for -pl emcip-tdlib-adapter -am.
# Solution: copy all sibling pom.xml files (no src needed) to satisfy validation,
# plus emcip-core/src because -am will compile it as a transitive dependency.
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

# Install local artifacts into the Maven repo so emcip-tdlib-adapter can depend on them.
# -N installs the root parent POM without recursing into child modules.
# emcip-core must be installed before emcip-tdlib-adapter can compile against it.
# This layer is cached until any pom.xml changes.
RUN mvn install -N -q && \
    mvn install -pl emcip-core -DskipTests -q

# Copy module source
COPY emcip-tdlib-adapter/src ./emcip-tdlib-adapter/src

# Replace compile-time stubs with the real TDLib Java bindings
COPY --from=tdlib-builder /td-java/ ./emcip-tdlib-adapter/src/main/java/org/drinkless/tdlib/

RUN mvn clean package -DskipTests -q -pl emcip-tdlib-adapter -am

# --- Stage 3: Runtime ---
FROM eclipse-temurin:21-jre

# Runtime dependencies: curl (health check), shared libs required by libtdjni.so
RUN apt-get update && apt-get install -y \
    curl \
    libssl3 \
    zlib1g \
    --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

# Install native library and update linker cache
COPY --from=tdlib-builder /td/libtdjni.so /usr/local/lib/libtdjni.so
RUN ldconfig

WORKDIR /app

RUN groupadd -r emcip && useradd -r -g emcip emcip

COPY --from=app-builder /build/emcip-tdlib-adapter/target/*-exec.jar app.jar

RUN mkdir -p /app/tdlib-db /app/tdlib-files && chown -R emcip:emcip /app

USER emcip

EXPOSE 9080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -sf http://localhost:9080/actuator/health || exit 1

ENTRYPOINT ["java", "-Djava.library.path=/usr/local/lib", "-jar", "app.jar"]
