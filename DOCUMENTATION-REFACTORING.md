# Documentation Refactoring Plan

**Created:** April 21, 2026  
**Purpose:** Identify gaps between code and documentation, plan refactoring, and track open questions

---

## Executive Summary

This document catalogs discrepancies between the current codebase and existing documentation, identifies areas for documentation improvement, and lists open questions that need resolution. The goal is to ensure documentation accurately reflects the implementation and provides clear guidance for future development.

**Current Status:**
- **Phase 1:** Complete (Foundation & Infrastructure) ✅
- **Phase 2:** Complete (Core Messaging Pipeline) ✅
- **Phase 3:** Complete (Intelligence & Policy) ✅
- **Phase 4:** In Progress (Observability, Moderation & Audit) 🔄
- **Phase 5:** Not Started (Production Hardening) ⏳

---

## 1. Documentation-to-Code Discrepancies

### 1.1 README.md Issues

| Issue | Severity | Description | Action Required |
|-------|----------|-------------|-----------------|
| Missing Docker Compose mention | Medium | README mentions "A `docker-compose.yml` file will be provided in future user stories" but docker-compose.yml exists and is functional | Update line 103, remove "will be provided in future" |
| Path references | Low | Line 55 references `docs/architecture.adoc` but file is in `documentation/architecture.adoc` | Update all relative paths from `docs/` to `documentation/` |
| Docker Compose service coverage | 🔧 HIGH | Only 2 of 8 services are in docker-compose.yml: `tdlib-adapter` (9080) and `llm-orchestrator` (9084). Missing: conversation-context (9081), intent-classifier (9082), policy-engine (9083), moderation-service (9085), audit-service (9086), admin-api (9087) | Add remaining 6 services to docker-compose.yml |
| emcip-tdlib cleanup | 🔧 ACTION REQUIRED | `emcip-tdlib/` folder is outdated/duplicate. `TelegramUpdateHandler.java` is already correctly in `emcip-tdlib-adapter/`. Just delete the `emcip-tdlib/` folder entirely. | Delete duplicate `emcip-tdlib/` folder |

### 1.2 Architecture Documentation

| Issue | Severity | Description | Action Required |
|-------|----------|-------------|-----------------|
| Java Version Mismatch | ✅ RESOLVED | Target is **Java 25** (LTS). Current POM uses 21 for development compatibility, but production target is 25. | Update ADR-001 to reflect Java 25 decision |
| TDLib adapter description | Medium | architecture.adoc describes TDLib as connecting "as a real user" - this is accurate but needs authentication flow details | Add authentication state machine documentation |
| Missing service implementations | High | Some services listed in architecture have minimal/no implementation (audit-service, admin-api, moderation-service) | Mark as "Planned/Stub" in docs or implement |

### 1.3 Event Schemas

| Issue | Severity | Description | Action Required |
|-------|----------|-------------|-----------------|
| Schema vs Implementation | Medium | EVENT_SCHEMAS.md defines detailed JSON schemas, but actual event classes may differ | Audit all event classes against documented schemas |
| Missing event classes | Unknown | Need to verify all 8 defined event types have corresponding Java implementations | List actual implemented events |
| Topic naming | Low | Topic names in EVENT_SCHEMAS.md use dots (e.g., `telegram.raw.messages`) - verify this matches code | Check KafkaConfig classes |

### 1.4 Milestones & User Stories

| Issue | Severity | Description | Action Required |
|-------|----------|-------------|-----------------|
| Phase 3 LLM support | ✅ PLANNED | MiniMax and other cheap Chinese models **still planned**. Local models also planned. LLM URL must be configurable per model. | Document multi-provider LLM strategy |
| LLM Orchestrator implementation | Medium | US-3.2.1/3.2.2 marked complete but implementation details not fully documented | Add implementation notes to US-3.2.x |
| Test coverage gaps | Medium | TEST_MATRIX.md shows 0 tests for tdlib-adapter, intent-classifier | Update or implement tests |

---

## 2. Missing Documentation

### 2.1 High Priority (Blocking Future Development)

| Document | Purpose | Suggested Location |
|----------|---------|-------------------|
| **API Documentation** | REST endpoint documentation for all services | `documentation/api/` with OpenAPI specs |
| **Environment Variables Reference** | Complete .env variable listing with descriptions | `documentation/ops/ENVIRONMENT.md` |
| **TDLib Authentication Flow** | State machine for Telegram authentication | `documentation/developer/TDLIB_AUTH.md` |
| **Database Schema Documentation** | Entity relationship diagrams, table descriptions | `documentation/schema/` |
| **Policy Engine Rule Format** | How to write policy rules (YAML/JSON format) | `documentation/developer/POLICY_RULES.md` |

### 2.2 Medium Priority (Improves Developer Experience)

| Document | Purpose | Suggested Location |
|----------|---------|-------------------|
| **Troubleshooting Guide** | Common errors and solutions | `documentation/ops/TROUBLESHOOTING.md` |
| **Testing Guide** | How to write tests, test patterns used | `documentation/developer/TESTING.md` |
| **Kafka Event Flow Diagram** | Visual event flow through topics | Add to architecture.adoc |
| **Deployment Guide** | Production deployment procedures | `documentation/ops/DEPLOYMENT.md` |
| **Local Development Setup** | Step-by-step dev environment setup | Update ONBOARDING.md with more detail |

### 2.3 Low Priority (Nice to Have)

| Document | Purpose | Suggested Location |
|----------|---------|-------------------|
| **Performance Tuning Guide** | JVM, Kafka, PostgreSQL tuning | `documentation/ops/PERFORMANCE.md` |
| **Security Checklist** | Security review checklist | `documentation/security/CHECKLIST.md` |
| **Contributing Code Examples** | Code style examples beyond LOGGING.md | `documentation/developer/EXAMPLES.md` |

---

## 3. Code Quality Issues

### 3.1 Documentation Drift

| Location | Issue | Evidence |
|----------|-------|----------|
| `emcip-tdlib-adapter/Dockerfile` | Complex multi-stage build for TDLib | Documented in TDLIB_SETUP.md but may be outdated |
| `emcip-llm-orchestrator/` | Anthropic integration implemented | Not fully documented in user stories |
| Health endpoints | Described in HEALTH_ENDPOINTS.md | Verify all services actually expose these |
| emcip-core | Shared library | No clear documentation of what belongs here |

### 3.2 Inconsistent Naming

| Current | Inconsistent With | Suggested Fix |
|---------|-------------------|---------------|
| `emcip-tdlib` (folder) - TO BE DELETED | `emcip-tdlib-adapter` (module) | Delete outdated `emcip-tdlib/` folder (content already properly in emcip-tdlib-adapter) |
| `documentation/` folder | `docs/` references in README | Standardize on `documentation/` |
| `EventId` vs `eventId` | JSON schema vs Java naming | Document naming convention |

---

## 4. Open Questions

### 4.1 Architecture Decisions (Need Clarification)

| # | Question | Answer / Decision | Status |
|---|----------|-------------------|--------|
| Q1 | **Is MiniMax 2.7 integration still planned?** | **YES** - MiniMax and other cheap Chinese models still planned. Local models also planned. LLM URL must be configurable per model to support multiple providers. | ✅ Answered |
| Q2 | **What is the relationship between emcip-tdlib and emcip-tdlib-adapter?** | `emcip-tdlib/` was a temporary location. The `TelegramUpdateHandler.java` is already correctly placed and formatted in `emcip-tdlib-adapter/`. Just delete the entire `emcip-tdlib/` folder. | 🔧 Delete outdated folder |
| Q3 | **Should all services be runnable via docker-compose?** | **YES** - All 8 services should be runnable. Many may already be ready, need to verify and add to docker-compose.yml if missing. | 🔧 Verification Needed |
| Q4 | **What is the intended multi-tenancy timeline?** | Phase 5. Need ability to have multiple Telegram "tenants" (multiple Telegram user accounts). Term "tenant" may need renaming since "user" is ambiguous. Design decision needed later. | ⏳ Phase 5 |
| Q5 | **Is Schema Registry still on the roadmap?** | **NO** - Not needed at this time. No customers yet, JSON serialization is sufficient. Re-evaluate if needed later. | ❌ Rejected for now |

### 4.2 Implementation Gaps

| # | Question | Impact | Status / Action |
|---|----------|--------|-----------------|
| Q6 | **Why are audit-service, admin-api, moderation-service stubs?** | Phase 4 is in progress but unclear what's implemented | 🔧 **Planned** - Document current status of each service, implement during Phase 4 |
| Q7 | **Are there any integration tests that verify full event flow?** | End-to-end testing unclear | 🔧 **Planned** - Create integration test documentation and verify coverage |
| Q8 | **How is cost tracking for LLM calls actually implemented?** | US-3.2.3 mentions this but details unclear | 🔧 **Planned** - Review emcip-llm-orchestrator implementation and document |
| Q9 | **What prompt templates are currently defined?** | PromptTemplate entity exists but templates unclear | 🔧 **Planned** - Document default templates and configuration |
| Q10 | **How are policy rules configured?** | PolicyRule entity exists but format unclear | 🔧 **Planned** - Document rule configuration format (YAML/JSON) |

### 4.3 Operational Questions

| # | Question | Context | Urgency |
|---|----------|---------|---------|
| Q11 | **What are the resource requirements for production?** | INFRASTRUCTURE.md only covers local dev | Medium |
| Q12 | **How are secrets managed in production?** | Environment variables for dev, but Vault mentioned for later | Medium |
| Q13 | **What is the backup/restore strategy?** | Mentioned in Phase 5 but no details | Low |
| Q14 | **How are database migrations handled in production?** | Liquibase is configured but strategy unclear | Medium |
| Q15 | **What monitoring/alerting is recommended?** | Prometheus mentioned but no dashboard configs | Low |

### 4.4 Technical Debt Questions

| # | Question | Evidence | Priority |
|---|----------|----------|----------|
| Q16 | **Should TDLib health indicator check actual connection?** | TdLibHealthIndicator exists but implementation unclear | Medium |
| Q17 | **Are Kafka dead-letter topics implemented?** | US-3.3.2 marked complete but needs verification | Medium |
| Q18 | **What is the correlation ID propagation strategy?** | Mentioned in LOGGING.md but implementation unclear | Medium |
| Q19 | **Is reactive programming (WebFlux) actually used?** | Documented but verify actual usage | Low |
| Q20 | **Are there circuit breakers for external calls?** | Not mentioned but should be considered | Low |

---

## 5. Refactoring Priorities

### Phase A: Critical Fixes (Do First)

1. **Fix README.md paths** - Change `docs/` to `documentation/`
2. **Update Docker Compose status** - Remove "will be provided in future" text
3. **Cleanup emcip-tdlib folder** - Delete outdated `emcip-tdlib/` folder (content already in emcip-tdlib-adapter)
4. **Update Java version documentation** - Target is Java 25 (update ADR-001)

### Phase B: High Value Documentation

1. **Create API Documentation** - OpenAPI specs for all service endpoints
2. **Environment Variables Reference** - Complete .env documentation
3. **TDLib Authentication Flow** - State machine documentation
4. **Policy Engine Rule Format** - How to write rules

### Phase C: Comprehensive Updates

1. **Audit all event schemas** - Verify code matches documentation
2. **Service implementation status** - Document what each service actually does
3. **Testing documentation** - Expand TEST_MATRIX.md with coverage gaps
4. **Deployment guide** - Production deployment procedures

### Phase D: Nice-to-Have Improvements

1. **Troubleshooting guide** - Common errors and solutions
2. **Performance tuning** - JVM, Kafka, PostgreSQL optimization
3. **Security documentation** - Security model and checklist
4. **Architecture decision log** - Post-Phase 3 decisions

---

## 6. Documentation Structure Proposal

```
documentation/
├── README.md                     # Documentation index
├── architecture/
│   ├── architecture.adoc         # Current C4 diagrams
│   ├── api/                      # OpenAPI specs per service
│   └── diagrams/               # PlantUML diagrams
├── developer/
│   ├── ONBOARDING.md             # Enhanced onboarding
│   ├── LOGGING.md                # Move from root
│   ├── TESTING.md                # New: Testing guide
│   ├── TDLIB_AUTH.md             # New: TDLib auth flow
│   ├── POLICY_RULES.md           # New: Policy rule format
│   └── CONTRIBUTING.md           # Move from root
├── ops/
│   ├── ENVIRONMENT.md            # New: Environment variables
│   ├── INFRASTRUCTURE.md       # Move from root
│   ├── DEPLOYMENT.md             # New: Deployment guide
│   ├── TROUBLESHOOTING.md        # New: Common issues
│   └── PERFORMANCE.md            # New: Tuning guide
├── planning/
│   ├── MILESTONES.md             # Current
│   ├── DEEP-DIVE_MILESTONES.md  # Current
│   └── phases/                   # Current user stories
├── adrs/
│   ├── ADR-001-*.md              # Current
│   ├── ADR-002-*.md              # Current
│   └── ADR-003-*.md              # Current
└── concept/
    ├── DOMAIN_CONCEPT.md         # Current
    └── SOUL.md                   # Current
```

---

## 7. Action Items

### Immediate (This Sprint)

- [ ] Fix README.md path references (docs/ → documentation/)
- [ ] Update README.md docker-compose statement
- [ ] Delete emcip-tdlib folder (already properly in emcip-tdlib-adapter)
- [ ] Create API documentation index
- [ ] Verify all 8 services are in docker-compose.yml with proper profiles
- [ ] Update ADR-001: Java 25 is target, 21 is dev compatibility

### Short-term (Next 2 Sprints)

- [ ] Create ENVIRONMENT.md with all env vars
- [ ] Audit event schemas against code
- [ ] Document TDLib authentication flow
- [ ] Document Policy Engine rule format
- [ ] Answer Q6-Q10 (Implementation gaps)

### Medium-term (Phase 4 Completion)

- [ ] Create TROUBLESHOOTING.md
- [ ] Create DEPLOYMENT.md
- [ ] Expand TEST_MATRIX.md
- [ ] Create OpenAPI specs for all services
- [ ] Answer Q11-Q20 (Operational and technical debt)

---

## 8. Notes

### Documentation Principles

1. **Single Source of Truth** - Documentation should be in one place, referenced elsewhere
2. **Code as Documentation** - Where possible, generate docs from code (OpenAPI, JavaDoc)
3. **Living Documents** - Update docs when code changes
4. **Version Alignment** - Doc versions should match code releases

### Standards to Maintain

- Use PlantUML for diagrams (as in architecture.adoc)
- Use Markdown for text documents
- Use OpenAPI/Swagger for API docs
- Keep ADRs for significant decisions
- Document "why" not just "what"

---

*This document is a living document. Update it as questions are answered and refactoring is completed.*
