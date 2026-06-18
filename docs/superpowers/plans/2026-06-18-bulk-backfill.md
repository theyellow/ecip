# US-26.7 Bulk Backfill — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Operator-triggered historical backfill: fetch past Telegram messages for a watched group and push them through the knowledge extraction pipeline, bounded by a date preset.

**Architecture:** Admin-ui triggers via BackfillModal → admin-api BackfillProxyController proxies to knowledge-engine → BackfillService fetches pages from tdlib-adapter's new getChatHistory endpoint and publishes to kafka:knowledge.raw.messages → KnowledgeMessageConsumer processes normally. BackfillService already exists as a stub; BackfillController already exists as a stub; both are completed in this plan.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate (knowledge-engine), R2DBC/WebFlux (admin-api), RestClient (knowledge-engine for tdlib calls), WebClient (admin-api for knowledge-engine calls), Resilience4j CircuitBreaker, Kafka, React 18, CSS Modules, Vitest + Testing Library.

---

## File Map

| Action | File | Responsibility |
|---|---|---|
| Modify | `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/controller/InternalController.java` | Add `GET /internal/chat-history/{accountId}/{chatId}` endpoint + `ChatHistoryResponse` record |
| Modify | `emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/controller/InternalControllerTest.java` | Three new tests for getChatHistory; update setUp to inject ObjectMapper |
| Modify | `emcip-knowledge-engine/src/main/resources/application.yml` | Add `knowledge.tdlib-adapter.base-url` config key |
| Modify | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/BackfillService.java` | Implement async paginated fetch loop, Kafka publish, status tracking |
| Create | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/BackfillServiceTest.java` | Unit tests: triggerBackfill returns id, getStatus RUNNING/NOT_FOUND |
| Modify | `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/BackfillController.java` | Update BackfillRequest record (UUID accountId, long fromDate); wire new service signature |
| Create | `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/BackfillControllerTest.java` | MockMvc tests: 202 trigger, status endpoints |
| Create | `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/BackfillProxyController.java` | POST /{chatId}/backfill + GET /{chatId}/backfill/{backfillId} via WebClient |
| Create | `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/BackfillProxyControllerTest.java` | WebTestClient tests using ExchangeFunction stub pattern |
| Modify | `emcip-admin-ui/src/main/frontend/src/api/groups.js` | Add `backfill` and `backfillStatus` methods |
| Create | `emcip-admin-ui/src/main/frontend/src/pages/Groups/BackfillModal.jsx` | Config/polling/done/error phases, preset chips, account selector |
| Create | `emcip-admin-ui/src/main/frontend/src/pages/Groups/BackfillModal.module.css` | Chip row, phase-specific layout styles |
| Create | `emcip-admin-ui/src/main/frontend/src/pages/Groups/BackfillModal.test.jsx` | 5 Vitest/Testing Library tests |
| Modify | `emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.jsx` | Add Backfill column + BackfillModal rendering |

---

## Task 1: TDLib-adapter getChatHistory endpoint

**Files:**
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/controller/InternalController.java`
- Modify: `emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/controller/InternalControllerTest.java`

### Background

`InternalController` is a `@RestController` in the tdlib-adapter module. It uses reactive types (`Mono`/`Flux`) because the TDLib SDK callbacks are async — they signal results via a `ResultHandler` callback rather than returning values. The `Mono.create(sink -> client.sendRequest(..., result -> sink.success/error(...)))` pattern bridges TDLib's callback world into Project Reactor. All new TDLib calls must follow this same pattern.

The controller currently has no `ObjectMapper` field. We need to add one to serialize `TelegramMessageEvent` records to JSON strings for the response payload. The constructor uses `@RequiredArgsConstructor` from Lombok, so adding a `final` field automatically adds it to the constructor — we must update the test setUp accordingly.

Jackson 3 in this project: import `tools.jackson.databind.ObjectMapper` and `tools.jackson.core.JacksonException` (NOT `com.fasterxml.jackson`). The existing `TelegramEventPublisher` in the same module constructs `new ObjectMapper()` directly; the controller will receive it via Spring injection (it's already a bean defined by Spring Boot autoconfiguration for the servlet layer).

`TdApi.GetChatHistory` parameters: `(long chatId, long fromMessageId, int offset, int limit, boolean onlyLocal)`. Pass `offsetMessageId` as `fromMessageId`, `0` as `offset`, the `limit` query param as `limit`, and `false` as `onlyLocal`.

`TdApi.Messages` has a field `messages` which is `TdApi.Message[]`. Each `TdApi.Message` has:
- `id` (long) — message ID
- `chatId` (long)
- `date` (int) — Unix epoch seconds
- `editDate` (int)
- `isOutgoing` (boolean)
- `senderId` (TdApi.MessageSender — either `MessageSenderUser` or `MessageSenderChat`)
- `content` (TdApi.MessageContent)
- `replyTo` (TdApi.MessageReplyTo — may be `MessageReplyToMessage`)

The `toHistoricalEvent` helper constructs a `TelegramMessageEvent` using `null` for profile-cache fields (displayName, username, chatTitle) because the backfill path has no profile cache lookup — these will be enriched later in the pipeline. The `EventSchemas.TelegramMessageEvent` record is at `io.emcip.common.events.EventSchemas` in the `emcip-core` module (already a dependency of tdlib-adapter).

- [ ] **Step 1.1: Update setUp in InternalControllerTest to inject ObjectMapper**

The existing `setUp` constructs `new InternalController(manager)`. After adding `objectMapper` as the second constructor arg, this will no longer compile. Fix it now so the test class compiles.

Open `emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/controller/InternalControllerTest.java` and replace the setUp method and add the import:

```java
// Add at top with other imports:
import tools.jackson.databind.ObjectMapper;

// Replace setUp:
@BeforeEach
void setUp() {
    controller = new InternalController(manager, new ObjectMapper());
}
```

- [ ] **Step 1.2: Verify the existing tests still compile and pass**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-tdlib-adapter -Dtest=InternalControllerTest -q
```

Expected: BUILD SUCCESS (all existing tests pass). If they fail because `InternalController` doesn't yet have the ObjectMapper field, that's expected — continue to Step 1.3.

- [ ] **Step 1.3: Add ObjectMapper field and getChatHistory tests to InternalControllerTest**

Replace the full content of `InternalControllerTest.java` with the following (preserving all existing tests, adding the new field and three new tests):

```java
package io.emcip.tdlib.adapter.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.tdlib.adapter.config.TdLibClient;
import io.emcip.tdlib.adapter.config.TdLibClientManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class InternalControllerTest {

    @Mock TdLibClientManager manager;
    @Mock TdLibClient client;
    InternalController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalController(manager, new ObjectMapper());
    }

    @Test
    void updateWatchedGroups_callsManager() {
        UUID accountId = UUID.randomUUID();
        InternalController.WatchedGroupsRequest req =
                new InternalController.WatchedGroupsRequest(List.of(111L, 222L), List.of());

        StepVerifier.create(controller.updateWatchedGroups(accountId, req)).verifyComplete();

        verify(manager).updateWatchedChats(accountId, Set.of(111L, 222L), Set.of());
    }

    @Test
    void discoverChats_accountNotFound_returnsBadRequest() {
        UUID accountId = UUID.randomUUID();
        when(manager.hasClient(accountId)).thenReturn(false);

        StepVerifier.create(controller.discoverChats(accountId))
                .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(400))
                .verifyComplete();
    }

    @Test
    void sendMessage_accountNotFound_returnsBadRequest() {
        UUID accountId = UUID.randomUUID();
        when(manager.hasClient(accountId)).thenReturn(false);

        InternalController.SendMessageRequest req =
                new InternalController.SendMessageRequest(-100123L, "Hello", 0, null);

        StepVerifier.create(controller.sendMessage(accountId, req))
                .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(400))
                .verifyComplete();
    }

    @Test
    void sendMessage_clientNotAuthorized_returnsBadRequest() {
        UUID accountId = UUID.randomUUID();
        when(manager.hasClient(accountId)).thenReturn(true);
        when(manager.getClient(accountId)).thenReturn(client);
        when(client.isAuthorized()).thenReturn(false);

        InternalController.SendMessageRequest req =
                new InternalController.SendMessageRequest(-100123L, "Hello", 0, null);

        StepVerifier.create(controller.sendMessage(accountId, req))
                .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(400))
                .verifyComplete();
    }

    @Test
    void sendMessage_success_returns201WithMessageId() {
        UUID accountId = UUID.randomUUID();
        when(manager.hasClient(accountId)).thenReturn(true);
        when(manager.getClient(accountId)).thenReturn(client);
        when(client.isAuthorized()).thenReturn(true);

        TdApi.Message sentMsg = new TdApi.Message();
        sentMsg.id = 42L;
        doAnswer(
                        invocation -> {
                            Client.ResultHandler handler = invocation.getArgument(1);
                            handler.onResult(sentMsg);
                            return null;
                        })
                .when(client)
                .sendRequest(any(TdApi.SendMessage.class), any(Client.ResultHandler.class));

        InternalController.SendMessageRequest req =
                new InternalController.SendMessageRequest(-100123L, "Hello", 0, null);

        StepVerifier.create(controller.sendMessage(accountId, req))
                .assertNext(
                        resp -> {
                            assertThat(resp.getStatusCode().value()).isEqualTo(201);
                            assertThat(resp.getBody()).isNotNull();
                            assertThat(resp.getBody().success()).isTrue();
                            assertThat(resp.getBody().messageId()).isEqualTo(42L);
                        })
                .verifyComplete();
    }

    @Test
    void sendMessage_tdlibError_returns500() {
        UUID accountId = UUID.randomUUID();
        when(manager.hasClient(accountId)).thenReturn(true);
        when(manager.getClient(accountId)).thenReturn(client);
        when(client.isAuthorized()).thenReturn(true);

        TdApi.Error tdError = new TdApi.Error();
        tdError.message = "Chat not found";
        doAnswer(
                        invocation -> {
                            Client.ResultHandler handler = invocation.getArgument(1);
                            handler.onResult(tdError);
                            return null;
                        })
                .when(client)
                .sendRequest(any(TdApi.SendMessage.class), any(Client.ResultHandler.class));

        InternalController.SendMessageRequest req =
                new InternalController.SendMessageRequest(-100123L, "Hello", 0, null);

        StepVerifier.create(controller.sendMessage(accountId, req))
                .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(500))
                .verifyComplete();
    }

    // ── getChatHistory tests ──────────────────────────────────────────────────

    @Test
    void getChatHistory_accountNotFound_returnsNotFound() {
        UUID accountId = UUID.randomUUID();
        when(manager.hasClient(accountId)).thenReturn(false);

        StepVerifier.create(
                        controller.getChatHistory(
                                accountId, -1001234567890L, 0L, 100, 0L))
                .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(404))
                .verifyComplete();
    }

    @Test
    void getChatHistory_returnsMessagesAfterFromDate() {
        UUID accountId = UUID.randomUUID();
        when(manager.hasClient(accountId)).thenReturn(true);
        when(manager.getClient(accountId)).thenReturn(client);
        when(client.isAuthorized()).thenReturn(true);

        TdApi.Message msg = new TdApi.Message();
        msg.id = 999L;
        msg.chatId = -1001234567890L;
        msg.date = 1_700_000_100; // after fromDate
        msg.senderId = new TdApi.MessageSenderUser();
        ((TdApi.MessageSenderUser) msg.senderId).userId = 42L;
        TdApi.MessageText content = new TdApi.MessageText();
        content.text = new TdApi.FormattedText();
        content.text.text = "hello backfill";
        content.text.entities = new TdApi.TextEntity[0];
        msg.content = content;

        TdApi.Messages tdMessages = new TdApi.Messages();
        tdMessages.messages = new TdApi.Message[]{msg};
        tdMessages.totalCount = 1;

        doAnswer(
                        invocation -> {
                            Client.ResultHandler handler = invocation.getArgument(1);
                            handler.onResult(tdMessages);
                            return null;
                        })
                .when(client)
                .sendRequest(
                        any(TdApi.GetChatHistory.class), any(Client.ResultHandler.class));

        StepVerifier.create(
                        controller.getChatHistory(
                                accountId, -1001234567890L, 1_700_000_000L, 100, 0L))
                .assertNext(
                        resp -> {
                            assertThat(resp.getStatusCode().value()).isEqualTo(200);
                            assertThat(resp.getBody()).isNotNull();
                            assertThat(resp.getBody().messages()).hasSize(1);
                        })
                .verifyComplete();
    }

    @Test
    void getChatHistory_stopsAtOlderMessage() {
        UUID accountId = UUID.randomUUID();
        when(manager.hasClient(accountId)).thenReturn(true);
        when(manager.getClient(accountId)).thenReturn(client);
        when(client.isAuthorized()).thenReturn(true);

        TdApi.Message oldMsg = new TdApi.Message();
        oldMsg.id = 1L;
        oldMsg.chatId = -1001234567890L;
        oldMsg.date = 1_699_999_999; // BEFORE fromDate = 1_700_000_000
        oldMsg.senderId = new TdApi.MessageSenderUser();
        ((TdApi.MessageSenderUser) oldMsg.senderId).userId = 1L;
        TdApi.MessageText content = new TdApi.MessageText();
        content.text = new TdApi.FormattedText();
        content.text.text = "old message";
        content.text.entities = new TdApi.TextEntity[0];
        oldMsg.content = content;

        TdApi.Messages tdMessages = new TdApi.Messages();
        tdMessages.messages = new TdApi.Message[]{oldMsg};
        tdMessages.totalCount = 1;

        doAnswer(
                        invocation -> {
                            Client.ResultHandler handler = invocation.getArgument(1);
                            handler.onResult(tdMessages);
                            return null;
                        })
                .when(client)
                .sendRequest(
                        any(TdApi.GetChatHistory.class), any(Client.ResultHandler.class));

        StepVerifier.create(
                        controller.getChatHistory(
                                accountId, -1001234567890L, 1_700_000_000L, 100, 0L))
                .assertNext(
                        resp -> {
                            assertThat(resp.getStatusCode().value()).isEqualTo(200);
                            assertThat(resp.getBody()).isNotNull();
                            assertThat(resp.getBody().messages()).isEmpty();
                            assertThat(resp.getBody().hasMore()).isFalse();
                        })
                .verifyComplete();
    }
}
```

- [ ] **Step 1.4: Run the new getChatHistory tests — expect compilation failure**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-tdlib-adapter -Dtest=InternalControllerTest -q 2>&1 | tail -20
```

Expected: compile error — `getChatHistory` method does not exist yet. This confirms the tests are wired correctly.

- [ ] **Step 1.5: Implement getChatHistory endpoint in InternalController**

Replace the full content of `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/controller/InternalController.java`:

```java
package io.emcip.tdlib.adapter.controller;

import io.emcip.common.events.EventSchemas;
import io.emcip.tdlib.adapter.config.TdLibClient;
import io.emcip.tdlib.adapter.config.TdLibClientManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.drinkless.tdlib.TdApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final TdLibClientManager manager;
    private final ObjectMapper objectMapper;

    @Value("${app.adapter-id:default}")
    private String adapterId;

    @GetMapping("/identity")
    public Map<String, String> identity() {
        return Map.of("adapterId", adapterId);
    }

    @PostMapping("/watched-groups/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> updateWatchedGroups(
            @PathVariable UUID accountId, @RequestBody WatchedGroupsRequest req) {
        manager.updateWatchedChats(
                accountId, new HashSet<>(req.chatIds()), new HashSet<>(req.knowledgeChatIds()));
        log.info(
                "[{}] Watched chat IDs updated: {}, knowledge fork: {}",
                accountId,
                req.chatIds(),
                req.knowledgeChatIds());
        return Mono.empty();
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
                .onErrorResume(
                        e -> {
                            log.error("[{}] discoverChats error: {}", accountId, e.getMessage());
                            return Mono.just(
                                    ResponseEntity.internalServerError().<List<ChatInfo>>build());
                        });
    }

    /**
     * Fetch a page of historical messages from a chat, starting before {@code offsetMessageId} and
     * no older than {@code fromDate} (Unix epoch seconds). Returns serialized
     * TelegramMessageEvent JSON strings so the caller can publish them directly to Kafka.
     *
     * <p>Returns 404 if the account has no TDLib client, 400 if the client is not authorized.
     */
    @GetMapping("/chat-history/{accountId}/{chatId}")
    public Mono<ResponseEntity<ChatHistoryResponse>> getChatHistory(
            @PathVariable UUID accountId,
            @PathVariable long chatId,
            @RequestParam(defaultValue = "0") long fromDate,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") long offsetMessageId) {
        if (!manager.hasClient(accountId)) {
            log.warn("[{}] getChatHistory: no client found", accountId);
            return Mono.just(ResponseEntity.notFound().<ChatHistoryResponse>build());
        }
        TdLibClient client = manager.getClient(accountId);
        if (!client.isAuthorized()) {
            log.warn("[{}] getChatHistory: client not authorized", accountId);
            return Mono.just(ResponseEntity.badRequest().<ChatHistoryResponse>build());
        }
        return loadChatHistory(client, accountId, chatId, fromDate, limit, offsetMessageId)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[{}] getChatHistory error chatId={}: {}",
                                    accountId,
                                    chatId,
                                    e.getMessage());
                            return Mono.just(
                                    ResponseEntity.internalServerError()
                                            .<ChatHistoryResponse>build());
                        });
    }

    private Mono<ChatHistoryResponse> loadChatHistory(
            TdLibClient client,
            UUID accountId,
            long chatId,
            long fromDate,
            int limit,
            long offsetMessageId) {
        return Mono.<TdApi.Messages>create(
                        sink ->
                                client.sendRequest(
                                        new TdApi.GetChatHistory(
                                                chatId, offsetMessageId, 0, limit, false),
                                        result -> {
                                            if (result instanceof TdApi.Messages messages)
                                                sink.success(messages);
                                            else if (result instanceof TdApi.Error err)
                                                sink.error(
                                                        new RuntimeException(
                                                                "GetChatHistory error: "
                                                                        + err.message));
                                        }))
                .map(
                        messages -> {
                            List<String> jsons = new ArrayList<>();
                            boolean hasMore = messages.messages.length == limit;
                            long lastId = 0L;

                            for (TdApi.Message msg : messages.messages) {
                                if (msg.date < fromDate) {
                                    hasMore = false;
                                    break;
                                }
                                try {
                                    EventSchemas.TelegramMessageEvent event =
                                            toHistoricalEvent(msg);
                                    jsons.add(objectMapper.writeValueAsString(event));
                                    lastId = msg.id;
                                } catch (JacksonException e) {
                                    log.warn(
                                            "[{}] Failed to serialize message {}: {}",
                                            accountId,
                                            msg.id,
                                            e.getMessage());
                                }
                            }

                            return new ChatHistoryResponse(jsons, hasMore, lastId);
                        });
    }

    private EventSchemas.TelegramMessageEvent toHistoricalEvent(TdApi.Message message) {
        return new EventSchemas.TelegramMessageEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                null, // schemaVersion defaults to "1.0.0"
                null, // eventType defaults to "TelegramMessage"
                message.id,
                message.chatId,
                message.senderId != null ? getSenderId(message.senderId) : null,
                getSenderType(message.senderId),
                extractText(message),
                message.date,
                message.editDate,
                message.isOutgoing,
                extractReplyToMessageId(message),
                extractReplyInChatId(message),
                Map.of("contentType", contentTypeOf(message), "isChannelPost", message.isChannelPost),
                Instant.now().toString(),
                null, // senderDisplayName — not available without profile cache in backfill path
                null, // senderUsername — not available without profile cache in backfill path
                null  // chatTitle — not available without profile cache in backfill path
        );
    }

    private static String contentTypeOf(TdApi.Message message) {
        return switch (message.content) {
            case TdApi.MessageText ignored -> "text";
            case TdApi.MessageSticker ignored -> "sticker";
            case TdApi.MessagePhoto ignored -> "photo";
            case TdApi.MessageVideo ignored -> "video";
            case TdApi.MessageDocument ignored -> "document";
            default -> "other";
        };
    }

    private static String extractText(TdApi.Message message) {
        if (message.content instanceof TdApi.MessageText mt) {
            return mt.text != null ? mt.text.text : "";
        }
        return "";
    }

    private static long extractReplyToMessageId(TdApi.Message message) {
        if (message.replyTo instanceof TdApi.MessageReplyToMessage reply) {
            return reply.messageId;
        }
        return 0L;
    }

    private static long extractReplyInChatId(TdApi.Message message) {
        if (message.replyTo instanceof TdApi.MessageReplyToMessage reply) {
            return reply.chatId;
        }
        return 0L;
    }

    private static String getSenderId(TdApi.MessageSender sender) {
        if (sender instanceof TdApi.MessageSenderUser user) return String.valueOf(user.userId);
        if (sender instanceof TdApi.MessageSenderChat chat) return String.valueOf(chat.chatId);
        return null;
    }

    private static String getSenderType(TdApi.MessageSender sender) {
        if (sender instanceof TdApi.MessageSenderUser) return "USER";
        if (sender instanceof TdApi.MessageSenderChat) return "CHAT";
        return "UNKNOWN";
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
                .flatMapMany(chats -> Flux.fromStream(Arrays.stream(chats.chatIds).boxed()))
                .flatMap(
                        cId ->
                                Mono.<TdApi.Chat>create(
                                        sink ->
                                                client.sendRequest(
                                                        new TdApi.GetChat(cId),
                                                        result -> {
                                                            if (result instanceof TdApi.Chat chat)
                                                                sink.success(chat);
                                                            else
                                                                sink.error(
                                                                        new RuntimeException(
                                                                                "GetChat error for "
                                                                                        + cId));
                                                        })))
                .filter(
                        chat ->
                                chat.type instanceof TdApi.ChatTypeSupergroup
                                        || chat.type instanceof TdApi.ChatTypeBasicGroup)
                .map(chat -> new ChatInfo(chat.id, chat.title, chatType(chat.type)))
                .collectList();
    }

    private static String chatType(TdApi.ChatType type) {
        if (type instanceof TdApi.ChatTypeSupergroup sg) {
            return sg.isChannel ? "CHANNEL" : "SUPERGROUP";
        }
        return "GROUP";
    }

    @PostMapping("/send-message/{accountId}")
    public Mono<ResponseEntity<SendMessageResponse>> sendMessage(
            @PathVariable UUID accountId, @Valid @RequestBody SendMessageRequest req) {
        if (!manager.hasClient(accountId)) {
            log.warn("[{}] sendMessage: no client found", accountId);
            return Mono.just(ResponseEntity.badRequest().build());
        }
        TdLibClient client = manager.getClient(accountId);
        if (!client.isAuthorized()) {
            log.warn("[{}] sendMessage: client not authorized", accountId);
            return Mono.just(ResponseEntity.badRequest().build());
        }

        Mono<Long> chatIdMono;
        if (req.recipientUserId() != null && req.recipientUserId() > 0) {
            chatIdMono =
                    Mono.<Long>create(
                            sink ->
                                    client.sendRequest(
                                            new TdApi.CreatePrivateChat(
                                                    req.recipientUserId(), false),
                                            result -> {
                                                if (result instanceof TdApi.Chat chat)
                                                    sink.success(chat.id);
                                                else if (result instanceof TdApi.Error err)
                                                    sink.error(
                                                            new RuntimeException(
                                                                    "CreatePrivateChat error: "
                                                                            + err.message));
                                            }));
        } else {
            chatIdMono = Mono.just(req.chatId());
        }

        return chatIdMono
                .flatMap(
                        resolvedChatId -> {
                            TdApi.FormattedText formattedText = new TdApi.FormattedText();
                            formattedText.text = req.text();
                            formattedText.entities = new TdApi.TextEntity[0];

                            TdApi.InputMessageText inputContent = new TdApi.InputMessageText();
                            inputContent.text = formattedText;

                            TdApi.SendMessage sendMsg = new TdApi.SendMessage();
                            sendMsg.chatId = resolvedChatId;
                            sendMsg.inputMessageContent = inputContent;
                            if (req.replyToMessageId() > 0) {
                                TdApi.InputMessageReplyToMessage replyTo =
                                        new TdApi.InputMessageReplyToMessage();
                                replyTo.messageId = req.replyToMessageId();
                                sendMsg.replyTo = replyTo;
                            }

                            return Mono.<SendMessageResponse>create(
                                    sink ->
                                            client.sendRequest(
                                                    sendMsg,
                                                    result -> {
                                                        if (result instanceof TdApi.Message msg) {
                                                            log.info(
                                                                    "[{}] Message sent to chat {},"
                                                                            + " messageId={}",
                                                                    accountId,
                                                                    resolvedChatId,
                                                                    msg.id);
                                                            sink.success(
                                                                    new SendMessageResponse(
                                                                            true, msg.id));
                                                        } else if (result
                                                                instanceof TdApi.Error err) {
                                                            log.error(
                                                                    "[{}] SendMessage error: {}",
                                                                    accountId,
                                                                    err.message);
                                                            sink.error(
                                                                    new RuntimeException(
                                                                            "SendMessage error: "
                                                                                    + err.message));
                                                        }
                                                    }));
                        })
                .map(resp -> ResponseEntity.status(HttpStatus.CREATED).body(resp))
                .onErrorResume(
                        e -> {
                            log.error("[{}] sendMessage failed: {}", accountId, e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                            .build());
                        });
    }

    public record WatchedGroupsRequest(List<Long> chatIds, List<Long> knowledgeChatIds) {
        public WatchedGroupsRequest {
            if (knowledgeChatIds == null) knowledgeChatIds = List.of();
        }
    }

    public record ChatInfo(long chatId, String title, String type) {}

    public record SendMessageRequest(
            long chatId, @NotBlank String text, long replyToMessageId, Long recipientUserId) {}

    public record SendMessageResponse(boolean success, long messageId) {}

    /**
     * Response payload for GET /internal/chat-history/{accountId}/{chatId}.
     *
     * @param messages JSON strings (serialized TelegramMessageEvent) for messages newer than
     *     fromDate
     * @param hasMore true if more pages exist (batch was full AND no message fell below fromDate)
     * @param lastMessageId the ID of the last returned message; use as offsetMessageId in the next
     *     call
     */
    public record ChatHistoryResponse(
            List<String> messages, boolean hasMore, long lastMessageId) {}
}
```

- [ ] **Step 1.6: Run all tests in tdlib-adapter**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-tdlib-adapter -q
```

Expected: BUILD SUCCESS. All tests pass including the three new getChatHistory tests.

- [ ] **Step 1.7: Apply Spotless formatting**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-tdlib-adapter -q
```

- [ ] **Step 1.8: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/controller/InternalController.java \
        emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/controller/InternalControllerTest.java
git commit -m "feat(tdlib-adapter): add GET /internal/chat-history endpoint for bulk backfill (#26.7)"
```

---

## Task 2: Knowledge-engine BackfillService implementation

**Files:**
- Modify: `emcip-knowledge-engine/src/main/resources/application.yml`
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/BackfillService.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/BackfillServiceTest.java`

### Background

The knowledge-engine is a standard Spring Boot (servlet-stack, `spring-boot-starter-web`) service using JPA/Hibernate, not WebFlux. Use `RestClient` (blocking) for outbound HTTP — the existing stub already uses it.

The new `triggerBackfill` uses `Executors.newVirtualThreadPerTaskExecutor()` (Java 21) to run the fetch loop in a virtual thread. This avoids blocking the servlet thread pool and enables thousands of concurrent backfills without overhead. Virtual threads are a Java 21 feature and are available in Spring Boot 4 without any special config.

The Kafka publish in the backfill loop uses raw `ProducerRecord<String, String>` on the `knowledge.raw.messages` topic — the same topic the live pipeline uses for knowledge fork messages. This means backfilled messages flow through the exact same `KnowledgeMessageConsumer` as live messages, requiring zero changes downstream.

The `BackfillStatus` record replaces the old `(backfillId, chatId, status, processed, total)` shape with `(backfillId, chatId, status, processed, fromDate, startedAt, errorMessage)`. The `total` field is removed because Telegram's `GetChatHistory` doesn't provide a total count for the date-bounded range — we use `-1` in the Kafka progress event instead.

`RestClient.get().uri(...).retrieve().body(ChatHistoryResponse.class)` returns `null` if the server returns 404 or an empty body. The loop guards against this with a null check.

`TenantContext.KAFKA_HEADER` is the constant `"X-Tenant-Id"` defined in `emcip-core`. Import: `io.emcip.common.tenant.TenantContext`.

- [ ] **Step 2.1: Add tdlib-adapter base-url to application.yml**

In `emcip-knowledge-engine/src/main/resources/application.yml`, find the `knowledge:` block (currently at line 33) and add the `tdlib-adapter` section. The final `knowledge:` block should look like:

```yaml
knowledge:
  embedding:
    dimension: ${KNOWLEDGE_EMBEDDING_DIMENSION:1536}
  llm-orchestrator:
    base-url: ${LLM_ORCHESTRATOR_URL:http://localhost:9084}
  tdlib-adapter:
    base-url: ${TDLIB_ADAPTER_URL:http://localhost:9080}
  resolution:
    merge-threshold: ${KNOWLEDGE_RESOLUTION_MERGE_THRESHOLD:0.92}
    flag-threshold: ${KNOWLEDGE_RESOLUTION_FLAG_THRESHOLD:0.80}
```

- [ ] **Step 2.2: Write BackfillServiceTest**

Create `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/BackfillServiceTest.java`:

```java
package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class BackfillServiceTest {

    @Mock KnowledgeEventPublisher eventPublisher;

    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    BackfillService service;

    @BeforeEach
    void setUp() {
        // Use a non-reachable base URL — the async loop is not exercised in unit tests
        service = new BackfillService("http://localhost:19999", eventPublisher, kafkaTemplate);
    }

    @Test
    void triggerBackfill_returnsNonNullBackfillId() {
        UUID accountId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        long fromDate = 1_700_000_000L;

        String backfillId = service.triggerBackfill(accountId, -1001234567890L, fromDate, tenantId);

        assertThat(backfillId).isNotNull().isNotBlank();
        // Verify it parses as a UUID
        assertThat(UUID.fromString(backfillId)).isNotNull();
    }

    @Test
    void getStatus_returnsRunningForActiveBackfill() {
        UUID accountId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String backfillId = service.triggerBackfill(accountId, -1001234567890L, 1_700_000_000L, tenantId);

        // Status is written synchronously before the async task fires
        BackfillService.BackfillStatus status = service.getStatus(backfillId);

        assertThat(status.status()).isEqualTo("RUNNING");
        assertThat(status.backfillId()).isEqualTo(backfillId);
        assertThat(status.chatId()).isEqualTo(-1001234567890L);
        assertThat(status.processed()).isZero();
        assertThat(status.startedAt()).isNotNull();
    }

    @Test
    void getStatus_returnsNotFoundForUnknownId() {
        String unknownId = UUID.randomUUID().toString();

        BackfillService.BackfillStatus status = service.getStatus(unknownId);

        assertThat(status.status()).isEqualTo("NOT_FOUND");
        assertThat(status.backfillId()).isEqualTo(unknownId);
    }
}
```

- [ ] **Step 2.3: Run the new BackfillServiceTest — expect compilation failure**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-knowledge-engine -Dtest=BackfillServiceTest -q 2>&1 | tail -20
```

Expected: compile error — `triggerBackfill` signature mismatch and `BackfillStatus` record shape mismatch. Confirms tests target the new API.

- [ ] **Step 2.4: Implement the new BackfillService**

Replace the full content of `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/BackfillService.java`:

```java
package io.emcip.knowledge.engine.service;

import io.emcip.common.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class BackfillService {

    private static final String TOPIC_KNOWLEDGE_RAW = "knowledge.raw.messages";
    private static final ExecutorService BACKFILL_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final RestClient tdlibRestClient;
    private final KnowledgeEventPublisher eventPublisher;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private final Map<String, BackfillStatus> activeBackfills = new ConcurrentHashMap<>();

    public BackfillService(
            @Value("${knowledge.tdlib-adapter.base-url:http://localhost:9080}") String tdlibBaseUrl,
            KnowledgeEventPublisher eventPublisher,
            KafkaTemplate<String, String> kafkaTemplate) {
        this.tdlibRestClient = RestClient.builder().baseUrl(tdlibBaseUrl).build();
        this.eventPublisher = eventPublisher;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Trigger an asynchronous backfill. Returns a backfill ID immediately; callers poll
     * {@link #getStatus(String)} for progress.
     *
     * @param accountId the TDLib account (watcher) UUID
     * @param chatId the Telegram chat ID to backfill
     * @param fromDate Unix epoch seconds — do not fetch messages older than this
     * @param tenantId optional tenant context for Kafka header propagation
     */
    public String triggerBackfill(UUID accountId, long chatId, long fromDate, UUID tenantId) {
        String backfillId = UUID.randomUUID().toString();
        String startedAt = Instant.now().toString();

        activeBackfills.put(
                backfillId,
                new BackfillStatus(backfillId, chatId, "RUNNING", 0, fromDate, startedAt, null));

        log.info(
                "Backfill triggered: id={}, accountId={}, chatId={}, fromDate={}, tenantId={}",
                backfillId,
                accountId,
                chatId,
                fromDate,
                tenantId);

        BACKFILL_EXECUTOR.submit(() -> runBackfill(backfillId, accountId, chatId, fromDate, tenantId, startedAt));

        return backfillId;
    }

    private void runBackfill(
            String backfillId,
            UUID accountId,
            long chatId,
            long fromDate,
            UUID tenantId,
            String startedAt) {
        long offsetMessageId = 0L;
        int processed = 0;

        try {
            while (true) {
                ChatHistoryResponse batch =
                        tdlibRestClient
                                .get()
                                .uri(
                                        "/internal/chat-history/{accountId}/{chatId}"
                                                + "?fromDate={fromDate}&limit=100&offsetMessageId={offset}",
                                        accountId,
                                        chatId,
                                        fromDate,
                                        offsetMessageId)
                                .retrieve()
                                .body(ChatHistoryResponse.class);

                if (batch == null || batch.messages() == null || batch.messages().isEmpty()) {
                    break;
                }

                for (String msgJson : batch.messages()) {
                    ProducerRecord<String, String> record =
                            new ProducerRecord<>(
                                    TOPIC_KNOWLEDGE_RAW, String.valueOf(chatId), msgJson);
                    if (tenantId != null) {
                        record.headers()
                                .add(
                                        TenantContext.KAFKA_HEADER,
                                        tenantId.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    kafkaTemplate.send(record);
                }

                processed += batch.messages().size();

                activeBackfills.put(
                        backfillId,
                        new BackfillStatus(
                                backfillId, chatId, "RUNNING", processed, fromDate, startedAt, null));

                eventPublisher.publishBackfillProgress(
                        String.valueOf(chatId), processed, -1, tenantId);

                log.debug(
                        "Backfill {}: published {} messages so far for chatId={}",
                        backfillId,
                        processed,
                        chatId);

                if (!batch.hasMore()) {
                    break;
                }

                offsetMessageId = batch.lastMessageId();
            }

            activeBackfills.put(
                    backfillId,
                    new BackfillStatus(
                            backfillId, chatId, "COMPLETED", processed, fromDate, startedAt, null));

            log.info(
                    "Backfill {} completed: chatId={}, totalProcessed={}",
                    backfillId,
                    chatId,
                    processed);

        } catch (Exception e) {
            log.error("Backfill {} failed: {}", backfillId, e.getMessage(), e);
            activeBackfills.put(
                    backfillId,
                    new BackfillStatus(
                            backfillId,
                            chatId,
                            "FAILED",
                            processed,
                            fromDate,
                            startedAt,
                            e.getMessage()));
        }
    }

    public BackfillStatus getStatus(String backfillId) {
        return activeBackfills.getOrDefault(
                backfillId,
                new BackfillStatus(backfillId, 0L, "NOT_FOUND", 0, 0L, null, null));
    }

    public record BackfillStatus(
            String backfillId,
            long chatId,
            String status,
            int processed,
            long fromDate,
            String startedAt,
            String errorMessage) {}

    /** Mirrors InternalController.ChatHistoryResponse from the tdlib-adapter. */
    public record ChatHistoryResponse(
            List<String> messages, boolean hasMore, long lastMessageId) {}
}
```

- [ ] **Step 2.5: Run BackfillServiceTest**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-knowledge-engine -Dtest=BackfillServiceTest -q
```

Expected: BUILD SUCCESS. All three tests pass.

- [ ] **Step 2.6: Run all knowledge-engine tests**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-knowledge-engine -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 2.7: Apply Spotless**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-knowledge-engine -q
```

- [ ] **Step 2.8: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-knowledge-engine/src/main/resources/application.yml \
        emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/service/BackfillService.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/service/BackfillServiceTest.java
git commit -m "feat(knowledge-engine): implement BackfillService async fetch loop (#26.7)"
```

---

## Task 3: Knowledge-engine BackfillController update + test

**Files:**
- Modify: `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/BackfillController.java`
- Create: `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/BackfillControllerTest.java`

### Background

`BackfillController` is a standard Spring MVC `@RestController` (not reactive). Tests use `MockMvc` with `MockMvcBuilders.standaloneSetup(...)` — the same pattern used throughout the knowledge-engine. Do not use `WebTestClient` here.

The `BackfillRequest` record currently has `String accountId`. It must change to `UUID accountId` so it matches the `BackfillService.triggerBackfill(UUID, long, long, UUID)` signature. The `fromDate` field (epoch seconds as `long`) is also new.

- [ ] **Step 3.1: Write BackfillControllerTest**

Create `emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/BackfillControllerTest.java`:

```java
package io.emcip.knowledge.engine.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.emcip.knowledge.engine.service.BackfillService;
import io.emcip.knowledge.engine.service.BackfillService.BackfillStatus;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class BackfillControllerTest {

    @Mock BackfillService backfillService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new BackfillController(backfillService)).build();
    }

    @Test
    void triggerBackfill_returns202WithBackfillId() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(backfillService.triggerBackfill(eq(accountId), eq(-1001234567890L), eq(1_700_000_000L), eq(tenantId)))
                .thenReturn("backfill-abc-123");

        mvc.perform(
                        post("/api/knowledge/backfill")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "accountId": "%s",
                                          "chatId": -1001234567890,
                                          "tenantId": "%s",
                                          "fromDate": 1700000000
                                        }
                                        """
                                                .formatted(accountId, tenantId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.backfillId").value("backfill-abc-123"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void getStatus_returnsRunningStatus() throws Exception {
        BackfillStatus running =
                new BackfillStatus(
                        "backfill-abc-123",
                        -1001234567890L,
                        "RUNNING",
                        42,
                        1_700_000_000L,
                        "2026-06-18T10:00:00Z",
                        null);
        when(backfillService.getStatus("backfill-abc-123")).thenReturn(running);

        mvc.perform(get("/api/knowledge/backfill/status").param("backfillId", "backfill-abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.processed").value(42))
                .andExpect(jsonPath("$.backfillId").value("backfill-abc-123"));
    }

    @Test
    void getStatus_returnsNotFoundStatusForUnknownId() throws Exception {
        String unknownId = UUID.randomUUID().toString();
        BackfillStatus notFound =
                new BackfillStatus(unknownId, 0L, "NOT_FOUND", 0, 0L, null, null);
        when(backfillService.getStatus(unknownId)).thenReturn(notFound);

        mvc.perform(get("/api/knowledge/backfill/status").param("backfillId", unknownId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
    }
}
```

- [ ] **Step 3.2: Run BackfillControllerTest — expect compilation failure**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-knowledge-engine -Dtest=BackfillControllerTest -q 2>&1 | tail -20
```

Expected: compile error — `BackfillRequest` record and `triggerBackfill` signature mismatch. Confirms tests target the new API.

- [ ] **Step 3.3: Update BackfillController**

Replace the full content of `emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/BackfillController.java`:

```java
package io.emcip.knowledge.engine.controller;

import io.emcip.knowledge.engine.service.BackfillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Backfill", description = "Trigger and monitor chat history backfill")
@RestController
@RequestMapping("/api/knowledge/backfill")
@RequiredArgsConstructor
public class BackfillController {

    private final BackfillService backfillService;

    @Operation(summary = "Trigger backfill for a Telegram chat")
    @PostMapping
    public ResponseEntity<Map<String, Object>> triggerBackfill(
            @RequestBody BackfillRequest request) {
        String backfillId =
                backfillService.triggerBackfill(
                        request.accountId(), request.chatId(), request.fromDate(), request.tenantId());
        return ResponseEntity.accepted()
                .body(Map.of("backfillId", backfillId, "status", "RUNNING"));
    }

    @Operation(summary = "Get backfill progress")
    @GetMapping("/status")
    public BackfillService.BackfillStatus getStatus(@RequestParam String backfillId) {
        return backfillService.getStatus(backfillId);
    }

    public record BackfillRequest(UUID accountId, long chatId, UUID tenantId, long fromDate) {}
}
```

- [ ] **Step 3.4: Run all knowledge-engine tests**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-knowledge-engine -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3.5: Apply Spotless**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-knowledge-engine -q
```

- [ ] **Step 3.6: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-knowledge-engine/src/main/java/io/emcip/knowledge/engine/controller/BackfillController.java \
        emcip-knowledge-engine/src/test/java/io/emcip/knowledge/engine/controller/BackfillControllerTest.java
git commit -m "feat(knowledge-engine): update BackfillController for UUID accountId + fromDate (#26.7)"
```

---

## Task 4: Admin-api BackfillProxyController

**Files:**
- Create: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/BackfillProxyController.java`
- Create: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/BackfillProxyControllerTest.java`

### Background

The admin-api is a WebFlux/R2DBC service. All HTTP proxy controllers follow the same pattern as `CostsProxyController` and `ResolutionReviewProxyController`:
- Constructor injects `@Qualifier("knowledgeWebClient") WebClient` and `CircuitBreakerRegistry`
- Every method returns `Mono<...>` and ends with `.transformDeferred(CircuitBreakerOperator.of(circuitBreaker))`
- Test uses `WebTestClient.bindToController(controller).build()` with a `WebClient` constructed from a mock `ExchangeFunction` (see `CostsProxyControllerTest`)

The `POST /{chatId}/backfill` endpoint must:
1. Extract the reactor tenant context (using `Mono.deferContextual`) to pass `tenantId` downstream
2. Convert the incoming ISO-8601 `fromDate` string to epoch seconds (`Instant.parse(...).getEpochSecond()`)
3. Build a request body map with `{ accountId, chatId (long), tenantId (from context or request), fromDate (epoch) }`
4. POST that map to `/api/knowledge/backfill` on the knowledge-engine

The tenantId priority: use `ReactorTenantContext.getTenantId(ctx)` from the reactor context first; fall back to `request.accountId()` is NOT the tenantId — tenantId is separate from accountId. The tenantId from the reactor context is set by the `AdminTenantContextFilter` earlier in the filter chain and is the correct source. Pass it to the body as a UUID string if present, or omit it (null) if absent.

The admin-api uses `ReactorTenantContext` (from `emcip-core`). See `AuditServiceClient` for the exact `Mono.deferContextual(ctx -> { String tenantId = ReactorTenantContext.getTenantId(ctx); ... })` pattern.

For the test, use the `ExchangeFunction` mock pattern from `CostsProxyControllerTest`:
```java
WebClient webClient = WebClient.builder().exchangeFunction(exchangeFunction).build();
```
This avoids needing to mock the full `WebClient.RequestBodyUriSpec` chain — the `ExchangeFunction` is the single seam where all WebClient calls can be intercepted.

- [ ] **Step 4.1: Write BackfillProxyControllerTest**

Create `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/BackfillProxyControllerTest.java`:

```java
package io.emcip.admin.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class BackfillProxyControllerTest {

    @Mock private ExchangeFunction exchangeFunction;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        WebClient knowledgeWebClient =
                WebClient.builder().exchangeFunction(exchangeFunction).build();
        BackfillProxyController controller =
                new BackfillProxyController(
                        knowledgeWebClient, CircuitBreakerRegistry.ofDefaults());
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    private void stubOk(String body) {
        ClientResponse response =
                ClientResponse.create(HttpStatus.ACCEPTED)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));
    }

    private void stubGetOk(String body) {
        ClientResponse response =
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build();
        when(exchangeFunction.exchange(any())).thenReturn(Mono.just(response));
    }

    @Test
    void triggerBackfill_proxiesRequestToKnowledgeEngine() {
        stubOk("{\"backfillId\":\"abc-123\",\"status\":\"RUNNING\"}");

        UUID accountId = UUID.randomUUID();
        webTestClient
                .post()
                .uri("/api/groups/-1001234567890/backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        """
                        {"accountId":"%s","fromDate":"2026-01-01T00:00:00Z"}
                        """.formatted(accountId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        body -> {
                            assert body != null;
                            assert body.contains("abc-123");
                        });
    }

    @Test
    void getBackfillStatus_proxiesRequestToKnowledgeEngine() {
        stubGetOk(
                "{\"backfillId\":\"abc-123\",\"status\":\"RUNNING\",\"processed\":42}");

        webTestClient
                .get()
                .uri("/api/groups/-1001234567890/backfill/abc-123")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        body -> {
                            assert body != null;
                            assert body.contains("RUNNING");
                        });
    }
}
```

- [ ] **Step 4.2: Run BackfillProxyControllerTest — expect compilation failure**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-admin-api -Dtest=BackfillProxyControllerTest -q 2>&1 | tail -20
```

Expected: compile error — `BackfillProxyController` does not exist yet.

- [ ] **Step 4.3: Create BackfillProxyController**

Create `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/BackfillProxyController.java`:

```java
package io.emcip.admin.api.controller;

import io.emcip.common.tenant.ReactorTenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Proxies backfill requests to the knowledge-engine service. Admin-UI → admin-api →
 * knowledge-engine (API Gateway pattern).
 */
@Slf4j
@RestController
@RequestMapping("/api/groups")
@Tag(name = "Backfill", description = "Trigger and monitor bulk backfill for a watched group")
public class BackfillProxyController {

    private final WebClient knowledgeWebClient;
    private final CircuitBreaker circuitBreaker;

    public BackfillProxyController(
            @Qualifier("knowledgeWebClient") WebClient knowledgeWebClient,
            CircuitBreakerRegistry registry) {
        this.knowledgeWebClient = knowledgeWebClient;
        this.circuitBreaker = registry.circuitBreaker("knowledge");
    }

    /**
     * Trigger a historical backfill for the given chat. Converts the ISO-8601 fromDate to epoch
     * seconds, attaches the reactor tenant context, and proxies to knowledge-engine.
     */
    @Operation(summary = "Trigger bulk backfill for a watched group")
    @PostMapping("/{chatId}/backfill")
    public Mono<ResponseEntity<String>> triggerBackfill(
            @PathVariable long chatId,
            @RequestBody BackfillTriggerRequest request) {
        return Mono.deferContextual(
                        ctx -> {
                            String tenantIdStr = ReactorTenantContext.getTenantId(ctx);

                            long fromEpoch = Instant.parse(request.fromDate()).getEpochSecond();

                            Map<String, Object> body = new HashMap<>();
                            body.put("accountId", request.accountId().toString());
                            body.put("chatId", chatId);
                            body.put("fromDate", fromEpoch);
                            if (tenantIdStr != null) {
                                body.put("tenantId", tenantIdStr);
                            }

                            return knowledgeWebClient
                                    .post()
                                    .uri("/api/knowledge/backfill")
                                    .bodyValue(body)
                                    .retrieve()
                                    .bodyToMono(String.class)
                                    .map(ResponseEntity::ok)
                                    .onErrorResume(
                                            e -> {
                                                log.error(
                                                        "Backfill proxy error chatId={}: {}",
                                                        chatId,
                                                        e.getMessage());
                                                return Mono.just(
                                                        ResponseEntity.status(
                                                                        HttpStatus
                                                                                .SERVICE_UNAVAILABLE)
                                                                .<String>build());
                                            });
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /** Proxy backfill status check to knowledge-engine. */
    @Operation(summary = "Get backfill progress for a watched group")
    @GetMapping("/{chatId}/backfill/{backfillId}")
    public Mono<ResponseEntity<String>> getBackfillStatus(
            @PathVariable long chatId, @PathVariable String backfillId) {
        return knowledgeWebClient
                .get()
                .uri("/api/knowledge/backfill/status?backfillId={id}", backfillId)
                .retrieve()
                .bodyToMono(String.class)
                .map(ResponseEntity::ok)
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Backfill status proxy error chatId={}, backfillId={}: {}",
                                    chatId,
                                    backfillId,
                                    e.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .<String>build());
                        })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public record BackfillTriggerRequest(UUID accountId, String fromDate) {}
}
```

- [ ] **Step 4.4: Run all admin-api tests**

```bash
cd /home/ben/Development/ecip
mvn test -pl emcip-admin-api -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4.5: Apply Spotless**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-admin-api -q
```

- [ ] **Step 4.6: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-api/src/main/java/io/emcip/admin/api/controller/BackfillProxyController.java \
        emcip-admin-api/src/test/java/io/emcip/admin/api/controller/BackfillProxyControllerTest.java
git commit -m "feat(admin-api): add BackfillProxyController proxying to knowledge-engine (#26.7)"
```

---

## Task 5: Admin-ui BackfillModal + Groups page wiring

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/api/groups.js`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Groups/BackfillModal.jsx`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Groups/BackfillModal.module.css`
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Groups/BackfillModal.test.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.jsx`

### Background

The admin-ui uses React 18, CSS Modules, and Vitest + Testing Library. No icon library — only Unicode glyphs. No Tailwind — only CSS custom properties from `variables.css`. No rounded corners on data surfaces (`border-radius: 0`). Buttons use the `Button` component from `../../components/Button/Button`.

Modal component at `../../components/Modal/Modal` accepts `{ title, onClose, onSubmit?, submitLabel?, children }`. It already handles Esc key and overlay click to close. Do not reimplement these — use `<Modal>` as the outer wrapper.

The `Groups.test.jsx` mocks `global.fetch` rather than mocking the API module, because `groupsApi` is constructed inline inside `Groups()`. The `BackfillModal.test.jsx` must mock the `api` prop directly (pass a mock object) since `BackfillModal` receives `api` as a prop from `Groups`.

Polling with `useEffect`: the effect depends on `phase` — when `phase === 'polling'`, set up a `setInterval` that calls `api.backfillStatus(chatId, backfillId)` every 2000ms. Clean up the interval in the effect's return function.

The `▶ Backfill` button in COLUMNS must call `e.stopPropagation()` to prevent `DataTable`'s row `onEdit` handler from also firing (which would open `GroupEditModal`).

Design token rules (from `CLAUDE.md`): no hex values, only `var(--token-name)`. No rounded corners on the chip buttons (data surfaces = `border-radius: 0`). The chip row uses `border: 1px solid var(--border)` with hover to `var(--accent)` border and text.

The `BackfillModal` receives `{ group, onClose, api }` as props:
- `group.telegramChatId` — the chat ID (long)
- `group.name` — display name
- `api.watchers(chatId)` — returns Promise resolving to array of `{ accountId, displayName, phoneNumber }`
- `api.backfill(chatId, body)` — returns Promise resolving to `{ backfillId, status }`
- `api.backfillStatus(chatId, backfillId)` — returns Promise resolving to `{ backfillId, status, processed }`

- [ ] **Step 5.1: Add backfill methods to groups.js**

Replace the full content of `emcip-admin-ui/src/main/frontend/src/api/groups.js`:

```js
export function groupsApi(request) {
  return {
    list: () => request('/api/groups'),
    create: body => request('/api/groups', { method: 'POST', body: JSON.stringify(body) }),
    update: (chatId, body) =>
      request(`/api/groups/${encodeURIComponent(chatId)}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    remove: chatId =>
      request(`/api/groups/${encodeURIComponent(chatId)}`, { method: 'DELETE' }),
    watchers: chatId => request(`/api/groups/${encodeURIComponent(chatId)}/watchers`),
    backfill: (chatId, body) =>
      request(`/api/groups/${encodeURIComponent(chatId)}/backfill`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    backfillStatus: (chatId, backfillId) =>
      request(
        `/api/groups/${encodeURIComponent(chatId)}/backfill/${encodeURIComponent(backfillId)}`
      ),
  }
}
```

- [ ] **Step 5.2: Write BackfillModal.test.jsx**

Create `emcip-admin-ui/src/main/frontend/src/pages/Groups/BackfillModal.test.jsx`:

```jsx
import { render, screen, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { BackfillModal } from './BackfillModal'

const GROUP = { telegramChatId: -1001234567890, name: 'Test Group' }

const makeApi = overrides => ({
  watchers: vi.fn().mockResolvedValue([
    { accountId: 'acc-1', displayName: 'Bot Account', phoneNumber: '+491234' },
  ]),
  backfill: vi.fn().mockResolvedValue({ backfillId: 'bf-1', status: 'RUNNING' }),
  backfillStatus: vi.fn().mockResolvedValue({ backfillId: 'bf-1', status: 'RUNNING', processed: 0 }),
  ...overrides,
})

describe('BackfillModal', () => {
  it('renders account dropdown populated with watchers', async () => {
    const api = makeApi()
    render(<BackfillModal group={GROUP} onClose={vi.fn()} api={api} />)

    await waitFor(() =>
      expect(screen.getByRole('option', { name: 'Bot Account' })).toBeInTheDocument()
    )
  })

  it('submit button disabled until account and preset selected', async () => {
    const api = makeApi()
    render(<BackfillModal group={GROUP} onClose={vi.fn()} api={api} />)

    await waitFor(() => screen.getByRole('option', { name: 'Bot Account' }))

    expect(screen.getByRole('button', { name: /start backfill/i })).toBeDisabled()

    await userEvent.selectOptions(
      screen.getByRole('combobox'),
      screen.getByRole('option', { name: 'Bot Account' })
    )
    // still disabled — no preset yet
    expect(screen.getByRole('button', { name: /start backfill/i })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: /last 7 days/i }))

    expect(screen.getByRole('button', { name: /start backfill/i })).not.toBeDisabled()
  })

  it('shows processing count while polling', async () => {
    vi.useFakeTimers()
    const api = makeApi({
      backfillStatus: vi.fn().mockResolvedValue({ backfillId: 'bf-1', status: 'RUNNING', processed: 42 }),
    })
    render(<BackfillModal group={GROUP} onClose={vi.fn()} api={api} />)

    await waitFor(() => screen.getByRole('option', { name: 'Bot Account' }))
    await userEvent.selectOptions(screen.getByRole('combobox'), 'acc-1')
    await userEvent.click(screen.getByRole('button', { name: /last 7 days/i }))
    await userEvent.click(screen.getByRole('button', { name: /start backfill/i }))

    // Advance the polling interval
    await act(async () => { vi.advanceTimersByTime(2500) })

    await waitFor(() =>
      expect(screen.getByText(/42 messages/i)).toBeInTheDocument()
    )
    vi.useRealTimers()
  })

  it('shows done state on completion', async () => {
    vi.useFakeTimers()
    const api = makeApi({
      backfillStatus: vi.fn().mockResolvedValue({ backfillId: 'bf-1', status: 'COMPLETED', processed: 100 }),
    })
    render(<BackfillModal group={GROUP} onClose={vi.fn()} api={api} />)

    await waitFor(() => screen.getByRole('option', { name: 'Bot Account' }))
    await userEvent.selectOptions(screen.getByRole('combobox'), 'acc-1')
    await userEvent.click(screen.getByRole('button', { name: /last 7 days/i }))
    await userEvent.click(screen.getByRole('button', { name: /start backfill/i }))

    await act(async () => { vi.advanceTimersByTime(2500) })

    await waitFor(() =>
      expect(screen.getByText(/100 messages ingested/i)).toBeInTheDocument()
    )
    vi.useRealTimers()
  })

  it('shows empty-watchers message when no accounts', async () => {
    const api = makeApi({ watchers: vi.fn().mockResolvedValue([]) })
    render(<BackfillModal group={GROUP} onClose={vi.fn()} api={api} />)

    await waitFor(() =>
      expect(
        screen.getByText(/no watcher accounts are connected to this group/i)
      ).toBeInTheDocument()
    )
    expect(screen.getByRole('button', { name: /start backfill/i })).toBeDisabled()
  })
})
```

- [ ] **Step 5.3: Run BackfillModal.test.jsx — expect failure (component doesn't exist)**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npx vitest run src/pages/Groups/BackfillModal.test.jsx 2>&1 | tail -20
```

Expected: FAIL — `BackfillModal` module not found.

- [ ] **Step 5.4: Create BackfillModal.module.css**

Create `emcip-admin-ui/src/main/frontend/src/pages/Groups/BackfillModal.module.css`:

```css
.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: var(--sp-3);
}

.field label {
  font-family: var(--font-body);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--fg-2);
}

.select {
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 0;
  background: var(--bg-input);
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 13px;
  width: 100%;
  outline: none;
  transition: border-color 0.15s;
}

.select:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent), 0 0 12px var(--orb-glow);
}

.chipRow {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: var(--sp-3);
}

.chip {
  padding: 5px 12px;
  border: 1px solid var(--border);
  border-radius: 0;
  background: transparent;
  color: var(--fg-2);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.06em;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.chip:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.chipActive {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}

.status {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) 0;
  font-family: var(--font-body);
  font-size: 13px;
  color: var(--fg-1);
}

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  flex-shrink: 0;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.done {
  color: var(--signal-ok-fg);
  font-family: var(--font-mono);
  font-size: 12px;
  padding: var(--sp-3) 0;
}

.error {
  color: var(--signal-stop-fg);
  font-family: var(--font-mono);
  font-size: 12px;
  padding: var(--sp-3) 0;
}

.emptyWatchers {
  color: var(--fg-3);
  font-family: var(--font-body);
  font-size: 12px;
  font-style: italic;
  padding: var(--sp-2) 0 var(--sp-3);
}

.customDateRow {
  margin-top: var(--sp-2);
}

.dateInput {
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 0;
  background: var(--bg-input);
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 13px;
  width: 100%;
  outline: none;
  transition: border-color 0.15s;
}

.dateInput:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent), 0 0 12px var(--orb-glow);
}
```

- [ ] **Step 5.5: Create BackfillModal.jsx**

Create `emcip-admin-ui/src/main/frontend/src/pages/Groups/BackfillModal.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { Modal } from '../../components/Modal/Modal'
import { Button } from '../../components/Button/Button'
import { SectionLabel } from '../../components/SectionLabel/SectionLabel'
import styles from './BackfillModal.module.css'

const PRESETS = [
  { label: 'Last 7 days', days: 7 },
  { label: 'Last 30 days', days: 30 },
  { label: 'Last 3 months', days: 90 },
  { label: 'Last 6 months', days: 180 },
  { label: 'Last year', days: 365 },
]

function presetToFromDate(days) {
  const d = new Date()
  d.setDate(d.getDate() - days)
  d.setHours(0, 0, 0, 0)
  return d.toISOString()
}

/**
 * BackfillModal — operator-triggered historical backfill for a watched group.
 *
 * Props:
 *   group   { telegramChatId, name }
 *   onClose () => void
 *   api     groupsApi instance — must have watchers(), backfill(), backfillStatus()
 */
export function BackfillModal({ group, onClose, api }) {
  const [watchers, setWatchers] = useState([])
  const [selectedAccountId, setSelectedAccountId] = useState('')
  const [selectedPreset, setSelectedPreset] = useState(null) // days number
  const [customDate, setCustomDate] = useState('')
  const [phase, setPhase] = useState('config') // 'config' | 'polling' | 'done' | 'error'
  const [backfillId, setBackfillId] = useState(null)
  const [processed, setProcessed] = useState(0)
  const [errorMsg, setErrorMsg] = useState('')

  useEffect(() => {
    api.watchers(group.telegramChatId)
      .then(setWatchers)
      .catch(() => setWatchers([]))
  }, [group.telegramChatId])

  // Polling effect — fires every 2s while phase === 'polling'
  useEffect(() => {
    if (phase !== 'polling' || !backfillId) return

    const interval = setInterval(async () => {
      try {
        const s = await api.backfillStatus(group.telegramChatId, backfillId)
        setProcessed(s.processed ?? 0)
        if (s.status === 'COMPLETED') {
          setPhase('done')
        } else if (s.status === 'FAILED') {
          setErrorMsg(s.errorMessage || 'Backfill failed.')
          setPhase('error')
        }
      } catch (e) {
        setErrorMsg(e.message || 'Failed to fetch status.')
        setPhase('error')
      }
    }, 2000)

    return () => clearInterval(interval)
  }, [phase, backfillId, group.telegramChatId])

  const resolvedFromDate = selectedPreset != null
    ? presetToFromDate(selectedPreset)
    : customDate
      ? new Date(customDate).toISOString()
      : null

  const canSubmit =
    watchers.length > 0 &&
    selectedAccountId !== '' &&
    resolvedFromDate != null &&
    phase === 'config'

  async function handleSubmit() {
    try {
      const result = await api.backfill(group.telegramChatId, {
        accountId: selectedAccountId,
        fromDate: resolvedFromDate,
      })
      setBackfillId(result.backfillId)
      setPhase('polling')
    } catch (e) {
      setErrorMsg(e.message || 'Failed to start backfill.')
      setPhase('error')
    }
  }

  return (
    <Modal
      title={`Backfill \u00b7 ${group.name}`}
      onClose={phase === 'polling' ? undefined : onClose}
    >
      {phase === 'config' && (
        <>
          <SectionLabel>Configuration</SectionLabel>

          {watchers.length === 0 ? (
            <p className={styles.emptyWatchers}>
              No watcher accounts are connected to this group.
            </p>
          ) : (
            <div className={styles.field}>
              <label>Watcher Account</label>
              <select
                className={styles.select}
                value={selectedAccountId}
                onChange={e => setSelectedAccountId(e.target.value)}
              >
                <option value="">Select account\u2026</option>
                {watchers.map(w => (
                  <option key={w.accountId} value={w.accountId}>
                    {w.displayName || w.phoneNumber}
                  </option>
                ))}
              </select>
            </div>
          )}

          <div className={styles.field}>
            <label>Date Range</label>
            <div className={styles.chipRow}>
              {PRESETS.map(p => (
                <button
                  key={p.days}
                  type="button"
                  className={
                    `${styles.chip}${selectedPreset === p.days ? ` ${styles.chipActive}` : ''}`
                  }
                  onClick={() => {
                    setSelectedPreset(p.days)
                    setCustomDate('')
                  }}
                >
                  {p.label}
                </button>
              ))}
            </div>
            <div className={styles.customDateRow}>
              <input
                type="date"
                className={styles.dateInput}
                value={customDate}
                onChange={e => {
                  setCustomDate(e.target.value)
                  setSelectedPreset(null)
                }}
                aria-label="Custom start date"
              />
            </div>
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', marginTop: 'var(--sp-3)' }}>
            <Button variant="secondary" onClick={onClose}>Cancel</Button>
            <Button variant="primary" disabled={!canSubmit} onClick={handleSubmit}>
              Start Backfill
            </Button>
          </div>
        </>
      )}

      {phase === 'polling' && (
        <div className={styles.status}>
          <div className={styles.spinner} aria-hidden="true" />
          <span>Processing\u2026 {processed} messages ingested</span>
        </div>
      )}

      {phase === 'done' && (
        <>
          <p className={styles.done}>Done \u2014 {processed} messages ingested.</p>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 'var(--sp-3)' }}>
            <Button variant="secondary" onClick={onClose}>Close</Button>
          </div>
        </>
      )}

      {phase === 'error' && (
        <>
          <p className={styles.error}>{errorMsg}</p>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 'var(--sp-3)' }}>
            <Button variant="secondary" onClick={onClose}>Close</Button>
          </div>
        </>
      )}
    </Modal>
  )
}
```

- [ ] **Step 5.6: Run BackfillModal tests**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npx vitest run src/pages/Groups/BackfillModal.test.jsx 2>&1 | tail -30
```

Expected: All 5 tests pass.

- [ ] **Step 5.7: Update Groups.jsx to wire in BackfillModal**

Replace the full content of `emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { groupsApi } from '../../api/groups'
import { tenantsApi } from '../../api/tenants'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { DataTable } from '../../components/DataTable/DataTable'
import { Modal } from '../../components/Modal/Modal'
import { SectionLabel } from '../../components/SectionLabel/SectionLabel'
import { BackfillModal } from './BackfillModal'
import styles from './Groups.module.css'

const LEVELS = ['LOW', 'MEDIUM', 'HIGH', 'STRICT']
const LEVEL_VARIANT = { LOW: 'green', MEDIUM: 'blue', HIGH: 'yellow', STRICT: 'red' }

const COLUMNS = [
  { key: 'name', label: 'Group' },
  { key: 'telegramChatId', label: 'Chat ID', mono: true, width: 180 },
  { key: 'moderationLevel', label: 'Mod', width: 100, render: v => <Badge variant={LEVEL_VARIANT[v] ?? 'gray'}>{v}</Badge> },
  { key: 'autoRespond', label: 'Auto-respond', width: 120, render: v => <Badge variant={v ? 'green' : 'gray'}>{v ? 'YES' : 'NO'}</Badge> },
  { key: 'knowledgeForkEnabled', label: 'Knowledge Fork', width: 130, render: v => <Badge variant={v ? 'green' : 'gray'}>{v ? 'YES' : 'NO'}</Badge> },
  {
    key: '_backfill',
    label: '',
    width: 80,
    render: (_, row) => (
      <Button
        variant="secondary"
        onClick={e => { e.stopPropagation(); }}
        data-row={JSON.stringify(row)}
        style={{ fontSize: '10px', padding: '3px 8px' }}
      >
        \u25B6 Backfill
      </Button>
    ),
  },
  { key: 'description', label: 'Description', render: v => v || '\u2014' },
]

function GroupEditModal({ group, onClose, onSave, tenants, api }) {
  const isNew = !group
  const [watchers, setWatchers] = useState([])
  useEffect(() => {
    if (group?.telegramChatId) {
      api.watchers(group.telegramChatId).then(setWatchers).catch(() => {})
    }
  }, [group?.telegramChatId])
  const [form, setForm] = useState({
    telegramChatId: group?.telegramChatId ?? '',
    name: group?.name ?? '',
    description: group?.description ?? '',
    moderationLevel: group?.moderationLevel ?? 'LOW',
    autoRespond: group?.autoRespond ?? false,
    knowledgeForkEnabled: group?.knowledgeForkEnabled ?? false,
    welcomeMessage: group?.welcomeMessage ?? '',
    tenantId: group?.tenantId ?? '',
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={isNew ? 'Add Group' : `Edit \u00b7 ${group.name}`} onClose={onClose} onSubmit={() => onSave(form)}>
      {!isNew && (
        <>
          <SectionLabel>Details</SectionLabel>
          <div className={styles.metaGrid}>
            <span className={styles.metaLabel}>Chat ID</span>
            <span className={styles.metaValue}>{group.telegramChatId}</span>
            <span className={styles.metaLabel}>Auto-respond</span>
            <span className={styles.metaValue}>{group.autoRespond ? 'Yes' : 'No'}</span>
            {group.tenantId && <>
              <span className={styles.metaLabel}>Tenant</span>
              <span className={styles.metaValue}>{group.tenantId}</span>
            </>}
            {group.createdAt && <>
              <span className={styles.metaLabel}>Created</span>
              <span className={styles.metaValue}>{new Date(group.createdAt).toLocaleString()}</span>
            </>}
            {group.rulesEnabled && <>
              <span className={styles.metaLabel}>Rules</span>
              <span className={styles.metaValue}>{group.rulesEnabled}</span>
            </>}
            {watchers.length > 0 && <>
              <span className={styles.metaLabel}>Watched by</span>
              <span className={styles.metaValue}>
                {watchers.map(w => w.displayName || w.phoneNumber).join(', ')}
              </span>
            </>}
          </div>
        </>
      )}

      {isNew && (
        <div className={styles.field}>
          <label>Telegram Chat ID</label>
          <input type="number" className={styles.input} value={form.telegramChatId}
            onChange={e => set('telegramChatId', parseInt(e.target.value, 10))} required />
        </div>
      )}

      <div className={styles.field}>
        <label>Name</label>
        <input type="text" className={styles.input} value={form.name}
          onChange={e => set('name', e.target.value)} required />
      </div>

      <div className={styles.field}>
        <label>Description</label>
        <input type="text" className={styles.input} value={form.description}
          onChange={e => set('description', e.target.value)} />
      </div>

      <div className={styles.field}>
        <label>Moderation Level</label>
        <select className={styles.input} value={form.moderationLevel}
          onChange={e => set('moderationLevel', e.target.value)}>
          {LEVELS.map(l => <option key={l}>{l}</option>)}
        </select>
      </div>

      <div className={styles.checkboxRow}>
        <input type="checkbox" checked={form.autoRespond}
          onChange={e => set('autoRespond', e.target.checked)} />
        Auto-respond
      </div>

      <div className={styles.checkboxRow}>
        <input type="checkbox" checked={form.knowledgeForkEnabled}
          onChange={e => set('knowledgeForkEnabled', e.target.checked)} />
        Knowledge Fork
      </div>

      <div className={styles.field}>
        <label>Welcome Message</label>
        <textarea className={styles.input} value={form.welcomeMessage}
          onChange={e => set('welcomeMessage', e.target.value)} rows={3} />
      </div>

      <div className={styles.field}>
        <label>Tenant</label>
        <select className={styles.input} value={form.tenantId ?? ''}
          onChange={e => set('tenantId', e.target.value || null)}>
          <option value="">None</option>
          {tenants.map(t => (
            <option key={t.id} value={t.id}>{t.name} ({t.id.slice(0, 8)})</option>
          ))}
        </select>
      </div>
    </Modal>
  )
}

export function Groups() {
  const authRequest = useAuthRequest()
  const api = groupsApi(authRequest)
  const [groups, setGroups] = useState([])
  const [modal, setModal] = useState(null)
  const [backfillGroup, setBackfillGroup] = useState(null)
  const [error, setError] = useState('')
  const [tenants, setTenants] = useState([])
  const [levelFilter, setLevelFilter] = useState('')

  const load = () => api.list().then(setGroups).catch(e => setError(e.message))
  useEffect(() => { load() }, [])
  useEffect(() => { tenantsApi(authRequest).list().then(setTenants).catch(() => {}) }, [])

  const filtered = groups.filter(g => !levelFilter || g.moderationLevel === levelFilter)

  const save = async form => {
    try {
      if (modal === 'add') await api.create(form)
      else await api.update(modal.telegramChatId, form)
      setModal(null)
      load()
    } catch (e) { setError(e.message) }
  }

  const remove = async group => {
    try { await api.remove(group.telegramChatId); load() }
    catch (e) { setError(e.message) }
  }

  // The backfill column button sets backfillGroup via a table-level click handler.
  // DataTable's onEdit fires on row click; we intercept _backfill column clicks separately.
  const handleEdit = row => {
    // Ignore clicks that originated from the backfill button (stopPropagation handles it in render,
    // but onEdit may still fire on some DataTable implementations — guard here too).
    setModal(row)
  }

  const handleRowClick = row => {
    // Used for backfill column button — DataTable passes row to onEdit; we only open
    // BackfillModal when explicitly triggered via the button's onClick.
  }

  return (
    <>
      {error && <p style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }} role="alert">{error}</p>}

      <DataTable
        title="Groups"
        systemId={`\u25C8 groups \u00b7 ${groups.length} watched`}
        addLabel="+ Add Group"
        onAdd={() => setModal('add')}
        columns={COLUMNS.map(col =>
          col.key === '_backfill'
            ? {
                ...col,
                render: (_, row) => (
                  <Button
                    variant="secondary"
                    onClick={e => { e.stopPropagation(); setBackfillGroup(row) }}
                    style={{ fontSize: '10px', padding: '3px 8px' }}
                  >
                    \u25B6 Backfill
                  </Button>
                ),
              }
            : col
        )}
        rows={filtered}
        rowKey={r => r.telegramChatId ?? r.id}
        onEdit={handleEdit}
        onDelete={remove}
        deleteMessage={g => `Stop watching "${g.name}"? This cannot be undone.`}
        filters={[{
          value: levelFilter,
          onChange: e => setLevelFilter(e.target.value),
          options: [
            { value: '', label: 'All moderation levels' },
            ...LEVELS.map(l => ({ value: l, label: l })),
          ],
        }]}
        emptyText="No groups match this filter"
      />

      {modal && (
        <GroupEditModal
          group={modal === 'add' ? null : modal}
          onClose={() => setModal(null)}
          onSave={save}
          tenants={tenants}
          api={api}
        />
      )}

      {backfillGroup && (
        <BackfillModal
          group={backfillGroup}
          onClose={() => setBackfillGroup(null)}
          api={api}
        />
      )}
    </>
  )
}
```

- [ ] **Step 5.8: Run all admin-ui tests**

```bash
cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend
npx vitest run src/pages/Groups/ 2>&1 | tail -30
```

Expected: All tests pass (Groups.test.jsx + BackfillModal.test.jsx).

- [ ] **Step 5.9: Commit**

```bash
cd /home/ben/Development/ecip
git add \
  emcip-admin-ui/src/main/frontend/src/api/groups.js \
  emcip-admin-ui/src/main/frontend/src/pages/Groups/BackfillModal.jsx \
  emcip-admin-ui/src/main/frontend/src/pages/Groups/BackfillModal.module.css \
  emcip-admin-ui/src/main/frontend/src/pages/Groups/BackfillModal.test.jsx \
  emcip-admin-ui/src/main/frontend/src/pages/Groups/Groups.jsx
git commit -m "feat(admin-ui): add BackfillModal and Backfill column to Groups page (#26.7)"
```

---

## Self-review checklist

**Spec coverage:**
- [x] TDLib getChatHistory endpoint — Task 1
- [x] BackfillService async loop + Kafka publish — Task 2
- [x] BackfillController updated signature — Task 3
- [x] Admin-api proxy with ReactorTenantContext + circuit breaker — Task 4
- [x] groups.js backfill/backfillStatus methods — Task 5
- [x] BackfillModal with preset chips, account selector, polling — Task 5
- [x] Date presets (7d, 30d, 90d, 180d, 365d) — Task 5
- [x] All 5 BackfillModal tests — Task 5
- [x] Backfill column in Groups.jsx — Task 5
- [x] Per-group, per-account bounded by fromDate — Tasks 1–5
- [x] `tools.jackson` imports (not `com.fasterxml`) — Tasks 1, 2
- [x] MockMvc for knowledge-engine controller test (JPA stack) — Task 3
- [x] WebTestClient for admin-api controller test (WebFlux stack) — Task 4
- [x] ExchangeFunction mock pattern (not WebClient chain mocks) — Task 4
- [x] setUp updated for InternalController(manager, objectMapper) — Task 1

**Type consistency check:**
- `BackfillService.triggerBackfill(UUID, long, long, UUID)` called in BackfillController as `request.accountId(), request.chatId(), request.fromDate(), request.tenantId()` — all types match
- `BackfillStatus` record: `(backfillId, chatId, status, processed, fromDate, startedAt, errorMessage)` — used consistently in BackfillService and BackfillControllerTest
- `ChatHistoryResponse(List<String> messages, boolean hasMore, long lastMessageId)` — defined in InternalController, mirrored in BackfillService — field names match across call sites
- `BackfillProxyController.BackfillTriggerRequest(UUID accountId, String fromDate)` — matches `api.backfill(chatId, { accountId, fromDate })` in groups.js
- `InternalController` constructor now `(TdLibClientManager manager, ObjectMapper objectMapper)` — test setUp uses `new InternalController(manager, new ObjectMapper())`
