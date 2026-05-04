# Telegram Group Watching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-account Telegram group watching — operators discover groups the user-bot is a member of, subscribe/unsubscribe, and only messages from watched groups reach Kafka, with deduplication when multiple accounts watch the same group.

**Architecture:** A `ConcurrentMap<UUID, Set<Long>> watchedChatIds` (Spring `@Bean`) is shared between `TdLibClientManager` and `TelegramUpdateHandler`. admin-api stores subscriptions in `account_watched_groups` and pushes the current set to tdlib-adapter via `POST /internal/watched-groups/{accountId}` on every change and on startup. `TelegramEventPublisher` deduplicates with a short-lived Caffeine cache keyed by `chatId:messageId`.

**Tech Stack:** Java 21, Spring Boot 4, WebFlux/R2DBC (admin-api), TDLib JNI (tdlib-adapter), Caffeine, Liquibase, React/JSX (admin-ui), Vitest

---

## File Map

### emcip-tdlib-adapter
| Action | File |
|--------|------|
| CREATE | `config/WatchedChatsConfig.java` — defines the shared `ConcurrentMap` Spring bean |
| MODIFY | `config/TdLibClientManager.java` — inject map, add `updateWatchedChats()`, clear on remove |
| MODIFY | `service/TelegramUpdateHandler.java` — inject map, capture accountId in `registerOn`, filter in `handleNewMessage` |
| MODIFY | `service/TelegramEventPublisher.java` — add Caffeine dedup cache |
| CREATE | `controller/InternalController.java` — `POST /internal/watched-groups/{id}` + `GET /internal/chats/{id}` |
| MODIFY | `pom.xml` — add Caffeine dependency |
| MODIFY | `TdLibClientManagerTest.java` — tests for `updateWatchedChats`, `removeClient` clears set |
| CREATE | `service/TelegramUpdateHandlerTest.java` — filter tests |

### emcip-admin-api
| Action | File |
|--------|------|
| CREATE | `entity/AccountWatchedGroup.java` |
| CREATE | `repository/AccountWatchedGroupRepository.java` |
| MODIFY | `controller/TelegramAccountController.java` — inject new repos, add `/chats`, `/watched`, `/watch`, `DELETE /watch/{chatId}` |
| MODIFY | `config/TelegramSessionResumeRunner.java` — push watched groups after session resume |
| CREATE | `db/changelog/changes/008-account-watched-groups.xml` |
| MODIFY | `db/changelog/db.changelog-master.xml` — include 008 |

### emcip-admin-ui
| Action | File |
|--------|------|
| MODIFY | `src/api/telegram.js` — add `discoverChats`, `listWatched`, `watchGroup`, `unwatchGroup` |
| MODIFY | `src/pages/Telegram/Telegram.jsx` — groups panel, discover modal, auto-open on ACTIVE |
| MODIFY | `src/pages/Telegram/Telegram.test.jsx` — replace stale tests with current behaviour tests |

---

## Task 1: Shared watchedChatIds bean + TdLibClientManager update

**Files:**
- Create: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/config/WatchedChatsConfig.java`
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/config/TdLibClientManager.java`
- Modify: `emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/config/TdLibClientManagerTest.java`

- [ ] **Step 1: Write failing tests for the new manager behaviour**

```java
// Add to TdLibClientManagerTest.java (after existing tests)

@Test
void updateWatchedChats_storesSetForAccount() {
    UUID id = UUID.randomUUID();
    manager.updateWatchedChats(id, Set.of(111L, 222L));
    assertThat(manager.getWatchedChatIds(id)).containsExactlyInAnyOrder(111L, 222L);
}

@Test
void updateWatchedChats_replacesExistingSet() {
    UUID id = UUID.randomUUID();
    manager.updateWatchedChats(id, Set.of(111L));
    manager.updateWatchedChats(id, Set.of(999L));
    assertThat(manager.getWatchedChatIds(id)).containsExactly(999L);
}

@Test
void removeClient_clearsWatchedSet() {
    UUID id = UUID.randomUUID();
    manager.registerClient(id, stubClient(id));
    manager.updateWatchedChats(id, Set.of(111L));
    manager.removeClient(id);
    assertThat(manager.getWatchedChatIds(id)).isEmpty();
}

@Test
void getWatchedChatIds_unknownAccount_returnsEmptySet() {
    assertThat(manager.getWatchedChatIds(UUID.randomUUID())).isEmpty();
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-tdlib-adapter -Dtest=TdLibClientManagerTest -am -q 2>&1 | tail -20
```

Expected: `updateWatchedChats_storesSetForAccount` fails with `NoSuchMethodError` or compilation error.

- [ ] **Step 3: Create WatchedChatsConfig**

```java
// emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/config/WatchedChatsConfig.java
package io.emcip.tdlib.adapter.config;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WatchedChatsConfig {

    @Bean
    public ConcurrentMap<UUID, Set<Long>> watchedChatIds() {
        return new ConcurrentHashMap<>();
    }
}
```

- [ ] **Step 4: Update TdLibClientManager**

Replace the existing `TdLibClientManager.java` with:

```java
package io.emcip.tdlib.adapter.config;

import io.emcip.tdlib.adapter.service.TelegramUpdateHandler;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.drinkless.tdlib.TdApi;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TdLibClientManager {

    private final TdLibProperties properties;
    private final TelegramUpdateHandler updateHandler;
    private final ConcurrentMap<UUID, TdLibClient> clients = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<Long>> watchedChatIds;

    public TdLibClientManager(
            TdLibProperties properties,
            @Lazy TelegramUpdateHandler updateHandler,
            ConcurrentMap<UUID, Set<Long>> watchedChatIds) {
        this.properties = properties;
        this.updateHandler = updateHandler;
        this.watchedChatIds = watchedChatIds;
    }

    public TdLibClient createAndInitialize(
            UUID accountId, int apiId, String apiHash, String phoneNumber, String sessionString) {
        log.debug(
                "[{}] Session string present: {}",
                accountId,
                sessionString != null && !sessionString.isEmpty());
        removeClient(accountId);
        String dbDir = properties.baseDirectory() + "/" + accountId;
        TdLibClient client =
                new TdLibClient(
                        accountId,
                        apiId,
                        apiHash,
                        phoneNumber,
                        dbDir,
                        properties,
                        this::onAuthStateChange);
        clients.put(accountId, client);
        client.initialize();
        updateHandler.registerOn(client);
        return client;
    }

    public void registerClient(UUID accountId, TdLibClient client) {
        clients.put(accountId, client);
    }

    public TdLibClient getClient(UUID accountId) {
        TdLibClient client = clients.get(accountId);
        if (client == null) {
            throw new IllegalArgumentException(
                    "No TdLibClient registered for account " + accountId);
        }
        return client;
    }

    public boolean hasClient(UUID accountId) {
        return clients.containsKey(accountId);
    }

    public Map<UUID, TdLibClient> getClients() {
        return Collections.unmodifiableMap(clients);
    }

    public void removeClient(UUID accountId) {
        TdLibClient existing = clients.remove(accountId);
        if (existing != null) {
            try {
                existing.destroy();
            } catch (Exception e) {
                log.warn("[{}] Error destroying client: {}", accountId, e.getMessage());
            }
        }
        watchedChatIds.remove(accountId);
    }

    public void updateWatchedChats(UUID accountId, Set<Long> chatIds) {
        watchedChatIds.put(accountId, chatIds);
        log.debug("[{}] Watched chat IDs updated: {}", accountId, chatIds);
    }

    public Set<Long> getWatchedChatIds(UUID accountId) {
        return watchedChatIds.getOrDefault(accountId, Set.of());
    }

    private void onAuthStateChange(UUID accountId, TdApi.AuthorizationState state) {
        log.info("[{}] Auth state changed to: {}", accountId, state.getClass().getSimpleName());
    }
}
```

- [ ] **Step 5: Update TdLibClientManagerTest — inject map into manager**

Update `setUp()` in `TdLibClientManagerTest.java`:

```java
// Replace setUp():
@BeforeEach
void setUp() {
    properties = new TdLibProperties("tdlib-test", true, true, true, false, 1);
    manager = new TdLibClientManager(
            properties,
            mock(TelegramUpdateHandler.class),
            new ConcurrentHashMap<>());
}
```

- [ ] **Step 6: Run all manager tests**

```bash
mvn test -pl emcip-tdlib-adapter -Dtest=TdLibClientManagerTest -am -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, 8 tests pass.

- [ ] **Step 7: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-tdlib-adapter -q
git add emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/config/WatchedChatsConfig.java \
        emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/config/TdLibClientManager.java \
        emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/config/TdLibClientManagerTest.java
git commit -m "feat(tdlib): add per-account watched chat IDs registry to TdLibClientManager"
```

---

## Task 2: TelegramUpdateHandler — accountId capture + message filtering

**Files:**
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/service/TelegramUpdateHandler.java`
- Create: `emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/service/TelegramUpdateHandlerTest.java`

- [ ] **Step 1: Write failing filter tests**

```java
// emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/service/TelegramUpdateHandlerTest.java
package io.emcip.tdlib.adapter.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class TelegramUpdateHandlerTest {

    @Mock TelegramEventPublisher publisher;

    ConcurrentMap<UUID, Set<Long>> watchedChatIds;
    TelegramUpdateHandler handler;

    @BeforeEach
    void setUp() {
        watchedChatIds = new ConcurrentHashMap<>();
        handler = new TelegramUpdateHandler(publisher, watchedChatIds);
    }

    @Test
    void handleNewMessage_chatNotWatched_skipsPublish() {
        UUID accountId = UUID.randomUUID();
        watchedChatIds.put(accountId, Set.of(111L));

        handler.handleNewMessage(accountId, makeUpdate(999L, 1L));

        verifyNoInteractions(publisher);
    }

    @Test
    void handleNewMessage_chatWatched_publishes() {
        UUID accountId = UUID.randomUUID();
        watchedChatIds.put(accountId, Set.of(111L));
        when(publisher.publishMessage(any(), any())).thenReturn(Mono.empty());

        handler.handleNewMessage(accountId, makeUpdate(111L, 1L));

        verify(publisher).publishMessage(any(), any());
    }

    @Test
    void handleNewMessage_emptyWatchedSet_skipsPublish() {
        UUID accountId = UUID.randomUUID();
        watchedChatIds.put(accountId, Set.of()); // empty set

        handler.handleNewMessage(accountId, makeUpdate(111L, 1L));

        verifyNoInteractions(publisher);
    }

    @Test
    void handleNewMessage_noEntryForAccount_skipsPublish() {
        // account has no entry in watchedChatIds at all
        handler.handleNewMessage(UUID.randomUUID(), makeUpdate(111L, 1L));

        verifyNoInteractions(publisher);
    }

    private TdApi.UpdateNewMessage makeUpdate(long chatId, long messageId) {
        TdApi.FormattedText ft = new TdApi.FormattedText();
        ft.text = "hello";
        ft.entities = new TdApi.TextEntity[0];
        TdApi.MessageText content = new TdApi.MessageText();
        content.text = ft;
        TdApi.Message message = new TdApi.Message();
        message.id = messageId;
        message.chatId = chatId;
        message.content = content;
        TdApi.UpdateNewMessage update = new TdApi.UpdateNewMessage();
        update.message = message;
        return update;
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn test -pl emcip-tdlib-adapter -Dtest=TelegramUpdateHandlerTest -am -q 2>&1 | tail -20
```

Expected: compilation error — `TelegramUpdateHandler` constructor doesn't take the map yet, and `handleNewMessage` is private.

- [ ] **Step 3: Update TelegramUpdateHandler**

```java
package io.emcip.tdlib.adapter.service;

import io.emcip.tdlib.adapter.config.TdLibClient;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.drinkless.tdlib.TdApi;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
public class TelegramUpdateHandler {

    private final TelegramEventPublisher eventPublisher;
    private final ConcurrentMap<UUID, Set<Long>> watchedChatIds;

    public TelegramUpdateHandler(
            TelegramEventPublisher eventPublisher,
            ConcurrentMap<UUID, Set<Long>> watchedChatIds) {
        this.eventPublisher = eventPublisher;
        this.watchedChatIds = watchedChatIds;
    }

    public void registerOn(TdLibClient client) {
        UUID accountId = client.getAccountId();
        client.registerUpdateHandler(
                "UpdateNewMessage", update -> handleNewMessage(accountId, update));
        client.registerUpdateHandler("UpdateMessageEdited", this::handleMessageEdited);
        client.registerUpdateHandler("UpdateDeleteMessages", this::handleMessageDeleted);
        client.registerUpdateHandler("UpdateChatTitle", this::handleChatTitle);
        client.registerUpdateHandler("UpdateUser", this::handleUserUpdate);
        log.info("[{}] Telegram update handlers registered", accountId);
    }

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

        eventPublisher
                .publishMessage(newMessage.message, newMessage)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        error -> log.error("Error publishing message: {}", error.getMessage()));
    }

    private void handleMessageEdited(TdApi.Update update) {
        if (update instanceof TdApi.UpdateMessageEdited edited) {
            log.debug("Message {} edited in chat {}", edited.messageId, edited.chatId);
            eventPublisher
                    .publishUpdate(update)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            null,
                            error ->
                                    log.error(
                                            "Error publishing edited event: {}",
                                            error.getMessage()));
        }
    }

    private void handleMessageDeleted(TdApi.Update update) {
        if (update instanceof TdApi.UpdateDeleteMessages deleted) {
            log.debug("Messages deleted in chat {}", deleted.chatId);
            eventPublisher
                    .publishUpdate(update)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            null,
                            error ->
                                    log.error(
                                            "Error publishing deleted event: {}",
                                            error.getMessage()));
        }
    }

    private void handleChatTitle(TdApi.Update update) {
        if (update instanceof TdApi.UpdateChatTitle title) {
            log.debug("Chat {} title updated: {}", title.chatId, title.title);
            eventPublisher
                    .publishUpdate(update)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            null,
                            error ->
                                    log.error(
                                            "Error publishing chat title event: {}",
                                            error.getMessage()));
        }
    }

    private void handleUserUpdate(TdApi.Update update) {
        if (update instanceof TdApi.UpdateUser userUpdate) {
            log.debug("User {} updated", userUpdate.user.id);
            eventPublisher
                    .publishUpdate(update)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            null,
                            error ->
                                    log.error(
                                            "Error publishing user update: {}",
                                            error.getMessage()));
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
mvn test -pl emcip-tdlib-adapter -Dtest="TelegramUpdateHandlerTest,TdLibClientManagerTest" -am -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-tdlib-adapter -q
git add emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/service/TelegramUpdateHandler.java \
        emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/service/TelegramUpdateHandlerTest.java
git commit -m "feat(tdlib): filter UpdateNewMessage to watched chats only, capture accountId in handler"
```

---

## Task 3: Caffeine dependency + TelegramEventPublisher deduplication

**Files:**
- Modify: `emcip-tdlib-adapter/pom.xml`
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/service/TelegramEventPublisher.java`

- [ ] **Step 1: Add Caffeine to pom.xml**

Open `emcip-tdlib-adapter/pom.xml`. After the `jackson-datatype-jsr310` dependency block, add:

```xml
    <!-- Caffeine cache for message deduplication -->
    <dependency>
      <groupId>com.github.ben-manes.caffeine</groupId>
      <artifactId>caffeine</artifactId>
    </dependency>
```

(Spring Boot BOM manages the version — no `<version>` tag needed.)

- [ ] **Step 2: Write a deduplication test**

The dedup logic lives inside `publishMessage`. The cleanest test is to verify that calling `publishMessage` twice with the same `chatId:messageId` results in only one Kafka send. Since the publisher uses `KafkaTemplate`, mock it.

Add a new test class:

```java
// emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/service/TelegramEventPublisherTest.java
package io.emcip.tdlib.adapter.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TelegramEventPublisherTest {

    @Mock KafkaTemplate<String, String> kafkaTemplate;
    TelegramEventPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        publisher = new TelegramEventPublisher(kafkaTemplate);
        SendResult<String, String> sendResult =
                new SendResult<>(null, new RecordMetadata(null, 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
    }

    @Test
    void publishMessage_sameMessageTwice_onlySendsOnce() {
        TdApi.UpdateNewMessage update1 = makeUpdate(100L, 42L, "hello");
        TdApi.UpdateNewMessage update2 = makeUpdate(100L, 42L, "hello"); // same chatId + messageId

        StepVerifier.create(publisher.publishMessage(update1.message, update1)).verifyComplete();
        StepVerifier.create(publisher.publishMessage(update2.message, update2)).verifyComplete();

        verify(kafkaTemplate, times(1)).send(eq("telegram.raw.messages"), anyString(), anyString());
    }

    @Test
    void publishMessage_differentMessages_sendsBoth() {
        TdApi.UpdateNewMessage update1 = makeUpdate(100L, 1L, "hello");
        TdApi.UpdateNewMessage update2 = makeUpdate(100L, 2L, "world"); // different messageId

        StepVerifier.create(publisher.publishMessage(update1.message, update1)).verifyComplete();
        StepVerifier.create(publisher.publishMessage(update2.message, update2)).verifyComplete();

        verify(kafkaTemplate, times(2)).send(eq("telegram.raw.messages"), anyString(), anyString());
    }

    private TdApi.UpdateNewMessage makeUpdate(long chatId, long messageId, String text) {
        TdApi.FormattedText ft = new TdApi.FormattedText();
        ft.text = text;
        ft.entities = new TdApi.TextEntity[0];
        TdApi.MessageText content = new TdApi.MessageText();
        content.text = ft;
        TdApi.Message message = new TdApi.Message();
        message.id = messageId;
        message.chatId = chatId;
        message.content = content;
        TdApi.UpdateNewMessage update = new TdApi.UpdateNewMessage();
        update.message = message;
        return update;
    }
}
```

- [ ] **Step 3: Run test to confirm it fails**

```bash
mvn test -pl emcip-tdlib-adapter -Dtest=TelegramEventPublisherTest -am -q 2>&1 | tail -20
```

Expected: `publishMessage_sameMessageTwice_onlySendsOnce` FAILS (Kafka is called twice without dedup).

- [ ] **Step 4: Update TelegramEventPublisher — add Caffeine dedup**

In `TelegramEventPublisher.java`, add the cache field and update `publishMessage`:

```java
// Add imports:
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Add field after kafkaTemplate:
private final Cache<String, Boolean> deduplicationCache =
        Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(10_000)
                .build();

// Replace publishMessage method:
public Mono<Void> publishMessage(TdApi.Message message, TdApi.UpdateNewMessage update) {
    String dedupKey = message.chatId + ":" + message.id;
    AtomicBoolean shouldPublish = new AtomicBoolean(false);
    deduplicationCache.get(dedupKey, k -> {
        shouldPublish.set(true);
        return Boolean.TRUE;
    });
    if (!shouldPublish.get()) {
        log.debug("Skipping duplicate message chatId={} messageId={}", message.chatId, message.id);
        return Mono.empty();
    }

    return Mono.fromCallable(
                    () -> {
                        TelegramMessageEvent event = convertToEvent(message, update);
                        String json = serialize(event);
                        return kafkaTemplate.send(
                                TOPIC_TELEGRAM_RAW, String.valueOf(message.chatId), json);
                    })
            .flatMap(future -> Mono.fromFuture(future.toCompletableFuture()))
            .doOnSuccess(result -> log.debug("Published message {} to Kafka", message.id))
            .doOnError(
                    e ->
                            log.error(
                                    "Failed to publish message {}: {}", message.id, e.getMessage()))
            .onErrorResume(
                    e -> {
                        log.error("Error publishing message to Kafka: {}", e.getMessage(), e);
                        return Mono.empty();
                    })
            .then();
}
```

- [ ] **Step 5: Run all tdlib-adapter tests**

```bash
mvn test -pl emcip-tdlib-adapter -am -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-tdlib-adapter -q
git add emcip-tdlib-adapter/pom.xml \
        emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/service/TelegramEventPublisher.java \
        emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/service/TelegramEventPublisherTest.java
git commit -m "feat(tdlib): deduplicate Kafka publishes with 60s Caffeine cache keyed by chatId:messageId"
```

---

## Task 4: InternalController (tdlib-adapter)

**Files:**
- Create: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/controller/InternalController.java`

- [ ] **Step 1: Write failing test**

```java
// emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/controller/InternalControllerTest.java
package io.emcip.tdlib.adapter.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.tdlib.adapter.config.TdLibClientManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class InternalControllerTest {

    @Mock TdLibClientManager manager;
    InternalController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalController(manager);
    }

    @Test
    void updateWatchedGroups_callsManager() {
        UUID accountId = UUID.randomUUID();
        InternalController.WatchedGroupsRequest req =
                new InternalController.WatchedGroupsRequest(List.of(111L, 222L));

        StepVerifier.create(controller.updateWatchedGroups(accountId, req))
                .verifyComplete();

        verify(manager).updateWatchedChats(accountId, Set.of(111L, 222L));
    }

    @Test
    void discoverChats_accountNotFound_returnsBadRequest() {
        UUID accountId = UUID.randomUUID();
        when(manager.hasClient(accountId)).thenReturn(false);

        StepVerifier.create(controller.discoverChats(accountId))
                .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(400))
                .verifyComplete();
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
mvn test -pl emcip-tdlib-adapter -Dtest=InternalControllerTest -am -q 2>&1 | tail -15
```

Expected: compilation error — `InternalController` doesn't exist.

- [ ] **Step 3: Create InternalController**

```java
// emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/controller/InternalController.java
package io.emcip.tdlib.adapter.controller;

import io.emcip.tdlib.adapter.config.TdLibClient;
import io.emcip.tdlib.adapter.config.TdLibClientManager;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.drinkless.tdlib.TdApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final TdLibClientManager manager;

    @PostMapping("/watched-groups/{accountId}")
    public Mono<ResponseEntity<Void>> updateWatchedGroups(
            @PathVariable UUID accountId, @RequestBody WatchedGroupsRequest req) {
        manager.updateWatchedChats(accountId, new HashSet<>(req.chatIds()));
        log.info("[{}] Watched chat IDs updated: {}", accountId, req.chatIds());
        return Mono.just(ResponseEntity.noContent().<Void>build());
    }

    @GetMapping("/chats/{accountId}")
    public Mono<ResponseEntity<List<ChatInfo>>> discoverChats(@PathVariable UUID accountId) {
        if (!manager.hasClient(accountId)) {
            log.warn("[{}] discoverChats: no client found", accountId);
            return Mono.just(ResponseEntity.badRequest().<List<ChatInfo>>build());
        }
        TdLibClient client = manager.getClient(accountId);
        if (!client.isAuthorized()) {
            log.warn("[{}] discoverChats: client not authorized", accountId);
            return Mono.just(ResponseEntity.badRequest().<List<ChatInfo>>build());
        }
        return loadChats(client)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("[{}] discoverChats error: {}", accountId, e.getMessage());
                    return Mono.just(ResponseEntity.internalServerError().<List<ChatInfo>>build());
                });
    }

    private Mono<List<ChatInfo>> loadChats(TdLibClient client) {
        return Mono.<TdApi.Chats>create(
                        sink ->
                                client.sendRequest(
                                        new TdApi.GetChats(null, 200),
                                        result -> {
                                            if (result instanceof TdApi.Chats chats)
                                                sink.success(chats);
                                            else if (result instanceof TdApi.Error err)
                                                sink.error(
                                                        new RuntimeException(
                                                                "GetChats error: " + err.message));
                                        }))
                .flatMapMany(
                        chats ->
                                Flux.fromStream(Arrays.stream(chats.chatIds).boxed()))
                .flatMap(
                        chatId ->
                                Mono.<TdApi.Chat>create(
                                        sink ->
                                                client.sendRequest(
                                                        new TdApi.GetChat(chatId),
                                                        result -> {
                                                            if (result instanceof TdApi.Chat chat)
                                                                sink.success(chat);
                                                            else
                                                                sink.error(
                                                                        new RuntimeException(
                                                                                "GetChat error for "
                                                                                        + chatId));
                                                        })))
                .filter(
                        chat ->
                                chat.type instanceof TdApi.ChatTypeSupergroup
                                        || chat.type instanceof TdApi.ChatTypeBasicGroup)
                .map(
                        chat -> {
                            String type = chatType(chat.type);
                            return new ChatInfo(chat.id, chat.title, type);
                        })
                .collectList();
    }

    private static String chatType(TdApi.ChatType type) {
        if (type instanceof TdApi.ChatTypeSupergroup sg) {
            return sg.isChannel ? "CHANNEL" : "SUPERGROUP";
        }
        return "GROUP";
    }

    public record WatchedGroupsRequest(List<Long> chatIds) {}

    public record ChatInfo(long chatId, String title, String type) {}
}
```

- [ ] **Step 4: Run tests**

```bash
mvn test -pl emcip-tdlib-adapter -Dtest=InternalControllerTest -am -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, 2 tests pass.

- [ ] **Step 5: Run all tdlib-adapter tests**

```bash
mvn test -pl emcip-tdlib-adapter -am -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-tdlib-adapter -q
git add emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/controller/InternalController.java \
        emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/controller/InternalControllerTest.java
git commit -m "feat(tdlib): add /internal/watched-groups and /internal/chats endpoints"
```

---

## Task 5: Liquibase migration + AccountWatchedGroup entity + repository

**Files:**
- Create: `emcip-admin-api/src/main/resources/db/changelog/changes/008-account-watched-groups.xml`
- Modify: `emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/entity/AccountWatchedGroup.java`
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/repository/AccountWatchedGroupRepository.java`

Note: `group_profiles.telegram_chat_id` already has a UNIQUE constraint from the initial migration — no constraint changes needed.

- [ ] **Step 1: Create 008-account-watched-groups.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">

    <changeSet id="008-account-watched-groups" author="emcip-team">
        <comment>Join table: telegram accounts to watched group profiles</comment>
        <createTable tableName="account_watched_groups">
            <column name="id" type="BIGSERIAL">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="account_id" type="UUID">
                <constraints nullable="false"/>
            </column>
            <column name="group_profile_id" type="BIGINT">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="NOW()">
                <constraints nullable="false"/>
            </column>
        </createTable>
        <addUniqueConstraint
            tableName="account_watched_groups"
            columnNames="account_id,group_profile_id"
            constraintName="uq_awg_account_group"/>
        <addForeignKeyConstraint
            baseTableName="account_watched_groups" baseColumnNames="account_id"
            referencedTableName="telegram_accounts" referencedColumnNames="id"
            constraintName="fk_awg_account" onDelete="CASCADE"/>
        <addForeignKeyConstraint
            baseTableName="account_watched_groups" baseColumnNames="group_profile_id"
            referencedTableName="group_profiles" referencedColumnNames="id"
            constraintName="fk_awg_group_profile" onDelete="CASCADE"/>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Include in db.changelog-master.xml**

Add after the `007` include:

```xml
    <include file="db/changelog/changes/008-account-watched-groups.xml"/>
```

- [ ] **Step 3: Create AccountWatchedGroup entity**

```java
// emcip-admin-api/src/main/java/io/emcip/admin/api/entity/AccountWatchedGroup.java
package io.emcip.admin.api.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("account_watched_groups")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountWatchedGroup {

    @Id private Long id;

    @Column("account_id")
    private UUID accountId;

    @Column("group_profile_id")
    private Long groupProfileId;

    @Column("created_at")
    private Instant createdAt;
}
```

- [ ] **Step 4: Create AccountWatchedGroupRepository**

```java
// emcip-admin-api/src/main/java/io/emcip/admin/api/repository/AccountWatchedGroupRepository.java
package io.emcip.admin.api.repository;

import io.emcip.admin.api.entity.AccountWatchedGroup;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface AccountWatchedGroupRepository
        extends ReactiveCrudRepository<AccountWatchedGroup, Long> {

    Flux<AccountWatchedGroup> findByAccountId(UUID accountId);

    Mono<Void> deleteByAccountIdAndGroupProfileId(UUID accountId, Long groupProfileId);

    Mono<Boolean> existsByAccountIdAndGroupProfileId(UUID accountId, Long groupProfileId);
}
```

- [ ] **Step 5: Build to confirm compilation**

```bash
mvn compile -pl emcip-admin-api -am -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/resources/db/changelog/changes/008-account-watched-groups.xml \
        emcip-admin-api/src/main/resources/db/changelog/db.changelog-master.xml \
        emcip-admin-api/src/main/java/io/emcip/admin/api/entity/AccountWatchedGroup.java \
        emcip-admin-api/src/main/java/io/emcip/admin/api/repository/AccountWatchedGroupRepository.java
git commit -m "feat(admin-api): add account_watched_groups migration, entity, and repository"
```

---

## Task 6: Admin-api watch/unwatch/discover endpoints

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/TelegramAccountControllerTest.java`

- [ ] **Step 1: Write failing tests for the new endpoints**

Add these tests to `TelegramAccountControllerTest.java`. First, update the class to add the two new mock dependencies and rebuild the controller in `setUp`:

```java
// Add fields:
@Mock AccountWatchedGroupRepository watchedGroupRepository;
@Mock GroupProfileRepository groupProfileRepository;

// Replace setUp():
@BeforeEach
void setUp() {
    controller =
            new TelegramAccountController(
                    repository,
                    r2dbcEntityTemplate,
                    tdlibClient,
                    watchedGroupRepository,
                    groupProfileRepository,
                    12345,
                    "abc123");
}
```

Add new tests:

```java
@Test
void watchGroup_createsGroupProfileAndWatchedEntry() {
    UUID accountId = UUID.randomUUID();
    GroupProfile profile =
            GroupProfile.builder()
                    .id(1L)
                    .telegramChatId(555L)
                    .name("Test Group")
                    .rulesEnabled("[]")
                    .moderationLevel("MEDIUM")
                    .autoRespond(false)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

    when(groupProfileRepository.findByTelegramChatId(555L)).thenReturn(Mono.empty());
    when(r2dbcEntityTemplate.insert(any(GroupProfile.class))).thenReturn(Mono.just(profile));
    when(watchedGroupRepository.existsByAccountIdAndGroupProfileId(accountId, 1L))
            .thenReturn(Mono.just(false));
    when(r2dbcEntityTemplate.insert(any(AccountWatchedGroup.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // Mock the tdlib push (WebClient chain)
    WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(tdlibClient.post()).thenReturn(uriSpec);
    when(uriSpec.uri(anyString(), any(Object.class))).thenReturn(bodySpec);
    when(bodySpec.bodyValue(any())).thenReturn(bodySpec);
    when(bodySpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToMono(Void.class)).thenReturn(Mono.empty());

    // Mock the findByAccountId for pushWatchedGroups
    AccountWatchedGroup awg = AccountWatchedGroup.builder()
            .accountId(accountId).groupProfileId(1L).build();
    when(watchedGroupRepository.findByAccountId(accountId)).thenReturn(Flux.just(awg));
    when(groupProfileRepository.findById(1L)).thenReturn(Mono.just(profile));

    TelegramAccountController.WatchRequest req =
            new TelegramAccountController.WatchRequest(555L, "Test Group");

    StepVerifier.create(controller.watchGroup(accountId, req))
            .assertNext(map -> {
                assertThat(map.get("chatId")).isEqualTo(555L);
                assertThat(map.get("name")).isEqualTo("Test Group");
            })
            .verifyComplete();
}

@Test
void listWatched_returnsWatchedGroupsForAccount() {
    UUID accountId = UUID.randomUUID();
    GroupProfile profile =
            GroupProfile.builder()
                    .id(1L)
                    .telegramChatId(555L)
                    .name("Test Group")
                    .moderationLevel("MEDIUM")
                    .build();
    AccountWatchedGroup awg =
            AccountWatchedGroup.builder().accountId(accountId).groupProfileId(1L).build();

    when(watchedGroupRepository.findByAccountId(accountId)).thenReturn(Flux.just(awg));
    when(groupProfileRepository.findById(1L)).thenReturn(Mono.just(profile));

    StepVerifier.create(controller.listWatched(accountId))
            .assertNext(list -> {
                assertThat(list).hasSize(1);
                assertThat(list.get(0).get("chatId")).isEqualTo(555L);
                assertThat(list.get(0).get("name")).isEqualTo("Test Group");
            })
            .verifyComplete();
}
```

You'll also need to add `import` statements for the new types (`AccountWatchedGroup`, `AccountWatchedGroupRepository`, `GroupProfileRepository`, `GroupProfile`, `Flux`).

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn test -pl emcip-admin-api -Dtest=TelegramAccountControllerTest -am -q 2>&1 | tail -20
```

Expected: compilation error — `TelegramAccountController` constructor doesn't take the new repos yet.

- [ ] **Step 3: Update TelegramAccountController**

Add the new constructor parameters, fields, and endpoints. The full updated controller:

```java
package io.emcip.admin.api.controller;

import io.emcip.admin.api.entity.AccountWatchedGroup;
import io.emcip.admin.api.entity.GroupProfile;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/telegram/accounts")
public class TelegramAccountController {

    private final TelegramAccountRepository repository;
    private final R2dbcEntityTemplate r2dbcEntityTemplate;
    private final WebClient tdlibClient;
    private final AccountWatchedGroupRepository watchedGroupRepository;
    private final GroupProfileRepository groupProfileRepository;
    private final int telegramApiId;
    private final String telegramApiHash;

    public TelegramAccountController(
            TelegramAccountRepository repository,
            R2dbcEntityTemplate r2dbcEntityTemplate,
            @Qualifier("tdlibWebClient") WebClient tdlibClient,
            AccountWatchedGroupRepository watchedGroupRepository,
            GroupProfileRepository groupProfileRepository,
            @Value("${telegram.api-id}") int telegramApiId,
            @Value("${telegram.api-hash}") String telegramApiHash) {
        this.repository = repository;
        this.r2dbcEntityTemplate = r2dbcEntityTemplate;
        this.tdlibClient = tdlibClient;
        this.watchedGroupRepository = watchedGroupRepository;
        this.groupProfileRepository = groupProfileRepository;
        this.telegramApiId = telegramApiId;
        this.telegramApiHash = telegramApiHash;
    }

    @GetMapping
    public Mono<List<Map<String, Object>>> listAccounts() {
        return repository.findAll().map(TelegramAccountController::toSafeMap).collectList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> createAccount(@RequestBody CreateAccountRequest req) {
        TelegramAccount account =
                TelegramAccount.builder()
                        .id(UUID.randomUUID())
                        .phoneNumber(req.phoneNumber())
                        .apiId(telegramApiId)
                        .apiHash(telegramApiHash)
                        .displayName(req.displayName())
                        .status(TelegramAccountStatus.UNCONFIGURED)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
        return r2dbcEntityTemplate.insert(account).map(TelegramAccountController::toSafeMap);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteAccount(@PathVariable("id") UUID id) {
        return repository.deleteById(id);
    }

    @GetMapping("/{id}/status")
    public Mono<Map<String, Object>> getStatus(@PathVariable("id") UUID id) {
        return repository
                .findById(id)
                .flatMap(
                        account ->
                                tdlibClient
                                        .get()
                                        .uri("/api/auth/{id}/status", id)
                                        .retrieve()
                                        .bodyToMono(TdlibStatusResponse.class)
                                        .flatMap(
                                                r -> {
                                                    TelegramAccountStatus adapterStatus =
                                                            TelegramAccountStatus.valueOf(
                                                                    r.getStatus());
                                                    Map<String, Object> m = new LinkedHashMap<>();
                                                    m.put("id", id.toString());
                                                    m.put("status", r.getStatus());
                                                    m.put("lastError", r.getLastError());
                                                    if (adapterStatus != account.getStatus()
                                                            || (r.getLastError() != null
                                                                    && !r.getLastError()
                                                                            .equals(
                                                                                    account
                                                                                            .getLastError()))) {
                                                        return repository
                                                                .save(
                                                                        update(
                                                                                account,
                                                                                adapterStatus,
                                                                                r.getLastError()))
                                                                .thenReturn(m);
                                                    }
                                                    return Mono.just(m);
                                                })
                                        .onErrorResume(
                                                e -> {
                                                    Map<String, Object> m = new LinkedHashMap<>();
                                                    m.put("id", id.toString());
                                                    m.put("status", account.getStatus().name());
                                                    m.put("lastError", account.getLastError());
                                                    return Mono.just(m);
                                                }))
                .switchIfEmpty(
                        Mono.error(new IllegalArgumentException("Account not found: " + id)));
    }

    @PostMapping("/{id}/reconnect")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Map<String, Object>> reconnect(@PathVariable("id") UUID id) {
        return repository
                .findById(id)
                .flatMap(
                        account -> {
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("phoneNumber", account.getPhoneNumber());
                            payload.put("apiId", account.getApiId());
                            payload.put("apiHash", account.getApiHash());
                            payload.put("sessionString", account.getSessionString());
                            return tdlibClient
                                    .post()
                                    .uri("/api/auth/{id}/initialize", id)
                                    .bodyValue(payload)
                                    .retrieve()
                                    .bodyToMono(Void.class)
                                    .then(
                                            repository.save(
                                                    update(
                                                            account,
                                                            TelegramAccountStatus.AWAITING_CODE,
                                                            null)))
                                    .thenReturn(Map.<String, Object>of("accepted", true))
                                    .onErrorResume(
                                            e -> {
                                                log.warn(
                                                        "reconnect failed for {}: {}",
                                                        id,
                                                        e.getMessage());
                                                return Mono.just(
                                                        Map.of(
                                                                "accepted",
                                                                false,
                                                                "reason",
                                                                e.getMessage()));
                                            });
                        })
                .switchIfEmpty(Mono.just(Map.of("accepted", false, "reason", "Account not found")));
    }

    @PostMapping("/{id}/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> submitCode(@PathVariable("id") UUID id, @RequestBody CodeRequest req) {
        return tdlibClient
                .post()
                .uri("/api/auth/{id}/code", id)
                .bodyValue(Map.of("code", req.code()))
                .retrieve()
                .bodyToMono(Void.class);
    }

    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> submitPassword(
            @PathVariable("id") UUID id, @RequestBody PasswordRequest req) {
        return tdlibClient
                .post()
                .uri("/api/auth/{id}/password", id)
                .bodyValue(Map.of("password", req.password()))
                .retrieve()
                .bodyToMono(Void.class);
    }

    @PostMapping("/{id}/logout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> logout(@PathVariable("id") UUID id) {
        return tdlibClient
                .post()
                .uri("/api/auth/{id}/logout", id)
                .retrieve()
                .bodyToMono(Void.class)
                .then(
                        repository
                                .findById(id)
                                .flatMap(
                                        a ->
                                                repository.save(
                                                        update(
                                                                a,
                                                                TelegramAccountStatus.DISCONNECTED,
                                                                null)))
                                .then());
    }

    // ── Group watching ────────────────────────────────────────────────────────

    @GetMapping("/{id}/chats")
    public Mono<List<Map<String, Object>>> discoverChats(@PathVariable("id") UUID id) {
        return tdlibClient
                .get()
                .uri("/internal/chats/{id}", id)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<Map<String, Object>>() {})
                .collectList()
                .onErrorReturn(List.of());
    }

    @GetMapping("/{id}/watched")
    public Mono<List<Map<String, Object>>> listWatched(@PathVariable("id") UUID id) {
        return watchedGroupRepository
                .findByAccountId(id)
                .flatMap(
                        awg ->
                                groupProfileRepository
                                        .findById(awg.getGroupProfileId())
                                        .map(profile -> toWatchedMap(profile)))
                .collectList();
    }

    @PostMapping("/{id}/watch")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> watchGroup(
            @PathVariable("id") UUID accountId, @RequestBody WatchRequest req) {
        return groupProfileRepository
                .findByTelegramChatId(req.chatId())
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        r2dbcEntityTemplate.insert(
                                                GroupProfile.builder()
                                                        .telegramChatId(req.chatId())
                                                        .name(
                                                                req.title() != null
                                                                        ? req.title()
                                                                        : "Chat " + req.chatId())
                                                        .rulesEnabled("[]")
                                                        .autoRespond(false)
                                                        .moderationLevel("MEDIUM")
                                                        .createdAt(Instant.now())
                                                        .updatedAt(Instant.now())
                                                        .build())))
                .flatMap(
                        profile ->
                                watchedGroupRepository
                                        .existsByAccountIdAndGroupProfileId(
                                                accountId, profile.getId())
                                        .flatMap(
                                                exists ->
                                                        exists
                                                                ? Mono.just(profile)
                                                                : r2dbcEntityTemplate
                                                                        .insert(
                                                                                AccountWatchedGroup
                                                                                        .builder()
                                                                                        .accountId(
                                                                                                accountId)
                                                                                        .groupProfileId(
                                                                                                profile
                                                                                                        .getId())
                                                                                        .createdAt(
                                                                                                Instant
                                                                                                        .now())
                                                                                        .build())
                                                                        .thenReturn(profile)))
                .flatMap(profile -> pushWatchedGroups(accountId).thenReturn(profile))
                .map(profile -> toWatchedMap(profile));
    }

    @DeleteMapping("/{id}/watch/{chatId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unwatchGroup(
            @PathVariable("id") UUID accountId, @PathVariable("chatId") Long chatId) {
        return groupProfileRepository
                .findByTelegramChatId(chatId)
                .flatMap(
                        profile ->
                                watchedGroupRepository.deleteByAccountIdAndGroupProfileId(
                                        accountId, profile.getId()))
                .then(pushWatchedGroups(accountId));
    }

    private Mono<Void> pushWatchedGroups(UUID accountId) {
        return watchedGroupRepository
                .findByAccountId(accountId)
                .flatMap(awg -> groupProfileRepository.findById(awg.getGroupProfileId()))
                .map(GroupProfile::getTelegramChatId)
                .collectList()
                .flatMap(
                        chatIds ->
                                tdlibClient
                                        .post()
                                        .uri("/internal/watched-groups/{id}", accountId)
                                        .bodyValue(Map.of("chatIds", chatIds))
                                        .retrieve()
                                        .bodyToMono(Void.class)
                                        .onErrorResume(
                                                e -> {
                                                    log.warn(
                                                            "[{}] Failed to push watched groups: {}",
                                                            accountId,
                                                            e.getMessage());
                                                    return Mono.empty();
                                                }));
    }

    private static Map<String, Object> toWatchedMap(GroupProfile profile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chatId", profile.getTelegramChatId());
        m.put("groupProfileId", profile.getId());
        m.put("name", profile.getName());
        m.put("moderationLevel", profile.getModerationLevel());
        return m;
    }

    private static TelegramAccount update(
            TelegramAccount a, TelegramAccountStatus status, String lastError) {
        a.setStatus(status);
        a.setLastError(lastError);
        a.setUpdatedAt(Instant.now());
        return a;
    }

    private static Map<String, Object> toSafeMap(TelegramAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId().toString());
        m.put("displayName", a.getDisplayName() != null ? a.getDisplayName() : "");
        m.put("phoneNumber", a.getPhoneNumber());
        m.put("apiId", a.getApiId());
        m.put("status", a.getStatus().name());
        m.put("lastError", a.getLastError());
        m.put("sessionStringSet", a.getSessionString() != null && !a.getSessionString().isEmpty());
        m.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
        return m;
    }

    public record CreateAccountRequest(String phoneNumber, String displayName) {}

    public record CodeRequest(String code) {}

    public record PasswordRequest(String password) {}

    public record WatchRequest(long chatId, String title) {}

    @Data
    public static class TdlibStatusResponse {
        private String status;
        private String lastError;
    }
}
```

- [ ] **Step 4: Run all admin-api tests**

```bash
mvn test -pl emcip-admin-api -am -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/java/io/emcip/admin/api/controller/TelegramAccountController.java \
        emcip-admin-api/src/test/java/io/emcip/admin/api/controller/TelegramAccountControllerTest.java
git commit -m "feat(admin-api): add group watch/unwatch/discover/list endpoints to TelegramAccountController"
```

---

## Task 7: TelegramSessionResumeRunner — push watched groups on startup

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/config/TelegramSessionResumeRunner.java`

- [ ] **Step 1: Update TelegramSessionResumeRunner**

```java
package io.emcip.admin.api.config;

import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.entity.TelegramAccountStatus;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class TelegramSessionResumeRunner {

    private final TelegramAccountRepository repository;
    private final WebClient tdlibClient;
    private final AccountWatchedGroupRepository watchedGroupRepository;
    private final GroupProfileRepository groupProfileRepository;

    public TelegramSessionResumeRunner(
            TelegramAccountRepository repository,
            @Qualifier("tdlibWebClient") WebClient tdlibClient,
            AccountWatchedGroupRepository watchedGroupRepository,
            GroupProfileRepository groupProfileRepository) {
        this.repository = repository;
        this.tdlibClient = tdlibClient;
        this.watchedGroupRepository = watchedGroupRepository;
        this.groupProfileRepository = groupProfileRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeActiveSessions() {
        repository
                .findByStatus(TelegramAccountStatus.ACTIVE)
                .flatMap(
                        account ->
                                initializeAccount(account)
                                        .then(pushWatchedGroups(account.getId())))
                .subscribe(
                        id -> log.info("Session resume triggered for account {}", id),
                        err -> log.warn("Session resume error: {}", err.getMessage()));
    }

    private Mono<String> initializeAccount(TelegramAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phoneNumber", account.getPhoneNumber());
        payload.put("apiId", account.getApiId());
        payload.put("apiHash", account.getApiHash());
        payload.put("sessionString", account.getSessionString());

        return tdlibClient
                .post()
                .uri("/api/auth/{id}/initialize", account.getId())
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Void.class)
                .thenReturn(account.getId().toString())
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "Failed to resume session for account {}: {}",
                                    account.getId(),
                                    e.getMessage());
                            account.setStatus(TelegramAccountStatus.DISCONNECTED);
                            account.setLastError("Session resume failed: " + e.getMessage());
                            account.setUpdatedAt(Instant.now());
                            return repository.save(account).thenReturn(account.getId().toString());
                        });
    }

    private Mono<Void> pushWatchedGroups(UUID accountId) {
        return watchedGroupRepository
                .findByAccountId(accountId)
                .flatMap(awg -> groupProfileRepository.findById(awg.getGroupProfileId()))
                .map(profile -> profile.getTelegramChatId())
                .collectList()
                .flatMap(
                        chatIds ->
                                tdlibClient
                                        .post()
                                        .uri("/internal/watched-groups/{id}", accountId)
                                        .bodyValue(Map.of("chatIds", chatIds))
                                        .retrieve()
                                        .bodyToMono(Void.class)
                                        .onErrorResume(
                                                e -> {
                                                    log.warn(
                                                            "[{}] Failed to push watched groups on"
                                                                    + " startup: {}",
                                                            accountId,
                                                            e.getMessage());
                                                    return Mono.empty();
                                                }))
                .then();
    }
}
```

- [ ] **Step 2: Build to confirm compilation**

```bash
mvn compile -pl emcip-admin-api -am -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run all admin-api tests**

```bash
mvn test -pl emcip-admin-api -am -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Apply Spotless and commit**

```bash
mvn spotless:apply -pl emcip-admin-api -q
git add emcip-admin-api/src/main/java/io/emcip/admin/api/config/TelegramSessionResumeRunner.java
git commit -m "feat(admin-api): push watched group IDs to tdlib-adapter on session startup"
```

---

## Task 8: Admin UI — groups panel, discover modal, auto-open on ACTIVE

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/api/telegram.js`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.test.jsx`

- [ ] **Step 1: Update telegram.js — add group watching API methods**

```js
export function telegramApi(request) {
  return {
    listAccounts: () => request('/api/telegram/accounts'),
    createAccount: body =>
      request('/api/telegram/accounts', { method: 'POST', body: JSON.stringify(body) }),
    deleteAccount: id =>
      request(`/api/telegram/accounts/${id}`, { method: 'DELETE' }),
    getStatus: id => request(`/api/telegram/accounts/${id}/status`),
    reconnect: id =>
      request(`/api/telegram/accounts/${id}/reconnect`, { method: 'POST' }),
    submitCode: (id, code) =>
      request(`/api/telegram/accounts/${id}/code`, {
        method: 'POST',
        body: JSON.stringify({ code }),
      }),
    submitPassword: (id, password) =>
      request(`/api/telegram/accounts/${id}/password`, {
        method: 'POST',
        body: JSON.stringify({ password }),
      }),
    logout: id =>
      request(`/api/telegram/accounts/${id}/logout`, { method: 'POST' }),
    discoverChats: id => request(`/api/telegram/accounts/${id}/chats`),
    listWatched: id => request(`/api/telegram/accounts/${id}/watched`),
    watchGroup: (id, body) =>
      request(`/api/telegram/accounts/${id}/watch`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    unwatchGroup: (id, chatId) =>
      request(`/api/telegram/accounts/${id}/watch/${chatId}`, { method: 'DELETE' }),
  }
}
```

- [ ] **Step 2: Write failing UI tests**

Replace the entire `Telegram.test.jsx` (the existing tests are stale — they test the old single-account config page):

```jsx
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { Telegram } from './Telegram'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token' }),
}))
vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

const mockApi = {
  listAccounts: vi.fn(),
  createAccount: vi.fn(),
  deleteAccount: vi.fn(),
  getStatus: vi.fn(),
  reconnect: vi.fn(),
  submitCode: vi.fn(),
  submitPassword: vi.fn(),
  logout: vi.fn(),
  discoverChats: vi.fn(),
  listWatched: vi.fn(),
  watchGroup: vi.fn(),
  unwatchGroup: vi.fn(),
}

vi.mock('../../api/telegram', () => ({
  telegramApi: () => mockApi,
}))

describe('Telegram page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.listAccounts.mockResolvedValue([])
  })

  it('renders accounts table with no-accounts empty state', async () => {
    render(<Telegram />)
    await waitFor(() =>
      expect(screen.getByText('No accounts configured')).toBeInTheDocument()
    )
  })

  it('shows account row with status badge', async () => {
    mockApi.listAccounts.mockResolvedValue([
      { id: 'uuid-1', displayName: 'Monitor 1', phoneNumber: '+49123', status: 'ACTIVE', lastError: null },
    ])
    render(<Telegram />)
    await waitFor(() => expect(screen.getByText('Monitor 1')).toBeInTheDocument())
    expect(screen.getByText('ACTIVE')).toBeInTheDocument()
  })

  it('Groups button expands group panel for that account', async () => {
    mockApi.listAccounts.mockResolvedValue([
      { id: 'uuid-1', displayName: 'Monitor 1', phoneNumber: '+49123', status: 'ACTIVE', lastError: null },
    ])
    mockApi.listWatched.mockResolvedValue([])
    render(<Telegram />)
    await waitFor(() => screen.getByText('Monitor 1'))

    await userEvent.click(screen.getByRole('button', { name: /groups/i }))
    await waitFor(() =>
      expect(screen.getByText('No groups watched')).toBeInTheDocument()
    )
  })

  it('Discover button opens modal and shows discovered chats', async () => {
    mockApi.listAccounts.mockResolvedValue([
      { id: 'uuid-1', displayName: 'Monitor 1', phoneNumber: '+49123', status: 'ACTIVE', lastError: null },
    ])
    mockApi.listWatched.mockResolvedValue([])
    mockApi.discoverChats.mockResolvedValue([
      { chatId: 111, title: 'My Group', type: 'SUPERGROUP' },
    ])
    render(<Telegram />)
    await waitFor(() => screen.getByText('Monitor 1'))
    await userEvent.click(screen.getByRole('button', { name: /groups/i }))
    await userEvent.click(screen.getByRole('button', { name: /discover/i }))

    await waitFor(() => expect(screen.getByText('My Group')).toBeInTheDocument())
  })

  it('Watch button in discover modal calls watchGroup', async () => {
    mockApi.listAccounts.mockResolvedValue([
      { id: 'uuid-1', displayName: 'Monitor 1', phoneNumber: '+49123', status: 'ACTIVE', lastError: null },
    ])
    mockApi.listWatched.mockResolvedValue([])
    mockApi.discoverChats.mockResolvedValue([
      { chatId: 111, title: 'My Group', type: 'SUPERGROUP' },
    ])
    mockApi.watchGroup.mockResolvedValue({ chatId: 111, name: 'My Group', moderationLevel: 'MEDIUM' })
    mockApi.listWatched.mockResolvedValue([
      { chatId: 111, name: 'My Group', moderationLevel: 'MEDIUM' },
    ])
    render(<Telegram />)
    await waitFor(() => screen.getByText('Monitor 1'))
    await userEvent.click(screen.getByRole('button', { name: /groups/i }))
    await userEvent.click(screen.getByRole('button', { name: /discover/i }))
    await waitFor(() => screen.getByText('My Group'))

    await userEvent.click(screen.getByRole('button', { name: /^watch$/i }))
    expect(mockApi.watchGroup).toHaveBeenCalledWith('uuid-1', { chatId: 111, title: 'My Group' })
  })

  it('adds account and shows it in list', async () => {
    const newAccount = { id: 'uuid-2', displayName: 'Bot 2', phoneNumber: '+49999', status: 'UNCONFIGURED', lastError: null }
    mockApi.createAccount.mockResolvedValue(newAccount)
    mockApi.listAccounts
      .mockResolvedValueOnce([])
      .mockResolvedValue([newAccount])

    render(<Telegram />)
    await waitFor(() => screen.getByText('No accounts configured'))
    await userEvent.click(screen.getByRole('button', { name: /add account/i }))
    await userEvent.type(screen.getByPlaceholderText(/monitor account/i), 'Bot 2')
    await userEvent.type(screen.getByPlaceholderText(/\+49/i), '+49999')
    await userEvent.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() => expect(screen.getByText('Bot 2')).toBeInTheDocument())
  })
})
```

- [ ] **Step 3: Run tests to confirm they fail**

```bash
cd emcip-admin-ui/src/main/frontend && npm test -- --reporter=verbose 2>&1 | tail -30
```

Expected: most tests fail — groups panel and discover modal don't exist yet.

- [ ] **Step 4: Update Telegram.jsx**

```jsx
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { telegramApi } from '../../api/telegram'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import styles from './Telegram.module.css'

const STATUS_VARIANT = {
  ACTIVE: 'green',
  AWAITING_CODE: 'yellow',
  AWAITING_PASSWORD: 'yellow',
  UNCONFIGURED: 'gray',
  DISCONNECTED: 'red',
}

export function Telegram() {
  const { token } = useAuth()
  const api = useMemo(() => telegramApi(makeRequest(token)), [token])

  const [accounts, setAccounts] = useState([])
  const [error, setError] = useState('')
  const [showAdd, setShowAdd] = useState(false)
  const [addForm, setAddForm] = useState({ phoneNumber: '', displayName: '' })
  const [wizard, setWizard] = useState(null) // { accountId, step: 'code'|'password', error }
  const [codeInput, setCodeInput] = useState('')
  const [passwordInput, setPasswordInput] = useState('')

  // Groups panel state
  const [expandedAccount, setExpandedAccount] = useState(null) // accountId whose panel is open
  const [watchedGroups, setWatchedGroups] = useState({}) // { accountId: [...] }
  const [showDiscover, setShowDiscover] = useState(null) // accountId for discover modal
  const [discoveredChats, setDiscoveredChats] = useState([])
  const [discoverLoading, setDiscoverLoading] = useState(false)
  const [discoverError, setDiscoverError] = useState('')

  const loadAccounts = useCallback(() => {
    api.listAccounts().then(setAccounts).catch(e => setError(e.message))
  }, [api])

  useEffect(() => {
    loadAccounts()
  }, [loadAccounts])

  const loadWatched = useCallback((accountId) => {
    api.listWatched(accountId)
      .then(groups => setWatchedGroups(prev => ({ ...prev, [accountId]: groups })))
      .catch(() => {})
  }, [api])

  const openGroupsPanel = useCallback((accountId) => {
    setExpandedAccount(id => id === accountId ? null : accountId)
    loadWatched(accountId)
  }, [loadWatched])

  const openDiscover = useCallback(async (accountId) => {
    setShowDiscover(accountId)
    setDiscoverLoading(true)
    setDiscoverError('')
    setDiscoveredChats([])
    try {
      const chats = await api.discoverChats(accountId)
      setDiscoveredChats(chats)
    } catch (e) {
      setDiscoverError(e.message)
    } finally {
      setDiscoverLoading(false)
    }
  }, [api])

  const handleWatch = async (accountId, chat) => {
    try {
      await api.watchGroup(accountId, { chatId: chat.chatId, title: chat.title })
      loadWatched(accountId)
    } catch (e) {
      setDiscoverError(e.message)
    }
  }

  const handleUnwatch = async (accountId, chatId) => {
    try {
      await api.unwatchGroup(accountId, chatId)
      loadWatched(accountId)
    } catch (e) {
      setError(e.message)
    }
  }

  // Poll status when wizard is open
  useEffect(() => {
    if (!wizard) return
    const interval = setInterval(async () => {
      try {
        const s = await api.getStatus(wizard.accountId)
        if (s.status === 'ACTIVE') {
          setWizard(null)
          loadAccounts()
          // Auto-open groups panel and discover modal
          setExpandedAccount(wizard.accountId)
          loadWatched(wizard.accountId)
          openDiscover(wizard.accountId)
        } else if (s.status === 'AWAITING_PASSWORD' && wizard.step !== 'password') {
          setWizard(w => ({ ...w, step: 'password', error: null }))
        }
      } catch (_) {}
    }, 2500)
    return () => clearInterval(interval)
  }, [wizard, api, loadAccounts, loadWatched, openDiscover])

  const handleAdd = async () => {
    setError('')
    try {
      await api.createAccount({
        phoneNumber: addForm.phoneNumber,
        displayName: addForm.displayName,
      })
      setShowAdd(false)
      setAddForm({ phoneNumber: '', displayName: '' })
      loadAccounts()
    } catch (e) {
      setError(e.message)
    }
  }

  const handleReconnect = async id => {
    setError('')
    try {
      const res = await api.reconnect(id)
      if (res.accepted) {
        setWizard({ accountId: id, step: 'code', error: null })
      } else {
        setError(res.reason)
      }
    } catch (e) {
      setError(e.message)
    }
  }

  const handleSubmitCode = async () => {
    try {
      await api.submitCode(wizard.accountId, codeInput)
      setCodeInput('')
    } catch (e) {
      setWizard(w => ({ ...w, error: e.message }))
    }
  }

  const handleSubmitPassword = async () => {
    try {
      await api.submitPassword(wizard.accountId, passwordInput)
      setPasswordInput('')
    } catch (e) {
      setWizard(w => ({ ...w, error: e.message }))
    }
  }

  const handleLogout = async id => {
    setError('')
    try {
      await api.logout(id)
      loadAccounts()
    } catch (e) {
      setError(e.message)
    }
  }

  const handleDelete = async id => {
    setError('')
    try {
      await api.deleteAccount(id)
      loadAccounts()
    } catch (e) {
      setError(e.message)
    }
  }

  const isWatched = (accountId, chatId) =>
    (watchedGroups[accountId] || []).some(g => g.chatId === chatId)

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h2>Telegram Accounts</h2>
        <Button onClick={() => setShowAdd(true)}>Add Account</Button>
      </div>

      {error && <p className={styles.error} role="alert">{error}</p>}

      <table className={styles.table}>
        <thead>
          <tr>
            <th>Name</th>
            <th>Phone</th>
            <th>Status</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {accounts.map(a => (
            <>
              <tr key={a.id}>
                <td>{a.displayName || '—'}</td>
                <td>{a.phoneNumber}</td>
                <td>
                  <Badge variant={STATUS_VARIANT[a.status] ?? 'gray'} title={a.lastError ?? ''}>
                    {a.status}
                  </Badge>
                </td>
                <td className={styles.actions}>
                  <Button variant="secondary" onClick={() => openGroupsPanel(a.id)}>Groups</Button>
                  <Button variant="secondary" onClick={() => handleReconnect(a.id)}>Auth</Button>
                  <Button variant="secondary" onClick={() => handleLogout(a.id)}>Logout</Button>
                  <Button variant="danger" onClick={() => handleDelete(a.id)}>Delete</Button>
                </td>
              </tr>
              {expandedAccount === a.id && (
                <tr key={`${a.id}-groups`}>
                  <td colSpan={4} className={styles.groupsPanel}>
                    <div className={styles.groupsPanelHeader}>
                      <span>Watched Groups</span>
                      <Button variant="secondary" onClick={() => openDiscover(a.id)}>Discover</Button>
                    </div>
                    {(watchedGroups[a.id] || []).length === 0 ? (
                      <p className={styles.empty}>No groups watched. Use Discover to add groups.</p>
                    ) : (
                      <table className={styles.innerTable}>
                        <thead>
                          <tr>
                            <th>Name</th>
                            <th>Chat ID</th>
                            <th>Moderation</th>
                            <th></th>
                          </tr>
                        </thead>
                        <tbody>
                          {(watchedGroups[a.id] || []).map(g => (
                            <tr key={g.chatId}>
                              <td>{g.name}</td>
                              <td>{g.chatId}</td>
                              <td>{g.moderationLevel}</td>
                              <td>
                                <Button variant="danger" onClick={() => handleUnwatch(a.id, g.chatId)}>
                                  Unwatch
                                </Button>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </td>
                </tr>
              )}
            </>
          ))}
          {accounts.length === 0 && (
            <tr><td colSpan={4} className={styles.empty}>No accounts configured</td></tr>
          )}
        </tbody>
      </table>

      {/* Add account modal */}
      {showAdd && (
        <Modal title="Add Telegram Account" onClose={() => setShowAdd(false)}>
          <div className={styles.form}>
            {[
              { label: 'Display Name', key: 'displayName', type: 'text', placeholder: 'Monitor account 1' },
              { label: 'Phone Number', key: 'phoneNumber', type: 'text', placeholder: '+49123456789' },
            ].map(({ label, key, type, placeholder }) => (
              <div key={key}>
                <label className={styles.label}>{label}</label>
                <input
                  type={type}
                  className={styles.input}
                  placeholder={placeholder}
                  value={addForm[key]}
                  onChange={e => setAddForm(f => ({ ...f, [key]: e.target.value }))}
                />
              </div>
            ))}
            <div className={styles.modalActions}>
              <Button onClick={handleAdd}>Save</Button>
              <Button variant="secondary" onClick={() => setShowAdd(false)}>Cancel</Button>
            </div>
          </div>
        </Modal>
      )}

      {/* Auth wizard modal */}
      {wizard && (
        <Modal title="Authenticate Account" onClose={() => setWizard(null)}>
          <div className={styles.form}>
            {wizard.error && <p className={styles.error}>{wizard.error}</p>}
            {wizard.step === 'code' && (
              <>
                <p>Enter the verification code sent to your Telegram app.</p>
                <input
                  type="text"
                  className={styles.input}
                  placeholder="12345"
                  value={codeInput}
                  onChange={e => setCodeInput(e.target.value)}
                  autoFocus
                />
                <div className={styles.modalActions}>
                  <Button onClick={handleSubmitCode}>Submit Code</Button>
                </div>
              </>
            )}
            {wizard.step === 'password' && (
              <>
                <p>Enter your 2FA password.</p>
                <input
                  type="password"
                  className={styles.input}
                  value={passwordInput}
                  onChange={e => setPasswordInput(e.target.value)}
                  autoFocus
                />
                <div className={styles.modalActions}>
                  <Button onClick={handleSubmitPassword}>Submit Password</Button>
                </div>
              </>
            )}
          </div>
        </Modal>
      )}

      {/* Discover groups modal */}
      {showDiscover && (
        <Modal title="Discover Groups" onClose={() => setShowDiscover(null)}>
          <div className={styles.discoverModal}>
            <div className={styles.discoverHeader}>
              <Button variant="secondary" onClick={() => openDiscover(showDiscover)}>Refresh</Button>
            </div>
            {discoverError && <p className={styles.error}>{discoverError}</p>}
            {discoverLoading && <p>Loading groups...</p>}
            {!discoverLoading && discoveredChats.length === 0 && !discoverError && (
              <p className={styles.empty}>No groups found. Ensure the account is ACTIVE and in at least one group.</p>
            )}
            {!discoverLoading && discoveredChats.length > 0 && (
              <table className={styles.innerTable}>
                <thead>
                  <tr><th>Name</th><th>Type</th><th></th></tr>
                </thead>
                <tbody>
                  {discoveredChats.map(chat => (
                    <tr key={chat.chatId}>
                      <td>{chat.title}</td>
                      <td>{chat.type}</td>
                      <td>
                        {isWatched(showDiscover, chat.chatId) ? (
                          <Button variant="secondary" disabled>Watching</Button>
                        ) : (
                          <Button onClick={() => handleWatch(showDiscover, chat)}>Watch</Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </Modal>
      )}
    </div>
  )
}
```

- [ ] **Step 5: Run UI tests**

```bash
cd emcip-admin-ui/src/main/frontend && npm test -- --reporter=verbose 2>&1 | tail -30
```

Expected: all tests in `Telegram.test.jsx` pass.

- [ ] **Step 6: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/api/telegram.js \
        emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.jsx \
        emcip-admin-ui/src/main/frontend/src/pages/Telegram/Telegram.test.jsx
git commit -m "feat(admin-ui): add group watching panel, discover modal, auto-open on ACTIVE"
```

---

## Final verification

- [ ] **Run all backend tests**

```bash
mvn test -pl emcip-tdlib-adapter,emcip-admin-api -am -q 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`.

- [ ] **Run all frontend tests**

```bash
cd emcip-admin-ui/src/main/frontend && npm test 2>&1 | tail -15
```

Expected: all pass.
