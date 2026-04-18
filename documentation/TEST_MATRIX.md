# ECIP Test Matrix

**Document Purpose:** Comprehensive overview of all tests implemented during Phase 2 (Core Messaging Pipeline) and Phase 3 (Policy Engine), including their purpose, scope, and additional notes.

**Last Updated:** April 18, 2026
**Phase 2 Status:** ✅ COMPLETE
**Phase 3 Status:** ✅ Policy Engine COMPLETE

---

## Overview

| Module | Test Files | Total Tests | Test Framework |
|--------|------------|-------------|----------------|
| emcip-core | 0 | 0 | N/A (shared library) |
| emcip-conversation-context | 3 | 19 | JUnit 5 + Testcontainers |
| emcip-tdlib-adapter | 0 | 0 | Integration tests pending |
| emcip-intent-classifier | 0 | 0 | Unit tests pending |
| emcip-policy-engine | 4 | 23 | JUnit 5 + Testcontainers + Mockito |
| **Total** | **7** | **42** | |

---

## Phase 2 Test Categories

### 1. Persistence Layer Tests (19 tests)
**Purpose:** Verify JPA/Hibernate entity mappings, repository queries, and database constraints using Testcontainers PostgreSQL.

#### 1.1 MessageRepositoryTest (7 tests)
**File:** `emcip-conversation-context/src/test/java/io/emcip/conversation/context/repository/MessageRepositoryTest.java`

| Test Method | Purpose | Notes |
|-------------|---------|-------|
| `shouldSaveAndFindMessageByEventId` | Verifies basic CRUD operations for Message entity | Core persistence functionality |
| `shouldFindByTelegramMessageIdAndChatId` | Tests composite query for unique message identification | Uses telegramMessageId + chatId combination |
| `shouldFindByThreadOrderByTimestamp` | Verifies thread-based message retrieval with ordering | Pagination support, chronological order |
| `shouldFindBySender` | Tests querying messages by sender (User entity) | Foreign key relationship validation |
| `shouldCountByThread` | Verifies aggregation query for message counts | Used for thread statistics |
| `shouldCheckExistence` | Tests existence check queries | Optimized query for validation |
| `shouldPaginateResults` | Verifies Spring Data pagination | 25 messages, 3 pages tested |

**Infrastructure:**
- Uses `@IntegrationTest` meta-annotation
- PostgreSQL 16 via Testcontainers
- Liquibase migrations applied automatically
- `@Transactional` rollback after each test

#### 1.2 MessageThreadRepositoryTest (6 tests)
**File:** `emcip-conversation-context/src/test/java/io/emcip/conversation/context/repository/MessageThreadRepositoryTest.java`

| Test Method | Purpose | Notes |
|-------------|---------|-------|
| `shouldSaveAndFindThreadByChatId` | Verifies thread persistence and retrieval | Primary key: telegramChatId |
| `shouldFindByThreadType` | Tests filtering by thread type (PRIVATE, GROUP, CHANNEL) | Enum mapping validation |
| `shouldFindActiveThreads` | Verifies active thread filtering | isActive flag functionality |
| `shouldFindByMemberCountGreaterThan` | Tests member count threshold queries | Used for popular group detection |
| `shouldUpdateLastMessageAt` | Verifies @Modifying query with timestamp update | `clearAutomatically=true` required |
| `shouldDeactivateThread` | Tests soft-delete via isActive flag | `clearAutomatically=true` required |

**Key Implementation Notes:**
- `@Modifying` queries require `clearAutomatically=true, flushAutomatically=true` for test consistency
- Custom queries use JPQL with entity names (not table names)

#### 1.3 UserRepositoryTest (6 tests)
**File:** `emcip-conversation-context/src/test/java/io/emcip/conversation/context/repository/UserRepositoryTest.java`

| Test Method | Purpose | Notes |
|-------------|---------|-------|
| `shouldSaveAndFindUserByTelegramId` | Verifies user persistence | Primary key: telegramId (Long) |
| `shouldFindByUsername` | Tests username-based lookup | Case-sensitive search |
| `shouldFindByUsernameContaining` | Verifies partial match search | Used for user search functionality |
| `shouldCheckExistenceByTelegramId` | Tests existence query | Optimized for validation checks |
| `shouldUpdateLastSeenAt` | Verifies @Modifying timestamp update | Requires `clearAutomatically=true` |
| `shouldFindAllByIds` | Tests batch retrieval by multiple IDs | Used for batch operations |

---

## Test Infrastructure

### Testcontainers Configuration
**File:** `emcip-conversation-context/src/test/java/io/emcip/conversation/context/TestcontainersInitializer.java`

| Component | Configuration |
|-----------|---------------|
| Database | PostgreSQL 16 (postgres:16-alpine) |
| Startup | Static initializer (one container per JVM) |
| JDBC URL | Dynamic, injected via TestPropertyValues |
| Liquibase | Enabled with drop-first=true for clean state |
| DDL Mode | none (Liquibase manages schema) |

### Test Annotations
**File:** `emcip-conversation-context/src/test/java/io/emcip/conversation/context/IntegrationTest.java`

| Annotation | Purpose |
|------------|---------|
| `@SpringBootTest` | Full application context with random port |
| `@ActiveProfiles("test")` | Test profile activation |
| `@ContextConfiguration` | Testcontainers initializer |
| `@Import(TestDatabaseConfig.class)` | Liquibase bean configuration |

**File:** `emcip-conversation-context/src/test/java/io/emcip/conversation/context/EnableIfDockerAvailable.java`

| Feature | Description |
|---------|-------------|
| Conditional Execution | Tests skip if Docker unavailable |
| Detection | Uses `DockerClientFactory.instance().client()` |
| Logging | Warns when Docker is unavailable |

---

## Phase 2 Feature Coverage

### Implemented & Tested

| Feature | Test Coverage | Notes |
|---------|---------------|-------|
| PostgreSQL Persistence | ✅ Full (19 tests) | All 3 entities: User, MessageThread, Message |
| JPA/Hibernate Mappings | ✅ Full | Includes enums, timestamps, relationships |
| Repository Queries | ✅ Full | Custom JPQL, pagination, @Modifying |
| Liquibase Migrations | ✅ Verified | 3 changelogs: users, threads, messages |
| Testcontainers Integration | ✅ Verified | PostgreSQL 16, Docker-based |

### Implemented but Not Tested (Integration Level)

| Feature | Status | Reason |
|---------|--------|--------|
| Kafka Consumers | ⚠️ Runtime only | Requires running Kafka broker |
| TelegramMessageConsumer | ⚠️ Manual testing | Needs live Telegram connection |
| IntentClassificationConsumer | ⚠️ Manual testing | Needs live Telegram + Kafka |
| Event Validation | ⚠️ Unit tests pending | EventValidator has no unit tests |

### Test Gaps Identified (Phase 2)

| Gap | Priority | Recommended Action |
|-----|----------|-------------------|
| Unit tests for EventValidator | Medium | Add pure unit tests with mocked ObjectMapper |
| Unit tests for ConversationContextService | Medium | Mock repositories, test business logic |
| Kafka integration tests | Low | Requires embedded Kafka or Testcontainers Kafka |
| End-to-end message flow | Low | Requires full Docker Compose environment |

---

## Phase 3 Policy Engine Test Categories

### 2. Policy Engine Tests (23 tests)
**Purpose:** Verify policy evaluation logic, database persistence, and configurable rules.

#### 2.1 PolicyDecisionRepositoryTest (6 tests)
**File:** `emcip-policy-engine/src/test/java/io/emcip/policy/engine/repository/PolicyDecisionRepositoryTest.java`

| Test Method | Purpose | Notes |
|-------------|---------|-------|
| `shouldSaveAndFindById` | Verifies basic CRUD for PolicyDecision | UUID primary key |
| `shouldFindBySourceEventId` | Tests lookup by source event | Links to classification events |
| `shouldFindByDecision` | Query by decision type (BLOCK, ALLOW, etc.) | Used for audit reporting |
| `shouldFindTopBySourceEventIdOrderByTimestampDesc` | Most recent decision for event | Supports decision history |
| `shouldFindByOriginalIntent` | Query by intent classification | Analysis by intent type |
| `shouldFindByConfidenceGreaterThan` | Threshold-based confidence queries | Quality control queries |

**Infrastructure:**
- PostgreSQL 16 via Testcontainers
- JSONB column storage for `matchedRules` and `metadata`
- `@Transactional` rollback after each test

#### 2.2 PolicyRuleConfigRepositoryTest (5 tests)
**File:** `emcip-policy-engine/src/test/java/io/emcip/policy/engine/repository/PolicyRuleConfigRepositoryTest.java`

| Test Method | Purpose | Notes |
|-------------|---------|-------|
| `shouldSaveAndFindById` | Verifies rule persistence | UUID primary key |
| `shouldFindByActiveTrueOrderByPriorityAsc` | Priority-ordered active rules | Critical for rule evaluation order |
| `shouldFindByTargetIntentAndActiveTrueOrderByPriorityAsc` | Rules for specific intent | Supports intent-specific lookups |
| `shouldFindByName` | Lookup by unique rule name | Admin/management use |
| `shouldActivateAndDeactivate` | Tests rule activation toggle | `@Modifying` query with `clearAutomatically=true` |

**Key Implementation Notes:**
- Priority ordering: lower number = higher priority
- `@Modifying` queries require `clearAutomatically=true` for consistency
- Active flag enables soft-disable of rules

#### 2.3 PolicyEvaluationServiceTest (12 tests)
**File:** `emcip-policy-engine/src/test/java/io/emcip/policy/engine/service/PolicyEvaluationServiceTest.java`

| Test Method | Purpose | Notes |
|-------------|---------|-------|
| `shouldUseDefaultRulesWhenNoDbRules` | Fallback to hardcoded rules | Ensures system works without DB rules |
| `shouldUseDatabaseRulesWhenAvailable` | Load and apply DB rules | Priority-based rule ordering |
| `shouldMatchSpamWithHighConfidence` | SPAM > 0.8 → BLOCK | First-match-wins evaluation |
| `shouldNotMatchSpamWithLowConfidence` | SPAM < 0.8 → ALLOW (no match) | Confidence threshold test |
| `shouldMatchGreeting` | GREETING > 0.7 → RESPOND | Intent-based rule matching |
| `shouldMatchQuestion` | QUESTION > 0.75 → ESCALATE | Intent-based rule matching |
| `shouldMatchCommand` | COMMAND > 0.8 → EXECUTE | Intent-based rule matching |
| `shouldTriggerModerationForLowConfidence` | confidence < 0.3 → REVIEW | Catch-all moderation rule |
| `shouldPersistDecisionWithCorrectMetadata` | Verify saved decision fields | Event linkage, timestamps |
| `shouldPublishEventToKafka` | Kafka publishing verification | `policies.decisions` topic |
| `shouldHandleWildcardIntentMatching` | "*" matches any intent | Wildcard support in rules |
| `shouldGetActiveRules` | Service method for rule retrieval | Admin/management API support |

**Infrastructure:**
- Mockito for repository and KafkaTemplate mocking
- Pure unit tests (no Spring context)
- Tests evaluation logic in isolation

### Policy Engine Test Infrastructure

**Testcontainers Configuration**
**File:** `emcip-policy-engine/src/test/java/io/emcip/policy/engine/TestcontainersInitializer.java`

| Component | Configuration |
|-----------|---------------|
| Database | PostgreSQL 16 (postgres:16-alpine) |
| Startup | Static initializer |
| Liquibase | Enabled with drop-first=true |
| Kafka | Mocked via Mockito in unit tests |

**Unit Test Configuration**
**File:** `emcip-policy-engine/src/test/java/io/emcip/policy/engine/config/TestDatabaseConfig.java`

| Feature | Description |
|---------|-------------|
| Liquibase Bean | Ensures schema before JPA initialization |
| KafkaAdmin Mock | Provides mock bean for health checks |
| `@TestConfiguration` | Test-only beans |

### Phase 3 Feature Coverage

| Feature | Test Coverage | Notes |
|---------|---------------|-------|
| PolicyDecision persistence | ✅ Full (6 tests) | JSONB metadata, query methods |
| PolicyRuleConfig persistence | ✅ Full (5 tests) | Priority ordering, activation |
| Rule evaluation logic | ✅ Full (12 tests) | Intent matching, confidence thresholds |
| Default rule fallback | ✅ Verified | Hardcoded rules when DB empty |
| Kafka publishing | ✅ Verified | Event publishing tested |
| Database health indicator | ✅ Verified | JDBC-based health checks |

### Phase 3 Test Gaps

| Gap | Priority | Recommended Action |
|-----|----------|-------------------|
| Kafka consumer integration test | Medium | Requires embedded Kafka |
| End-to-end policy flow | Low | Full Docker Compose |
| Rule management REST API | Low | Controller tests pending |
| Policy metrics/observability | Low | Micrometer tests pending |

---

## Running Tests

### Individual Module
```bash
cd /home/ben/Development/ecip
mvn clean test -pl emcip-conversation-context
```

### All Modules (Phase 2)
```bash
cd /home/ben/Development/ecip
mvn clean test -pl emcip-core,emcip-tdlib-adapter,emcip-conversation-context,emcip-intent-classifier,emcip-policy-engine
```

### Full Build with Verification
```bash
cd /home/ben/Development/ecip
mvn clean verify -DskipITs
```

### Requirements
- Docker (for Testcontainers)
- Java 21+
- Maven 3.8+

---

## Test Results (Latest Run)

### Phase 2 (emcip-conversation-context)
```
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: ~37s
```

### Phase 3 (emcip-policy-engine)
```
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: ~60s
```

### Combined
```
Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
```

---

## Notes for Phase 4

### Lessons Learned (Phase 3)
1. **Mockito for service tests**: Pure unit tests without Spring context are fast and focused
2. **Database + fallback**: Dual-mode rule loading (DB + hardcoded) improves resilience
3. **Wildcard matching**: "*" intent support enables catch-all rules
4. **JSONB columns**: Flexible metadata storage without schema changes

### Recommended for Phase 4 Testing
1. Add `@WebMvcTest` for REST controller tests
2. Add embedded Kafka for consumer testing
3. Implement contract tests for LLM orchestrator APIs
4. Add integration tests for moderation service flow

---

## Related Documents

- [PHASE-2_USER_STORIES.md](planning/phases/PHASE-2_USER_STORIES.md) - User story details
- [ADR-003-data-persistence.md](adrs/ADR-003-data-persistence.md) - Persistence architecture decision
- [DECISIONS_SUMMARY.md](DECISIONS_SUMMARY.md) - Phase completion status
- [MILESTONES.md](planning/MILESTONES.md) - Phase 2 milestone tracking

---

*Updated for Phase 3 Policy Engine - April 18, 2026*
