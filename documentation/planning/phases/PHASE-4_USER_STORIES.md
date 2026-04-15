# PHASE-4_USER_STORIES.md

## Phase 4: Observability, Moderation & Audit – Epics & User Stories

---

### Epic 4.1: Moderation Service – Toxicity Filter & Rule Violation Detection
- [ ] US-4.1.1: Integrate a toxicity detection library or service (e.g., OpenNLP, Perspective API, or custom rules)
- [ ] US-4.1.2: Define and implement rules for content moderation (e.g., banned words, spam detection)
- [ ] US-4.1.3: Connect moderation service to Kafka event backbone (consume MessageReceivedEvent, publish ModerationResultEvent)
- [ ] US-4.1.4: Document moderation rules and escalation paths
- [ ] US-4.1.5: Add integration tests for moderation logic

### Epic 4.2: Audit & Observability – Logging, Tracing, Metrics
- [ ] US-4.2.1: Integrate structured logging (JSON) in all services
- [ ] US-4.2.2: Set up OpenTelemetry for distributed tracing across services
- [ ] US-4.2.3: Expose Prometheus metrics endpoints in all services
- [ ] US-4.2.4: Document observability setup and provide sample dashboards
- [ ] US-4.2.5: Add integration tests for logging and metrics

### Epic 4.3: Admin API – JWT Security & Service-to-Service Auth
- [ ] US-4.3.1: Design and implement Admin API endpoints (see admin-service responsibilities)
- [ ] US-4.3.2: Secure endpoints with JWT
- [ ] US-4.3.3: Implement service-to-service authentication (e.g., mutual TLS or JWT trust)
- [ ] US-4.3.4: Document API endpoints and security model
- [ ] US-4.3.5: Add integration tests for authentication and authorization

### Epic 4.4: Audit/Event Log Tables & Retention Policies
- [ ] US-4.4.1: Design DB schema for audit/event logs
- [ ] US-4.4.2: Implement retention policies (e.g., time-based deletion, archiving)
- [ ] US-4.4.3: Add Flyway/Liquibase migrations for schema changes
- [ ] US-4.4.4: Document schema and retention strategy
- [ ] US-4.4.5: Add integration tests for log persistence and retention

---

# User Stories (Details)

## US-4.1.1: Integrate a toxicity detection library or service
**Overview:**
Integrate a library or external service to detect toxic or inappropriate content in real time.
**Details:**
- Evaluate and select a suitable library or API (e.g., OpenNLP, Perspective API, custom regex).
- Integrate with the moderation service.
- Ensure performance and scalability for real-time analysis.
**Acceptance Criteria:**
- Toxic content is reliably detected in test scenarios.
- Integration is documented and tested.
**In-scope:** Java, API integration, moderation logic
**Out-of-scope:** Human moderation, advanced ML
**Hints:**
- Start with simple rules, extend to ML if needed.

## US-4.1.2: Define and implement rules for content moderation
**Overview:**
Define and implement rules for content moderation (e.g., banned words, spam detection).
**Details:**
- Create a ruleset for moderation (configurable, versioned).
- Implement rule engine or pattern matching.
- Document all rules and escalation paths.
**Acceptance Criteria:**
- Rules are configurable and documented.
- Moderation logic is testable and auditable.
**In-scope:** Java, rule engine, documentation
**Out-of-scope:** External rule management
**Hints:**
- Use enums/config files for rules.

## US-4.1.3: Connect moderation service to Kafka event backbone
**Overview:**
Connect the moderation service to the Kafka event backbone for real-time event processing.
**Details:**
- Consume MessageReceivedEvent from Kafka.
- Publish ModerationResultEvent to Kafka.
- Ensure event schema compliance.
**Acceptance Criteria:**
- Events are processed and published in real time.
- Event flow is documented.
**In-scope:** Java, Kafka, event schema
**Out-of-scope:** Cross-messenger events
**Hints:**
- Use Avro/JSON schemas.

## US-4.1.4: Document moderation rules and escalation paths
**Overview:**
Document all moderation rules and escalation paths for audit and onboarding.
**Details:**
- Create documentation in /docs or README.
- Include examples and edge cases.
**Acceptance Criteria:**
- Documentation is complete and accessible.
**In-scope:** Markdown, diagrams
**Out-of-scope:** Automated doc generation
**Hints:**
- Use Mermaid for diagrams.

## US-4.1.5: Add integration tests for moderation logic
**Overview:**
Add integration tests for all moderation logic and rules.
**Details:**
- Write tests for all rule branches and edge cases.
- Automate test execution in CI/CD.
**Acceptance Criteria:**
- All rules are covered by tests.
- Tests run in CI/CD pipeline.
**In-scope:** Java, JUnit, CI/CD
**Out-of-scope:** Manual testing
**Hints:**
- Use testcontainers for Kafka integration tests.

## US-4.2.1: Integrate structured logging (JSON) in all services
**Overview:**
Integrate structured logging in JSON format across all services for consistent and queryable logs.
**Details:**
- Implement JSON logging in all service components.
- Ensure compatibility with existing log management solutions.
- Document logging structure and fields.
**Acceptance Criteria:**
- Logs are generated in JSON format and contain all necessary information.
- Integration with log management solutions is tested.
**In-scope:** Java, logging framework, documentation
**Out-of-scope:** Log management solution
**Hints:**
- Use SLF4J with a JSON encoder.

## US-4.2.2: Set up OpenTelemetry for distributed tracing across services
**Overview:**
Set up OpenTelemetry to enable distributed tracing for all services, providing visibility into request flows and performance.
**Details:**
- Instrument all services with OpenTelemetry SDK.
- Configure tracing context propagation across service boundaries.
- Set up a tracing backend (e.g., Jaeger, Zipkin).
- Document tracing setup and how to read traces.
**Acceptance Criteria:**
- Traces are visible in the tracing backend and show complete request flows.
- Documentation explains how to use and query traces.
**In-scope:** Java, OpenTelemetry, tracing backend
**Out-of-scope:** Advanced trace analysis
**Hints:**
- Use OpenTelemetry auto-instrumentation where possible.

## US-4.2.3: Expose Prometheus metrics endpoints in all services
**Overview:**
Expose metrics endpoints in all services for Prometheus to scrape, enabling monitoring of service performance and health.
**Details:**
- Implement Prometheus metrics endpoint in each service.
- Ensure all relevant metrics are exposed (e.g., request counts, latencies, error rates).
- Document available metrics and their meaning.
**Acceptance Criteria:**
- Metrics are available at the /actuator/prometheus endpoint.
- Documentation provides clear guidance on metrics interpretation.
**In-scope:** Java, Spring Boot Actuator, documentation
**Out-of-scope:** Prometheus server setup
**Hints:**
- Use Micrometer for metrics instrumentation.

## US-4.2.4: Document observability setup and provide sample dashboards
**Overview:**
Document the observability setup, including logging, tracing, and metrics, and provide sample dashboards for common use cases.
**Details:**
- Create comprehensive documentation covering all aspects of the observability setup.
- Provide sample Grafana dashboards for key metrics and traces.
**Acceptance Criteria:**
- Documentation is complete, including setup, usage, and troubleshooting.
- Sample dashboards are provided and customizable.
**In-scope:** Markdown, Grafana, documentation
**Out-of-scope:** Custom dashboard development
**Hints:**
- Use Grafana's built-in dashboards as a starting point.

## US-4.2.5: Add integration tests for logging and metrics
**Overview:**
Add integration tests to verify the correctness and completeness of logging and metrics across services.
**Details:**
- Implement tests to check for the presence and correctness of log entries and metrics.
- Ensure tests cover all critical paths and components.
**Acceptance Criteria:**
- All critical logs and metrics are verified by tests.
- Tests are integrated into the CI/CD pipeline.
**In-scope:** Java, testing framework, CI/CD
**Out-of-scope:** End-to-end monitoring
**Hints:**
- Use WireMock to simulate external dependencies in tests.

## US-4.3.1: Design and implement Admin API endpoints
**Overview:**
Design and implement the Admin API endpoints required for managing rules, group profiles, and approvals.
**Details:**
- Define API contracts (OpenAPI/Swagger) for all Admin API endpoints.
- Implement endpoints in the admin service.
- Ensure proper request validation and error handling.
**Acceptance Criteria:**
- API endpoints are implemented as per the specifications.
- Documentation is complete and includes examples.
**In-scope:** Java, Spring Boot, API docs
**Out-of-scope:** Client SDK generation
**Hints:**
- Use Spring REST Docs for generating API documentation.

## US-4.3.2: Secure endpoints with JWT
**Overview:**
Secure the Admin API endpoints using JWT authentication to ensure that only authorized users can access them.
**Details:**
- Implement JWT creation and validation logic.
- Secure API endpoints using Spring Security.
- Document the authentication process and token structure.
**Acceptance Criteria:**
- Endpoints are secured and accessible only with a valid JWT.
- Documentation provides clear guidance on authentication.
**In-scope:** Java, Spring Security, JWT
**Out-of-scope:** OAuth2.0, OpenID Connect
**Hints:**
- Use a shared secret or RSA keys for signing JWTs.

## US-4.3.3: Implement service-to-service authentication
**Overview:**
Implement authentication mechanisms for secure communication between services, such as mutual TLS or JWT trust.
**Details:**
- Choose and implement a service-to-service authentication method.
- Ensure secure storage and handling of credentials or keys.
- Document the authentication mechanism and configuration.
**Acceptance Criteria:**
- Services can securely authenticate and communicate with each other.
- Documentation is clear and includes configuration examples.
**In-scope:** Java, Spring Security, documentation
**Out-of-scope:** External IAM integration
**Hints:**
- Consider using Spring Cloud Commons for service discovery and security.

## US-4.3.4: Document API endpoints and security model
**Overview:**
Document all Admin API endpoints and the security model, including authentication and authorization mechanisms.
**Details:**
- Create comprehensive API documentation (OpenAPI/Swagger).
- Document the security model, including JWT structure and service-to-service auth.
**Acceptance Criteria:**
- API documentation is complete and accessible.
- Security model documentation is clear and detailed.
**In-scope:** Markdown, API docs
**Out-of-scope:** Automated documentation generation
**Hints:**
- Use Swagger UI for interactive API documentation.

## US-4.3.5: Add integration tests for authentication and authorization
**Overview:**
Add integration tests to verify the authentication and authorization mechanisms of the Admin API.
**Details:**
- Implement tests to check JWT validation, role-based access, and service-to-service authentication.
- Ensure tests cover all possible scenarios and edge cases.
**Acceptance Criteria:**
- All authentication and authorization scenarios are covered by tests.
- Tests are automated and run in the CI/CD pipeline.
**In-scope:** Java, testing framework, CI/CD
**Out-of-scope:** Manual security testing
**Hints:**
- Use MockMvc for testing Spring Security configurations.

## US-4.4.1: Design DB schema for audit/event logs
**Overview:**
Design the database schema for storing audit and event logs, ensuring it meets compliance and traceability requirements.
**Details:**
- Define tables, indexes, and relationships for audit/event logs.
- Ensure schema supports efficient querying and archiving.
- Document the schema design and rationale.
**Acceptance Criteria:**
- DB schema is designed and documented.
- Schema design is reviewed and approved.
**In-scope:** PostgreSQL, DB schema, documentation
**Out-of-scope:** Schema migration
**Hints:**
- Consider using entity-attribute-value (EAV) model for flexibility.

## US-4.4.2: Implement retention policies
**Overview:**
Implement retention policies for audit/event logs, including time-based deletion and archiving strategies.
**Details:**
- Define retention periods for different types of logs.
- Implement automated processes for log deletion and archiving.
- Document retention policies and procedures.
**Acceptance Criteria:**
- Retention policies are implemented and tested.
- Documentation is complete and accessible.
**In-scope:** PostgreSQL, DB procedures, documentation
**Out-of-scope:** External archiving solutions
**Hints:**
- Use PostgreSQL partitioning for efficient data management.

## US-4.4.3: Add Flyway/Liquibase migrations for schema changes
**Overview:**
Add database migrations using Flyway or Liquibase to manage schema changes for audit/event logs.
**Details:**
- Create migration scripts for initial schema and changes.
- Ensure migrations are idempotent and reversible.
- Document the migration process and scripts.
**Acceptance Criteria:**
- Migrations are tested and applied successfully.
- Documentation provides clear guidance on managing migrations.
**In-scope:** Flyway/Liquibase, DB schema, documentation
**Out-of-scope:** Manual schema updates
**Hints:**
- Use versioned migrations for easy tracking and rollback.

## US-4.4.4: Document schema and retention strategy
**Overview:**
Document the database schema and retention strategy for audit/event logs, including details on tables, fields, and retention periods.
**Details:**
- Create ER diagrams and data dictionaries for the schema.
- Document retention strategy, including deletion and archiving processes.
**Acceptance Criteria:**
- Documentation is complete, including schema and retention details.
- Diagrams and data dictionaries are clear and accurate.
**In-scope:** Markdown, diagrams, documentation
**Out-of-scope:** Automated documentation generation
**Hints:**
- Use dbdiagram.io or similar tools for ER diagrams.

## US-4.4.5: Add integration tests for log persistence and retention
**Overview:**
Add integration tests to verify the persistence and retention of audit/event logs in the database.
**Details:**
- Implement tests to check log insertion, querying, and retention policies.
- Ensure tests cover all possible scenarios and edge cases.
**Acceptance Criteria:**
- All log persistence and retention scenarios are covered by tests.
- Tests are automated and run in the CI/CD pipeline.
**In-scope:** Java, testing framework, CI/CD
**Out-of-scope:** Manual database testing
**Hints:**
- Use Testcontainers for PostgreSQL integration tests.

---

### Note on Telegram Integration (ECIP as User)
All integrations with Telegram are performed as a real Telegram user (not a bot). The ECIP platform connects via TDLib as a user, listens in groups/channels/discussion chats, and collects information. This architecture is the foundation for all following user stories and epics.
