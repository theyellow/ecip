# PHASE-3_USER_STORIES.md

## Phase 3: Intelligence & Policy – Epics & User Stories

---

### Epic 3.1: Policy Engine Implementation ✅ COMPLETE
- [x] US-3.1.1: Implement deterministic policy logic (rule engine)
- [x] US-3.1.2: Define escalation paths and policy outcomes
- [x] US-3.1.3: Integrate with event backbone and persistence

### Epic 3.2: LLM Orchestrator & AI Integration 🔄 IN PROGRESS
- [x] US-3.2.1: Implement LLM orchestrator (model routing, prompt templates)
- [x] US-3.2.3: Track and log AI call costs
- [x] US-3.2.4: Ensure policy layer is gatekeeper for AI responses
- [ ] US-3.2.2: Integrate at least one small model (intent, summaries) ⏳ POSTPONED (after Epic 3.3)

### Epic 3.3: Kafka Monitoring & Dead-Letter Topics ✅ COMPLETE
- [x] US-3.3.1: Set up Kafka monitoring dashboards (Prometheus/Micrometer metrics)
- [x] US-3.3.2: Implement dead-letter topic handling with retry logic

---

# User Stories (Details)

## US-3.1.1: Implement deterministic policy logic (rule engine)
**Overview:**
Implement a deterministic policy engine as the core for all automated, auditable, and explainable decisions in the platform.

**Details:**
- Design a rule engine for policy decisions (YAML/JSON config or code-based, see MinimalIdeaTechnical.md: "policy-engine-service").
- Ensure all responses pass through policy and moderation logic (see MinimalIdeaTechnical.md: Architectural Principles).

**Acceptance Criteria:**
- Policy rules are configurable and testable.
- Policy decisions are logged and persisted.
- Test suite covers all rule branches.
- Documentation for rules and escalation paths is available.

**In-scope:**
- Java, rule engine, DB, event backbone, documentation
**Out-of-scope:**
- AI/ML-based policy, external policy sources

**Hints:**
- Use Drools, Easy Rules, or custom logic.
- Document rules and escalation paths in code and README.
- Deterministic policy logic is a core compliance and audit requirement (see ExtendedTechnical.md: keep policy engine deterministic).

---

## US-3.1.2: Define escalation paths and policy outcomes
**Overview:**
Define how the system should react under different circumstances by establishing clear escalation paths and policy outcomes.

**Details:**
- Define escalation paths and policy outcomes (e.g., escalate, ignore, respond; see MinimalIdeaTechnical.md: "Policy engine decides: react, wait, ignore").
- Document all rules and escalation paths for auditability and onboarding.

**Acceptance Criteria:**
- Clear documentation of all escalation paths and policy outcomes.
- Integration of escalation paths within the policy engine.

**In-scope:**
- Policy documentation, rule engine integration
**Out-of-scope:**
- External policy sources

**Hints:**
- Use flowcharts or decision trees for visualization.
- Ensure alignment with organizational compliance requirements.

---

## US-3.1.3: Integrate with event backbone and persistence
**Overview:**
Integrate the policy engine with the event backbone and persistence layer to ensure seamless event processing and policy enforcement.

**Details:**
- Integrate with event backbone and persistence layer (see event flow).
- Provide test suite for policy logic.

**Acceptance Criteria:**
- Successful integration with the event backbone.
- Policy decisions are persisted and retrievable.

**In-scope:**
- Event backbone integration, persistence layer integration, testing
**Out-of-scope:**
- Changes to the event backbone or persistence layer architecture

**Hints:**
- Follow the existing event flow for integration.
- Ensure backward compatibility with existing events.

---

## US-3.2.1: Implement LLM orchestrator (model routing, prompt templates)
**Overview:**
Implement an LLM orchestrator to manage model routing and prompt templates, enabling flexible and efficient AI integrations.

**Details:**
- Design orchestrator to route requests to different models (intent, summary, etc.; see MinimalIdeaTechnical.md: llm-orchestration-service, AI Strategy).
- Implement prompt template management (versioned, see ExtendedTechnical.md Step 3, 4).

**Acceptance Criteria:**
- Requests are routed to the correct model based on config.
- Prompt templates are versioned and testable.

**In-scope:**
- Java, LLM API, documentation
**Out-of-scope:**
- Model training, advanced prompt engineering

**Hints:**
- Use config files for model routing.
- Log all AI calls for audit.

---

## US-3.2.2: Integrate at least one small model (intent, summaries)
**Overview:**
Integrate at least one small AI model for intent recognition or summarization to validate the LLM orchestrator setup.

**Details:**
- Select and integrate a small model for either intent recognition or summarization.
- Ensure the model can be called through the orchestrator.

**Acceptance Criteria:**
- Successful integration of the small model.
- Ability to route requests to the model through the orchestrator.

**In-scope:**
- Model integration, orchestrator configuration
**Out-of-scope:**
- Development of new AI models

**Hints:**
- Start with a pre-trained model for faster integration.
- Ensure the model complies with data privacy regulations.

---

## US-3.2.3: Track and log AI call costs
**Overview:**
Implement tracking and logging of AI call costs to enable cost-effective use of AI resources.

**Details:**
- Track and log AI call costs (see MinimalIdeaTechnical.md: Non-Functional Requirements, "Cost control for LLM calls").

**Acceptance Criteria:**
- AI call costs are logged and reportable.

**In-scope:**
- Cost tracking implementation, logging
**Out-of-scope:**
- Budgeting or financial forecasting

**Hints:**
- Use existing logging infrastructure for cost logging.
- Ensure cost tracking does not impact system performance.

---

## US-3.2.4: Ensure policy layer is gatekeeper for AI responses
**Overview:**
Ensure the policy layer acts as the final authority for all AI-generated responses, maintaining compliance and control.

**Details:**
- Ensure policy layer is gatekeeper for AI responses (see MinimalIdeaTechnical.md: "Policy layer: final authority before external response").

**Acceptance Criteria:**
- All AI responses are checked by the policy layer.
- Policy violations are logged and handled according to the defined rules.

**In-scope:**
- Policy layer configuration, AI response handling
**Out-of-scope:**
- Changes to AI model behavior

**Hints:**
- Regularly review and update policy rules.
- Ensure transparency in policy decisions.

---

## US-3.3.1: Set up Kafka monitoring dashboards (e.g., Prometheus, Grafana)
**Overview:**
Set up monitoring dashboards for Kafka using Prometheus and Grafana to ensure operational transparency and reliability.

**Details:**
- Integrate Prometheus/Grafana for Kafka monitoring (see ExtendedTechnical.md Step 5, MinimalIdeaTechnical.md: Observability).

**Acceptance Criteria:**
- Kafka health and throughput are visible in dashboards.

**In-scope:**
- Kafka monitoring setup, dashboard configuration
**Out-of-scope:**
- Advanced analytics, cross-cluster monitoring

**Hints:**
- Use Kafka Exporter for Prometheus.
- Document monitoring setup in the operational runbook.

---

## US-3.3.2: Implement dead-letter topic handling for failed events
**Overview:**
Implement handling for dead-letter topics in Kafka to manage failed events effectively.

**Details:**
- Configure dead-letter topics (DLQ) for failed events (see ExtendedTechnical.md Step 4, "Event Backbone").
- Log and alert on DLQ events.

**Acceptance Criteria:**
- Failed events are routed to DLQ and logged.
- Alerts are triggered on DLQ events.

**In-scope:**
- DLQ configuration, logging, alerting
**Out-of-scope:**
- Recovery or reprocessing of failed events

**Hints:**
- Regularly monitor DLQ for unexpected spikes in failed events.
- Document DLQ handling procedures in the operational runbook.

---

### Note on Telegram Integration (ECIP as User)
All integrations with Telegram are performed as a real Telegram user (not a bot). The ECIP platform connects via TDLib as a user, listens in groups/channels/discussion chats, and collects information. This architecture is the foundation for all following user stories and epics.
