# ECIP Phase 2 Test Matrix

**Document Purpose:** Comprehensive overview of all tests implemented during Phase 2 (Core Messaging Pipeline), including their purpose, scope, and additional notes.

**Last Updated:** April 17, 2026
**Phase Status:** ✅ COMPLETE

---

## Overview

| Module | Test Files | Total Tests | Test Framework |
|--------|------------|-------------|----------------|
| emcip-core | 0 | 0 | N/A (shared library) |
| emcip-conversation-context | 3 | 19 | JUnit 5 + Testcontainers |
| emcip-tdlib-adapter | 0 | 0 | Integration tests pending |
| emcip-intent-classifier | 0 | 0 | Unit tests pending |
| emcip-policy-engine | 0 | 0 | Tests pending |
| **Total** | **3** | **19** | |

---

## Test Categories

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

### Test Gaps Identified

| Gap | Priority | Recommended Action |
|-----|----------|-------------------|
| Unit tests for EventValidator | Medium | Add pure unit tests with mocked ObjectMapper |
| Unit tests for ConversationContextService | Medium | Mock repositories, test business logic |
| Kafka integration tests | Low | Requires embedded Kafka or Testcontainers Kafka |
| End-to-end message flow | Low | Requires full Docker Compose environment |

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

```
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: ~37s (emcip-conversation-context)
```

---

## Notes for Phase 3

### Lessons Learned
1. **JPA over R2DBC**: Migrated during Phase 2 due to better ecosystem support and testing ease
2. **@Modifying queries**: Require `clearAutomatically=true` for test consistency
3. **Liquibase XML**: Strict schema ordering (tagDatabase before comment caused issues)
4. **Testcontainers**: Static container = faster tests, but requires careful resource cleanup

### Recommended for Phase 3 Testing
1. Add `@DataJpaTest` slice tests for faster repository testing
2. Consider `@TestConfiguration` for service layer unit tests
3. Add embedded Kafka for consumer testing (if feasible)
4. Implement contract tests for Kafka message formats

---

## Related Documents

- [PHASE-2_USER_STORIES.md](planning/phases/PHASE-2_USER_STORIES.md) - User story details
- [ADR-003-data-persistence.md](adrs/ADR-003-data-persistence.md) - Persistence architecture decision
- [DECISIONS_SUMMARY.md](DECISIONS_SUMMARY.md) - Phase completion status
- [MILESTONES.md](planning/MILESTONES.md) - Phase 2 milestone tracking

---

*Generated for Phase 2 Merge Request - April 17, 2026*
