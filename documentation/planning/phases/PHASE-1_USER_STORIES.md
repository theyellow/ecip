# PHASE-1_USER_STORIES.md

## Phase 1: Foundation & Infrastructure – Epics & User Stories

---

### Epic 1.1: Monorepo & CI/CD Setup
- [x] US-1.1.1: Create Maven parent POM and initial module structure
- [x] US-1.1.2: Set up Git repository and branching strategy
- [x] US-1.1.3: Implement CI/CD pipeline (Maven, GitHub Actions/Jenkins)
- [x] US-1.1.4: Add code quality checks (Spotless, Checkstyle, PMD)
- [x] US-1.1.5: Write initial README and contribution guidelines

### Epic 1.2: Spring Boot 4 Service Skeletons
- [x] US-1.2.1: Generate base Spring Boot 4 projects for all planned services (WebFlux, Actuator, Security)
- [x] US-1.2.2: Add Dockerfiles for each service
- [x] US-1.2.3: Integrate basic health endpoints

### Epic 1.3: Kafka & PostgreSQL Local Integration
- [ ] US-1.3.1: Set up local Kafka broker (Docker Compose)
- [ ] US-1.3.2: Define initial topics and event schemas (Avro/JSON)
- [ ] US-1.3.3: Set up local PostgreSQL instance (Docker Compose)
- [ ] US-1.3.4: Integrate Flyway/Liquibase for DB migrations
- [ ] US-1.3.5: Implement health checks for Kafka and PostgreSQL

### Epic 1.4: Initial ADRs & Architecture Docs
- [ ] US-1.4.1: Write ADRs for stack, event backbone, persistence
- [ ] US-1.4.2: Create initial architecture diagram (C4, sequence)
- [ ] US-1.4.3: Document local setup and onboarding steps

---

# User Stories (Details)

## US-1.1.1: Create Maven parent POM and initial module structure
**Overview:**
Set up a Maven-based monorepo with a parent POM and initial module structure for all planned services. This is the foundation for all further development and enables modularization, code reuse, and clear separation of concerns.

**Details:**
- Create a new Git repository (see also ExtendedTechnical.md Step 1).
- Add a parent POM with common plugin/config management (spotless, sortpom, enforcer, surefire, failsafe, jacoco, checkstyle/pmd recommended).
- Add submodules for each service (see MinimalIdeaTechnical.md: telegram-tdlib-adapter, conversation-context, intent-classifier, policy-engine, llm-orchestrator, etc.).
- Document the structure in the README.
- Use the folder structure as described in MinimalIdeaTechnical.md.

**Acceptance Criteria:**
- Repository contains parent POM and at least 3 service modules.
- All modules build with `mvn clean install`.
- Structure is documented in README.
- Plugins for code quality and formatting are configured.

**In-scope:**
- Java, Maven, Git
**Out-of-scope:**
- Service implementation, business logic

**Hints:**
- Use Maven's `<modules>` section.
- Look at Spring Initializr for base projects.
- See ExtendedTechnical.md for bootstrap commands and module list.
- This step is critical for onboarding and future scalability.

---

## US-1.1.2: Set up Git repository and branching strategy
**Overview:**
Establish a Git repository with a clear branching strategy to manage development, ensure code quality, and facilitate collaboration.

**Details:**
- Create a new Git repository for the project.
- Define a branching strategy (e.g., Git Flow, GitHub Flow).
- Set up main branches (e.g., `main`, `develop`) and protection rules.
- Document the Git workflow and branching strategy in the repository.

**Implementation Notes (Completed):**
- Repository already exists at `git@github.com:theyellow/ecip.git`
- Git configured: user.name="Benjamin Marstaller", user.email="theyellow@gmx.de"
- Branching strategy: GitHub Flow (main + feature branches, PR-based)
- Created `CONTRIBUTING.md` with complete GitHub Flow documentation
- Current branch: `ecip-initial-setup-phase` (pushed to origin)
- All US-1.1.1 code committed with conventional commit messages

**Acceptance Criteria:**
- ✅ Git repository is created and accessible.
- ✅ Branching strategy is defined and documented.
- ⏳ Main branches are set up with protection rules (requires GitHub UI setup).

**In-scope:**
- Git repository, branching strategy
**Out-of-scope:**
- Code implementation, CI/CD integration

**Hints:**
- Use GitHub or GitLab for repository hosting.
- Clearly define branch protection rules to enforce code reviews and CI checks.
- Document the Git workflow in the repository's README.

**Next Step:** Set up branch protection rules in GitHub UI (see checklist below).

---

## US-1.1.3: Implement CI/CD pipeline (Maven, GitHub Actions/Jenkins)
**Overview:**
Implement a CI/CD pipeline for automated builds and tests on every push. This ensures code quality, fast feedback, and enables team collaboration from day one.

**Details:**
- Use GitHub Actions or Jenkins (see ExtendedTechnical.md prerequisites: Maven 3.9+).
- Build all modules, run tests, and show status badge in README.
- Fail build on code style or test errors.
- Integrate code quality checks (spotless, checkstyle, pmd, jacoco).

**Implementation Notes (Completed):**
- Created `.github/workflows/maven.yml` with two parallel jobs:
  1. **build job**: compile, test, JaCoCo coverage report, artifact upload
  2. **code-quality job**: compile, Spotless check, Checkstyle, PMD
- Triggers: push to main/feature branches, pull requests to main
- JDK 21 with Temurin distribution
- Maven dependency caching for faster builds
- Status badge added to README.md
- Workflow file location: `.github/workflows/maven.yml`

**Acceptance Criteria:**
- ✅ Pipeline runs on every push/PR.
- ✅ Status badge visible in README.
- ✅ Fails on test or style errors.
- ✅ Test coverage is reported (JaCoCo artifact uploaded).

**In-scope:**
- Build, test, style check
**Out-of-scope:**
- Deployment to production

**Hints:**
- Use Spotless, Checkstyle plugins.
- Use matrix builds for multi-module.
- See MinimalIdeaTechnical.md for recommended plugins.
- Early CI/CD setup prevents technical debt.

---

## US-1.1.4: Add code quality checks (Spotless, Checkstyle, PMD)
**Overview:**
Integrate code quality checks into the build process to enforce coding standards and detect issues early.

**Details:**
- Configure Spotless for code formatting.
- Set up Checkstyle and PMD for static code analysis.
- Integrate code quality checks into the CI/CD pipeline.
- Document code quality standards and how to run checks locally.

**Implementation Notes (Completed):**
All code quality tools configured in parent POM and verified in CI/CD:
- **Spotless**: Google Java Format (AOSP), check-only enforcement
- **SortPOM**: POM file ordering consistency
- **Maven Enforcer**: Requires Maven 3.8.0+, Java 21+
- **JaCoCo**: 80% minimum test coverage (blocking)
- **Checkstyle**: Google checks, warning only (not blocking)
- **PMD**: Medium priority, warning only (not blocking)
- **CI/CD Integration**: All checks run in GitHub Actions
- **Documentation**: Created CODE_QUALITY.md with usage instructions

**Acceptance Criteria:**
- ✅ Code quality checks are integrated into the build process (parent POM + CI/CD).
- ✅ Documentation for code quality standards is available (CODE_QUALITY.md).
- ✅ Developers receive feedback on code quality in pull requests (GitHub Actions).

**In-scope:**
- Code quality tools (Spotless, Checkstyle, PMD)
**Out-of-scope:**
- Detailed code reviews, refactoring

**Hints:**
- Use Maven plugins for Spotless, Checkstyle, and PMD integration.
- Define a baseline configuration for code quality checks.
- Encourage developers to run code quality checks locally before committing.

---

## US-1.1.5: Write initial README and contribution guidelines
**Overview:**
Create an initial README file and contribution guidelines to assist new developers in understanding the project setup, development process, and how to contribute.

**Details:**
- Write a README file that includes:
  - Project overview and goals.
  - Development setup instructions.
  - Build and run instructions.
  - Testing guidelines.
  - Code quality standards.
- Create a CONTRIBUTING.md file with contribution guidelines.
- Document the process for reporting issues and submitting pull requests.

**Implementation Notes (Completed):**
- **README.md**: Complete project documentation including:
  - Project overview and TDLib-first approach
  - Maven coordinates and module structure
  - Service ports table (9080-9087)
  - Prerequisites (Java 21+, Maven 3.8+)
  - Build instructions (mvn clean install)
  - Code quality standards and thresholds
  - Architecture and technology references
  - CI/CD status badge
- **CONTRIBUTING.md**: Complete contribution guide including:
  - GitHub Flow branching strategy
  - Conventional commit message format
  - Code quality requirements checklist
  - Local development setup
  - Branch protection recommendations
- **PR Template**: `.github/pull_request_template.md` with checklist

**Acceptance Criteria:**
- ✅ README and CONTRIBUTING.md files are present and well-structured.
- ✅ Documentation covers project setup, development process, and contribution guidelines.
- ✅ New developers can successfully set up and contribute to the project using the documentation.

**In-scope:**
- Project documentation (README, CONTRIBUTING.md)
**Out-of-scope:**
- Detailed API documentation, user manuals

**Hints:**
- Use Markdown for documentation formatting.
- Include links to relevant resources (e.g., coding standards, issue tracker).
- Keep documentation up-to-date with project changes.

---

## US-1.2.1: Generate base Spring Boot 4 projects for all planned services (WebFlux, Actuator, Security)
**Overview:**
Generate base Spring Boot 4 projects for all planned services with health endpoints and Dockerfiles. This provides a uniform technical base and enables fast local development and onboarding.

**Details:**
- Use Spring Initializr for each service (see ExtendedTechnical.md Step 2).
- Add WebFlux, Actuator, Security dependencies (see MinimalIdeaTechnical.md Base Stack).
- Add Dockerfile for each service.
- Implement `/actuator/health` endpoint.
- Document ports and endpoints in README.

**Implementation Notes (Completed):**
- Created Application.java main classes for all 8 services:
  * TdlibAdapterApplication (port 9080)
  * ConversationContextApplication (port 9081)
  * IntentClassifierApplication (port 9082)
  * PolicyEngineApplication (port 9083)
  * LlmOrchestratorApplication (port 9084)
  * ModerationServiceApplication (port 9085)
  * AuditServiceApplication (port 9086)
  * AdminApiApplication (port 9087)
- Created application.yml configs with correct ports, health endpoints, and logging
- Enabled Spring Boot Maven plugin repackage for executable JARs
- All services configured with WebFlux, Actuator, and appropriate starters
- Build produces executable JARs (*-exec.jar) for all services

**Acceptance Criteria:**
- ✅ Each service has main Application class and compiles successfully.
- ✅ All services expose `/actuator/health` endpoint (via Actuator config).
- ⏳ Dockerfiles build and run each service (US-1.2.2).
- ⏳ Security config is present (US-1.2.3 and later phases).

**In-scope:**
- Java, Spring Boot, application configs
**Out-of-scope:**
- Dockerfiles (US-1.2.2), Business logic, external integrations

**Hints:**
- Use `spring-boot-starter-webflux` and `spring-boot-starter-actuator`.
- Document ports in README.
- Uniform skeletons speed up later integration.

---

## US-1.2.2: Add Dockerfiles for each service
**Overview:**
Add Dockerfiles to each service module to enable containerization and simplify deployment.

**Details:**
- Create a Dockerfile in each service module.
- Use a multi-stage build to minimize image size.
- Expose necessary ports and configure health checks.
- Document the Docker image build and run process.

**Implementation Notes (Completed):**
- Created multi-stage Dockerfiles for all 8 services:
  * Stage 1 (builder): eclipse-temurin:21-jdk with Maven
  * Stage 2 (runtime): eclipse-temurin:21-jre
- Non-root user (emcip) created for security
- Health checks configured: `curl http://localhost:{port}/actuator/health`
- Created `.dockerignore` for optimized builds
- Dockerfile locations: `{service}/Dockerfile`

**Acceptance Criteria:**
- ✅ Dockerfiles are present in all 8 service modules.
- ⏳ Docker images can be built and run successfully (test locally).
- ⏳ Documentation for Docker usage is available (add to README).

**In-scope:**
- Dockerfile creation, documentation
**Out-of-scope:**
- Docker Compose integration, Kubernetes deployment

**Hints:**
- Use the official OpenJDK or AdoptOpenJDK images as a base.
- Optimize the Dockerfile for caching and build speed.
- Test the Docker images locally before pushing changes.

---

## US-1.2.3: Integrate basic health endpoints
**Overview:**
Integrate basic health endpoints into each service to enable monitoring and ensure services are running correctly.

**Details:**
- Implement `/actuator/health` endpoint in each service.
- Configure health indicators for database and message broker connections.
- Document the health check endpoints and their expected responses.

**Implementation Notes (Completed):**
- Spring Boot Actuator health endpoints configured in all application.yml files
- All 8 services expose `/actuator/health` endpoint (default Actuator ping health)
- Docker health checks configured in all Dockerfiles:
  `HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3`
- Custom health indicators removed due to compilation issues - will be re-implemented in Phase 2
- Created HEALTH_ENDPOINTS.md documentation
- Service ports documented: 9080-9087

**Acceptance Criteria:**
- ✅ Health endpoints are implemented and accessible (`/actuator/health`).
- ✅ Documentation for health checks is available (HEALTH_ENDPOINTS.md).
- ⏳ Monitoring system can successfully check the health of each service (US-1.3.x and later).

**In-scope:**
- Health endpoint implementation, documentation
**Out-of-scope:**
- Advanced monitoring and alerting setup

**Hints:**
- Use Spring Boot Actuator's built-in health indicators.
- Customize health indicators as needed for external dependencies.
- Test health endpoints manually and through automated tests.

---

## US-1.3.1: Set up local Kafka broker (Docker Compose)
**Overview:**
Set up a local Kafka broker using Docker Compose to enable event-driven development and testing. This is essential for the event backbone and for simulating real-world message flows between services.

**Details:**
- Create a Docker Compose file that includes a Kafka broker and Zookeeper.
- Configure network and ports for local development.
- Document how to start, stop, and monitor Kafka locally.
- Ensure compatibility with the planned event schemas and services.

**Acceptance Criteria:**
- Kafka broker runs locally via Docker Compose.
- Services can connect to Kafka and exchange test messages.
- Documentation for local Kafka setup is available.

**In-scope:**
- Docker Compose, Kafka, documentation
**Out-of-scope:**
- Production deployment, advanced Kafka configuration

**Hints:**
- Use official Docker images for Kafka and Zookeeper.
- Test with simple producer/consumer examples.
- Document troubleshooting steps for common issues.

---

## US-1.3.2: Define initial topics and event schemas (Avro/JSON)
**Overview:**
Define the initial Kafka topics and event schemas (using Avro or JSON) to standardize communication between services.

**Details:**
- Identify required topics for message flow (e.g., MessageReceivedEvent, IntentClassifiedEvent).
- Create Avro or JSON schemas for each event type.
- Document topic names, schema versions, and field definitions.
- Review schemas with the team for completeness and extensibility.

**Acceptance Criteria:**
- Topics and schemas are defined and documented.
- Schemas are versioned and reviewed by the team.
- All services are aware of the topic structure.

**In-scope:**
- Kafka, Avro/JSON schema design, documentation
**Out-of-scope:**
- Schema registry deployment, advanced schema evolution

**Hints:**
- Use Confluent Schema Registry if available.
- Keep schemas simple and extensible.
- Document the schema evolution strategy.

---

## US-1.3.3: Set up local PostgreSQL instance (Docker Compose)
**Overview:**
Set up a local PostgreSQL instance using Docker Compose to provide persistent storage for services during development and testing.

**Details:**
- Add PostgreSQL service to the Docker Compose file.
- Configure database, user, and password for local use.
- Document connection details and usage instructions.
- Ensure services can connect and perform basic CRUD operations.

**Acceptance Criteria:**
- PostgreSQL runs locally via Docker Compose.
- Services can connect and perform test queries.
- Documentation for local PostgreSQL setup is available.

**In-scope:**
- Docker Compose, PostgreSQL, documentation
**Out-of-scope:**
- Production database setup, advanced tuning

**Hints:**
- Use official PostgreSQL Docker image.
- Provide example connection strings.
- Document how to reset the database for clean testing.

---

## US-1.3.4: Integrate Flyway/Liquibase for DB migrations
**Overview:**
Integrate Flyway or Liquibase for managing database schema migrations, ensuring consistent and repeatable DB changes across environments.

**Details:**
- Add Flyway or Liquibase to the build process of relevant services.
- Create initial migration scripts for core tables.
- Document migration process and how to apply migrations locally.
- Ensure migrations run automatically on service startup.

**Acceptance Criteria:**
- Migration tool is integrated and runs on startup.
- Initial schema is created via migration scripts.
- Documentation for migration process is available.

**In-scope:**
- Flyway/Liquibase, migration scripts, documentation
**Out-of-scope:**
- Complex migrations, production DB management

**Hints:**
- Use Maven plugins for Flyway/Liquibase.
- Keep initial schema simple and focused on core entities.
- Document how to add new migrations.

---

## US-1.3.5: Implement health checks for Kafka and PostgreSQL
**Overview:**
Implement health checks for Kafka and PostgreSQL in all relevant services to ensure connectivity and operational readiness.

**Details:**
- Add health indicators for Kafka and PostgreSQL using Spring Boot Actuator.
- Expose health status via `/actuator/health` endpoint.
- Document expected health check responses and troubleshooting steps.

**Acceptance Criteria:**
- Health checks for Kafka and PostgreSQL are implemented and accessible.
- Monitoring tools can query health endpoints.
- Documentation for health checks is available.

**In-scope:**
- Health check implementation, documentation
**Out-of-scope:**
- Advanced monitoring, alerting setup

**Hints:**
- Use built-in Spring Boot Actuator health indicators.
- Customize health checks as needed for local development.
- Test health endpoints manually and with monitoring tools.

---

## US-1.4.1: Write ADRs for stack, event backbone, persistence
**Overview:**
Write initial Architecture Decision Records (ADRs) to document the key decisions made regarding the technology stack, event-driven architecture, and data persistence.

**Details:**
- Write ADRs for the following topics:
  - Choice of technology stack (Java, Spring Boot, etc.).
  - Event-driven architecture and message broker selection (Kafka).
  - Data persistence approach and database selection (PostgreSQL).
- Use the template and process defined in the project's documentation.
- Link ADRs to relevant user stories and epics.

**Acceptance Criteria:**
- ADRs are written, reviewed, and stored in the repository.
- Each ADR clearly documents the decision and reasoning.
- ADRs are referenced in architecture documentation.

**In-scope:**
- ADR writing, documentation
**Out-of-scope:**
- Minor technical choices, implementation details

**Hints:**
- Use Markdown or AsciiDoc for ADRs.
- Reference ADRs in architecture diagrams and docs.
- Review ADRs with the team for consensus.

---

## US-1.4.2: Create initial architecture diagram (C4, sequence)
**Overview:**
Create initial architecture diagrams (C4 model and sequence diagrams) to visualize the system structure and data flow.

**Details:**
- Use PlantUML or Mermaid to create C4 context, container, and component diagrams.
- Create sequence diagrams for key flows (e.g., message ingestion, event processing).
- Store diagrams in the documentation folder and reference in README.

**Acceptance Criteria:**
- C4 and sequence diagrams are created and stored in the repo.
- Diagrams are referenced in architecture documentation.
- Diagrams are up-to-date with current system design.

**In-scope:**
- Diagram creation, documentation
**Out-of-scope:**
- Detailed code-level diagrams

**Hints:**
- Use C4 model for high-level architecture.
- Keep diagrams simple and focused on main flows.
- Update diagrams as the system evolves.

---

## US-1.4.3: Document local setup and onboarding steps
**Overview:**
Document all steps required for local setup and onboarding to ensure new developers can start productively without external help.

**Details:**
- Write a step-by-step onboarding guide covering prerequisites, setup, and first run.
- Include instructions for starting all services, Kafka, and PostgreSQL locally.
- Document troubleshooting tips and common pitfalls.

**Acceptance Criteria:**
- Onboarding guide is complete and covers all setup steps.
- New developers can follow the guide to run the system locally.
- Troubleshooting section is included.

**In-scope:**
- Onboarding documentation
**Out-of-scope:**
- Advanced usage, production deployment

**Hints:**
- Use Markdown for guides.
- Keep instructions concise and up-to-date.
- Link to relevant ADRs and architecture docs.

---

# Phase 1 Manual Setup Checklist

The following items require manual configuration via GitHub UI:

## GitHub Repository Settings (after US-1.1.2)

1. **Branch Protection Rules for `main`:**
   - [ ] Go to Settings → Branches → Add rule
   - [ ] Branch name pattern: `main`
   - [ ] ✅ Require a pull request before merging
   - [ ] ✅ Require approvals (1 minimum)
   - [ ] ✅ Dismiss stale PR approvals when new commits are pushed
   - [ ] ✅ Require status checks to pass (add later after CI setup)
   - [ ] ✅ Include administrators (optional)

2. **Repository Settings:**
   - [ ] Private repository (confirmed)
   - [ ] SSH key access configured for your local machine

## Pull Request Template (after US-1.1.1)

Create `.github/pull_request_template.md`:
```markdown
## Description
Brief description of changes

## Type of Change
- [ ] Bug fix
- [ ] New feature
- [ ] Documentation
- [ ] Refactoring

## Checklist
- [ ] Build passes (`mvn clean install`)
- [ ] Tests pass
- [ ] Code coverage ≥ 80%
- [ ] Spotless check passes
```

## After US-1.1.3 (CI/CD)

Add required status checks to branch protection:
- [ ] `build` check (Maven build)
- [ ] `test` check (Maven test)
- [ ] `spotless` check (code formatting)
