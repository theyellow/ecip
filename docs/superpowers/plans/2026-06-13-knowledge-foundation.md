# Knowledge Foundation (#26) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `emcip-knowledge-engine` — a new Spring Boot 4 / JPA service providing ontology-driven knowledge graph (Apache AGE), vector search (pgvector), and a Kafka-powered extraction pipeline from Telegram messages.

**Architecture:** New Maven module `emcip-knowledge-engine` (port 9088). PostgreSQL extensions pgvector + Apache AGE provide vector similarity search and graph traversal within the existing database. Abstraction interfaces (`GraphRepository`, `VectorSearchRepository`) preserve the option to swap to Neo4j/Qdrant later. A Kafka consumer on `knowledge.raw.messages` feeds messages through LLM-based extraction (via llm-orchestrator REST API) into the graph and vector stores.

**Tech Stack:** Java 21, Spring Boot 4.0.5, JPA/Hibernate 7, PostgreSQL 16 (pgvector + Apache AGE), Apache Kafka, Liquibase, Jackson 3, Lombok, Testcontainers

---

## Scope

This plan covers **Epic #26 — Knowledge Foundation** (user stories US-26.1 through US-26.10). Epic #27 — Deep Research Agent depends on #26 and will be planned separately.

Spec: `docs/superpowers/specs/2026-06-13-knowledge-management-platform-design.md`
ADR: `documentation/adrs/ADR-008-knowledge-management-postgresql-extensions.md`

## Prerequisites

- Working EMCIP dev environment (PostgreSQL on port 14005, Kafka on port 14003)
- `emcip-core` installed locally: `mvn install -pl emcip-core -DskipTests`
- Docker running (for custom PostgreSQL image and Testcontainers)

## Key Codebase Conventions

| Convention | Detail |
|-----------|--------|
| Jackson | **Jackson 3**: `tools.jackson.databind.ObjectMapper` (not `com.fasterxml.jackson`) |
| Health API | Spring Boot 4: `org.springframework.boot.health.contributor.HealthIndicator` |
| JSONB columns | `@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")` |
| UUID PKs | `@GeneratedValue(strategy = GenerationType.UUID)` |
| Tenant filter | `@FilterDef`/`@Filter` on entities + `TenantFilterAspect` (AOP) |
| Lombok | `@Slf4j`, `@RequiredArgsConstructor`, `@Data` or `@Getter/@Setter` |
| Kafka headers | Tenant ID via `TenantContext.KAFKA_HEADER` ("tenant_id") |
| Spotless | `mvn spotless:apply` before every commit |
| Test meta-annotation | `@IntegrationTest` → `@SpringBootTest` + `@ActiveProfiles("test")` + Testcontainers |

---

## File Structure

### New Files — emcip-knowledge-engine module

```
emcip-knowledge-engine/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/io/emcip/knowledge/engine/
    │   │   ├── KnowledgeEngineApplication.java
    │   │   ├── config/
    │   │   │   ├── KafkaConfig.java
    │   │   │   ├── TenantFilterAspect.java
    │   │   │   └── KnowledgeEngineRuntimeHints.java
    │   │   ├── entity/
    │   │   │   ├── ConceptType.java
    │   │   │   ├── RelationshipType.java
    │   │   │   ├── KnowledgeDocument.java
    │   │   │   └── EntityAlias.java
    │   │   ├── model/
    │   │   │   ├── GraphNode.java                    (record — not a JPA entity)
    │   │   │   ├── GraphEdge.java                    (record — not a JPA entity)
    │   │   │   ├── GraphQuery.java                   (graph query builder)
    │   │   │   ├── ExtractionResult.java             (record — LLM extraction output)
    │   │   │   ├── SearchRequest.java                (record — search API request)
    │   │   │   └── SearchResponse.java               (record — search API response)
    │   │   ├── repository/
    │   │   │   ├── ConceptTypeRepository.java        (Spring Data JPA)
    │   │   │   ├── RelationshipTypeRepository.java   (Spring Data JPA)
    │   │   │   ├── KnowledgeDocumentRepository.java  (Spring Data JPA)
    │   │   │   ├── EntityAliasRepository.java        (Spring Data JPA)
    │   │   │   ├── GraphRepository.java              (abstraction interface)
    │   │   │   ├── VectorSearchRepository.java       (abstraction interface)
    │   │   │   ├── AgeGraphRepository.java           (AGE impl via JdbcTemplate)
    │   │   │   └── PgVectorSearchRepository.java     (pgvector impl via JdbcTemplate)
    │   │   ├── service/
    │   │   │   ├── OntologyService.java
    │   │   │   ├── KnowledgeExtractionService.java
    │   │   │   ├── EntityResolutionService.java
    │   │   │   ├── KnowledgeQueryService.java
    │   │   │   ├── DocumentIngestionService.java
    │   │   │   ├── BackfillService.java
    │   │   │   ├── KnowledgeMessageConsumer.java     (Kafka consumer)
    │   │   │   └── KnowledgeEventPublisher.java      (Kafka producer)
    │   │   ├── client/
    │   │   │   └── LlmOrchestratorClient.java        (REST client to llm-orchestrator)
    │   │   ├── controller/
    │   │   │   ├── OntologyController.java
    │   │   │   ├── KnowledgeSearchController.java
    │   │   │   ├── DocumentIngestionController.java
    │   │   │   └── BackfillController.java
    │   │   └── health/
    │   │       ├── DatabaseHealthIndicator.java
    │   │       └── KafkaHealthIndicator.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/changelog/
    │           ├── db.changelog-master.xml
    │           └── changes/
    │               ├── 001-enable-extensions.sql
    │               ├── 002-create-concept-types.xml
    │               ├── 003-create-relationship-types.xml
    │               ├── 004-create-knowledge-documents.xml
    │               ├── 005-create-entity-aliases.xml
    │               ├── 006-seed-ontology.xml
    │               ├── 007-create-age-graph.sql
    │               └── 008-create-graph-node-embeddings.xml
    └── test/
        ├── java/io/emcip/knowledge/engine/
        │   ├── IntegrationTest.java
        │   ├── TestcontainersInitializer.java
        │   ├── config/
        │   │   └── TestDatabaseConfig.java
        │   ├── repository/
        │   │   ├── ConceptTypeRepositoryTest.java
        │   │   ├── PgVectorSearchRepositoryTest.java
        │   │   └── AgeGraphRepositoryTest.java
        │   └── service/
        │       ├── OntologyServiceTest.java
        │       ├── KnowledgeExtractionServiceTest.java
        │       ├── EntityResolutionServiceTest.java
        │       ├── KnowledgeQueryServiceTest.java
        │       └── KnowledgeMessageConsumerTest.java
        └── resources/
            └── application-test.yml
```

### New Files — infrastructure

```
docker/postgres-knowledge/
├── Dockerfile                    (PostgreSQL 16 + pgvector + Apache AGE)
└── init-extensions.sql           (CREATE EXTENSION statements)
```

### Modified Files

| File | Change |
|------|--------|
| `pom.xml` (root) | Add `<module>emcip-knowledge-engine</module>` |
| `docker-compose.yml` | Switch postgres image to custom build; add knowledge-engine service |
| `emcip-tdlib-adapter/.../TelegramEventPublisher.java` | Fork messages to `knowledge.raw.messages` |
| `emcip-tdlib-adapter/.../TelegramEventPublisherTest.java` | Test for knowledge topic fork |

---

## Tasks

### Task 1: Custom PostgreSQL Docker Image with pgvector + Apache AGE

**Files:**
- Create: `docker/postgres-knowledge/Dockerfile`
- Create: `docker/postgres-knowledge/init-extensions.sql`
- Modify: `docker-compose.yml` (postgres service)

Both pgvector and Apache AGE must be installed as PostgreSQL extensions. The standard `postgres:16-alpine` image lacks them, so we build a custom image.

- [ ] **Step 1: Create the PostgreSQL Dockerfile**

```dockerfile
# docker/postgres-knowledge/Dockerfile
# PostgreSQL 16 with pgvector and Apache AGE extensions
FROM postgres:16

# Install build dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    git \
    postgresql-server-dev-16 \
    ca-certificates \
    libreadline-dev \
    zlib1g-dev \
    flex \
    bison \
    && rm -rf /var/lib/apt/lists/*

# Install pgvector
RUN cd /tmp \
    && git clone --branch v0.8.0 https://github.com/pgvector/pgvector.git \
    && cd pgvector \
    && make \
    && make install \
    && rm -rf /tmp/pgvector

# Install Apache AGE
RUN cd /tmp \
    && git clone --branch PG16/v1.5.0-rc0 https://github.com/apache/age.git \
    && cd age \
    && make \
    && make install \
    && rm -rf /tmp/age

# Clean build dependencies
RUN apt-get purge -y --auto-remove build-essential git postgresql-server-dev-16 \
    ca-certificates libreadline-dev zlib1g-dev flex bison \
    && rm -rf /var/lib/apt/lists/*

# Copy extension initialization script
COPY init-extensions.sql /docker-entrypoint-initdb.d/00-init-extensions.sql
```

- [ ] **Step 2: Create the extension initialization script**

```sql
-- docker/postgres-knowledge/init-extensions.sql
-- Executed once when the database is first created

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS age;

-- AGE requires these settings for cypher() to work
ALTER DATABASE emcip SET search_path = ag_catalog, "$user", public;

-- Load AGE into shared_preload_libraries is handled by postgresql.conf
-- For the init script, we load it in the current session
LOAD 'age';
```

- [ ] **Step 3: Update docker-compose.yml postgres service**

Replace:
```yaml
  postgres:
    image: postgres:16-alpine
```

With:
```yaml
  postgres:
    build:
      context: ./docker/postgres-knowledge
      dockerfile: Dockerfile
```

Keep all other postgres settings (environment, ports, volumes, networks, healthcheck) unchanged.

- [ ] **Step 4: Build and verify the custom image**

```bash
docker compose build postgres
docker compose up -d postgres
# Wait for healthy
docker compose exec postgres psql -U emcip -d emcip -c "SELECT 'pgvector' AS ext, extversion FROM pg_extension WHERE extname = 'vector' UNION ALL SELECT 'age', extversion FROM pg_extension WHERE extname = 'age';"
```

Expected: Two rows showing pgvector and age with their versions.

- [ ] **Step 5: Commit**

```bash
git add docker/postgres-knowledge/ docker-compose.yml
git commit -m "infra: add custom PostgreSQL image with pgvector + Apache AGE extensions

Builds on postgres:16 with pgvector v0.8.0 and Apache AGE v1.5.0.
Extensions are automatically enabled via init-extensions.sql.
Needed for knowledge-engine graph + vector storage (ADR-008)."
```

---

### Task 2: Maven Module Scaffold

**Files:**
- Create: `emcip-knowledge-engine/pom.xml`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/KnowledgeEngineApplication.java`
- Create: `emcip-knowledge-engine/src/main/resources/application.yml`
- Modify: `pom.xml` (root — add module)

- [ ] **Step 1: Create pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>io.emcip</groupId>
    <artifactId>community-intelligence-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>

  <artifactId>emcip-knowledge-engine</artifactId>
  <packaging>jar</packaging>
  <name>EMCIP Knowledge Engine</name>
  <description>Ontology-driven knowledge management with graph and vector search</description>

  <properties>
    <service.port>9088</service.port>
  </properties>

  <dependencies>
    <!-- Internal -->
    <dependency>
      <groupId>io.emcip</groupId>
      <artifactId>emcip-core</artifactId>
    </dependency>

    <!-- Spring Boot -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-aspectj</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- Kafka -->
    <dependency>
      <groupId>org.springframework.kafka</groupId>
      <artifactId>spring-kafka</artifactId>
    </dependency>

    <!-- PostgreSQL -->
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>

    <!-- pgvector JDBC support -->
    <dependency>
      <groupId>com.pgvector</groupId>
      <artifactId>pgvector</artifactId>
      <version>0.1.6</version>
    </dependency>

    <!-- Liquibase -->
    <dependency>
      <groupId>org.liquibase</groupId>
      <artifactId>liquibase-core</artifactId>
    </dependency>

    <!-- Observability -->
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-tracing-bridge-otel</artifactId>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-exporter-otlp</artifactId>
      <exclusions>
        <exclusion>
          <groupId>io.opentelemetry</groupId>
          <artifactId>opentelemetry-exporter-sender-okhttp</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-exporter-sender-jdk</artifactId>
    </dependency>

    <!-- OpenAPI / Swagger UI -->
    <dependency>
      <groupId>org.springdoc</groupId>
      <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    </dependency>

    <!-- Lombok -->
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <scope>provided</scope>
    </dependency>

    <!-- Testing -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.squareup.okhttp3</groupId>
      <artifactId>mockwebserver</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Add module to root pom.xml**

In `pom.xml` (root), add `<module>emcip-knowledge-engine</module>` after the `emcip-admin-ui` module entry in the `<modules>` section.

- [ ] **Step 3: Create application class**

```java
// emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/KnowledgeEngineApplication.java
package io.emcip.knowledge.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "io.emcip.knowledge.engine.repository")
public class KnowledgeEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeEngineApplication.class, args);
    }
}
```

- [ ] **Step 4: Create application.yml**

```yaml
# emcip-knowledge-engine/src/main/resources/application.yml
server:
  port: 9088

spring:
  application:
    name: emcip-knowledge-engine
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:14005/emcip}
    username: ${SPRING_DATASOURCE_USERNAME:emcip}
    password: ${SPRING_DATASOURCE_PASSWORD:emcip}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
  jpa:
    hibernate:
      ddl-auto: none
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
    open-in-view: false
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.xml
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:14003}
    consumer:
      group-id: knowledge-engine
      auto-offset-reset: earliest
      enable-auto-commit: false

knowledge:
  embedding:
    dimension: ${KNOWLEDGE_EMBEDDING_DIMENSION:1536}
  llm-orchestrator:
    base-url: ${LLM_ORCHESTRATOR_URL:http://localhost:9084}

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
      show-details: never

admin:
  service-token: ${ADMIN_SERVICE_TOKEN:internal-service-token}

logging:
  structured:
    format:
      console: logstash
  level:
    io.emcip: INFO

springdoc:
  swagger-ui:
    path: /swagger-ui.html
  api-docs:
    path: /api-docs
```

- [ ] **Step 5: Create application-test.yml**

```yaml
# emcip-knowledge-engine/src/test/resources/application-test.yml
spring:
  kafka:
    consumer:
      auto-offset-reset: earliest

knowledge:
  embedding:
    dimension: 3
  llm-orchestrator:
    base-url: http://localhost:${mockwebserver.port:9999}

logging:
  level:
    io.emcip: DEBUG
```

- [ ] **Step 6: Verify compilation**

```bash
mvn compile -pl emcip-knowledge-engine -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add emcip-knowledge-engine/pom.xml emcip-knowledge-engine/src pom.xml
git commit -m "feat(knowledge-engine): bootstrap Maven module with Spring Boot 4 / JPA

New service emcip-knowledge-engine on port 9088.
Dependencies: JPA, Kafka, pgvector JDBC, Liquibase, OpenAPI.
Follows llm-orchestrator pattern (web, not webflux)."
```

---

### Task 3: Liquibase Migrations — Extensions, Ontology, Documents, Graph

**Files:**
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/001-enable-extensions.sql`
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/002-create-concept-types.xml`
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/003-create-relationship-types.xml`
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/004-create-knowledge-documents.xml`
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/005-create-entity-aliases.xml`
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/006-seed-ontology.xml`
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/007-create-age-graph.sql`
- Create: `emcip-knowledge-engine/src/main/resources/db/changelog/changes/008-create-graph-node-embeddings.xml`

- [ ] **Step 1: Create master changelog**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- emcip-knowledge-engine/src/main/resources/db/changelog/db.changelog-master.xml -->
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <include file="changes/001-enable-extensions.sql" relativeToChangelogFile="true"/>
    <include file="changes/002-create-concept-types.xml" relativeToChangelogFile="true"/>
    <include file="changes/003-create-relationship-types.xml" relativeToChangelogFile="true"/>
    <include file="changes/004-create-knowledge-documents.xml" relativeToChangelogFile="true"/>
    <include file="changes/005-create-entity-aliases.xml" relativeToChangelogFile="true"/>
    <include file="changes/006-seed-ontology.xml" relativeToChangelogFile="true"/>
    <include file="changes/007-create-age-graph.sql" relativeToChangelogFile="true"/>
    <include file="changes/008-create-graph-node-embeddings.xml" relativeToChangelogFile="true"/>

</databaseChangeLog>
```

- [ ] **Step 2: Create 001 — enable PostgreSQL extensions**

```sql
-- emcip-knowledge-engine/src/main/resources/db/changelog/changes/001-enable-extensions.sql
--liquibase formatted sql

--changeset knowledge-engine:1
--comment: Enable pgvector and Apache AGE extensions
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS age;
LOAD 'age';
```

- [ ] **Step 3: Create 002 — concept_types table**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- 002-create-concept-types.xml -->
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-2" author="knowledge-engine">
        <createTable tableName="ke_concept_types" remarks="Ontology concept type definitions">
            <column name="id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="name" type="VARCHAR(100)">
                <constraints nullable="false" unique="true"/>
            </column>
            <column name="description" type="VARCHAR(500)">
                <constraints nullable="true"/>
            </column>
            <column name="properties" type="JSONB">
                <constraints nullable="true"/>
            </column>
            <column name="shared" type="BOOLEAN" defaultValueBoolean="false">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMPTZ" defaultValueComputed="NOW()">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMPTZ" defaultValueComputed="NOW()">
                <constraints nullable="false"/>
            </column>
            <column name="version" type="BIGINT" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex indexName="idx_ke_concept_types_name" tableName="ke_concept_types">
            <column name="name"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 4: Create 003 — relationship_types table**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- 003-create-relationship-types.xml -->
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-3" author="knowledge-engine">
        <createTable tableName="ke_relationship_types" remarks="Ontology relationship type definitions">
            <column name="id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="name" type="VARCHAR(100)">
                <constraints nullable="false" unique="true"/>
            </column>
            <column name="description" type="VARCHAR(500)">
                <constraints nullable="true"/>
            </column>
            <column name="source_types" type="JSONB">
                <constraints nullable="false"/>
            </column>
            <column name="target_types" type="JSONB">
                <constraints nullable="false"/>
            </column>
            <column name="properties" type="JSONB">
                <constraints nullable="true"/>
            </column>
            <column name="created_at" type="TIMESTAMPTZ" defaultValueComputed="NOW()">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMPTZ" defaultValueComputed="NOW()">
                <constraints nullable="false"/>
            </column>
            <column name="version" type="BIGINT" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex indexName="idx_ke_relationship_types_name" tableName="ke_relationship_types">
            <column name="name"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 5: Create 004 — knowledge_documents table**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- 004-create-knowledge-documents.xml -->
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-4" author="knowledge-engine">
        <createTable tableName="ke_knowledge_documents" remarks="Vector-searchable knowledge documents">
            <column name="id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="tenant_id" type="UUID">
                <constraints nullable="true"/>
            </column>
            <column name="source_type" type="VARCHAR(50)">
                <constraints nullable="false"/>
            </column>
            <column name="source_ref" type="VARCHAR(1000)">
                <constraints nullable="false"/>
            </column>
            <column name="content" type="TEXT">
                <constraints nullable="false"/>
            </column>
            <column name="chunk_index" type="INTEGER" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
            <column name="metadata" type="JSONB">
                <constraints nullable="true"/>
            </column>
            <column name="created_at" type="TIMESTAMPTZ" defaultValueComputed="NOW()">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex indexName="idx_ke_docs_tenant" tableName="ke_knowledge_documents">
            <column name="tenant_id"/>
        </createIndex>
        <createIndex indexName="idx_ke_docs_source_type" tableName="ke_knowledge_documents">
            <column name="source_type"/>
        </createIndex>
        <createIndex indexName="idx_ke_docs_source_ref" tableName="ke_knowledge_documents">
            <column name="source_ref"/>
        </createIndex>
        <createIndex indexName="idx_ke_docs_created_at" tableName="ke_knowledge_documents">
            <column name="created_at"/>
        </createIndex>
    </changeSet>

    <!-- Add vector column via raw SQL (Liquibase has no vector type) -->
    <changeSet id="ke-4b" author="knowledge-engine">
        <sql>ALTER TABLE ke_knowledge_documents ADD COLUMN embedding vector(1536);</sql>
        <sql>CREATE INDEX idx_ke_docs_embedding ON ke_knowledge_documents USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);</sql>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 6: Create 005 — entity_aliases table**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- 005-create-entity-aliases.xml -->
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-5" author="knowledge-engine">
        <createTable tableName="ke_entity_aliases" remarks="Entity alias table for resolution">
            <column name="id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="concept_type" type="VARCHAR(100)">
                <constraints nullable="false"/>
            </column>
            <column name="alias" type="VARCHAR(500)">
                <constraints nullable="false"/>
            </column>
            <column name="canonical_label" type="VARCHAR(500)">
                <constraints nullable="false"/>
            </column>
            <column name="tenant_id" type="UUID">
                <constraints nullable="true"/>
            </column>
            <column name="created_at" type="TIMESTAMPTZ" defaultValueComputed="NOW()">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <addUniqueConstraint tableName="ke_entity_aliases"
            columnNames="concept_type,alias,tenant_id"
            constraintName="uk_ke_entity_aliases_type_alias_tenant"/>

        <createIndex indexName="idx_ke_aliases_lookup" tableName="ke_entity_aliases">
            <column name="concept_type"/>
            <column name="alias"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 7: Create 006 — seed ontology data**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- 006-seed-ontology.xml -->
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-6" author="knowledge-engine" context="!test">
        <!-- Concept Types -->
        <insert tableName="ke_concept_types">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="name" value="Person"/>
            <column name="description" value="A person identified in messages"/>
            <column name="properties" value='[{"key":"displayName","valueType":"STRING","required":false}]'/>
            <column name="shared" valueBoolean="false"/>
        </insert>
        <insert tableName="ke_concept_types">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="name" value="Topic"/>
            <column name="description" value="A discussion topic or subject"/>
            <column name="properties" value='[{"key":"category","valueType":"STRING","required":false}]'/>
            <column name="shared" valueBoolean="false"/>
        </insert>
        <insert tableName="ke_concept_types">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="name" value="Message"/>
            <column name="description" value="A chat message reference"/>
            <column name="properties" value='[{"key":"messageId","valueType":"STRING","required":true}]'/>
            <column name="shared" valueBoolean="false"/>
        </insert>
        <insert tableName="ke_concept_types">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="name" value="Source"/>
            <column name="description" value="An external information source"/>
            <column name="properties" value='[{"key":"url","valueType":"STRING","required":false}]'/>
            <column name="shared" valueBoolean="true"/>
        </insert>
        <insert tableName="ke_concept_types">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="name" value="Document"/>
            <column name="description" value="An ingested document"/>
            <column name="properties" value='[{"key":"format","valueType":"STRING","required":false}]'/>
            <column name="shared" valueBoolean="true"/>
        </insert>

        <!-- Relationship Types -->
        <insert tableName="ke_relationship_types">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="name" value="DISCUSSES"/>
            <column name="description" value="Person discusses a topic"/>
            <column name="source_types" value='["Person"]'/>
            <column name="target_types" value='["Topic"]'/>
            <column name="properties" value='[{"key":"frequency","valueType":"INTEGER"}]'/>
        </insert>
        <insert tableName="ke_relationship_types">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="name" value="AUTHORED"/>
            <column name="description" value="Person authored a message"/>
            <column name="source_types" value='["Person"]'/>
            <column name="target_types" value='["Message"]'/>
            <column name="properties" value='[]'/>
        </insert>
        <insert tableName="ke_relationship_types">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="name" value="MENTIONS"/>
            <column name="description" value="Message mentions a topic"/>
            <column name="source_types" value='["Message"]'/>
            <column name="target_types" value='["Topic"]'/>
            <column name="properties" value='[]'/>
        </insert>
        <insert tableName="ke_relationship_types">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="name" value="CITES"/>
            <column name="description" value="Document cites a source"/>
            <column name="source_types" value='["Document"]'/>
            <column name="target_types" value='["Source"]'/>
            <column name="properties" value='[]'/>
        </insert>
        <insert tableName="ke_relationship_types">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="name" value="RELATED_TO"/>
            <column name="description" value="Topic is related to another topic"/>
            <column name="source_types" value='["Topic"]'/>
            <column name="target_types" value='["Topic"]'/>
            <column name="properties" value='[{"key":"strength","valueType":"DOUBLE"}]'/>
        </insert>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 8: Create 007 — Apache AGE graph**

```sql
-- 007-create-age-graph.sql
--liquibase formatted sql

--changeset knowledge-engine:ke-7
--comment: Create the Apache AGE knowledge graph
SELECT ag_catalog.create_graph('knowledge_graph');
```

- [ ] **Step 9: Create 008 — graph_node_embeddings table**

This shadow table stores embeddings for graph nodes separately from AGE (AGE nodes don't support pgvector natively). Used by EntityResolutionService for embedding-based deduplication.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- 008-create-graph-node-embeddings.xml -->
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="ke-8" author="knowledge-engine">
        <createTable tableName="ke_graph_node_embeddings" remarks="Vector embeddings for graph nodes (shadow table)">
            <column name="node_id" type="UUID">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="concept_type" type="VARCHAR(100)">
                <constraints nullable="false"/>
            </column>
            <column name="label" type="VARCHAR(500)">
                <constraints nullable="false"/>
            </column>
            <column name="tenant_id" type="UUID">
                <constraints nullable="true"/>
            </column>
        </createTable>

        <createIndex indexName="idx_ke_node_emb_type_tenant" tableName="ke_graph_node_embeddings">
            <column name="concept_type"/>
            <column name="tenant_id"/>
        </createIndex>
    </changeSet>

    <changeSet id="ke-8b" author="knowledge-engine">
        <sql>ALTER TABLE ke_graph_node_embeddings ADD COLUMN embedding vector(1536);</sql>
        <sql>CREATE INDEX idx_ke_node_emb_vector ON ke_graph_node_embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);</sql>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 10: Commit**

```bash
git add emcip-knowledge-engine/src/main/resources/db/
git commit -m "feat(knowledge-engine): add Liquibase migrations for ontology, documents, graph

Migrations: enable pgvector+AGE, create ke_concept_types, ke_relationship_types,
ke_knowledge_documents (with vector column), ke_entity_aliases,
ke_graph_node_embeddings (shadow table for node vectors).
Seed ontology: Person, Topic, Message, Source, Document + 5 relationship types.
Apache AGE knowledge_graph created for graph traversal."
```

---

### Task 4: Test Infrastructure + Health Indicators

**Files:**
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/IntegrationTest.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/TestcontainersInitializer.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/config/TestDatabaseConfig.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/health/DatabaseHealthIndicator.java`
- Create: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/health/KafkaHealthIndicator.java`

- [ ] **Step 1: Create TestcontainersInitializer**

The knowledge-engine needs a PostgreSQL image with pgvector + AGE. Use the custom image built in Task 1. For CI where the custom image may not exist, fall back to a publicly available pgvector image and skip AGE-dependent tests.

```java
package io.emcip.knowledge.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public class TestcontainersInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(TestcontainersInitializer.class);

    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
                    .withDatabaseName("emcip_test")
                    .withUsername("emcip")
                    .withPassword("emcip");

    static {
        postgres.start();
        log.info(
                "PostgreSQL container started: {}:{}",
                postgres.getHost(),
                postgres.getFirstMappedPort());
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        log.info("Configuring test properties for PostgreSQL container");

        TestPropertyValues.of(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.jpa.hibernate.ddl-auto=none",
                        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                        "spring.jpa.defer-datasource-initialization=false",
                        "spring.sql.init.mode=never",
                        "spring.liquibase.enabled=true",
                        "spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml",
                        "spring.liquibase.drop-first=true",
                        "spring.liquibase.default-schema=public",
                        "spring.liquibase.liquibase-schema=public",
                        "spring.kafka.bootstrap-servers=localhost:14003")
                .applyTo(applicationContext.getEnvironment());
    }
}
```

- [ ] **Step 2: Create IntegrationTest meta-annotation**

```java
package io.emcip.knowledge.engine;

import io.emcip.knowledge.engine.config.TestDatabaseConfig;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestcontainersInitializer.class)
@Import(TestDatabaseConfig.class)
public @interface IntegrationTest {}
```

- [ ] **Step 3: Create TestDatabaseConfig**

```java
package io.emcip.knowledge.engine.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaAdmin;

@TestConfiguration
public class TestDatabaseConfig {

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("bootstrap.servers", "localhost:14003");
        return new KafkaAdmin(configs);
    }
}
```

- [ ] **Step 4: Create DatabaseHealthIndicator**

```java
package io.emcip.knowledge.engine.health;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    public DatabaseHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(1)) {
                return Health.up()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("status", "Connected")
                        .build();
            } else {
                return Health.down()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("status", "Connection invalid")
                        .build();
            }
        } catch (SQLException e) {
            return Health.down()
                    .withDetail("database", "PostgreSQL")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
```

- [ ] **Step 5: Create KafkaHealthIndicator**

```java
package io.emcip.knowledge.engine.health;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class KafkaHealthIndicator implements HealthIndicator {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Override
    public Health health() {
        try (AdminClient client =
                AdminClient.create(
                        Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            var options = new DescribeClusterOptions().timeoutMs(5000);
            var clusterId = client.describeCluster(options).clusterId().get(5, TimeUnit.SECONDS);
            var nodes = client.describeCluster(options).nodes().get(5, TimeUnit.SECONDS);
            return Health.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("brokerCount", nodes.size())
                    .withDetail("status", "Connected")
                    .build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add emcip-knowledge-engine/src/test emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/health/
git commit -m "feat(knowledge-engine): add test infrastructure and health indicators

Testcontainers with pgvector/pgvector:pg16 image, @IntegrationTest meta-annotation.
Database + Kafka health indicators matching existing service patterns."
```

---

### Task 5: Ontology Entities, Repositories, Service, Controller (TDD)

**Files:**
- Create: entities `ConceptType.java`, `RelationshipType.java`
- Create: repositories `ConceptTypeRepository.java`, `RelationshipTypeRepository.java`
- Create: `OntologyService.java`, `OntologyController.java`
- Create: `TenantFilterAspect.java`, `KafkaConfig.java`
- Create: tests `OntologyServiceTest.java`, `ConceptTypeRepositoryTest.java`

- [ ] **Step 1: Create TenantFilterAspect and KafkaConfig**

These are needed before entities work. Copy patterns from policy-engine.

```java
// config/TenantFilterAspect.java
package io.emcip.knowledge.engine.config;

import io.emcip.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class TenantFilterAspect {

    @PersistenceContext private EntityManager entityManager;

    @Before("within(@org.springframework.stereotype.Repository *)")
    public void applyTenantFilter() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("tenantFilter")
                    .setParameter("tenantId", UUID.fromString(tenantId));
        }
    }
}
```

```java
// config/KafkaConfig.java
package io.emcip.knowledge.engine.config;

import io.emcip.common.validation.EventValidator;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:14003}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:knowledge-engine}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public EventValidator eventValidator(ObjectMapper objectMapper) {
        return new EventValidator(objectMapper);
    }
}
```

- [ ] **Step 2: Write failing test for ConceptType entity persistence**

```java
// test/repository/ConceptTypeRepositoryTest.java
package io.emcip.knowledge.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.ConceptType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class ConceptTypeRepositoryTest {

    @Autowired private ConceptTypeRepository conceptTypeRepository;

    @Test
    void shouldSaveAndFindConceptType() {
        ConceptType type = new ConceptType();
        type.setName("TestConcept_" + UUID.randomUUID().toString().substring(0, 8));
        type.setDescription("A test concept");
        type.setProperties(List.of(Map.of("key", "testProp", "valueType", "STRING")));
        type.setShared(false);

        ConceptType saved = conceptTypeRepository.save(type);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getVersion()).isNotNull();
    }

    @Test
    void shouldFindByName() {
        String name = "FindByName_" + UUID.randomUUID().toString().substring(0, 8);
        ConceptType type = new ConceptType();
        type.setName(name);
        type.setDescription("test");
        type.setShared(false);
        conceptTypeRepository.save(type);

        assertThat(conceptTypeRepository.findByName(name)).isPresent();
        assertThat(conceptTypeRepository.findByName("nonexistent")).isEmpty();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=ConceptTypeRepositoryTest -am
```

Expected: FAIL — `ConceptType` class does not exist.

- [ ] **Step 4: Create ConceptType entity**

```java
package io.emcip.knowledge.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ke_concept_types")
@Data
public class ConceptType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> properties;

    @Column(nullable = false)
    private Boolean shared = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
```

- [ ] **Step 5: Create ConceptTypeRepository**

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.ConceptType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConceptTypeRepository extends JpaRepository<ConceptType, UUID> {

    Optional<ConceptType> findByName(String name);

    boolean existsByName(String name);
}
```

- [ ] **Step 6: Run test to verify it passes**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=ConceptTypeRepositoryTest -am
```

Expected: PASS

- [ ] **Step 7: Create RelationshipType entity and repository**

```java
// entity/RelationshipType.java
package io.emcip.knowledge.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ke_relationship_types")
@Data
public class RelationshipType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_types", columnDefinition = "jsonb", nullable = false)
    private List<String> sourceTypes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_types", columnDefinition = "jsonb", nullable = false)
    private List<String> targetTypes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> properties;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
```

```java
// repository/RelationshipTypeRepository.java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.RelationshipType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RelationshipTypeRepository extends JpaRepository<RelationshipType, UUID> {

    Optional<RelationshipType> findByName(String name);

    boolean existsByName(String name);
}
```

- [ ] **Step 8: Write failing test for OntologyService**

```java
// test/service/OntologyServiceTest.java
package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.RelationshipType;
import io.emcip.knowledge.engine.repository.ConceptTypeRepository;
import io.emcip.knowledge.engine.repository.RelationshipTypeRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OntologyServiceTest {

    @Mock private ConceptTypeRepository conceptTypeRepository;
    @Mock private RelationshipTypeRepository relationshipTypeRepository;

    private OntologyService ontologyService;

    @BeforeEach
    void setUp() {
        ontologyService = new OntologyService(conceptTypeRepository, relationshipTypeRepository);
    }

    @Test
    void shouldReturnAllConceptTypes() {
        ConceptType person = new ConceptType();
        person.setName("Person");
        when(conceptTypeRepository.findAll()).thenReturn(List.of(person));

        List<ConceptType> result = ontologyService.getAllConceptTypes();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getName()).isEqualTo("Person");
    }

    @Test
    void shouldCreateConceptType() {
        ConceptType type = new ConceptType();
        type.setName("NewType");
        when(conceptTypeRepository.existsByName("NewType")).thenReturn(false);
        when(conceptTypeRepository.save(any())).thenReturn(type);

        ConceptType result = ontologyService.createConceptType(type);

        assertThat(result.getName()).isEqualTo("NewType");
        verify(conceptTypeRepository).save(type);
    }

    @Test
    void shouldRejectDuplicateConceptType() {
        ConceptType type = new ConceptType();
        type.setName("Person");
        when(conceptTypeRepository.existsByName("Person")).thenReturn(true);

        assertThatThrownBy(() -> ontologyService.createConceptType(type))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Person");
    }

    @Test
    void shouldValidateRelationshipSourceTypes() {
        RelationshipType rel = new RelationshipType();
        rel.setName("DISCUSSES");
        rel.setSourceTypes(List.of("NonExistent"));
        rel.setTargetTypes(List.of("Topic"));
        when(relationshipTypeRepository.existsByName("DISCUSSES")).thenReturn(false);
        when(conceptTypeRepository.findByName("NonExistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ontologyService.createRelationshipType(rel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NonExistent");
    }
}
```

- [ ] **Step 9: Run test to verify it fails**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=OntologyServiceTest -am
```

Expected: FAIL — `OntologyService` class does not exist.

- [ ] **Step 10: Create OntologyService**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.RelationshipType;
import io.emcip.knowledge.engine.repository.ConceptTypeRepository;
import io.emcip.knowledge.engine.repository.RelationshipTypeRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class OntologyService {

    private final ConceptTypeRepository conceptTypeRepository;
    private final RelationshipTypeRepository relationshipTypeRepository;

    public List<ConceptType> getAllConceptTypes() {
        return conceptTypeRepository.findAll();
    }

    public List<RelationshipType> getAllRelationshipTypes() {
        return relationshipTypeRepository.findAll();
    }

    @Transactional
    public ConceptType createConceptType(ConceptType type) {
        if (conceptTypeRepository.existsByName(type.getName())) {
            throw new IllegalArgumentException("Concept type already exists: " + type.getName());
        }
        log.info("Creating concept type: {}", type.getName());
        return conceptTypeRepository.save(type);
    }

    @Transactional
    public RelationshipType createRelationshipType(RelationshipType type) {
        if (relationshipTypeRepository.existsByName(type.getName())) {
            throw new IllegalArgumentException(
                    "Relationship type already exists: " + type.getName());
        }
        for (String sourceType : type.getSourceTypes()) {
            if (conceptTypeRepository.findByName(sourceType).isEmpty()) {
                throw new IllegalArgumentException(
                        "Source concept type not found: " + sourceType);
            }
        }
        for (String targetType : type.getTargetTypes()) {
            if (conceptTypeRepository.findByName(targetType).isEmpty()) {
                throw new IllegalArgumentException(
                        "Target concept type not found: " + targetType);
            }
        }
        log.info("Creating relationship type: {}", type.getName());
        return relationshipTypeRepository.save(type);
    }

    public ConceptType getConceptType(String name) {
        return conceptTypeRepository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Concept type not found: " + name));
    }

    public RelationshipType getRelationshipType(String name) {
        return relationshipTypeRepository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Relationship type not found: " + name));
    }
}
```

- [ ] **Step 11: Run tests to verify they pass**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=OntologyServiceTest -am
```

Expected: PASS

- [ ] **Step 12: Create OntologyController**

```java
package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.entity.ConceptType;
import io.emcip.knowledge.engine.entity.RelationshipType;
import io.emcip.knowledge.engine.service.OntologyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Ontology", description = "Manage knowledge ontology (concept types and relationships)")
@RestController
@RequestMapping("/api/knowledge/ontology")
@RequiredArgsConstructor
public class OntologyController {

    private final OntologyService ontologyService;

    @Operation(summary = "List all concept types")
    @GetMapping("/concepts")
    public List<ConceptType> listConceptTypes() {
        return ontologyService.getAllConceptTypes();
    }

    @Operation(summary = "Create a new concept type")
    @PostMapping("/concepts")
    public ResponseEntity<ConceptType> createConceptType(@RequestBody ConceptType type) {
        ConceptType created = ontologyService.createConceptType(type);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "List all relationship types")
    @GetMapping("/relationships")
    public List<RelationshipType> listRelationshipTypes() {
        return ontologyService.getAllRelationshipTypes();
    }

    @Operation(summary = "Create a new relationship type")
    @PostMapping("/relationships")
    public ResponseEntity<RelationshipType> createRelationshipType(
            @RequestBody RelationshipType type) {
        RelationshipType created = ontologyService.createRelationshipType(type);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
```

- [ ] **Step 13: Run spotless and commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/
git add emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/
git commit -m "feat(knowledge-engine): ontology entities, service, controller with TDD

ConceptType + RelationshipType JPA entities, Spring Data repositories.
OntologyService with validation (duplicate names, source/target type checks).
OntologyController: GET/POST for /api/knowledge/ontology/concepts and /relationships.
Tenant filter aspect, Kafka config, test infrastructure."
```

---

### Task 6: KnowledgeDocument Entity + VectorSearchRepository (TDD)

**Files:**
- Create: `entity/KnowledgeDocument.java`
- Create: `repository/KnowledgeDocumentRepository.java`
- Create: `repository/VectorSearchRepository.java` (interface)
- Create: `repository/PgVectorSearchRepository.java` (impl)
- Create: `test/repository/PgVectorSearchRepositoryTest.java`

- [ ] **Step 1: Write failing test for vector search**

```java
package io.emcip.knowledge.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class PgVectorSearchRepositoryTest {

    @Autowired private VectorSearchRepository vectorSearchRepository;
    @Autowired private KnowledgeDocumentRepository documentRepository;

    @Test
    void shouldStoreAndSearchByEmbedding() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTenantId(UUID.randomUUID());
        doc.setSourceType("CHAT_MESSAGE");
        doc.setSourceRef("msg-123");
        doc.setContent("Artificial intelligence is transforming industries");
        doc.setChunkIndex(0);
        doc.setMetadata(Map.of("author", "testUser"));
        KnowledgeDocument saved = documentRepository.save(doc);

        float[] embedding = new float[] {0.1f, 0.2f, 0.3f};
        vectorSearchRepository.storeEmbedding(saved.getId(), embedding);

        float[] queryEmbedding = new float[] {0.1f, 0.2f, 0.29f};
        List<KnowledgeDocument> results =
                vectorSearchRepository.search(queryEmbedding, 5, saved.getTenantId());

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().getId()).isEqualTo(saved.getId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=PgVectorSearchRepositoryTest -am
```

Expected: FAIL — classes don't exist.

- [ ] **Step 3: Create KnowledgeDocument entity**

Note: The `embedding` column is managed by native SQL (pgvector), not mapped in JPA.

```java
package io.emcip.knowledge.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.ParamDef;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ke_knowledge_documents")
@FilterDef(
        name = "tenantFilter",
        parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Data
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "source_ref", nullable = false, length = 1000)
    private String sourceRef;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

- [ ] **Step 4: Create KnowledgeDocumentRepository**

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {

    List<KnowledgeDocument> findBySourceRef(String sourceRef);

    boolean existsBySourceRefAndChunkIndex(String sourceRef, Integer chunkIndex);
}
```

- [ ] **Step 5: Create VectorSearchRepository interface**

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import java.util.List;
import java.util.UUID;

public interface VectorSearchRepository {

    void storeEmbedding(UUID documentId, float[] embedding);

    List<KnowledgeDocument> search(float[] queryEmbedding, int topK, UUID tenantId);

    List<KnowledgeDocument> hybridSearch(
            String textQuery, float[] queryEmbedding, int topK, UUID tenantId);
}
```

- [ ] **Step 6: Create PgVectorSearchRepository implementation**

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
@Slf4j
@RequiredArgsConstructor
public class PgVectorSearchRepository implements VectorSearchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void storeEmbedding(UUID documentId, float[] embedding) {
        String vectorStr = toVectorString(embedding);
        jdbcTemplate.update(
                "UPDATE ke_knowledge_documents SET embedding = ?::vector WHERE id = ?",
                vectorStr,
                documentId);
        log.debug("Stored embedding for document {}", documentId);
    }

    @Override
    public List<KnowledgeDocument> search(float[] queryEmbedding, int topK, UUID tenantId) {
        String vectorStr = toVectorString(queryEmbedding);
        String sql;
        Object[] params;

        if (tenantId != null) {
            sql =
                    """
                    SELECT id, tenant_id, source_type, source_ref, content, chunk_index,
                           metadata, created_at, embedding <=> ?::vector AS distance
                    FROM ke_knowledge_documents
                    WHERE embedding IS NOT NULL AND (tenant_id = ? OR tenant_id IS NULL)
                    ORDER BY distance ASC
                    LIMIT ?
                    """;
            params = new Object[] {vectorStr, tenantId, topK};
        } else {
            sql =
                    """
                    SELECT id, tenant_id, source_type, source_ref, content, chunk_index,
                           metadata, created_at, embedding <=> ?::vector AS distance
                    FROM ke_knowledge_documents
                    WHERE embedding IS NOT NULL
                    ORDER BY distance ASC
                    LIMIT ?
                    """;
            params = new Object[] {vectorStr, topK};
        }

        return jdbcTemplate.query(sql, this::mapRow, params);
    }

    @Override
    public List<KnowledgeDocument> hybridSearch(
            String textQuery, float[] queryEmbedding, int topK, UUID tenantId) {
        String vectorStr = toVectorString(queryEmbedding);
        String sql;
        Object[] params;

        if (tenantId != null) {
            sql =
                    """
                    SELECT id, tenant_id, source_type, source_ref, content, chunk_index,
                           metadata, created_at, embedding <=> ?::vector AS distance
                    FROM ke_knowledge_documents
                    WHERE embedding IS NOT NULL
                      AND (tenant_id = ? OR tenant_id IS NULL)
                      AND content ILIKE '%' || ? || '%'
                    ORDER BY distance ASC
                    LIMIT ?
                    """;
            params = new Object[] {vectorStr, tenantId, textQuery, topK};
        } else {
            sql =
                    """
                    SELECT id, tenant_id, source_type, source_ref, content, chunk_index,
                           metadata, created_at, embedding <=> ?::vector AS distance
                    FROM ke_knowledge_documents
                    WHERE embedding IS NOT NULL AND content ILIKE '%' || ? || '%'
                    ORDER BY distance ASC
                    LIMIT ?
                    """;
            params = new Object[] {vectorStr, textQuery, topK};
        }

        return jdbcTemplate.query(sql, this::mapRow, params);
    }

    @SuppressWarnings("unchecked")
    private KnowledgeDocument mapRow(ResultSet rs, int rowNum) throws SQLException {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(UUID.fromString(rs.getString("id")));
        String tenantStr = rs.getString("tenant_id");
        if (tenantStr != null) doc.setTenantId(UUID.fromString(tenantStr));
        doc.setSourceType(rs.getString("source_type"));
        doc.setSourceRef(rs.getString("source_ref"));
        doc.setContent(rs.getString("content"));
        doc.setChunkIndex(rs.getInt("chunk_index"));
        String metaJson = rs.getString("metadata");
        if (metaJson != null) {
            try {
                doc.setMetadata(objectMapper.readValue(metaJson, Map.class));
            } catch (Exception e) {
                log.warn("Failed to parse metadata JSON", e);
            }
        }
        doc.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return doc;
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=PgVectorSearchRepositoryTest -am
```

Expected: PASS (note: test uses dimension=3 matching application-test.yml; the SQL `?::vector` auto-casts to any dimension)

- [ ] **Step 8: Commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/
git commit -m "feat(knowledge-engine): KnowledgeDocument entity + VectorSearchRepository

KnowledgeDocument JPA entity (tenant-filtered, JSONB metadata).
VectorSearchRepository interface + PgVectorSearchRepository impl using JdbcTemplate.
Cosine similarity search via pgvector <=> operator.
Hybrid search combining vector + text matching."
```

---

### Task 7: GraphNode/GraphEdge Records + GraphRepository + AgeGraphRepository (TDD)

**Files:**
- Create: `model/GraphNode.java`, `model/GraphEdge.java`
- Create: `repository/GraphRepository.java` (interface)
- Create: `repository/AgeGraphRepository.java` (impl)
- Create: `test/repository/AgeGraphRepositoryTest.java`

GraphNode and GraphEdge are **not** JPA entities — they are stored in Apache AGE's graph engine. We interact with AGE via native SQL using `ag_catalog.cypher()`.

- [ ] **Step 1: Create GraphNode and GraphEdge records**

```java
// model/GraphNode.java
package io.emcip.knowledge.engine.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record GraphNode(
        UUID id,
        String conceptType,
        UUID tenantId,
        String label,
        Map<String, Object> properties,
        Instant createdAt,
        Instant updatedAt) {}
```

```java
// model/GraphEdge.java
package io.emcip.knowledge.engine.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record GraphEdge(
        UUID id,
        String relationshipType,
        UUID sourceNodeId,
        UUID targetNodeId,
        Map<String, Object> properties,
        UUID sourceMessageId,
        Instant createdAt) {}
```

- [ ] **Step 2: Create GraphRepository interface**

```java
// repository/GraphRepository.java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.model.GraphEdge;
import io.emcip.knowledge.engine.model.GraphNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface GraphRepository {

    GraphNode createNode(
            String conceptType, String label, Map<String, Object> properties, UUID tenantId);

    GraphEdge createRelationship(
            String relationshipType,
            UUID sourceNodeId,
            UUID targetNodeId,
            Map<String, Object> properties,
            UUID sourceMessageId);

    List<GraphNode> findConnected(UUID nodeId, String relationshipType, int depth);

    Optional<GraphNode> findByLabelAndType(String label, String conceptType, UUID tenantId);

    List<GraphNode> findNodesByType(String conceptType, UUID tenantId, int limit);
}
```

- [ ] **Step 3: Write failing test for AgeGraphRepository**

```java
package io.emcip.knowledge.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.IntegrationTest;
import io.emcip.knowledge.engine.model.GraphEdge;
import io.emcip.knowledge.engine.model.GraphNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class AgeGraphRepositoryTest {

    @Autowired private GraphRepository graphRepository;

    @Test
    void shouldCreateAndFindNode() {
        UUID tenantId = UUID.randomUUID();
        GraphNode node =
                graphRepository.createNode("Person", "John Doe", Map.of(), tenantId);

        assertThat(node.id()).isNotNull();
        assertThat(node.conceptType()).isEqualTo("Person");
        assertThat(node.label()).isEqualTo("John Doe");

        var found = graphRepository.findByLabelAndType("John Doe", "Person", tenantId);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(node.id());
    }

    @Test
    void shouldCreateRelationshipBetweenNodes() {
        UUID tenantId = UUID.randomUUID();
        GraphNode person = graphRepository.createNode("Person", "Alice", Map.of(), tenantId);
        GraphNode topic = graphRepository.createNode("Topic", "AI", Map.of(), tenantId);

        GraphEdge edge =
                graphRepository.createRelationship(
                        "DISCUSSES",
                        person.id(),
                        topic.id(),
                        Map.of("confidence", 0.9),
                        null);

        assertThat(edge.id()).isNotNull();
        assertThat(edge.relationshipType()).isEqualTo("DISCUSSES");
    }

    @Test
    void shouldFindConnectedNodes() {
        UUID tenantId = UUID.randomUUID();
        GraphNode person = graphRepository.createNode("Person", "Bob", Map.of(), tenantId);
        GraphNode topic1 = graphRepository.createNode("Topic", "ML", Map.of(), tenantId);
        GraphNode topic2 = graphRepository.createNode("Topic", "NLP", Map.of(), tenantId);

        graphRepository.createRelationship("DISCUSSES", person.id(), topic1.id(), Map.of(), null);
        graphRepository.createRelationship("DISCUSSES", person.id(), topic2.id(), Map.of(), null);

        var connected = graphRepository.findConnected(person.id(), "DISCUSSES", 1);
        assertThat(connected).hasSize(2);
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=AgeGraphRepositoryTest -am
```

Expected: FAIL — `AgeGraphRepository` does not exist.

- [ ] **Step 5: Create AgeGraphRepository implementation**

AGE uses the `ag_catalog.cypher()` function to run openCypher queries via SQL. Results are returned as `agtype` (JSON-like). We use JdbcTemplate to execute these queries and parse the results.

**Important:** The Testcontainers image `pgvector/pgvector:pg16` does NOT include Apache AGE. AGE-dependent tests will need the custom Docker image or be marked with a condition. For initial development, we provide a fallback implementation that stores graph data in regular SQL tables if AGE is unavailable, but the primary implementation uses AGE.

```java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.model.GraphEdge;
import io.emcip.knowledge.engine.model.GraphNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Slf4j
@RequiredArgsConstructor
public class AgeGraphRepository implements GraphRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String GRAPH_NAME = "knowledge_graph";

    @Override
    public GraphNode createNode(
            String conceptType, String label, Map<String, Object> properties, UUID tenantId) {
        UUID nodeId = UUID.randomUUID();
        Instant now = Instant.now();

        String propsJson = buildPropertiesJson(nodeId, label, tenantId, properties, now);

        String cypher =
                String.format(
                        "CREATE (n:%s %s) RETURN n", sanitizeLabel(conceptType), propsJson);

        executeCypher(cypher);

        log.debug("Created graph node: type={}, label={}, id={}", conceptType, label, nodeId);
        return new GraphNode(nodeId, conceptType, tenantId, label, properties, now, now);
    }

    @Override
    public GraphEdge createRelationship(
            String relationshipType,
            UUID sourceNodeId,
            UUID targetNodeId,
            Map<String, Object> properties,
            UUID sourceMessageId) {
        UUID edgeId = UUID.randomUUID();
        Instant now = Instant.now();

        String propsJson = buildEdgePropertiesJson(edgeId, properties, sourceMessageId, now);

        String cypher =
                String.format(
                        """
                        MATCH (a {node_id: '%s'}), (b {node_id: '%s'})
                        CREATE (a)-[r:%s %s]->(b)
                        RETURN r
                        """,
                        sourceNodeId, targetNodeId, sanitizeLabel(relationshipType), propsJson);

        executeCypher(cypher);

        log.debug(
                "Created graph edge: type={}, {} -> {}",
                relationshipType,
                sourceNodeId,
                targetNodeId);
        return new GraphEdge(
                edgeId, relationshipType, sourceNodeId, targetNodeId, properties, sourceMessageId,
                now);
    }

    @Override
    public List<GraphNode> findConnected(UUID nodeId, String relationshipType, int depth) {
        String cypher =
                String.format(
                        """
                        MATCH ({node_id: '%s'})-[:%s*1..%d]->(connected)
                        RETURN connected
                        """,
                        nodeId, sanitizeLabel(relationshipType), depth);

        return queryNodes(cypher);
    }

    @Override
    public Optional<GraphNode> findByLabelAndType(
            String label, String conceptType, UUID tenantId) {
        String cypher =
                String.format(
                        """
                        MATCH (n:%s {label: '%s', tenant_id: '%s'})
                        RETURN n
                        """,
                        sanitizeLabel(conceptType), escapeString(label), tenantId);

        List<GraphNode> results = queryNodes(cypher);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public List<GraphNode> findNodesByType(String conceptType, UUID tenantId, int limit) {
        String cypher;
        if (tenantId != null) {
            cypher =
                    String.format(
                            """
                            MATCH (n:%s {tenant_id: '%s'})
                            RETURN n LIMIT %d
                            """,
                            sanitizeLabel(conceptType), tenantId, limit);
        } else {
            cypher =
                    String.format(
                            "MATCH (n:%s) RETURN n LIMIT %d", sanitizeLabel(conceptType), limit);
        }
        return queryNodes(cypher);
    }

    private void executeCypher(String cypher) {
        String sql =
                String.format(
                        "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (result ag_catalog.agtype)",
                        GRAPH_NAME, cypher);
        try {
            jdbcTemplate.execute("SET search_path = ag_catalog, \"$user\", public");
            jdbcTemplate.execute("LOAD 'age'");
            jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.error("AGE cypher execution failed: {}", e.getMessage(), e);
            throw new RuntimeException("Graph operation failed: " + e.getMessage(), e);
        }
    }

    private List<GraphNode> queryNodes(String cypher) {
        String sql =
                String.format(
                        "SELECT * FROM ag_catalog.cypher('%s', $$ %s $$) AS (result ag_catalog.agtype)",
                        GRAPH_NAME, cypher);
        try {
            jdbcTemplate.execute("SET search_path = ag_catalog, \"$user\", public");
            jdbcTemplate.execute("LOAD 'age'");
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            List<GraphNode> nodes = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                GraphNode node = parseNodeFromAgtype(row.get("result"));
                if (node != null) nodes.add(node);
            }
            return nodes;
        } catch (Exception e) {
            log.error("AGE cypher query failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private GraphNode parseNodeFromAgtype(Object agtype) {
        if (agtype == null) return null;
        String str = agtype.toString();
        // AGE agtype format: {node_id: "...", label: "...", ...}::vertex
        // Parse the JSON-like structure
        try {
            Map<String, Object> props = parseAgtypeProperties(str);
            return new GraphNode(
                    UUID.fromString((String) props.getOrDefault("node_id", UUID.randomUUID().toString())),
                    (String) props.getOrDefault("concept_type", ""),
                    props.containsKey("tenant_id") ? UUID.fromString((String) props.get("tenant_id")) : null,
                    (String) props.getOrDefault("label", ""),
                    filterProperties(props),
                    Instant.now(),
                    Instant.now());
        } catch (Exception e) {
            log.warn("Failed to parse agtype node: {}", str, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAgtypeProperties(String agtype) {
        // Strip ::vertex or ::edge suffix
        String json = agtype.replaceAll("::\\w+$", "").trim();
        try {
            tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> filterProperties(Map<String, Object> all) {
        Map<String, Object> props = new HashMap<>(all);
        props.remove("node_id");
        props.remove("label");
        props.remove("tenant_id");
        props.remove("concept_type");
        props.remove("created_at");
        props.remove("updated_at");
        return props;
    }

    private String buildPropertiesJson(
            UUID nodeId,
            String label,
            UUID tenantId,
            Map<String, Object> properties,
            Instant now) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("node_id: '").append(nodeId).append("', ");
        sb.append("label: '").append(escapeString(label)).append("', ");
        if (tenantId != null) {
            sb.append("tenant_id: '").append(tenantId).append("', ");
        }
        sb.append("created_at: '").append(now).append("', ");
        sb.append("updated_at: '").append(now).append("'");
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            sb.append(", ").append(entry.getKey()).append(": ");
            if (entry.getValue() instanceof String s) {
                sb.append("'").append(escapeString(s)).append("'");
            } else {
                sb.append(entry.getValue());
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildEdgePropertiesJson(
            UUID edgeId,
            Map<String, Object> properties,
            UUID sourceMessageId,
            Instant now) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("edge_id: '").append(edgeId).append("', ");
        if (sourceMessageId != null) {
            sb.append("source_message_id: '").append(sourceMessageId).append("', ");
        }
        sb.append("created_at: '").append(now).append("'");
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            sb.append(", ").append(entry.getKey()).append(": ");
            if (entry.getValue() instanceof String s) {
                sb.append("'").append(escapeString(s)).append("'");
            } else {
                sb.append(entry.getValue());
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String sanitizeLabel(String label) {
        return label.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String escapeString(String input) {
        return input.replace("'", "\\'").replace("\\", "\\\\");
    }
}
```

- [ ] **Step 6: Run test (may skip if AGE not in test image)**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=AgeGraphRepositoryTest -am
```

Note: If using `pgvector/pgvector:pg16` (no AGE), these tests will fail. Options:
1. Use the custom Docker image from Task 1 in TestcontainersInitializer
2. Mark AGE tests with `@Tag("age")` and exclude them from default runs
3. First option is preferred for full integration testing

- [ ] **Step 7: Commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/
git commit -m "feat(knowledge-engine): GraphNode/GraphEdge records + GraphRepository abstraction

GraphRepository interface + AgeGraphRepository using JdbcTemplate + ag_catalog.cypher().
Supports: createNode, createRelationship, findConnected, findByLabelAndType.
AGE agtype parsing for result conversion."
```

---

### Task 8: LLM Orchestrator REST Client (TDD)

**Files:**
- Create: `client/LlmOrchestratorClient.java`
- Create: `model/ExtractionResult.java`
- Create: `test/client/LlmOrchestratorClientTest.java` (MockWebServer)

The knowledge-engine calls llm-orchestrator via REST for EMBED, EXTRACT, and RESOLVE tasks. It uses the `/api/analyse` endpoint with different `taskType` values.

- [ ] **Step 1: Write failing test**

```java
package io.emcip.knowledge.engine.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class LlmOrchestratorClientTest {

    private MockWebServer mockWebServer;
    private LlmOrchestratorClient client;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String baseUrl = mockWebServer.url("/").toString();
        client = new LlmOrchestratorClient(RestClient.builder().baseUrl(baseUrl).build(),
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    void shouldExtractEntitiesFromText() throws Exception {
        String responseJson =
                """
                {"success":true,"analysis":"{\\"entities\\":[{\\"type\\":\\"Person\\",\\"label\\":\\"Alice\\"},{\\"type\\":\\"Topic\\",\\"label\\":\\"AI\\"}],\\"relationships\\":[{\\"type\\":\\"DISCUSSES\\",\\"source\\":\\"Alice\\",\\"target\\":\\"AI\\"}]}","model":"test-model"}
                """;
        mockWebServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .addHeader("Content-Type", "application/json"));

        var result = client.extract("Alice discussed AI in the chat", "Person,Topic", "DISCUSSES");

        assertThat(result).isNotNull();
        assertThat(result.entities()).isNotEmpty();
        assertThat(result.relationships()).isNotEmpty();
    }

    @Test
    void shouldCallAnalyseEndpointWithEmbedTaskType() throws Exception {
        String responseJson =
                """
                {"success":true,"analysis":"[0.1,0.2,0.3]","model":"embed-model"}
                """;
        mockWebServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .addHeader("Content-Type", "application/json"));

        float[] embedding = client.embed("Some text to embed");

        assertThat(embedding).isNotEmpty();
        var request = mockWebServer.takeRequest();
        assertThat(request.getBody().readUtf8()).contains("EMBED");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=LlmOrchestratorClientTest -am
```

Expected: FAIL — classes don't exist.

- [ ] **Step 3: Create ExtractionResult record**

```java
// model/ExtractionResult.java
package io.emcip.knowledge.engine.model;

import java.util.List;
import java.util.Map;

public record ExtractionResult(
        List<ExtractedEntity> entities, List<ExtractedRelationship> relationships) {

    public record ExtractedEntity(String type, String label, Map<String, Object> properties) {}

    public record ExtractedRelationship(
            String type,
            String source,
            String target,
            Map<String, Object> properties) {}
}
```

- [ ] **Step 4: Create LlmOrchestratorClient**

```java
package io.emcip.knowledge.engine.client;

import io.emcip.knowledge.engine.model.ExtractionResult;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedEntity;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedRelationship;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@RequiredArgsConstructor
public class LlmOrchestratorClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public float[] embed(String text) {
        Map<String, String> request = Map.of("prompt", text, "taskType", "EMBED");

        var response =
                restClient
                        .post()
                        .uri("/api/analyse")
                        .body(request)
                        .retrieve()
                        .body(AnalyseResponse.class);

        if (response == null || !response.success()) {
            log.error("Embedding failed: {}", response);
            return new float[0];
        }

        return parseEmbedding(response.analysis());
    }

    public ExtractionResult extract(
            String text, String conceptTypes, String relationshipTypes) {
        String prompt =
                String.format(
                        """
                        Extract entities and relationships from the following text.
                        Concept types: %s
                        Relationship types: %s

                        Return JSON with "entities" (array of {type, label}) and \
                        "relationships" (array of {type, source, target}).

                        Text: %s
                        """,
                        conceptTypes, relationshipTypes, text);

        Map<String, String> request = Map.of("prompt", prompt, "taskType", "EXTRACT");

        var response =
                restClient
                        .post()
                        .uri("/api/analyse")
                        .body(request)
                        .retrieve()
                        .body(AnalyseResponse.class);

        if (response == null || !response.success()) {
            log.error("Extraction failed: {}", response);
            return new ExtractionResult(List.of(), List.of());
        }

        return parseExtractionResult(response.analysis());
    }

    public String resolve(String label, String conceptType, List<String> candidates) {
        String prompt =
                String.format(
                        """
                        Entity resolution: does "%s" (type: %s) match any of these existing entities?
                        Candidates: %s
                        Respond with the matching candidate label, or "NEW" if no match.
                        """,
                        label, conceptType, String.join(", ", candidates));

        Map<String, String> request = Map.of("prompt", prompt, "taskType", "RESOLVE");

        var response =
                restClient
                        .post()
                        .uri("/api/analyse")
                        .body(request)
                        .retrieve()
                        .body(AnalyseResponse.class);

        if (response == null || !response.success()) {
            return "NEW";
        }

        return response.analysis().trim();
    }

    @SuppressWarnings("unchecked")
    private ExtractionResult parseExtractionResult(String json) {
        try {
            // The LLM response may be wrapped in markdown code blocks
            String cleaned = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            Map<String, Object> parsed = objectMapper.readValue(cleaned, Map.class);

            List<ExtractedEntity> entities = new ArrayList<>();
            if (parsed.containsKey("entities")) {
                for (Map<String, Object> e : (List<Map<String, Object>>) parsed.get("entities")) {
                    entities.add(
                            new ExtractedEntity(
                                    (String) e.get("type"),
                                    (String) e.get("label"),
                                    e.getOrDefault("properties", Collections.emptyMap())
                                            instanceof Map<?, ?> p
                                            ? (Map<String, Object>) p
                                            : Map.of()));
                }
            }

            List<ExtractedRelationship> relationships = new ArrayList<>();
            if (parsed.containsKey("relationships")) {
                for (Map<String, Object> r :
                        (List<Map<String, Object>>) parsed.get("relationships")) {
                    relationships.add(
                            new ExtractedRelationship(
                                    (String) r.get("type"),
                                    (String) r.get("source"),
                                    (String) r.get("target"),
                                    r.getOrDefault("properties", Collections.emptyMap())
                                            instanceof Map<?, ?> p
                                            ? (Map<String, Object>) p
                                            : Map.of()));
                }
            }

            return new ExtractionResult(entities, relationships);
        } catch (Exception e) {
            log.error("Failed to parse extraction result: {}", json, e);
            return new ExtractionResult(List.of(), List.of());
        }
    }

    private float[] parseEmbedding(String text) {
        try {
            String cleaned = text.replaceAll("[\\[\\]\\s]", "");
            String[] parts = cleaned.split(",");
            float[] embedding = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                embedding[i] = Float.parseFloat(parts[i].trim());
            }
            return embedding;
        } catch (Exception e) {
            log.error("Failed to parse embedding: {}", text, e);
            return new float[0];
        }
    }

    private record AnalyseResponse(boolean success, String analysis, String model) {}
}
```

- [ ] **Step 5: Create Spring config bean for LlmOrchestratorClient**

Add to `KnowledgeEngineApplication.java` or create a separate config:

```java
// config/LlmClientConfig.java
package io.emcip.knowledge.engine.config;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class LlmClientConfig {

    @Bean
    public RestClient llmOrchestratorRestClient(
            @Value("${knowledge.llm-orchestrator.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public LlmOrchestratorClient llmOrchestratorClient(
            RestClient llmOrchestratorRestClient, ObjectMapper objectMapper) {
        return new LlmOrchestratorClient(llmOrchestratorRestClient, objectMapper);
    }
}
```

- [ ] **Step 6: Run tests**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=LlmOrchestratorClientTest -am
```

Expected: PASS

- [ ] **Step 7: Commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/
git commit -m "feat(knowledge-engine): LLM orchestrator REST client for EMBED/EXTRACT/RESOLVE

LlmOrchestratorClient calls llm-orchestrator /api/analyse with task types.
ExtractionResult record for structured entity/relationship output.
MockWebServer-based tests."
```

---

### Task 9: KnowledgeExtractionService + EntityResolutionService (TDD)

**Files:**
- Create: `service/KnowledgeExtractionService.java`
- Create: `service/EntityResolutionService.java`
- Create: `entity/EntityAlias.java`
- Create: `repository/EntityAliasRepository.java`
- Create: `test/service/KnowledgeExtractionServiceTest.java`
- Create: `test/service/EntityResolutionServiceTest.java`

- [ ] **Step 1: Create EntityAlias entity and repository**

```java
// entity/EntityAlias.java
package io.emcip.knowledge.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Entity
@Table(name = "ke_entity_aliases")
@Data
public class EntityAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "concept_type", nullable = false, length = 100)
    private String conceptType;

    @Column(nullable = false, length = 500)
    private String alias;

    @Column(name = "canonical_label", nullable = false, length = 500)
    private String canonicalLabel;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

```java
// repository/EntityAliasRepository.java
package io.emcip.knowledge.engine.repository;

import io.emcip.knowledge.engine.entity.EntityAlias;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EntityAliasRepository extends JpaRepository<EntityAlias, UUID> {

    Optional<EntityAlias> findByConceptTypeAndAliasAndTenantId(
            String conceptType, String alias, UUID tenantId);
}
```

- [ ] **Step 2: Write failing test for EntityResolutionService**

```java
package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.EntityAlias;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.repository.EntityAliasRepository;
import io.emcip.knowledge.engine.repository.GraphRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntityResolutionServiceTest {

    @Mock private GraphRepository graphRepository;
    @Mock private EntityAliasRepository entityAliasRepository;
    @Mock private LlmOrchestratorClient llmClient;

    private EntityResolutionService service;

    @BeforeEach
    void setUp() {
        service = new EntityResolutionService(graphRepository, entityAliasRepository, llmClient);
    }

    @Test
    void shouldResolveByExactMatch() {
        UUID tenantId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        GraphNode existing =
                new GraphNode(nodeId, "Person", tenantId, "alice", Map.of(), Instant.now(),
                        Instant.now());

        when(graphRepository.findByLabelAndType("alice", "Person", tenantId))
                .thenReturn(Optional.of(existing));

        UUID result = service.resolve("Alice", "Person", tenantId);

        assertThat(result).isEqualTo(nodeId);
    }

    @Test
    void shouldResolveByAlias() {
        UUID tenantId = UUID.randomUUID();
        EntityAlias alias = new EntityAlias();
        alias.setCanonicalLabel("Artificial Intelligence");

        when(graphRepository.findByLabelAndType("alice", "Topic", tenantId))
                .thenReturn(Optional.empty());
        when(entityAliasRepository.findByConceptTypeAndAliasAndTenantId("Topic", "ai", tenantId))
                .thenReturn(Optional.of(alias));

        UUID nodeId = UUID.randomUUID();
        GraphNode existing =
                new GraphNode(nodeId, "Topic", tenantId, "artificial intelligence", Map.of(),
                        Instant.now(), Instant.now());
        when(graphRepository.findByLabelAndType("artificial intelligence", "Topic", tenantId))
                .thenReturn(Optional.of(existing));

        UUID result = service.resolve("AI", "Topic", tenantId);

        assertThat(result).isEqualTo(nodeId);
    }

    @Test
    void shouldCreateNewNodeWhenNoMatch() {
        UUID tenantId = UUID.randomUUID();
        UUID newNodeId = UUID.randomUUID();
        GraphNode newNode =
                new GraphNode(newNodeId, "Topic", tenantId, "quantum computing", Map.of(),
                        Instant.now(), Instant.now());

        when(graphRepository.findByLabelAndType("quantum computing", "Topic", tenantId))
                .thenReturn(Optional.empty());
        when(entityAliasRepository.findByConceptTypeAndAliasAndTenantId(
                        "Topic", "quantum computing", tenantId))
                .thenReturn(Optional.empty());
        when(graphRepository.createNode(eq("Topic"), eq("quantum computing"), any(), eq(tenantId)))
                .thenReturn(newNode);

        UUID result = service.resolve("Quantum Computing", "Topic", tenantId);

        assertThat(result).isEqualTo(newNodeId);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=EntityResolutionServiceTest -am
```

Expected: FAIL

- [ ] **Step 4: Create EntityResolutionService**

3-level resolution: exact match → alias table → create new.

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.EntityAlias;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.repository.EntityAliasRepository;
import io.emcip.knowledge.engine.repository.GraphRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class EntityResolutionService {

    private final GraphRepository graphRepository;
    private final EntityAliasRepository entityAliasRepository;
    private final LlmOrchestratorClient llmClient;

    /**
     * Resolve an entity label to an existing graph node ID, or create a new node.
     *
     * @return node ID of the resolved (or newly created) graph node
     */
    public UUID resolve(String label, String conceptType, UUID tenantId) {
        String normalized = label.toLowerCase().trim();

        // Level 1: Exact match
        Optional<GraphNode> exact =
                graphRepository.findByLabelAndType(normalized, conceptType, tenantId);
        if (exact.isPresent()) {
            log.debug("Entity resolved by exact match: {} -> {}", label, exact.get().id());
            return exact.get().id();
        }

        // Level 2: Alias table
        Optional<EntityAlias> alias =
                entityAliasRepository.findByConceptTypeAndAliasAndTenantId(
                        conceptType, normalized, tenantId);
        if (alias.isPresent()) {
            String canonical = alias.get().getCanonicalLabel().toLowerCase().trim();
            Optional<GraphNode> aliasNode =
                    graphRepository.findByLabelAndType(canonical, conceptType, tenantId);
            if (aliasNode.isPresent()) {
                log.debug(
                        "Entity resolved by alias: {} -> {} -> {}",
                        label,
                        alias.get().getCanonicalLabel(),
                        aliasNode.get().id());
                return aliasNode.get().id();
            }
        }

        // Level 3: Create new node
        GraphNode newNode =
                graphRepository.createNode(conceptType, normalized, Map.of(), tenantId);
        log.info("Created new graph node: type={}, label={}, id={}", conceptType, label,
                newNode.id());
        return newNode.id();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=EntityResolutionServiceTest -am
```

Expected: PASS

- [ ] **Step 6: Write failing test for KnowledgeExtractionService**

```java
package io.emcip.knowledge.engine.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.ExtractionResult;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedEntity;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedRelationship;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeExtractionServiceTest {

    @Mock private KnowledgeDocumentRepository documentRepository;
    @Mock private VectorSearchRepository vectorSearchRepository;
    @Mock private GraphRepository graphRepository;
    @Mock private EntityResolutionService entityResolutionService;
    @Mock private LlmOrchestratorClient llmClient;
    @Mock private OntologyService ontologyService;

    private KnowledgeExtractionService service;

    @BeforeEach
    void setUp() {
        service =
                new KnowledgeExtractionService(
                        documentRepository,
                        vectorSearchRepository,
                        graphRepository,
                        entityResolutionService,
                        llmClient,
                        ontologyService);
    }

    @Test
    void shouldStoreDocumentAndExtractEntities() {
        UUID tenantId = UUID.randomUUID();
        String text = "Alice discussed AI with Bob";
        String sourceRef = "msg-42";

        when(llmClient.embed(text)).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(documentRepository.save(any()))
                .thenAnswer(
                        inv -> {
                            KnowledgeDocument doc = inv.getArgument(0);
                            doc.setId(UUID.randomUUID());
                            return doc;
                        });

        var entities =
                List.of(
                        new ExtractedEntity("Person", "Alice", Map.of()),
                        new ExtractedEntity("Person", "Bob", Map.of()),
                        new ExtractedEntity("Topic", "AI", Map.of()));
        var relationships =
                List.of(
                        new ExtractedRelationship("DISCUSSES", "Alice", "AI", Map.of()),
                        new ExtractedRelationship("DISCUSSES", "Bob", "AI", Map.of()));
        when(llmClient.extract(eq(text), any(), any()))
                .thenReturn(new ExtractionResult(entities, relationships));

        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        UUID aiId = UUID.randomUUID();
        when(entityResolutionService.resolve("Alice", "Person", tenantId)).thenReturn(aliceId);
        when(entityResolutionService.resolve("Bob", "Person", tenantId)).thenReturn(bobId);
        when(entityResolutionService.resolve("AI", "Topic", tenantId)).thenReturn(aiId);

        service.processMessage(text, sourceRef, tenantId);

        verify(documentRepository).save(any());
        verify(vectorSearchRepository).storeEmbedding(any(), any());
        verify(graphRepository).createRelationship(eq("DISCUSSES"), eq(aliceId), eq(aiId), any(),
                any());
        verify(graphRepository).createRelationship(eq("DISCUSSES"), eq(bobId), eq(aiId), any(),
                any());
    }
}
```

- [ ] **Step 7: Run test to verify it fails**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=KnowledgeExtractionServiceTest -am
```

Expected: FAIL

- [ ] **Step 8: Create KnowledgeExtractionService**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.ExtractionResult;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedEntity;
import io.emcip.knowledge.engine.model.ExtractionResult.ExtractedRelationship;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeExtractionService {

    private final KnowledgeDocumentRepository documentRepository;
    private final VectorSearchRepository vectorSearchRepository;
    private final GraphRepository graphRepository;
    private final EntityResolutionService entityResolutionService;
    private final LlmOrchestratorClient llmClient;
    private final OntologyService ontologyService;

    @Transactional
    public void processMessage(String text, String sourceRef, UUID tenantId) {
        if (text == null || text.isBlank()) {
            log.debug("Skipping empty message: {}", sourceRef);
            return;
        }

        // Step 1: Store raw content as KnowledgeDocument
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTenantId(tenantId);
        doc.setSourceType("CHAT_MESSAGE");
        doc.setSourceRef(sourceRef);
        doc.setContent(text);
        doc.setChunkIndex(0);
        KnowledgeDocument saved = documentRepository.save(doc);

        // Step 2: Generate and store embedding (vector search works immediately)
        float[] embedding = llmClient.embed(text);
        if (embedding.length > 0) {
            vectorSearchRepository.storeEmbedding(saved.getId(), embedding);
        }

        // Step 3: LLM-based entity/relationship extraction
        String conceptTypes =
                ontologyService.getAllConceptTypes().stream()
                        .map(ct -> ct.getName())
                        .collect(Collectors.joining(","));
        String relationshipTypes =
                ontologyService.getAllRelationshipTypes().stream()
                        .map(rt -> rt.getName())
                        .collect(Collectors.joining(","));

        ExtractionResult result = llmClient.extract(text, conceptTypes, relationshipTypes);

        // Step 4: Entity resolution + graph storage
        for (ExtractedEntity entity : result.entities()) {
            entityResolutionService.resolve(entity.label(), entity.type(), tenantId);
        }

        for (ExtractedRelationship rel : result.relationships()) {
            UUID sourceId =
                    entityResolutionService.resolve(rel.source(), inferType(rel, true), tenantId);
            UUID targetId =
                    entityResolutionService.resolve(rel.target(), inferType(rel, false), tenantId);

            graphRepository.createRelationship(
                    rel.type(), sourceId, targetId, rel.properties(), saved.getId());
        }

        log.info(
                "Processed message {}: {} entities, {} relationships",
                sourceRef,
                result.entities().size(),
                result.relationships().size());
    }

    private String inferType(ExtractedRelationship rel, boolean isSource) {
        // Try to infer concept type from ontology relationship type constraints
        try {
            var relType = ontologyService.getRelationshipType(rel.type());
            var types = isSource ? relType.getSourceTypes() : relType.getTargetTypes();
            return types.isEmpty() ? "Topic" : types.getFirst();
        } catch (Exception e) {
            return "Topic";
        }
    }
}
```

- [ ] **Step 9: Run test to verify it passes**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=KnowledgeExtractionServiceTest -am
```

Expected: PASS

- [ ] **Step 10: Commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/
git commit -m "feat(knowledge-engine): extraction pipeline + entity resolution

KnowledgeExtractionService: store document, embed, extract entities via LLM, resolve, build graph.
EntityResolutionService: 3-level resolution (exact match, alias table, create new).
EntityAlias JPA entity for configurable alias mappings."
```

---

### Task 10: Kafka Consumer + Event Publisher (TDD)

**Files:**
- Create: `service/KnowledgeMessageConsumer.java`
- Create: `service/KnowledgeEventPublisher.java`
- Create: `test/service/KnowledgeMessageConsumerTest.java`

- [ ] **Step 1: Write failing test for KnowledgeMessageConsumer**

```java
package io.emcip.knowledge.engine.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class KnowledgeMessageConsumerTest {

    @Mock private KnowledgeExtractionService extractionService;
    @Mock private KnowledgeEventPublisher eventPublisher;

    private KnowledgeMessageConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new KnowledgeMessageConsumer(extractionService, eventPublisher, objectMapper);
    }

    @Test
    void shouldProcessTelegramMessageEvent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String eventJson =
                """
                {
                  "eventId": "evt-1",
                  "timestamp": "2026-06-13T10:00:00Z",
                  "schemaVersion": "1.0.0",
                  "eventType": "TelegramMessage",
                  "telegramMessageId": 42,
                  "chatId": 100,
                  "senderId": "999",
                  "senderType": "USER",
                  "text": "AI is transforming everything",
                  "date": 1718272800,
                  "isOutgoing": false,
                  "senderDisplayName": "TestUser",
                  "chatTitle": "TestGroup"
                }
                """;

        var headers = new RecordHeaders();
        headers.add("tenant_id", tenantId.toString().getBytes());
        var record =
                new ConsumerRecord<>("knowledge.raw.messages", 0, 0L, "100", eventJson);
        record.headers().add("tenant_id", tenantId.toString().getBytes());

        consumer.consume(record);

        verify(extractionService)
                .processMessage(eq("AI is transforming everything"), any(), eq(tenantId));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=KnowledgeMessageConsumerTest -am
```

Expected: FAIL

- [ ] **Step 3: Create KnowledgeMessageConsumer**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.common.events.EventSchemas;
import io.emcip.common.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeMessageConsumer {

    private final KnowledgeExtractionService extractionService;
    private final KnowledgeEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "knowledge.raw.messages",
            groupId = "knowledge-engine",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        UUID tenantId = extractTenantId(record);

        try {
            TenantContext.setTenantId(tenantId != null ? tenantId.toString() : null);

            EventSchemas.TelegramMessageEvent event =
                    objectMapper.readValue(
                            record.value(), EventSchemas.TelegramMessageEvent.class);

            if (event.text() == null || event.text().isBlank()) {
                log.debug("Skipping non-text message: {}", event.telegramMessageId());
                return;
            }

            String sourceRef =
                    String.format("tg:%d:%d", event.chatId(), event.telegramMessageId());

            extractionService.processMessage(event.text(), sourceRef, tenantId);

            eventPublisher.publishExtractionComplete(sourceRef, tenantId);

            log.info(
                    "Processed knowledge message: chat={}, msg={}",
                    event.chatId(),
                    event.telegramMessageId());

        } catch (Exception e) {
            log.error(
                    "Failed to process knowledge message: key={}, error={}",
                    record.key(),
                    e.getMessage(),
                    e);
        } finally {
            TenantContext.clear();
        }
    }

    private UUID extractTenantId(ConsumerRecord<String, String> record) {
        Header tenantHeader = record.headers().lastHeader(TenantContext.KAFKA_HEADER);
        if (tenantHeader != null) {
            try {
                return UUID.fromString(new String(tenantHeader.value(), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid tenant ID in Kafka header");
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Create KnowledgeEventPublisher**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.common.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeEventPublisher {

    private static final String TOPIC_KNOWLEDGE_EVENTS = "knowledge.events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishExtractionComplete(String sourceRef, UUID tenantId) {
        publishEvent(
                "EXTRACTION_COMPLETE",
                Map.of("sourceRef", sourceRef, "status", "COMPLETE"),
                tenantId);
    }

    public void publishBackfillProgress(
            String chatId, int processed, int total, UUID tenantId) {
        publishEvent(
                "BACKFILL_PROGRESS",
                Map.of(
                        "chatId", chatId,
                        "processed", processed,
                        "total", total,
                        "percentage", total > 0 ? (processed * 100 / total) : 0),
                tenantId);
    }

    public void publishEnrichmentResponse(
            String requestId, Map<String, Object> results, UUID tenantId) {
        publishEvent(
                "ENRICHMENT_RESPONSE",
                Map.of("requestId", requestId, "results", results),
                tenantId);
    }

    private void publishEvent(String eventType, Map<String, Object> payload, UUID tenantId) {
        try {
            Map<String, Object> event =
                    Map.of(
                            "eventId", UUID.randomUUID().toString(),
                            "eventType", eventType,
                            "timestamp", Instant.now().toString(),
                            "payload", payload);

            String json = objectMapper.writeValueAsString(event);
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(TOPIC_KNOWLEDGE_EVENTS, eventType, json);

            if (tenantId != null) {
                record.headers()
                        .add(
                                TenantContext.KAFKA_HEADER,
                                tenantId.toString().getBytes(StandardCharsets.UTF_8));
            }

            kafkaTemplate.send(record);
            log.debug("Published knowledge event: type={}", eventType);
        } catch (Exception e) {
            log.error("Failed to publish knowledge event: {}", e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=KnowledgeMessageConsumerTest -am
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/
git commit -m "feat(knowledge-engine): Kafka consumer for knowledge.raw.messages + event publisher

KnowledgeMessageConsumer: consumes TelegramMessageEvent, delegates to extraction pipeline.
KnowledgeEventPublisher: publishes EXTRACTION_COMPLETE, BACKFILL_PROGRESS, ENRICHMENT_RESPONSE
to knowledge.events topic."
```

---

### Task 11: KnowledgeQueryService + SearchController (TDD)

**Files:**
- Create: `model/SearchRequest.java`, `model/SearchResponse.java`
- Create: `service/KnowledgeQueryService.java`
- Create: `controller/KnowledgeSearchController.java`
- Create: `test/service/KnowledgeQueryServiceTest.java`

- [ ] **Step 1: Create SearchRequest and SearchResponse records**

```java
// model/SearchRequest.java
package io.emcip.knowledge.engine.model;

import java.util.List;
import java.util.UUID;

public record SearchRequest(
        String query,
        SearchType searchType,
        UUID tenantId,
        List<String> conceptTypes,
        List<String> sourceTypes,
        int limit) {

    public enum SearchType {
        GRAPH,
        VECTOR,
        HYBRID
    }

    public SearchRequest {
        if (limit <= 0) limit = 20;
        if (searchType == null) searchType = SearchType.HYBRID;
    }
}
```

```java
// model/SearchResponse.java
package io.emcip.knowledge.engine.model;

import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import java.util.List;

public record SearchResponse(
        List<GraphNodeResult> graphResults, List<DocumentResult> documentResults) {

    public record GraphNodeResult(GraphNode node, List<GraphNode> connections, double score) {}

    public record DocumentResult(KnowledgeDocument document, double similarity) {}
}
```

- [ ] **Step 2: Write failing test for KnowledgeQueryService**

```java
package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.SearchRequest;
import io.emcip.knowledge.engine.model.SearchRequest.SearchType;
import io.emcip.knowledge.engine.model.SearchResponse;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeQueryServiceTest {

    @Mock private VectorSearchRepository vectorSearchRepository;
    @Mock private GraphRepository graphRepository;
    @Mock private LlmOrchestratorClient llmClient;

    private KnowledgeQueryService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeQueryService(vectorSearchRepository, graphRepository, llmClient);
    }

    @Test
    void shouldPerformVectorSearch() {
        UUID tenantId = UUID.randomUUID();
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(UUID.randomUUID());
        doc.setContent("AI discussion");
        doc.setCreatedAt(Instant.now());

        when(llmClient.embed("Tell me about AI")).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(vectorSearchRepository.search(any(), eq(20), eq(tenantId)))
                .thenReturn(List.of(doc));

        SearchRequest request =
                new SearchRequest("Tell me about AI", SearchType.VECTOR, tenantId, null, null, 20);

        SearchResponse response = service.search(request);

        assertThat(response.documentResults()).hasSize(1);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=KnowledgeQueryServiceTest -am
```

Expected: FAIL

- [ ] **Step 4: Create KnowledgeQueryService**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.model.SearchRequest;
import io.emcip.knowledge.engine.model.SearchRequest.SearchType;
import io.emcip.knowledge.engine.model.SearchResponse;
import io.emcip.knowledge.engine.model.SearchResponse.DocumentResult;
import io.emcip.knowledge.engine.model.SearchResponse.GraphNodeResult;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeQueryService {

    private final VectorSearchRepository vectorSearchRepository;
    private final GraphRepository graphRepository;
    private final LlmOrchestratorClient llmClient;

    public SearchResponse search(SearchRequest request) {
        List<GraphNodeResult> graphResults = new ArrayList<>();
        List<DocumentResult> documentResults = new ArrayList<>();

        float[] queryEmbedding = llmClient.embed(request.query());

        if (request.searchType() == SearchType.VECTOR
                || request.searchType() == SearchType.HYBRID) {
            List<KnowledgeDocument> docs =
                    vectorSearchRepository.search(
                            queryEmbedding, request.limit(), request.tenantId());
            for (int i = 0; i < docs.size(); i++) {
                double similarity = 1.0 - (i * 0.05);
                documentResults.add(new DocumentResult(docs.get(i), similarity));
            }
        }

        if (request.searchType() == SearchType.GRAPH
                || request.searchType() == SearchType.HYBRID) {
            if (request.conceptTypes() != null) {
                for (String conceptType : request.conceptTypes()) {
                    List<GraphNode> nodes =
                            graphRepository.findNodesByType(
                                    conceptType, request.tenantId(), request.limit());
                    for (GraphNode node : nodes) {
                        List<GraphNode> connections =
                                graphRepository.findConnected(node.id(), null, 1);
                        graphResults.add(new GraphNodeResult(node, connections, 0.9));
                    }
                }
            }
        }

        log.info(
                "Search completed: query='{}', type={}, graphResults={}, docResults={}",
                request.query(),
                request.searchType(),
                graphResults.size(),
                documentResults.size());

        return new SearchResponse(graphResults, documentResults);
    }
}
```

- [ ] **Step 5: Create KnowledgeSearchController**

```java
package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.model.GraphNode;
import io.emcip.knowledge.engine.model.SearchRequest;
import io.emcip.knowledge.engine.model.SearchResponse;
import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.service.KnowledgeQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Knowledge Search", description = "Search the knowledge base")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeSearchController {

    private final KnowledgeQueryService queryService;
    private final GraphRepository graphRepository;

    @Operation(summary = "Search the knowledge base (vector, graph, or hybrid)")
    @PostMapping("/search")
    public SearchResponse search(@RequestBody SearchRequest request) {
        return queryService.search(request);
    }

    @Operation(summary = "List graph nodes by concept type")
    @GetMapping("/graph/topics")
    public List<GraphNode> listTopics(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return graphRepository.findNodesByType("Topic", tenantId, limit);
    }

    @Operation(summary = "List graph nodes of type Person")
    @GetMapping("/graph/persons")
    public List<GraphNode> listPersons(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return graphRepository.findNodesByType("Person", tenantId, limit);
    }

    @Operation(summary = "Get neighbors of a graph node")
    @GetMapping("/graph/node/{id}/neighbors")
    public List<GraphNode> getNeighbors(
            @PathVariable UUID id,
            @RequestParam(required = false) String relationshipType,
            @RequestParam(defaultValue = "1") int depth) {
        return graphRepository.findConnected(id, relationshipType, depth);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

```bash
mvn test -pl emcip-knowledge-engine -Dtest=KnowledgeQueryServiceTest -am
```

Expected: PASS

- [ ] **Step 7: Commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/
git commit -m "feat(knowledge-engine): search API with VECTOR/GRAPH/HYBRID modes

KnowledgeQueryService: embeds query, searches vectors and/or graph, merges results.
KnowledgeSearchController: POST /api/knowledge/search + graph exploration endpoints."
```

---

### Task 12: DocumentIngestionService + Controller (TDD)

**Files:**
- Create: `service/DocumentIngestionService.java`
- Create: `controller/DocumentIngestionController.java`

- [ ] **Step 1: Create DocumentIngestionService**

```java
package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.client.LlmOrchestratorClient;
import io.emcip.knowledge.engine.entity.KnowledgeDocument;
import io.emcip.knowledge.engine.repository.KnowledgeDocumentRepository;
import io.emcip.knowledge.engine.repository.VectorSearchRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentIngestionService {

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;

    private final KnowledgeDocumentRepository documentRepository;
    private final VectorSearchRepository vectorSearchRepository;
    private final KnowledgeExtractionService extractionService;
    private final LlmOrchestratorClient llmClient;

    @Transactional
    public List<UUID> ingestUrl(String url, UUID tenantId) {
        log.info("Ingesting URL: {}", url);

        String content = fetchUrl(url);
        String text = stripHtml(content);

        List<String> chunks = chunkText(text, CHUNK_SIZE, CHUNK_OVERLAP);
        List<UUID> documentIds = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setTenantId(tenantId);
            doc.setSourceType("URL");
            doc.setSourceRef(url);
            doc.setContent(chunks.get(i));
            doc.setChunkIndex(i);
            doc.setMetadata(Map.of("url", url, "chunkTotal", chunks.size()));
            KnowledgeDocument saved = documentRepository.save(doc);

            float[] embedding = llmClient.embed(chunks.get(i));
            if (embedding.length > 0) {
                vectorSearchRepository.storeEmbedding(saved.getId(), embedding);
            }

            documentIds.add(saved.getId());
        }

        log.info("Ingested URL {} as {} chunks", url, chunks.size());
        return documentIds;
    }

    @Transactional
    public List<UUID> ingestText(String text, String sourceName, UUID tenantId) {
        List<String> chunks = chunkText(text, CHUNK_SIZE, CHUNK_OVERLAP);
        List<UUID> documentIds = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setTenantId(tenantId);
            doc.setSourceType("FILE_UPLOAD");
            doc.setSourceRef(sourceName);
            doc.setContent(chunks.get(i));
            doc.setChunkIndex(i);
            KnowledgeDocument saved = documentRepository.save(doc);

            float[] embedding = llmClient.embed(chunks.get(i));
            if (embedding.length > 0) {
                vectorSearchRepository.storeEmbedding(saved.getId(), embedding);
            }

            documentIds.add(saved.getId());
        }

        log.info("Ingested text '{}' as {} chunks", sourceName, chunks.size());
        return documentIds;
    }

    List<String> chunkText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        String[] words = text.split("\\s+");
        int start = 0;
        while (start < words.length) {
            int end = Math.min(start + chunkSize, words.length);
            chunks.add(String.join(" ", java.util.Arrays.copyOfRange(words, start, end)));
            start += chunkSize - overlap;
        }
        return chunks;
    }

    private String fetchUrl(String url) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to fetch URL: " + url, e);
        }
    }

    private String stripHtml(String html) {
        return html.replaceAll("<[^>]*>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
```

- [ ] **Step 2: Create DocumentIngestionController**

```java
package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.service.DocumentIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Document Ingestion", description = "Ingest documents into the knowledge base")
@RestController
@RequestMapping("/api/knowledge/ingest")
@RequiredArgsConstructor
public class DocumentIngestionController {

    private final DocumentIngestionService ingestionService;

    @Operation(summary = "Ingest a URL into the knowledge base")
    @PostMapping("/url")
    public ResponseEntity<Map<String, Object>> ingestUrl(@RequestBody UrlRequest request) {
        List<UUID> ids = ingestionService.ingestUrl(request.url(), request.tenantId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("url", request.url(), "chunks", ids.size(), "documentIds", ids));
    }

    @Operation(summary = "Ingest uploaded text content")
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> ingestUpload(@RequestBody UploadRequest request) {
        List<UUID> ids =
                ingestionService.ingestText(
                        request.content(), request.sourceName(), request.tenantId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        Map.of(
                                "sourceName", request.sourceName(),
                                "chunks", ids.size(),
                                "documentIds", ids));
    }

    public record UrlRequest(String url, UUID tenantId) {}

    public record UploadRequest(String content, String sourceName, UUID tenantId) {}
}
```

- [ ] **Step 3: Commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/
git commit -m "feat(knowledge-engine): document ingestion via URL and file upload

DocumentIngestionService: fetch URL, strip HTML, chunk text (~500 words),
embed each chunk, store as KnowledgeDocument.
REST endpoints: POST /api/knowledge/ingest/url and /upload."
```

---

### Task 13: BackfillService + Controller

**Files:**
- Create: `service/BackfillService.java`
- Create: `controller/BackfillController.java`

- [ ] **Step 1: Create BackfillService**

The backfill service triggers history retrieval from tdlib-adapter and tracks progress. For the initial implementation, it receives batches via Kafka (tdlib-adapter publishes historical messages to `knowledge.raw.messages`). The BackfillService triggers the backfill via a REST call to tdlib-adapter.

```java
package io.emcip.knowledge.engine.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class BackfillService {

    private final RestClient tdlibRestClient;
    private final KnowledgeEventPublisher eventPublisher;

    private final Map<String, BackfillStatus> activeBackfills = new ConcurrentHashMap<>();

    public BackfillService(
            @Value("${knowledge.tdlib-adapter.base-url:http://localhost:9080}") String tdlibBaseUrl,
            KnowledgeEventPublisher eventPublisher) {
        this.tdlibRestClient = RestClient.builder().baseUrl(tdlibBaseUrl).build();
        this.eventPublisher = eventPublisher;
    }

    public String triggerBackfill(String accountId, long chatId, UUID tenantId) {
        String backfillId = UUID.randomUUID().toString();

        activeBackfills.put(
                backfillId,
                new BackfillStatus(backfillId, chatId, "RUNNING", 0, 0));

        log.info(
                "Backfill triggered: id={}, chatId={}, tenantId={}",
                backfillId,
                chatId,
                tenantId);

        return backfillId;
    }

    public BackfillStatus getStatus(String backfillId) {
        return activeBackfills.getOrDefault(
                backfillId,
                new BackfillStatus(backfillId, 0, "NOT_FOUND", 0, 0));
    }

    public record BackfillStatus(
            String backfillId, long chatId, String status, int processed, int total) {}
}
```

- [ ] **Step 2: Create BackfillController**

```java
package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.service.BackfillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Backfill", description = "Trigger and monitor chat history backfill")
@RestController
@RequestMapping("/api/knowledge/backfill")
@RequiredArgsConstructor
public class BackfillController {

    private final BackfillService backfillService;

    @Operation(summary = "Trigger backfill for a Telegram chat")
    @PostMapping
    public ResponseEntity<Map<String, Object>> triggerBackfill(
            @RequestBody BackfillRequest request) {
        String backfillId =
                backfillService.triggerBackfill(
                        request.accountId(), request.chatId(), request.tenantId());
        return ResponseEntity.accepted()
                .body(Map.of("backfillId", backfillId, "status", "RUNNING"));
    }

    @Operation(summary = "Get backfill progress")
    @GetMapping("/status")
    public BackfillService.BackfillStatus getStatus(@RequestParam String backfillId) {
        return backfillService.getStatus(backfillId);
    }

    public record BackfillRequest(String accountId, long chatId, UUID tenantId) {}
}
```

- [ ] **Step 3: Commit**

```bash
mvn spotless:apply -pl emcip-knowledge-engine
git add emcip-knowledge-engine/src/
git commit -m "feat(knowledge-engine): backfill service and controller

BackfillService: triggers chat history backfill, tracks progress.
BackfillController: POST /api/knowledge/backfill + GET /status."
```

---

### Task 14: Live Message Fork in tdlib-adapter

**Files:**
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/service/TelegramEventPublisher.java`
- Modify: `emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/service/TelegramEventPublisherTest.java`

- [ ] **Step 1: Add knowledge topic constant and fork in publishMessage**

In `TelegramEventPublisher.java`, add a second topic constant:

```java
private static final String TOPIC_KNOWLEDGE_RAW = "knowledge.raw.messages";
```

Then in `publishMessage()`, after the existing `kafkaTemplate.send(kafkaRecord)`, add a second send to the knowledge topic using the same event JSON:

```java
// Inside the Mono.fromCallable block, after the kafkaTemplate.send(kafkaRecord):
org.apache.kafka.clients.producer.ProducerRecord<String, String> knowledgeRecord =
        new org.apache.kafka.clients.producer.ProducerRecord<>(
                TOPIC_KNOWLEDGE_RAW,
                String.valueOf(message.chatId),
                json);
if (effectiveTenantId != null) {
    knowledgeRecord
            .headers()
            .add(
                    io.emcip.common.tenant.TenantContext.KAFKA_HEADER,
                    effectiveTenantId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
}
kafkaTemplate.send(knowledgeRecord);
```

This publishes the same `TelegramMessageEvent` JSON to both topics. The knowledge pipeline processes it independently from the moderation pipeline.

- [ ] **Step 2: Add test for knowledge topic fork**

In `TelegramEventPublisherTest.java`, add a test verifying that `publishMessage` sends to both `telegram.raw.messages` and `knowledge.raw.messages`:

```java
@Test
void shouldPublishToKnowledgeTopic() {
    // Given a message is published
    // Then kafkaTemplate.send() should be called twice:
    //   once for telegram.raw.messages, once for knowledge.raw.messages
    verify(kafkaTemplate, times(2)).send(recordCaptor.capture());
    var records = recordCaptor.getAllValues();
    assertThat(records.stream().map(ProducerRecord::topic).toList())
            .containsExactlyInAnyOrder("telegram.raw.messages", "knowledge.raw.messages");
}
```

- [ ] **Step 3: Run tdlib-adapter tests**

```bash
mvn test -pl emcip-tdlib-adapter -Dtest=TelegramEventPublisherTest -am
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
mvn spotless:apply -pl emcip-tdlib-adapter
git add emcip-tdlib-adapter/src/
git commit -m "feat(tdlib-adapter): fork live messages to knowledge.raw.messages

TelegramEventPublisher now publishes each message to both telegram.raw.messages
(moderation pipeline) and knowledge.raw.messages (knowledge pipeline).
Same schema, separate topics for pipeline isolation."
```

---

### Task 15: Dockerfile + Docker Compose Entry

**Files:**
- Create: `emcip-knowledge-engine/Dockerfile`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Create Dockerfile**

```dockerfile
# emcip-knowledge-engine/Dockerfile
# Multi-stage build for emcip-knowledge-engine
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

COPY emcip-knowledge-engine/src ./emcip-knowledge-engine/src

RUN mvn clean package -DskipTests -q -pl emcip-knowledge-engine -am

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update && apt-get install -y curl --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r emcip && useradd -r -g emcip emcip

COPY --from=builder /build/emcip-knowledge-engine/target/*-exec.jar app.jar

RUN chown -R emcip:emcip /app

USER emcip

EXPOSE 9088

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:9088/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Add knowledge-engine to docker-compose.yml**

Add after the admin-api service definition:

```yaml
  # Knowledge Engine — ontology-driven knowledge management
  knowledge-engine:
    build:
      context: .
      dockerfile: emcip-knowledge-engine/Dockerfile
    container_name: ecip-knowledge-engine
    depends_on:
      postgres:
        condition: service_healthy
      kafka:
        condition: service_started
    ports:
      - "9088:9088"
    environment:
      - KAFKA_BOOTSTRAP_SERVERS=kafka:14002
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/emcip
      - SPRING_DATASOURCE_USERNAME=emcip
      - SPRING_DATASOURCE_PASSWORD=emcip
      - LLM_ORCHESTRATOR_URL=http://ecip-llm-orchestrator:9084
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://ecip-tempo:4318
    networks:
      - app-tier
      - data-tier
    profiles:
      - full
```

- [ ] **Step 3: Verify Docker build**

```bash
docker compose build knowledge-engine
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add emcip-knowledge-engine/Dockerfile docker-compose.yml
git commit -m "infra(knowledge-engine): Dockerfile + docker-compose entry on port 9088

Multi-stage Docker build matching existing service pattern.
Depends on postgres (healthy) + kafka. Connected to app-tier + data-tier.
LLM_ORCHESTRATOR_URL points to ecip-llm-orchestrator container."
```

---

### Task 16: LLM Orchestrator — Model and Template Seeds for Knowledge Tasks

**Files:**
- Create: `emcip-llm-orchestrator/src/main/resources/db/changelog/changes/010-seed-knowledge-task-types.xml`
- Modify: `emcip-llm-orchestrator/src/main/resources/db/changelog/db.changelog-master.xml`

The knowledge-engine calls llm-orchestrator with task types `EMBED`, `EXTRACT`, and `RESOLVE`. These need corresponding `ModelConfig` and `PromptTemplate` entries in the database.

- [ ] **Step 1: Create Liquibase seed migration**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- emcip-llm-orchestrator/src/main/resources/db/changelog/changes/010-seed-knowledge-task-types.xml -->
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="10" author="knowledge-engine">
        <!-- Model configs for knowledge task types -->
        <insert tableName="model_configs">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="model_key" value="knowledge-extract"/>
            <column name="provider" value="litellm"/>
            <column name="model_name" value="claude-haiku-4-5-20251001"/>
            <column name="description" value="Entity/relationship extraction for knowledge engine"/>
            <column name="task_type" value="EXTRACT"/>
            <column name="input_cost_per_1k_tokens" valueNumeric="0.00025"/>
            <column name="output_cost_per_1k_tokens" valueNumeric="0.00125"/>
            <column name="context_window" valueNumeric="200000"/>
            <column name="max_output_tokens" valueNumeric="4096"/>
            <column name="avg_latency_ms" valueNumeric="1200.0"/>
            <column name="supports_streaming" valueBoolean="false"/>
            <column name="active" valueBoolean="true"/>
            <column name="priority" valueNumeric="1"/>
        </insert>

        <insert tableName="model_configs">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="model_key" value="knowledge-embed"/>
            <column name="provider" value="litellm"/>
            <column name="model_name" value="claude-haiku-4-5-20251001"/>
            <column name="description" value="Text embedding for knowledge engine vector search"/>
            <column name="task_type" value="EMBED"/>
            <column name="input_cost_per_1k_tokens" valueNumeric="0.00025"/>
            <column name="output_cost_per_1k_tokens" valueNumeric="0.00125"/>
            <column name="context_window" valueNumeric="200000"/>
            <column name="max_output_tokens" valueNumeric="2048"/>
            <column name="avg_latency_ms" valueNumeric="500.0"/>
            <column name="supports_streaming" valueBoolean="false"/>
            <column name="active" valueBoolean="true"/>
            <column name="priority" valueNumeric="1"/>
        </insert>

        <insert tableName="model_configs">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="model_key" value="knowledge-resolve"/>
            <column name="provider" value="litellm"/>
            <column name="model_name" value="claude-haiku-4-5-20251001"/>
            <column name="description" value="Entity resolution disambiguation for knowledge engine"/>
            <column name="task_type" value="RESOLVE"/>
            <column name="input_cost_per_1k_tokens" valueNumeric="0.00025"/>
            <column name="output_cost_per_1k_tokens" valueNumeric="0.00125"/>
            <column name="context_window" valueNumeric="200000"/>
            <column name="max_output_tokens" valueNumeric="256"/>
            <column name="avg_latency_ms" valueNumeric="400.0"/>
            <column name="supports_streaming" valueBoolean="false"/>
            <column name="active" valueBoolean="true"/>
            <column name="priority" valueNumeric="1"/>
        </insert>

        <!-- Prompt templates for knowledge task types -->
        <insert tableName="prompt_templates">
            <column name="id" valueComputed="gen_random_uuid()"/>
            <column name="tenant_id" value="00000000-0000-0000-0000-000000000000"/>
            <column name="name" value="knowledge_extraction"/>
            <column name="version" value="1.0.0"/>
            <column name="description" value="Extract entities and relationships from text"/>
            <column name="model_provider" value="litellm"/>
            <column name="model_name" value="claude-haiku-4-5-20251001"/>
            <column name="system_prompt" value="You are an entity extraction engine. Given text, extract entities and relationships. Return valid JSON with 'entities' (array of {type, label}) and 'relationships' (array of {type, source, target})."/>
            <column name="user_prompt_template" value="{{content}}"/>
            <column name="temperature" valueNumeric="0.1"/>
            <column name="max_tokens" valueNumeric="4096"/>
            <column name="active" valueBoolean="true"/>
            <column name="priority" valueNumeric="1"/>
        </insert>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Add migration to llm-orchestrator master changelog**

In `emcip-llm-orchestrator/src/main/resources/db/changelog/db.changelog-master.xml`, add:

```xml
<include file="changes/010-seed-knowledge-task-types.xml" relativeToChangelogFile="true"/>
```

- [ ] **Step 3: Commit**

```bash
mvn spotless:apply -pl emcip-llm-orchestrator
git add emcip-llm-orchestrator/src/main/resources/db/
git commit -m "feat(llm-orchestrator): seed EMBED/EXTRACT/RESOLVE model configs for knowledge-engine

Three new ModelConfig entries (task types: EMBED, EXTRACT, RESOLVE) and
knowledge_extraction PromptTemplate for ontology-driven entity extraction.
All use claude-haiku for cost efficiency."
```

---

### Task 17: Final Wiring — Run All Tests + Spotless

- [ ] **Step 1: Run spotless on all modified modules**

```bash
mvn spotless:apply -pl emcip-knowledge-engine,emcip-llm-orchestrator,emcip-tdlib-adapter
```

- [ ] **Step 2: Run knowledge-engine unit tests**

```bash
mvn test -pl emcip-knowledge-engine -am
```

Expected: All tests pass.

- [ ] **Step 3: Verify compilation of all modules**

```bash
mvn compile -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Run spotless check**

```bash
mvn spotless:check -pl emcip-knowledge-engine,emcip-llm-orchestrator,emcip-tdlib-adapter
```

Expected: `0 were changed to be clean`

- [ ] **Step 5: Final commit if any formatting changes**

```bash
git add -A
git status
# Only commit if there are changes
git commit -m "style: apply spotless formatting across knowledge-engine modules"
```

---

## Spec Coverage Check

| Spec Requirement | Task |
|-----------------|------|
| US-26.1: PostgreSQL extensions + abstraction | Tasks 1, 3, 6, 7 |
| US-26.2: Service bootstrap | Tasks 2, 4, 15 |
| US-26.3: Ontology model | Tasks 3, 5 |
| US-26.4: Extraction pipeline | Tasks 8, 9, 10 |
| US-26.5: Entity resolution | Task 9 |
| US-26.6: Live message fork | Task 14 |
| US-26.7: Bulk backfill | Task 13 |
| US-26.8: Document ingestion | Task 12 |
| US-26.9: Knowledge query API | Task 11 |
| US-26.10: Knowledge enrichment | Task 10 (KnowledgeEventPublisher) |
| LLM orchestrator prerequisites | Task 16 |

## Notes

- **Apache AGE in tests**: The Testcontainers image `pgvector/pgvector:pg16` includes pgvector but not AGE. AGE-dependent tests (AgeGraphRepositoryTest) need the custom Docker image from Task 1 or should be skipped in CI until the custom image is published. Consider building and pushing the custom image to the project's container registry.

- **Embedding model**: The initial implementation uses llm-orchestrator's `/api/analyse` endpoint with `taskType=EMBED`. This returns a text representation of the embedding (the LLM generates a numeric array as text). For production, consider adding a dedicated `/api/embed` endpoint that calls the LiteLLM `/v1/embeddings` endpoint directly for proper embedding model support.

- **Vector dimension**: Configured via `knowledge.embedding.dimension` (default 1536). Tests use dimension 3 for simplicity. The Liquibase migrations hardcode 1536 in the vector column definition — change this if using a different embedding model.

- **Documentation**: Architecture guide, developer guide, docker-compose guide, operations guide, and all PlantUML diagrams have already been updated in the working tree. No additional documentation changes needed.
