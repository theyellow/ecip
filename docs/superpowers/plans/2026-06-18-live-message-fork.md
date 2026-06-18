# US-26.6 Live Message Fork — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing knowledge topic fork per-group opt-in: only publish to `knowledge.raw.messages` when a watched group has `knowledgeForkEnabled = true`.

**Architecture:** `TelegramEventPublisher` already unconditionally publishes every message to both `telegram.raw.messages` and `knowledge.raw.messages`. This plan adds a `boolean knowledgeFork` parameter to `publishMessage()` so the knowledge branch is conditional. admin-api extends the `pushWatchedGroups` payload to tell tdlib-adapter which chatIds have the flag set; `TdLibClientManager` stores them in a second in-memory set; `TelegramUpdateHandler` consults that set before calling the publisher. A new `knowledge_fork_enabled` column on `group_profiles` and a toggle in admin-ui close the loop.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate (tdlib-adapter), R2DBC (admin-api), Liquibase, React 18, CSS Modules, JUnit 5 + Mockito, Vitest + Testing Library.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `emcip-admin-api/src/main/resources/db/changelog/changes/014-group-profiles-knowledge-fork.xml` | Create | Add `knowledge_fork_enabled` column |
| `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml` | Modify | Include new migration |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/GroupProfile.java` | Modify | Add `knowledgeForkEnabled` field |
| `emcip-admin-api/src/main/java/io/emcip/admin/api/service/TelegramAccountService.java` | Modify | Send `knowledgeChatIds` in `pushWatchedGroups` payload |
| `emcip-admin-api/src/test/java/io/emcip/admin/api/service/TelegramAccountServiceTest.java` | Modify | Assert `knowledgeChatIds` in pushed payload |
| `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/controller/InternalController.java` | Modify | Add `knowledgeChatIds` to `WatchedGroupsRequest` |
| `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/config/TdLibClientManager.java` | Modify | Add `knowledgeForkChatIds` map + accessor |
| `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/service/TelegramUpdateHandler.java` | Modify | Pass `knowledgeFork` flag to `publishMessage` |
| `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/service/TelegramEventPublisher.java` | Modify | Add `boolean knowledgeFork` param; conditional fork |
| `emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/service/TelegramEventPublisherTest.java` | Modify | Update existing tests; add `knowledgeFork=false` test |
| `emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/service/TelegramUpdateHandlerTest.java` | Modify | Add test for knowledge fork routing |
| `emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.jsx` | Modify | Add `knowledgeForkEnabled` column + form toggle |

---

## Task 1: Add `knowledge_fork_enabled` to group_profiles (admin-api)

**Files:**
- Create: `emcip-admin-api/src/main/resources/db/changelog/changes/014-group-profiles-knowledge-fork.xml`
- Modify: `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/GroupProfile.java`

- [ ] **Step 1: Create migration file**

Create `emcip-admin-api/src/main/resources/db/changelog/changes/014-group-profiles-knowledge-fork.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="014-group-profiles-knowledge-fork" author="emcip">
        <comment>Add knowledge_fork_enabled flag to group_profiles for per-group knowledge pipeline opt-in</comment>
        <addColumn tableName="group_profiles">
            <column name="knowledge_fork_enabled" type="BOOLEAN" defaultValueBoolean="false">
                <constraints nullable="false"/>
            </column>
        </addColumn>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Register migration in master changelog**

In `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml`, add after the `013-...` include line:

```xml
    <include file="db/changelog/changes/014-group-profiles-knowledge-fork.xml"/>
```

- [ ] **Step 3: Add field to GroupProfile entity**

In `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/GroupProfile.java`, add after the `autoRespond` field:

```java
    @Schema(description = "Whether live messages from this group are forked to the knowledge pipeline")
    @Column("knowledge_fork_enabled")
    private boolean knowledgeForkEnabled;
```

- [ ] **Step 4: Compile to verify**

```bash
cd /home/ben/Development/ecip
mvn compile -pl emcip-admin-api -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Spotless + commit**

```bash
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/java/io/emcip/admin/api/entity/GroupProfile.java \
        emcip-admin-api/src/main/resources/db/changelog/
git commit -m "$(cat <<'EOF'
feat(admin-api): add knowledge_fork_enabled to group_profiles

Liquibase migration 014 adds knowledge_fork_enabled boolean column
(default false). GroupProfile entity gains knowledgeForkEnabled field
following the same pattern as autoRespond.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Extend pushWatchedGroups to send knowledgeChatIds (admin-api)

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/TelegramAccountService.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/service/TelegramAccountServiceTest.java`

Background: `pushWatchedGroups` currently sends `{ "chatIds": [...] }`. We need to also send `"knowledgeChatIds": [...]` — the subset where `knowledgeForkEnabled = true`.

- [ ] **Step 1: Write failing test first**

Find the existing `pushWatchedGroups` test in `TelegramAccountServiceTest.java`. Add a new test after it:

```java
@Test
void pushWatchedGroups_includesKnowledgeChatIdsSubset() {
    UUID accountId = UUID.randomUUID();

    GroupProfile gp1 = new GroupProfile();
    gp1.setTelegramChatId(-1001L);
    gp1.setKnowledgeForkEnabled(true);

    GroupProfile gp2 = new GroupProfile();
    gp2.setTelegramChatId(-1002L);
    gp2.setKnowledgeForkEnabled(false);

    AccountWatchedGroup awg1 = new AccountWatchedGroup();
    awg1.setGroupProfileId(1L);
    AccountWatchedGroup awg2 = new AccountWatchedGroup();
    awg2.setGroupProfileId(2L);

    when(watchedGroupRepository.findByAccountId(accountId))
            .thenReturn(Flux.just(awg1, awg2));
    when(groupProfileRepository.findById(1L)).thenReturn(Mono.just(gp1));
    when(groupProfileRepository.findById(2L)).thenReturn(Mono.just(gp2));

    AtomicReference<Map<String, Object>> capturedBody = new AtomicReference<>();
    when(tdlibClient.post()).thenReturn(requestBodyUriSpec);
    when(requestBodyUriSpec.uri(anyString(), any(UUID.class))).thenReturn(requestBodySpec);
    when(requestBodySpec.bodyValue(any())).thenAnswer(inv -> {
        capturedBody.set((Map<String, Object>) inv.getArgument(0));
        return requestHeadersSpec;
    });
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToMono(Void.class)).thenReturn(Mono.empty());

    StepVerifier.create(service.pushWatchedGroups(accountId)).verifyComplete();

    @SuppressWarnings("unchecked")
    List<Long> knowledgeChatIds = (List<Long>) capturedBody.get().get("knowledgeChatIds");
    assertThat(knowledgeChatIds).containsExactly(-1001L);

    @SuppressWarnings("unchecked")
    List<Long> allChatIds = (List<Long>) capturedBody.get().get("chatIds");
    assertThat(allChatIds).containsExactlyInAnyOrder(-1001L, -1002L);
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-admin-api -Dtest=TelegramAccountServiceTest#pushWatchedGroups_includesKnowledgeChatIdsSubset -q 2>&1 | tail -15
```

Expected: FAIL — `knowledgeChatIds` key is not in the payload yet.

- [ ] **Step 3: Update pushWatchedGroups in TelegramAccountService**

Locate `pushWatchedGroups` in `TelegramAccountService.java`. The current implementation maps to `GroupProfile::getTelegramChatId` and builds a `Map.of("chatIds", chatIds)`. Replace the body of the method with:

```java
public Mono<Void> pushWatchedGroups(UUID accountId) {
    return watchedGroupRepository
            .findByAccountId(accountId)
            .flatMap(awg -> groupProfileRepository.findById(awg.getGroupProfileId()))
            .collectList()
            .flatMap(
                    profiles -> {
                        List<Long> chatIds =
                                profiles.stream()
                                        .map(GroupProfile::getTelegramChatId)
                                        .toList();
                        List<Long> knowledgeChatIds =
                                profiles.stream()
                                        .filter(GroupProfile::isKnowledgeForkEnabled)
                                        .map(GroupProfile::getTelegramChatId)
                                        .toList();
                        Map<String, Object> payload = new java.util.LinkedHashMap<>();
                        payload.put("chatIds", chatIds);
                        payload.put("knowledgeChatIds", knowledgeChatIds);
                        return tdlibClient
                                .post()
                                .uri("/internal/watched-groups/{id}", accountId)
                                .bodyValue(payload)
                                .retrieve()
                                .bodyToMono(Void.class)
                                .transformDeferred(
                                        CircuitBreakerOperator.of(tdlibCircuitBreaker))
                                .retryWhen(
                                        reactor.util.retry.Retry.backoff(
                                                        5, Duration.ofSeconds(2))
                                                .maxBackoff(Duration.ofSeconds(30))
                                                .doBeforeRetry(
                                                        signal ->
                                                                log.warn(
                                                                        "[{}] Retrying"
                                                                            + " watched-groups"
                                                                            + " push"
                                                                            + " (attempt"
                                                                            + " {}): {}",
                                                                        accountId,
                                                                        signal.totalRetries()
                                                                                + 1,
                                                                        signal.failure()
                                                                                .getMessage())))
                                .onErrorResume(
                                        e -> {
                                            log.error(
                                                    "[{}] Failed to push watched groups"
                                                        + " after retries: {}",
                                                    accountId,
                                                    e.getMessage());
                                            return Mono.empty();
                                        });
                    })
            .then();
}
```

Note: the import `java.util.Map` is already present; add `java.util.List` if not already imported.

- [ ] **Step 4: Run admin-api tests**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-admin-api -q 2>&1 | tail -10
```

Expected: all tests PASS.

- [ ] **Step 5: Spotless + commit**

```bash
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/java/io/emcip/admin/api/service/TelegramAccountService.java \
        emcip-admin-api/src/test/java/io/emcip/admin/api/service/TelegramAccountServiceTest.java
git commit -m "$(cat <<'EOF'
feat(admin-api): push knowledgeChatIds subset to tdlib-adapter

pushWatchedGroups now sends { chatIds, knowledgeChatIds } where
knowledgeChatIds is the subset of watched groups with
knowledgeForkEnabled=true.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Accept knowledgeChatIds in tdlib-adapter and store in manager

**Files:**
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/controller/InternalController.java`
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/config/TdLibClientManager.java`

- [ ] **Step 1: Update WatchedGroupsRequest and InternalController**

In `InternalController.java`, replace the `WatchedGroupsRequest` record and the `updateWatchedGroups` method body:

```java
// Replace the record at the bottom of the class:
public record WatchedGroupsRequest(List<Long> chatIds, List<Long> knowledgeChatIds) {
    public WatchedGroupsRequest {
        if (knowledgeChatIds == null) knowledgeChatIds = List.of();
    }
}
```

Update the handler method body:

```java
@PostMapping("/watched-groups/{accountId}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public Mono<Void> updateWatchedGroups(
        @PathVariable UUID accountId, @RequestBody WatchedGroupsRequest req) {
    manager.updateWatchedChats(
            accountId,
            new HashSet<>(req.chatIds()),
            new HashSet<>(req.knowledgeChatIds()));
    log.info(
            "[{}] Watched chat IDs updated: {}, knowledge fork: {}",
            accountId,
            req.chatIds(),
            req.knowledgeChatIds());
    return Mono.empty();
}
```

- [ ] **Step 2: Update TdLibClientManager**

In `TdLibClientManager.java`:

Add a new field after `watchedChatIds`:

```java
private final ConcurrentMap<UUID, Set<Long>> knowledgeForkChatIds = new ConcurrentHashMap<>();
```

Replace the `updateWatchedChats` method:

```java
public void updateWatchedChats(UUID accountId, Set<Long> chatIds, Set<Long> knowledgeChatIds) {
    watchedChatIds.put(accountId, chatIds);
    knowledgeForkChatIds.put(accountId, knowledgeChatIds);
    log.debug(
            "[{}] Watched chat IDs updated: {}, knowledge fork: {}",
            accountId,
            chatIds,
            knowledgeChatIds);
}
```

Add a new method after `getWatchedChatIds`:

```java
public boolean isKnowledgeForkEnabled(UUID accountId, long chatId) {
    return knowledgeForkChatIds.getOrDefault(accountId, Set.of()).contains(chatId);
}
```

Also update the `removeAccount` method to clear the new map:

```java
// In removeAccount(), add after watchedChatIds.remove(accountId):
knowledgeForkChatIds.remove(accountId);
```

- [ ] **Step 3: Compile to verify**

```bash
cd /home/ben/Development/ecip
mvn compile -pl emcip-tdlib-adapter -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

---

## Task 4: Wire knowledge fork flag through UpdateHandler and Publisher

**Files:**
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/service/TelegramUpdateHandler.java`
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/service/TelegramEventPublisher.java`
- Modify: `emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/service/TelegramEventPublisherTest.java`
- Modify: `emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/service/TelegramUpdateHandlerTest.java`

- [ ] **Step 1: Update TelegramEventPublisherTest — write failing tests first**

In `TelegramEventPublisherTest.java`:

1. All existing `publisher.publishMessage(...)` calls pass only 3 args. After adding the `knowledgeFork` param (next step), they'll need to be updated. **First**, add one new test that will encode the expected new behavior:

```java
@Test
@SuppressWarnings("unchecked")
void publishMessage_knowledgeForkFalse_sendsOnlyToTelegramTopic() {
    TdApi.UpdateNewMessage update = makeUpdate(100L, 20L, "hello");

    StepVerifier.create(publisher.publishMessage(update.message, update, null, false))
            .verifyComplete();

    ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafkaTemplate, times(1)).send(captor.capture());
    assertThat(captor.getValue().topic()).isEqualTo("telegram.raw.messages");
}

@Test
@SuppressWarnings("unchecked")
void publishMessage_knowledgeForkTrue_sendsToBothTopics() {
    TdApi.UpdateNewMessage update = makeUpdate(100L, 21L, "hello");

    StepVerifier.create(publisher.publishMessage(update.message, update, null, true))
            .verifyComplete();

    ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafkaTemplate, times(2)).send(captor.capture());
    assertThat(captor.getAllValues().stream().map(ProducerRecord::topic).toList())
            .containsExactlyInAnyOrder("telegram.raw.messages", "knowledge.raw.messages");
}
```

Also update all existing `publishMessage` call sites in the test file to pass `true` as the fourth argument (they verify dual-topic behaviour, which requires `knowledgeFork=true`):
- `publishMessage_sameMessageTwice_onlySendsOnce` — change both calls to `publishMessage(msg, update, null, true)`
- `publishMessage_differentMessages_sendsBoth` — change both calls to `publishMessage(msg, update, null, true)`
- `publishMessage_sendsToTelegramAndKnowledgeTopics` — change to `publishMessage(msg, update, null, true)`
- `extractMetadata_*` tests — change to `publishMessage(msg, update, null, true)` (they don't care about topic count, just payload)

- [ ] **Step 2: Run tests to confirm compile/runtime failure**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-tdlib-adapter -Dtest=TelegramEventPublisherTest -q 2>&1 | tail -15
```

Expected: COMPILE ERROR — `publishMessage` doesn't accept 4 args yet.

- [ ] **Step 3: Update TelegramEventPublisher — add knowledgeFork parameter**

In `TelegramEventPublisher.java`, change the method signature:

```java
public Mono<Void> publishMessage(
        TdApi.Message message, TdApi.UpdateNewMessage update, String tenantId, boolean knowledgeFork) {
```

Wrap the knowledge record construction and send call in a conditional:

```java
// Replace:
//   kafkaTemplate.send(knowledgeRecord);
//   return kafkaTemplate.send(kafkaRecord);
// With:
if (knowledgeFork) {
    kafkaTemplate.send(knowledgeRecord);
}
return kafkaTemplate.send(kafkaRecord);
```

The `knowledgeRecord` construction block (lines 93-106) can stay in place — just move it inside the `if (knowledgeFork)` block, or keep it outside but guard the send. **Cleaner: wrap the entire knowledgeRecord block:**

```java
if (knowledgeFork) {
    org.apache.kafka.clients.producer.ProducerRecord<String, String>
            knowledgeRecord =
                    new org.apache.kafka.clients.producer.ProducerRecord<>(
                            TOPIC_KNOWLEDGE_RAW,
                            String.valueOf(message.chatId),
                            json);
    if (effectiveTenantId != null) {
        knowledgeRecord
                .headers()
                .add(
                        io.emcip.common.tenant.TenantContext.KAFKA_HEADER,
                        effectiveTenantId.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8));
    }
    kafkaTemplate.send(knowledgeRecord);
}
return kafkaTemplate.send(kafkaRecord);
```

- [ ] **Step 4: Update TelegramUpdateHandler — pass the flag**

In `TelegramUpdateHandler.java`, in `handleNewMessage`, look up the flag and pass it:

```java
void handleNewMessage(UUID accountId, TdApi.Update update) {
    if (!(update instanceof TdApi.UpdateNewMessage newMessage)) return;

    long chatId = newMessage.message.chatId;
    Set<Long> watched = watchedChatIds.getOrDefault(accountId, Set.of());
    if (!watched.contains(chatId)) {
        log.debug("[{}] Skipping message from unwatched chat {}", accountId, chatId);
        return;
    }

    log.debug(
            "Received new message from chat {}: {}",
            chatId,
            newMessage.message.content instanceof TdApi.MessageText text
                    ? text.text.text
                    : "[non-text]");

    String tenantId = manager.getTenantId(accountId);
    boolean knowledgeFork = manager.isKnowledgeForkEnabled(accountId, chatId);
    eventPublisher
            .publishMessage(newMessage.message, newMessage, tenantId, knowledgeFork)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
```

(Keep the rest of the subscribe chain unchanged.)

- [ ] **Step 5: Add TelegramUpdateHandlerTest for knowledge fork routing**

In `TelegramUpdateHandlerTest.java`, add two new tests:

```java
@Test
void handleNewMessage_chatWithKnowledgeFork_publishesWithKnowledgeForkTrue() {
    UUID accountId = UUID.randomUUID();
    long chatId = 999L;
    watchedChatIds.put(accountId, Set.of(chatId));
    knowledgeForkChatIds.put(accountId, Set.of(chatId));  // fork enabled

    TdApi.UpdateNewMessage update = makeUpdate(chatId, 1L, "test");
    ArgumentCaptor<Boolean> forkCaptor = ArgumentCaptor.forClass(Boolean.class);
    when(publisher.publishMessage(any(), any(), any(), forkCaptor.capture()))
            .thenReturn(Mono.empty());

    handler.handleNewMessage(accountId, update);

    assertThat(forkCaptor.getValue()).isTrue();
}

@Test
void handleNewMessage_chatWithoutKnowledgeFork_publishesWithKnowledgeForkFalse() {
    UUID accountId = UUID.randomUUID();
    long chatId = 888L;
    watchedChatIds.put(accountId, Set.of(chatId));
    knowledgeForkChatIds.put(accountId, Set.of());  // fork not enabled for this chat

    TdApi.UpdateNewMessage update = makeUpdate(chatId, 2L, "test");
    ArgumentCaptor<Boolean> forkCaptor = ArgumentCaptor.forClass(Boolean.class);
    when(publisher.publishMessage(any(), any(), any(), forkCaptor.capture()))
            .thenReturn(Mono.empty());

    handler.handleNewMessage(accountId, update);

    assertThat(forkCaptor.getValue()).isFalse();
}
```

Note: `TelegramUpdateHandlerTest` currently has `watchedChatIds` as a field (the map injected into the handler). Add `knowledgeForkChatIds` field the same way. Check the existing test setup to see how `watchedChatIds` is wired and follow the same pattern for `knowledgeForkChatIds`.

Look at the existing test constructor call — it probably passes `watchedChatIds` directly. Update it to also pass `knowledgeForkChatIds` if `TdLibClientManager` is mocked; otherwise add `knowledgeForkChatIds` as a constructor parameter to `TelegramUpdateHandler` (only if `TelegramUpdateHandler` doesn't use `manager.isKnowledgeForkEnabled` — since we now use `manager`, the test just needs to stub `manager.isKnowledgeForkEnabled`).

Check `TelegramUpdateHandlerTest.java` setUp first:

```bash
grep -n "setUp\|handler\|manager\|watchedChatIds" \
  emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/service/TelegramUpdateHandlerTest.java \
  | head -20
```

If `manager` is mocked, stub it:
```java
when(manager.isKnowledgeForkEnabled(accountId, chatId)).thenReturn(true);  // or false
```

- [ ] **Step 6: Run all tdlib-adapter tests**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-tdlib-adapter -q 2>&1 | tail -10
```

Expected: all tests PASS.

- [ ] **Step 7: Spotless + commit tdlib-adapter**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-tdlib-adapter -q
git add emcip-tdlib-adapter/src/
git commit -m "$(cat <<'EOF'
feat(tdlib-adapter): per-group knowledge fork opt-in

- WatchedGroupsRequest now accepts knowledgeChatIds (default empty)
- TdLibClientManager stores knowledgeForkChatIds per account;
  exposes isKnowledgeForkEnabled(accountId, chatId)
- TelegramUpdateHandler passes knowledgeFork flag to publishMessage
- TelegramEventPublisher.publishMessage gains boolean knowledgeFork param;
  only publishes to knowledge.raw.messages when true

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Add knowledgeForkEnabled toggle to admin-ui Groups page

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.jsx`

The Groups page uses `autoRespond` as a boolean toggle pattern. `knowledgeForkEnabled` follows the same pattern.

- [ ] **Step 1: Read the current Groups.jsx**

```bash
cat emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.jsx
```

Note the current column definitions, modal form fields, and `form` state shape.

- [ ] **Step 2: Add column to the columns array**

In the `COLUMNS` array (after the `autoRespond` column), add:

```jsx
{ key: 'knowledgeForkEnabled', label: 'Knowledge Fork', width: 130,
  render: v => <Badge variant={v ? 'green' : 'gray'}>{v ? 'YES' : 'NO'}</Badge> },
```

- [ ] **Step 3: Add to form initial state**

In the form state initializer (inside `GroupModal` or wherever `form` is set), add `knowledgeForkEnabled`:

```js
knowledgeForkEnabled: group?.knowledgeForkEnabled ?? false,
```

- [ ] **Step 4: Add toggle to GroupModal form**

After the `autoRespond` checkbox field in the modal form, add:

```jsx
<div className={styles.field}>
  <label className={styles.checkLabel}>
    <input type="checkbox" checked={form.knowledgeForkEnabled}
      onChange={e => set('knowledgeForkEnabled', e.target.checked)} />
    Knowledge Fork
  </label>
  <span className={styles.hint}>Fork live messages to the knowledge pipeline</span>
</div>
```

- [ ] **Step 5: Run Groups tests (if any)**

```bash
cd emcip-admin-ui/src/main/frontend
npx vitest run src/pages/Groups/ 2>&1 | tail -10
```

If no test file exists, this returns "no test files found" — that's fine, proceed.

- [ ] **Step 6: Commit frontend**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.jsx
git commit -m "$(cat <<'EOF'
feat(admin-ui): add knowledgeForkEnabled toggle to Groups page

Column + modal checkbox following the same pattern as autoRespond.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- ✅ "tdlib-adapter publishes to `knowledge.raw.messages` in addition to `telegram.raw.messages`" — already done; now gated by flag
- ✅ "Same message format, no changes to existing consumers" — `TelegramMessageEvent` payload unchanged, knowledge-engine consumer unchanged
- ✅ "Configurable per tenant (opt-in/opt-out)" — implemented per-group (finer granularity than per-tenant; groups belong to a tenant so tenant-level opt-in is achievable by toggling all groups)

**Placeholder scan:** None found.

**Type consistency:**
- `isKnowledgeForkEnabled(UUID accountId, long chatId)` defined in Task 3, used in Task 4 ✅
- `publishMessage(..., boolean knowledgeFork)` defined in Task 4, all call sites updated ✅
- `WatchedGroupsRequest(List<Long> chatIds, List<Long> knowledgeChatIds)` defined in Task 3, payload constructed in Task 2 ✅
