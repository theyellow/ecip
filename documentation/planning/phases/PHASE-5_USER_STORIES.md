# PHASE-5_USER_STORIES.md

## Phase 5: Production Hardening & Admin – Epics & User Stories

---

### Epic 5.1: Multi-Tenancy – Tenant Isolation & Config Management
- [ ] US-5.1.1: Design and implement tenant isolation (schema, row-level, or service-level isolation)
- [ ] US-5.1.2: Implement tenant-aware configuration management
- [ ] US-5.1.3: Add tests for tenant isolation and config overrides
- [ ] US-5.1.4: Document multi-tenancy approach and onboarding for new tenants

### Epic 5.2: Performance Tuning – Load Tests & Latency Optimization
- [ ] US-5.2.1: Design and execute load tests for all critical paths
- [ ] US-5.2.2: Profile and optimize service latency
- [ ] US-5.2.3: Document performance benchmarks and tuning steps
- [ ] US-5.2.4: Add automated performance regression tests

### Epic 5.3: Advanced Policy Logic – Versioning & Complex Rules
- [ ] US-5.3.1: Design policy versioning mechanism (e.g., versioned configs, DB schema)
- [ ] US-5.3.2: Implement support for complex rules (e.g., time-based, context-aware)
- [ ] US-5.3.3: Add tests for rule versioning and complex scenarios
- [ ] US-5.3.4: Document policy logic and versioning strategy

### Epic 5.4: Admin UI (Optional, Stretch Goal)
- [ ] US-5.4.1: Design UI for tenant/rule/audit management
- [ ] US-5.4.2: Integrate with Admin API
- [ ] US-5.4.3: Document UI features and user roles
- [ ] US-5.4.4: Add tests for main UI flows

### Epic 5.5: PostgreSQL Indexing & Backup/Restore
- [ ] US-5.5.1: Design and implement indexes for critical tables
- [ ] US-5.5.2: Create and test backup/restore scripts
- [ ] US-5.5.3: Document backup/restore procedures
- [ ] US-5.5.4: Add tests for backup/restore scenarios

### Epic 5.6: Log Aggregation & Observability Dashboards
- [ ] US-5.6.1: Add Grafana + Loki + Promtail to docker-compose (default stack)
- [ ] US-5.6.2: Add Grafana dashboards for Prometheus metrics (error rate, Kafka lag, JVM)
- [ ] US-5.6.3: Configure Promtail to ship Docker container JSON logs into Loki
- [ ] US-5.6.4: Document observability stack usage and dashboard access

> **Context:** All 8 services already emit structured JSON (logstash-logback-encoder) and expose
> Prometheus metrics. The infrastructure layer (Loki, Promtail, Grafana) to consume them was
> deferred from Phase 4 (US-4.2.4). This epic closes that gap.

---

# User Stories (Details)

## US-5.1.1: Design and implement tenant isolation
**Overview:**
Design and implement tenant isolation (schema, row-level, or service-level) to ensure data security and privacy for each organization.
**Details:**
- Evaluate isolation strategies (schema, row-level, service-level).
- Implement chosen strategy in DB and service layer.
- Ensure all queries and configs are tenant-aware.
**Acceptance Criteria:**
- Tenants are fully isolated in all data and config paths.
- Isolation is tested and documented.
**In-scope:** Java, DB schema, config
**Out-of-scope:** Billing, IAM
**Hints:**
- Start with schema or row-level isolation.

## US-5.1.2: Implement tenant-aware configuration management
**Overview:**
Implement configuration management that supports tenant-specific overrides and defaults.
**Details:**
- Design config structure for tenant overrides.
- Implement config loading and validation.
- Document config management for onboarding.
**Acceptance Criteria:**
- Tenant-specific configs are supported and documented.
- Config loading is tested.
**In-scope:** Java, config management
**Out-of-scope:** External config servers
**Hints:**
- Use Spring profiles or custom config loaders.

## US-5.1.3: Add tests for tenant isolation and config overrides
**Overview:**
Add automated tests to verify tenant isolation and config override logic.
**Details:**
- Write tests for isolation boundaries and config scenarios.
- Integrate tests into CI/CD.
**Acceptance Criteria:**
- All isolation and config scenarios are covered by tests.
- Tests run in CI/CD pipeline.
**In-scope:** Java, testing framework, CI/CD
**Out-of-scope:** Manual testing
**Hints:**
- Use Testcontainers for DB isolation tests.

## US-5.1.4: Document multi-tenancy approach and onboarding
**Overview:**
Document the multi-tenancy approach, onboarding steps, and best practices for new tenants.
**Details:**
- Create onboarding guide and architecture docs.
- Include diagrams and config examples.
**Acceptance Criteria:**
- Documentation is complete and accessible.
**In-scope:** Markdown, diagrams
**Out-of-scope:** Automated doc generation
**Hints:**
- Use Mermaid for diagrams.

## US-5.2.1: Design and execute load tests for all critical paths
**Overview:**
Design and execute load tests to validate system performance under realistic and peak loads.
**Details:**
- Identify critical paths and endpoints.
- Create load test scripts and scenarios.
- Execute tests and collect metrics.
**Acceptance Criteria:**
- Load tests meet defined SLOs.
- Results are documented and reviewed.
**In-scope:** Load testing, scripting
**Out-of-scope:** Hardware procurement
**Hints:**
- Use JMeter, Gatling, or k6.

## US-5.2.2: Profile and optimize service latency
**Overview:**
Profile and optimize service latency to ensure responsiveness and scalability.
**Details:**
- Use profiling tools to identify bottlenecks.
- Optimize code and infrastructure as needed.
- Document findings and improvements.
**Acceptance Criteria:**
- Latency is within defined SLOs.
- Optimizations are documented.
**In-scope:** Profiling, optimization
**Out-of-scope:** Hardware upgrades
**Hints:**
- Use Java Flight Recorder or similar tools.

## US-5.2.3: Document performance benchmarks and tuning steps
**Overview:**
Document all performance benchmarks, test results, and tuning steps for future reference.
**Details:**
- Create performance test report.
- Document all tuning actions and their impact.
**Acceptance Criteria:**
- Performance report is available and reviewed.
**In-scope:** Documentation
**Out-of-scope:** Automated reporting
**Hints:**
- Use Markdown or Confluence for reports.

## US-5.2.4: Add automated performance regression tests
**Overview:**
Add automated performance regression tests to catch performance issues early.
**Details:**
- Integrate performance tests into CI/CD.
- Set thresholds and alerts for regressions.
**Acceptance Criteria:**
- Performance regressions are detected automatically.
- Tests run in CI/CD pipeline.
**In-scope:** CI/CD, testing framework
**Out-of-scope:** Manual performance testing
**Hints:**
- Use k6 or Gatling for CI integration.

## US-5.3.1: Design policy versioning mechanism
**Overview:**
Design a versioning mechanism for policy rules to support evolution and auditability.
**Details:**
- Define versioning strategy (e.g., versioned configs, DB schema).
- Document versioning approach and rationale.
**Acceptance Criteria:**
- Policy versioning is implemented and documented.
**In-scope:** Policy engine, DB schema
**Out-of-scope:** Policy simulation
**Hints:**
- Use config files or DB version fields.

## US-5.3.2: Implement support for complex rules
**Overview:**
Implement support for complex policy rules (e.g., time-based, context-aware, multi-step).
**Details:**
- Extend policy engine to support complex rule logic.
- Add tests for complex scenarios.
**Acceptance Criteria:**
- Complex rules are supported and tested.
**In-scope:** Policy engine, testing
**Out-of-scope:** External rule engines
**Hints:**
- Use feature flags for rule rollout.

## US-5.3.3: Add tests for rule versioning and complex scenarios
**Overview:**
Add automated tests for policy versioning and complex rule scenarios.
**Details:**
- Write tests for all versioning and rule branches.
- Integrate tests into CI/CD.
**Acceptance Criteria:**
- All versioning and complex rule scenarios are covered by tests.
- Tests run in CI/CD pipeline.
**In-scope:** Testing framework, CI/CD
**Out-of-scope:** Manual testing
**Hints:**
- Use JUnit and Testcontainers.

## US-5.3.4: Document policy logic and versioning strategy
**Overview:**
Document the policy logic, rule structure, and versioning strategy for audit and onboarding.
**Details:**
- Create documentation in /docs or README.
- Include diagrams and examples.
**Acceptance Criteria:**
- Documentation is complete and accessible.
**In-scope:** Markdown, diagrams
**Out-of-scope:** Automated doc generation
**Hints:**
- Use Mermaid for diagrams.

## US-5.4.1: Design UI for tenant/rule/audit management
**Overview:**
Design a user interface for managing tenants, rules, and audit logs.
**Details:**
- Create wireframes and UI concepts.
- Define user roles and permissions.
**Acceptance Criteria:**
- UI design is reviewed and approved.
**In-scope:** UI/UX design
**Out-of-scope:** UI implementation
**Hints:**
- Use Figma or similar tools.

## US-5.4.2: Integrate with Admin API
**Overview:**
Integrate the Admin UI with the Admin API for all management operations.
**Details:**
- Implement API calls for tenant, rule, and audit management.
- Handle authentication and error states.
**Acceptance Criteria:**
- UI integrates with all required API endpoints.
**In-scope:** UI, API integration
**Out-of-scope:** External IAM
**Hints:**
- Use React, Angular, or Vaadin.

## US-5.4.3: Document UI features and user roles
**Overview:**
Document all Admin UI features, user roles, and permissions.
**Details:**
- Create user guide and feature documentation.
- Include screenshots and role matrix.
**Acceptance Criteria:**
- Documentation is complete and accessible.
**In-scope:** Markdown, screenshots
**Out-of-scope:** Automated doc generation
**Hints:**
- Use Markdown or Confluence.

## US-5.4.4: Add tests for main UI flows
**Overview:**
Add automated tests for all main Admin UI flows.
**Details:**
- Write tests for all critical UI paths and edge cases.
- Integrate tests into CI/CD.
**Acceptance Criteria:**
- All main UI flows are covered by tests.
- Tests run in CI/CD pipeline.
**In-scope:** UI testing framework, CI/CD
**Out-of-scope:** Manual UI testing
**Hints:**
- Use Cypress or Selenium.

## US-5.5.1: Design and implement indexes for critical tables
**Overview:**
Design and implement indexes for all critical PostgreSQL tables to improve query performance.
**Details:**
- Identify critical tables and queries.
- Create and apply index scripts.
**Acceptance Criteria:**
- Indexes improve query performance in benchmarks.
**In-scope:** PostgreSQL, scripting
**Out-of-scope:** External DB tuning
**Hints:**
- Use EXPLAIN ANALYZE for query optimization.

## US-5.5.2: Create and test backup/restore scripts
**Overview:**
Create and test backup/restore scripts for PostgreSQL to ensure data integrity and disaster recovery.
**Details:**
- Write scripts using pg_dump/pg_restore.
- Test backup and restore in staging environment.
**Acceptance Criteria:**
- Backups and restores are successful and documented.
**In-scope:** PostgreSQL, scripting
**Out-of-scope:** Cloud backup
**Hints:**
- Automate backups with cron jobs.

## US-5.5.3: Document backup/restore procedures
**Overview:**
Document all backup and restore procedures for the ops team.
**Details:**
- Create step-by-step guides and troubleshooting tips.
- Include backup frequency and retention policies.
**Acceptance Criteria:**
- Documentation is complete and accessible.
**In-scope:** Markdown, documentation
**Out-of-scope:** Automated doc generation
**Hints:**
- Use Markdown or Confluence.

## US-5.5.4: Add tests for backup/restore scenarios
**Overview:**
Add automated tests for backup and restore scenarios to ensure reliability.
**Details:**
- Write tests for backup/restore in test environments.
- Integrate tests into CI/CD.
**Acceptance Criteria:**
- All backup/restore scenarios are covered by tests.
- Tests run in CI/CD pipeline.
**In-scope:** Testing framework, CI/CD
**Out-of-scope:** Manual backup testing
**Hints:**
- Use Testcontainers for DB tests.

## US-5.6.1: Add Grafana + Loki + Promtail to docker-compose
**Overview:**
Add a lightweight log aggregation stack to the default docker-compose so developers can query logs
from all 8 services in one place without external tooling.
**Details:**
- Add `grafana`, `loki`, and `promtail` services to `docker-compose.yml` (no profile — always-on)
- Promtail reads Docker container log files from `/var/lib/docker/containers` and ships to Loki
- Grafana pre-configured with Loki and Prometheus as data sources
- Grafana available at `http://localhost:3000` (default credentials: admin/admin)
**Acceptance Criteria:**
- `docker compose up -d` starts Grafana, Loki, Promtail alongside application services
- Logs from all 8 services visible in Grafana Explore within 30 seconds of service startup
- Prometheus data source also connected (emcip metrics queryable)
**In-scope:** docker-compose.yml, Promtail config, Grafana provisioning
**Out-of-scope:** Production log shipping, cloud-hosted Grafana
**Hints:**
- Use `grafana/grafana:latest`, `grafana/loki:latest`, `grafana/promtail:latest`
- Promtail pipeline_stages: use `json` parser since all services emit logstash JSON

## US-5.6.2: Add Grafana dashboards for Prometheus metrics
**Overview:**
Create pre-built Grafana dashboards that are provisioned automatically on startup.
**Details:**
- Dashboard 1: Per-service health — request rate, error rate, JVM heap, GC pauses
- Dashboard 2: Kafka consumer lag — per topic, per consumer group
- Dashboard 3: Audit event throughput — events/min by event type
- Dashboards provisioned via `grafana/provisioning/dashboards/` (JSON files, committed to repo)
**Acceptance Criteria:**
- Dashboards load automatically on first `docker compose up`
- All 8 services appear in the service health dashboard
- Kafka consumer lag visible for `telegram.messages`, `intent.classified`, `policy.decisions`, etc.
**In-scope:** Grafana dashboard JSON, provisioning config
**Out-of-scope:** Alerting rules, PagerDuty integration

## US-5.6.3: Configure Promtail to ship Docker JSON logs into Loki
**Overview:**
Wire Promtail to read the structured JSON logs emitted by logstash-logback-encoder and make them
queryable in Loki with useful labels.
**Details:**
- Extract labels from JSON log fields: `service_name`, `level`, `traceId`
- LogQL queries become: `{service_name="emcip-audit-service"} |= "ERROR"`
- Trace correlation: clicking a traceId in Grafana Explore links to related log lines
**Acceptance Criteria:**
- Each log line queryable by `service_name` and `level` in Loki
- `traceId` and `spanId` extracted and searchable
**In-scope:** Promtail pipeline config
**Out-of-scope:** OpenTelemetry collector, Jaeger/Tempo

## US-5.6.4: Document observability stack usage
**Overview:**
Write developer documentation covering the full observability setup.
**Details:**
- How to access Grafana (`localhost:3000`)
- How to query logs in Loki (example LogQL queries for common scenarios)
- How to read Prometheus metrics (example PromQL queries)
- How to correlate a trace ID across logs and metrics
**Acceptance Criteria:**
- `documentation/developer/OBSERVABILITY.md` created and accurate
- Covers all 4 observability pillars present in the stack (logs, metrics, traces, health)

---

### Note on Telegram Integration (ECIP as User)
All integrations with Telegram are performed as a real Telegram user (not a bot). The ECIP platform connects via TDLib as a user, listens in groups/channels/discussion chats, and collects information. This architecture is the foundation for all following user stories and epics.
