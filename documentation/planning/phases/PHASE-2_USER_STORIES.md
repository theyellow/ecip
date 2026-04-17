# PHASE-2_USER_STORIES.md

## Phase 2: Core Messaging Pipeline – Epics & User Stories

---

### Epic 2.1: TDLib Adapter Implementation
- [x] US-2.1.1: Implement Telegram client integration (TDLib)
- [x] US-2.1.2: Handle login flow and session management
- [x] US-2.1.3: Publish Telegram updates as events to Kafka

### Epic 2.2: Event Backbone & Schema Versioning
- [x] US-2.2.1: Finalize event schemas (JSON)
- [x] US-2.2.2: Implement schema versioning and validation
- [x] US-2.2.3: Ensure all services can consume/publish events

### Epic 2.3: PostgreSQL Persistence for Core Entities
- [x] US-2.3.1: Define and implement JPA entities for messages, threads, users
- [x] US-2.3.2: Implement repository layer and DB migrations (Liquibase)
- [x] US-2.3.3: Write integration tests for persistence

### Epic 2.4: Conversation Context & Intent Classifier
- [x] US-2.4.1: Implement thread tracking and speaker role detection
- [x] US-2.4.2: Develop rule-based intent classifier (first intent types)
- [x] US-2.4.3: Integrate with event backbone and persistence

---

# User Stories (Details)

## US-2.1.1: Implement Telegram client integration (TDLib)
**Overview:**
Integrate the Telegram TDLib client, handle login/session, and publish updates to Kafka. This enables the platform to act as a full Telegram client, supporting real-time, event-driven ingestion of all relevant Telegram updates.

**Details:**
- Add TDLib Java bindings to the adapter service (see MinimalIdeaTechnical.md: TDLib as primary client, not Bot API).
- Implement login flow (api_id, api_hash, phone number; see ExtendedTechnical.md prerequisites).
- Subscribe to Telegram updates (groups, channels, threads) and normalize events (see MinimalIdeaTechnical.md: "TDLib receives updates").
- Publish events to Kafka topic (see ExtendedTechnical.md Step 4, recommended: Apache Kafka, event types MessageReceivedEvent, etc.).
- Log all received events for debugging and traceability (see Non-Functional Requirements: auditability, observability).
- Document how to obtain Telegram API credentials and run the adapter locally.

**Acceptance Criteria:**
- Service connects to Telegram and receives updates (groups, channels, threads).
- Updates are published to Kafka in the defined schema (see ExtendedTechnical.md: event schema upfront, versioned).
- Error handling for failed logins and connection drops.
- All steps are documented for onboarding.

**In-scope:**
- Java, TDLib, Kafka, Docker Compose
**Out-of-scope:**
- Bot API, advanced event enrichment, multi-messenger support

**Hints:**
- Use official TDLib docs and Java bindings (see MinimalIdeaTechnical.md sources).
- Log all received events for debugging and audit.
- This is a critical path for all downstream services and observability.
- See MinimalIdeaTechnical.md for the event flow and microservice responsibilities.
- Ensure the adapter can be run in a local dev environment (see ExtendedTechnical.md Step 1, 2, 3).

---

## US-2.1.2: Handle login flow and session management
**Overview:**
Implement the login flow and session management for the TDLib integration. This ensures secure and reliable authentication with the Telegram API, maintaining session state for continuous message ingestion.

**Details:**
- Implement login flow using api_id, api_hash, and phone number (see ExtendedTechnical.md prerequisites).
- Handle two-factor authentication (2FA) if enabled for the account.
- Manage session state and refresh tokens as needed.
- Document the login process, including 2FA handling and session management.

**Acceptance Criteria:**
- Service can log in to Telegram and maintain session state.
- 2FA is handled correctly, and session tokens are refreshed as needed.
- Login process is documented, including troubleshooting tips.

**In-scope:**
- Java, TDLib, Kafka, Docker Compose
**Out-of-scope:**
- Bot API, advanced event enrichment, multi-messenger support

**Hints:**
- Use official TDLib docs and Java bindings (see MinimalIdeaTechnical.md sources).
- Test login flow with and without 2FA enabled.
- Document any issues or quirks encountered during login flow implementation.

---

## US-2.1.3: Publish Telegram updates as events to Kafka
**Overview:**
Publish Telegram updates (messages, events) to Kafka as they are received by the TDLib integration. This enables downstream processing and analytics of Telegram data in real-time.

**Details:**
- Define Kafka topic(s) and event schema for Telegram updates (see ExtendedTechnical.md Step 4).
- Implement logic to publish received Telegram updates to the appropriate Kafka topic.
- Ensure message delivery and handle any errors or retries as needed.
- Document the event publishing process and Kafka topic structure.

**Acceptance Criteria:**
- Telegram updates are published to Kafka in real-time.
- Event schema is followed, and messages are delivered reliably.
- Any errors or retries in message delivery are logged and handled.

**In-scope:**
- Java, TDLib, Kafka, Docker Compose
**Out-of-scope:**
- Bot API, advanced event enrichment, multi-messenger support

**Hints:**
- Use official TDLib docs and Java bindings (see MinimalIdeaTechnical.md sources).
- Test event publishing with different types of Telegram updates (messages, events).
- Document any issues or quirks encountered during event publishing implementation.

---

## US-2.2.1: Finalize event schemas (Avro/JSON)
**Overview:**
Define and document the event schemas for all event types (MessageReceivedEvent, IntentClassifiedEvent, PolicyDecisionEvent) using Avro/JSON. This ensures a consistent and evolvable event contract between services.

**Details:**
- Create Avro/JSON schemas for all event types (see ExtendedTechnical.md Step 4).
- Document the event schemas, including field descriptions and types.
- Review and finalize schemas with the team, incorporating feedback.

**Acceptance Criteria:**
- Event schemas are defined and documented.
- Schemas are reviewed and approved by the team.
- Any changes to schemas are versioned and communicated.

**In-scope:**
- Schema design, documentation, review
**Out-of-scope:**
- Event enrichment, analytics, cross-messenger schemas

**Hints:**
- Use Confluent Schema Registry if available.
- Document schema evolution strategy (see ExtendedTechnical.md recommendations).
- Versioning is critical for long-term maintainability and integration with other platforms.

---

## US-2.2.2: Implement schema versioning and validation
**Overview:**
Implement schema versioning and validation logic to ensure all events conform to the defined schemas. This supports robust, evolvable event contracts between services and prevents schema-related errors.

**Details:**
- Implement schema registry or validation logic (see ExtendedTechnical.md: define event schema upfront, versioned).
- Ensure all events are validated before publishing/consuming.
- Document schema evolution strategy and versioning policy.
- Add integration tests for schema validation.

**Acceptance Criteria:**
- All events conform to versioned schemas.
- Schema validation errors are logged and rejected.
- Schema registry or validation logic is documented.

**In-scope:**
- Schema design, validation logic, documentation
**Out-of-scope:**
- Event enrichment, analytics, cross-messenger schemas

**Hints:**
- Use Confluent Schema Registry if available.
- Document schema evolution strategy (see ExtendedTechnical.md recommendations).
- Versioning is critical for long-term maintainability and integration with other platforms.

---

## US-2.2.3: Ensure all services can consume/publish events
**Overview:**
Ensure all services in the ecosystem can correctly consume and publish events using the defined schemas and Kafka topics. This enables seamless integration and data exchange between services.

**Details:**
- Update service interfaces and documentation to reflect the new event schemas and Kafka topics.
- Implement any necessary changes to service logic to handle the new event formats.
- Test end-to-end event flow between services to ensure compatibility and correctness.

**Acceptance Criteria:**
- All services can consume and publish events using the new schemas and Kafka topics.
- End-to-end event flow between services is tested and verified.
- Any issues or incompatibilities are resolved and documented.

**In-scope:**
- Service interfaces, documentation, logic updates, testing
**Out-of-scope:**
- Event enrichment, analytics, cross-messenger schemas

**Hints:**
- Communicate schema and topic changes clearly to all service teams.
- Provide guidance and support for updating services to the new event formats.
- Test event flow in a staging environment before deploying to production.

---

## US-2.3.1: Define and implement JPA entities for messages, threads, users
**Overview:**
Define JPA entities for core domain objects (messages, threads, users) and implement persistence with migrations. This provides reliable, auditable storage for all ingested and processed data, supporting traceability and future analytics.

**Details:**
- Design JPA entities for core domain objects (messages, threads, users; see MinimalIdeaTechnical.md: conversation-context-service, intent-classification-service, etc.).
- Implement repository layer using Spring Data JPA.
- Add Flyway/Liquibase migrations for schema (see ExtendedTechnical.md Step 4, MinimalIdeaTechnical.md: Non-Functional Requirements: traceability).
- Write integration tests for CRUD operations.
- Document DB schema and migration process.

**Acceptance Criteria:**
- Entities persisted and retrievable from DB.
- Migrations run without error.
- Integration tests cover CRUD.
- DB schema and migration process are documented.

**In-scope:**
- Java, JPA, PostgreSQL, Flyway/Liquibase
**Out-of-scope:**
- Advanced queries, analytics, multi-tenancy

**Hints:**
- Use Spring Data JPA.
- Keep entities simple for now, but document extensibility for future features (e.g., multi-tenancy, audit).
- Persistence is key for auditability and compliance (see MinimalIdeaTechnical.md: audit-observability-service).

---

## US-2.3.2: Implement repository layer and DB migrations
**Overview:**
Implement the repository layer and database migrations for the defined JPA entities. This ensures reliable data storage and retrieval, supporting the platform's core functionality.

**Details:**
- Implement repository interfaces and classes using Spring Data JPA (see MinimalIdeaTechnical.md: conversation-context-service, intent-classification-service, etc.).
- Add Flyway/Liquibase migrations for initial schema (see ExtendedTechnical.md Step 4).
- Test database migrations in a local and staging environment.
- Document the repository and migration implementation.

**Acceptance Criteria:**
- Repository layer is implemented and tested.
- Database migrations are applied successfully in all environments.
- Documentation is updated to reflect the repository and migration implementation.

**In-scope:**
- Java, JPA, PostgreSQL, Flyway/Liquibase
**Out-of-scope:**
- Advanced queries, analytics, multi-tenancy

**Hints:**
- Use Spring Data JPA.
- Test repository methods thoroughly.
- Document any issues or quirks encountered during repository and migration implementation.

---

## US-2.3.3: Write integration tests for persistence
**Overview:**
Write integration tests for the persistence layer, covering CRUD operations for all JPA entities. This ensures the correctness and reliability of data storage and retrieval.

**Details:**
- Write integration tests for CRUD operations on messages, threads, and users entities.
- Test database migrations and schema changes.
- Document the integration testing strategy and coverage.

**Acceptance Criteria:**
- Integration tests cover all CRUD operations for JPA entities.
- Tests pass without errors, and code coverage is adequate.
- Integration testing strategy and coverage are documented.

**In-scope:**
- Java, JPA, PostgreSQL, Flyway/Liquibase
**Out-of-scope:**
- Advanced queries, analytics, multi-tenancy

**Hints:**
- Use Spring Data JPA.
- Keep tests focused on persistence logic.
- Document any issues or quirks encountered during integration testing.

---

## US-2.4.1: Implement thread tracking and speaker role detection
**Overview:**
Implement thread tracking and speaker role detection in the conversation context service. This enables the platform to maintain context across messages and identify speaker roles for policy and AI processing.

**Details:**
- Track threads and assign speaker roles in context service (see MinimalIdeaTechnical.md: "Identify speaker roles and topics").
- Implement logic to detect speaker roles based on message content and context.
- Integrate with event backbone and persistence (see event flow).
- Document thread tracking and speaker role detection logic.

**Acceptance Criteria:**
- Threads are tracked, and speaker roles are assigned to messages.
- Results are persisted and available via API or DB.
- Documentation covers thread tracking and speaker role detection logic.

**In-scope:**
- Java, rule engine, DB, documentation
**Out-of-scope:**
- ML-based classification, advanced context, multi-language

**Hints:**
- Use enums for intent types.
- Document rules in code and README.
- This is a foundation for all policy and AI features (see MinimalIdeaTechnical.md: "Make policy-driven decisions").

---

## US-2.4.2: Develop rule-based intent classifier (first intent types)
**Overview:**
Develop a rule-based intent classifier for the first set of intent types. This enables the platform to classify messages for downstream policy and AI processing.

**Details:**
- Develop rule-based intent classifier for first intent types (see MinimalIdeaTechnical.md: "Goals", ExtendedTechnical.md Step 3, 4).
- Integrate with event backbone and persistence (see event flow).
- Document rules and classification logic for transparency and audit.
- Add tests for main classification paths.

**Acceptance Criteria:**
- Intent is classified for messages based on defined rules.
- Results are persisted and available via API or DB.
- Tests cover main classification paths.
- Rules and logic are documented for audit and onboarding.

**In-scope:**
- Java, rule engine, DB, documentation
**Out-of-scope:**
- ML-based classification, advanced context, multi-language

**Hints:**
- Use enums for intent types.
- Document rules in code and README.
- This is a foundation for all policy and AI features (see MinimalIdeaTechnical.md: "Make policy-driven decisions").

---

## US-2.4.3: Integrate with event backbone and persistence
**Overview:**
Integrate the conversation context and intent classifier with the event backbone and persistence layer. This ensures that classified messages and conversation context are correctly processed and stored.

**Details:**
- Integrate context and intent classification results with event backbone (see event flow).
- Persist conversation context and intent classification results in the database.
- Document integration points and data flow between components.

**Acceptance Criteria:**
- Context and intent classification results are processed and stored correctly.
- Integration points and data flow are documented.
- Any issues or errors in integration are logged and handled.

**In-scope:**
- Java, rule engine, DB, documentation
**Out-of-scope:**
- ML-based classification, advanced context, multi-language

**Hints:**
- Use enums for intent types.
- Document rules in code and README.
- This is a foundation for all policy and AI features (see MinimalIdeaTechnical.md: "Make policy-driven decisions").

---

### Note on Telegram Integration (ECIP as User)
All integrations with Telegram are performed as a real Telegram user (not a bot). The ECIP platform connects via TDLib as a user, listens in groups/channels/discussion chats, and collects information. This architecture is the foundation for all following user stories and epics.
