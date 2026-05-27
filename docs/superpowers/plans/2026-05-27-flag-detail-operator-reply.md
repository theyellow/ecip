# Flag-Detail Operator Reply — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow operators to reply to flagged Telegram messages directly from the flag detail modal in the admin UI.

**Architecture:** Hybrid synchronous + async audit. Admin UI → admin-api → tdlib-adapter (HTTP) sends the message via TDLib. Admin-api publishes an audit event to Kafka after successful send. The tdlib-adapter gets a new send-message endpoint; the admin-api gets a reply endpoint; the UI gets a reply panel in the flag detail modal.

**Tech Stack:** Spring WebFlux (admin-api, reactive), Spring MVC (tdlib-adapter, blocking), TDLib native API, React, Kafka

**Spec:** `docs/superpowers/specs/2026-05-27-flag-detail-operator-reply-design.md`

---

## File Structure

| Module | File | Action | Responsibility |
|--------|------|--------|----------------|
| `emcip-tdlib-adapter` | `controller/InternalController.java` | Modify | Add `POST /internal/send-message/{accountId}` |
| `emcip-tdlib-adapter` | `controller/InternalControllerTest.java` | Modify | Tests for send-message endpoint |
| `emcip-admin-api` | `controller/FlagController.java` | Modify | Add `POST /api/flags/{id}/reply` |
| `emcip-admin-api` | `service/FlagService.java` | Modify | Add `reply()` method with account resolution + WebClient call |
| `emcip-admin-api` | `controller/FlagControllerTest.java` | Modify | Tests for reply endpoint |
| `emcip-admin-api` | `service/FlagServiceTest.java` | Modify | Tests for reply service logic |
| `emcip-admin-ui` | `src/api/flags.js` | Modify | Add `reply()` API method |
| `emcip-admin-ui` | `src/pages/Flags/Flags.jsx` | Modify | Reply panel in FlagDetailModal |
| `emcip-admin-ui` | `src/pages/Flags/Flags.module.css` | Modify | CSS for reply section |
| `emcip-intent-classifier` | `service/IntentClassificationService.java` | Modify | Pass `telegramMessageId` through params |
| `emcip-intent-classifier` | `service/IntentClassificationServiceTest.java` | Modify | Verify `telegramMessageId` in output |
| `emcip-policy-engine` | `service/PolicyEvaluationService.java` | Modify | Copy `telegramMessageId` into metadata |

---

### Task 1: Pass telegramMessageId through intent-classifier

**Files:**
- Modify: `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/service/IntentClassificationService.java:99-107`
- Modify: `emcip-intent-classifier/src/test/java/io/emcip/intent/classifier/service/IntentClassificationServiceTest.java`

- [ ] **Step 1: Update the parameters map**

In `IntentClassificationService.java`, the parameters map at line 99 currently uses `Map.of()` with 4 entries. Change it to include `telegramMessageId`. Since `Map.of()` is immutable and limited to 10 entries, and `telegramMessageId` can be null, switch to a `HashMap`:

```java
// Replace lines 99-107:
//   Map.of(
//       "textLength", text.length(),
//       "chatId", message.chatId(),
//       "senderId", message.senderId() != null ? message.senderId() : "",
//       "messageText", text),

// With:
java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
params.put("textLength", text.length());
params.put("chatId", message.chatId());
params.put("senderId", message.senderId() != null ? message.senderId() : "");
params.put("messageText", text);
if (message.telegramMessageId() != null) {
    params.put("telegramMessageId", message.telegramMessageId());
}
```

And update the `IntentClassifiedEvent` constructor call to use `params` instead of the inline `Map.of(...)`.

- [ ] **Step 2: Update the test**

In `IntentClassificationServiceTest.java`, find the test(s) that construct a `TelegramMessageEvent` and verify the output. Set `telegramMessageId` to a value like `999L` in the test event, and assert that the output `IntentClassifiedEvent.parameters()` contains `"telegramMessageId"` with value `999L`.

- [ ] **Step 3: Run tests**

Run: `mvn test -pl emcip-intent-classifier -q`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
mvn spotless:apply -pl emcip-intent-classifier
git add emcip-intent-classifier/
git commit -m "feat(intent-classifier): pass telegramMessageId through parameters map"
```

---

### Task 2: Store telegramMessageId in PolicyDecision metadata

**Files:**
- Modify: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/service/PolicyEvaluationService.java:235-237`

- [ ] **Step 1: Add telegramMessageId to metadata builder**

In `PolicyEvaluationService.java`, after line 237 (the `senderId` line), add:

```java
if (params.containsKey("telegramMessageId")) meta.put("telegramMessageId", params.get("telegramMessageId"));
```

- [ ] **Step 2: Run tests**

Run: `mvn test -pl emcip-policy-engine -q`
Expected: All tests pass (no test change needed — metadata is a flexible JSON map).

- [ ] **Step 3: Commit**

```bash
mvn spotless:apply -pl emcip-policy-engine
git add emcip-policy-engine/
git commit -m "feat(policy-engine): include telegramMessageId in PolicyDecision metadata"
```

---

### Task 3: TDLib adapter — send-message endpoint

**Files:**
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/controller/InternalController.java`
- Modify: `emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/controller/InternalControllerTest.java`

- [ ] **Step 1: Add the request/response records**

In `InternalController.java`, add at the end of the class (before the closing `}`):

```java
public record SendMessageRequest(
        long chatId,
        @jakarta.validation.constraints.NotBlank String text,
        long replyToMessageId,
        Long recipientUserId) {}

public record SendMessageResponse(boolean success, long messageId) {}
```

- [ ] **Step 2: Add the send-message endpoint**

In `InternalController.java`, add the endpoint method:

```java
@PostMapping("/send-message/{accountId}")
public Mono<ResponseEntity<SendMessageResponse>> sendMessage(
        @PathVariable UUID accountId,
        @jakarta.validation.Valid @RequestBody SendMessageRequest req) {
    if (!manager.hasClient(accountId)) {
        log.warn("[{}] sendMessage: no client found", accountId);
        return Mono.just(ResponseEntity.badRequest().build());
    }
    TdLibClient client = manager.getClient(accountId);
    if (!client.isAuthorized()) {
        log.warn("[{}] sendMessage: client not authorized", accountId);
        return Mono.just(ResponseEntity.badRequest().build());
    }

    long targetChatId = req.chatId();

    // If recipientUserId is set, open a private chat first
    Mono<Long> chatIdMono;
    if (req.recipientUserId() != null && req.recipientUserId() > 0) {
        chatIdMono = Mono.<Long>create(sink ->
                client.sendRequest(new TdApi.CreatePrivateChat(req.recipientUserId(), false),
                        result -> {
                            if (result instanceof TdApi.Chat chat) sink.success(chat.id);
                            else if (result instanceof TdApi.Error err)
                                sink.error(new RuntimeException("CreatePrivateChat error: " + err.message));
                        }));
    } else {
        chatIdMono = Mono.just(targetChatId);
    }

    return chatIdMono.flatMap(resolvedChatId -> {
        TdApi.FormattedText formattedText = new TdApi.FormattedText();
        formattedText.text = req.text();
        formattedText.entities = new TdApi.TextEntity[0];

        TdApi.InputMessageText inputContent = new TdApi.InputMessageText();
        inputContent.text = formattedText;

        TdApi.SendMessage sendMsg = new TdApi.SendMessage();
        sendMsg.chatId = resolvedChatId;
        sendMsg.inputMessageContent = inputContent;
        if (req.replyToMessageId() > 0) {
            TdApi.InputMessageReplyToMessage replyTo = new TdApi.InputMessageReplyToMessage();
            replyTo.messageId = req.replyToMessageId();
            sendMsg.replyTo = replyTo;
        }

        return Mono.<SendMessageResponse>create(sink ->
                client.sendRequest(sendMsg, result -> {
                    if (result instanceof TdApi.Message msg) {
                        log.info("[{}] Message sent to chat {}, messageId={}",
                                accountId, resolvedChatId, msg.id);
                        sink.success(new SendMessageResponse(true, msg.id));
                    } else if (result instanceof TdApi.Error err) {
                        log.error("[{}] SendMessage error: {}", accountId, err.message);
                        sink.error(new RuntimeException("SendMessage error: " + err.message));
                    }
                }));
    })
    .map(resp -> ResponseEntity.status(HttpStatus.CREATED).body(resp))
    .onErrorResume(e -> {
        log.error("[{}] sendMessage failed: {}", accountId, e.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .<SendMessageResponse>build());
    });
}
```

Add the missing import if not present: `import org.springframework.http.HttpStatus;` (already used via `@ResponseStatus`).

- [ ] **Step 3: Write tests**

In `InternalControllerTest.java`, add:

```java
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
```

- [ ] **Step 4: Verify the TdApi stub has required classes**

Check that the local `TdApi.java` stub includes `SendMessage`, `InputMessageText`, `FormattedText`, `InputMessageReplyToMessage`, `CreatePrivateChat`. If any are missing, add minimal stub classes. The Docker build generates the real ones from TDLib source.

- [ ] **Step 5: Run tests**

Run: `mvn test -pl emcip-tdlib-adapter -q`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
mvn spotless:apply -pl emcip-tdlib-adapter
git add emcip-tdlib-adapter/
git commit -m "feat(tdlib-adapter): add POST /internal/send-message endpoint"
```

---

### Task 4: Admin API — reply endpoint and service

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/controller/FlagController.java`
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/FlagService.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/service/FlagServiceTest.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/FlagControllerTest.java`

- [ ] **Step 1: Add request/response DTOs to FlagController**

In `FlagController.java`, add inner records at the end of the class:

```java
public record ReplyRequest(
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Size(max = 4096, message = "text must be 4096 characters or fewer")
        String text,
        @jakarta.validation.constraints.NotNull String target,
        boolean replyToOriginal,
        boolean prefixModerator,
        java.util.UUID accountId) {}

public record ReplyResponse(long messageId, String target, boolean markedActioned) {}

public record AccountOption(java.util.UUID id, String displayName, String phoneNumber) {}

public record AccountSelectionRequired(java.util.List<AccountOption> accounts) {}
```

- [ ] **Step 2: Add the reply endpoint to FlagController**

```java
@Operation(summary = "Reply to a flagged message via Telegram")
@PostMapping("/{id}/reply")
public Mono<ResponseEntity<?>> reply(
        @PathVariable String id, @Valid @RequestBody ReplyRequest req) {
    return flagService.reply(id, req.text(), req.target(), req.replyToOriginal(),
                    req.prefixModerator(), req.accountId())
            .map(resp -> ResponseEntity.status(HttpStatus.CREATED).body((Object) resp))
            .onErrorResume(AccountSelectionException.class, e ->
                    Mono.just(ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(new AccountSelectionRequired(e.getAccounts()))));
}
```

Add necessary imports: `PostMapping`, `ResponseEntity`, `HttpStatus`.

- [ ] **Step 3: Create AccountSelectionException**

Create a simple exception class. Add to `FlagController.java` or as a top-level class in the service package — keeping it in the service package is cleaner:

File: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/AccountSelectionException.java`

```java
package io.emcip.admin.api.service;

import io.emcip.admin.api.controller.FlagController;
import java.util.List;
import lombok.Getter;

@Getter
public class AccountSelectionException extends RuntimeException {
    private final List<FlagController.AccountOption> accounts;

    public AccountSelectionException(List<FlagController.AccountOption> accounts) {
        super("Multiple accounts watch this chat — selection required");
        this.accounts = accounts;
    }
}
```

- [ ] **Step 4: Add reply() method to FlagService**

The `FlagService` needs access to: `PolicyEngineClient` (already has), `TelegramAccountService` or direct repo access to resolve accounts, the tdlib WebClient, Kafka for audit. Inject what's needed:

```java
package io.emcip.admin.api.service;

import io.emcip.admin.api.client.PolicyEngineClient;
import io.emcip.admin.api.controller.FlagController;
import io.emcip.admin.api.entity.TelegramAccount;
import io.emcip.admin.api.repository.AccountWatchedGroupRepository;
import io.emcip.admin.api.repository.GroupProfileRepository;
import io.emcip.admin.api.repository.TelegramAccountRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class FlagService {

    private final PolicyEngineClient policyEngineClient;
    private final GroupProfileRepository groupProfileRepository;
    private final AccountWatchedGroupRepository watchedGroupRepository;
    private final TelegramAccountRepository accountRepository;
    private final WebClient tdlibClient;
    private final CircuitBreaker tdlibCircuitBreaker;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public FlagService(
            PolicyEngineClient policyEngineClient,
            GroupProfileRepository groupProfileRepository,
            AccountWatchedGroupRepository watchedGroupRepository,
            TelegramAccountRepository accountRepository,
            @Qualifier("tdlibWebClient") WebClient tdlibClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.policyEngineClient = policyEngineClient;
        this.groupProfileRepository = groupProfileRepository;
        this.watchedGroupRepository = watchedGroupRepository;
        this.accountRepository = accountRepository;
        this.tdlibClient = tdlibClient;
        this.tdlibCircuitBreaker = circuitBreakerRegistry.circuitBreaker("tdlib-adapter");
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public Mono<JsonNode> listFlags(int page, int size, String decision) {
        return policyEngineClient.listDecisions(page, size, decision);
    }

    public Mono<Void> updateStatus(String id, String status) {
        return policyEngineClient.updateDecisionStatus(id, status);
    }

    public Mono<FlagController.ReplyResponse> reply(
            String flagId, String text, String target,
            boolean replyToOriginal, boolean prefixModerator, UUID accountId) {

        return policyEngineClient.getDecision(flagId)
                .flatMap(flag -> {
                    JsonNode meta = flag.get("metadata");
                    if (meta == null || meta.isNull()) {
                        return Mono.error(new IllegalArgumentException("Flag has no metadata"));
                    }
                    long chatId = meta.get("chatId").asLong();
                    String senderId = meta.has("senderId") ? meta.get("senderId").asText() : null;
                    long telegramMessageId = meta.has("telegramMessageId")
                            ? meta.get("telegramMessageId").asLong() : 0L;

                    // Resolve account(s) watching this chat
                    return groupProfileRepository.findByTelegramChatId(chatId)
                            .switchIfEmpty(Mono.error(new IllegalArgumentException(
                                    "No group profile found for chatId " + chatId)))
                            .flatMap(profile -> {
                                if (accountId != null) {
                                    // Validate provided account watches this chat
                                    return watchedGroupRepository
                                            .existsByAccountIdAndGroupProfileId(accountId, profile.getId())
                                            .flatMap(exists -> exists
                                                    ? accountRepository.findById(accountId)
                                                          .map(a -> new AccountWithMeta(a, chatId, senderId, telegramMessageId))
                                                    : Mono.error(new IllegalArgumentException(
                                                          "Account " + accountId + " does not watch chat " + chatId)));
                                }
                                // Find all accounts watching this group
                                return watchedGroupRepository.findByAccountId_groupProfileId(profile.getId())
                                        .flatMap(awg -> accountRepository.findById(awg.getAccountId()))
                                        .collectList()
                                        .flatMap(accounts -> {
                                            if (accounts.isEmpty()) {
                                                return Mono.error(new IllegalArgumentException(
                                                        "No account is watching chat " + chatId));
                                            }
                                            if (accounts.size() > 1) {
                                                return Mono.error(new AccountSelectionException(
                                                        accounts.stream()
                                                                .map(a -> new FlagController.AccountOption(
                                                                        a.getId(), a.getDisplayName(), a.getPhoneNumber()))
                                                                .toList()));
                                            }
                                            return Mono.just(new AccountWithMeta(
                                                    accounts.get(0), chatId, senderId, telegramMessageId));
                                        });
                            });
                })
                .flatMap(awm -> {
                    String finalText = prefixModerator ? "[Moderator]: " + text : text;
                    Long recipientUserId = "DM".equalsIgnoreCase(target) && awm.senderId != null
                            ? parseSenderId(awm.senderId) : null;
                    long replyToMsgId = replyToOriginal ? awm.telegramMessageId : 0L;

                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("chatId", awm.chatId);
                    body.put("text", finalText);
                    body.put("replyToMessageId", replyToMsgId);
                    if (recipientUserId != null) body.put("recipientUserId", recipientUserId);

                    return tdlibClient.post()
                            .uri("/internal/send-message/{accountId}", awm.account.getId())
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .transformDeferred(CircuitBreakerOperator.of(tdlibCircuitBreaker))
                            .map(resp -> {
                                long msgId = resp.has("messageId") ? resp.get("messageId").asLong() : 0L;
                                publishAuditEvent(flagId, target, awm.chatId,
                                        awm.account.getId(), msgId, replyToOriginal, prefixModerator);
                                return new FlagController.ReplyResponse(msgId, target, false);
                            });
                });
    }

    private void publishAuditEvent(String flagId, String target, long chatId,
            UUID accountId, long messageId, boolean replyToOriginal, boolean prefixModerator) {
        try {
            Map<String, Object> event = Map.of(
                    "eventType", "OPERATOR_REPLY",
                    "action", "SEND_MESSAGE",
                    "sourceService", "admin-api",
                    "resourceId", flagId,
                    "outcome", "SUCCESS",
                    "details", Map.of(
                            "target", target,
                            "chatId", chatId,
                            "accountId", accountId.toString(),
                            "telegramMessageId", messageId,
                            "replyToOriginal", replyToOriginal,
                            "prefixModerator", prefixModerator));
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(new ProducerRecord<>("audit.events", flagId, json));
        } catch (Exception e) {
            log.error("Failed to publish OPERATOR_REPLY audit event for flag {}", flagId, e);
        }
    }

    private static Long parseSenderId(String senderId) {
        if (senderId == null) return null;
        // Format: "user:12345" or just "12345"
        String numeric = senderId.contains(":") ? senderId.split(":")[1] : senderId;
        try {
            return Long.parseLong(numeric);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record AccountWithMeta(
            TelegramAccount account, long chatId, String senderId, long telegramMessageId) {}
}
```

**Important:** The `AccountWatchedGroupRepository` needs a new query method. See Step 5.

- [ ] **Step 5: Add repository query method**

In `AccountWatchedGroupRepository.java`, add:

```java
Flux<AccountWatchedGroup> findByGroupProfileId(Long groupProfileId);
```

Then in the `FlagService.reply()` method above, replace `findByAccountId_groupProfileId(profile.getId())` with `findByGroupProfileId(profile.getId())`.

- [ ] **Step 6: Add getDecision to PolicyEngineClient**

Check if `PolicyEngineClient` already has a `getDecision(String id)` method. If not, add:

```java
public Mono<JsonNode> getDecision(String id) {
    return webClient.get()
            .uri("/api/policy-decisions/{id}", id)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
}
```

And verify the policy-engine has a corresponding `GET /api/policy-decisions/{id}` endpoint. If it doesn't, add one.

- [ ] **Step 7: Write FlagService tests**

In `FlagServiceTest.java`, the class needs to be updated since `FlagService` now has a manual constructor instead of `@InjectMocks`. Update the test setup and add a reply test:

```java
@ExtendWith(MockitoExtension.class)
class FlagServiceTest {

    @Mock private PolicyEngineClient policyEngineClient;
    @Mock private GroupProfileRepository groupProfileRepository;
    @Mock private AccountWatchedGroupRepository watchedGroupRepository;
    @Mock private TelegramAccountRepository accountRepository;
    @Mock private WebClient tdlibClient;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private ObjectMapper objectMapper;

    private FlagService flagService;

    @BeforeEach
    void setUp() {
        flagService = new FlagService(
                policyEngineClient, groupProfileRepository, watchedGroupRepository,
                accountRepository, tdlibClient,
                CircuitBreakerRegistry.ofDefaults(), kafkaTemplate, objectMapper);
    }

    // Keep existing tests (listFlags_*, updateStatus_*) but remove @InjectMocks

    @Test
    void reply_noMetadata_returnsError() {
        ObjectNode flag = JsonNodeFactory.instance.objectNode();
        flag.putNull("metadata");
        when(policyEngineClient.getDecision("flag-1")).thenReturn(Mono.just(flag));

        StepVerifier.create(flagService.reply("flag-1", "Hello", "GROUP", true, false, null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }
}
```

- [ ] **Step 8: Write FlagController reply test**

In `FlagControllerTest.java`, add:

```java
@Test
void reply_returns201() {
    when(flagService.reply("flag-1", "Hello", "GROUP", true, false, null))
            .thenReturn(Mono.just(new FlagController.ReplyResponse(12345L, "GROUP", false)));

    webTestClient
            .post()
            .uri("/api/flags/flag-1/reply")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("text", "Hello", "target", "GROUP",
                    "replyToOriginal", true, "prefixModerator", false))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody()
            .jsonPath("$.messageId")
            .isEqualTo(12345);
}
```

- [ ] **Step 9: Run tests**

Run: `mvn test -pl emcip-admin-api -q`
Expected: All tests pass.

- [ ] **Step 10: Commit**

```bash
mvn spotless:apply -pl emcip-admin-api
git add emcip-admin-api/
git commit -m "feat(admin-api): add POST /api/flags/{id}/reply endpoint with audit trail"
```

---

### Task 5: Admin UI — flags API and reply panel

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/api/flags.js`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.jsx`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.module.css`

- [ ] **Step 1: Add reply API method**

In `flags.js`, add to the returned object:

```javascript
reply: (id, body) =>
  request(`/api/flags/${encodeURIComponent(id)}/reply`, {
    method: 'POST',
    body: JSON.stringify(body),
  }),
```

- [ ] **Step 2: Add CSS classes for reply section**

Append to `Flags.module.css`:

```css
/* Reply section */
.replyToggle { background: none; border: none; color: var(--accent); cursor: pointer; font-size: 0.85rem; padding: 0; margin-top: 1rem; }
.replySection { display: flex; flex-direction: column; gap: 0.75rem; margin-top: 0.75rem; padding-top: 0.75rem; border-top: 1px solid var(--border); }
.replyTextarea { width: 100%; min-height: 80px; padding: 0.5rem 0.7rem; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-secondary); color: var(--text-primary); font-size: 0.875rem; resize: vertical; box-sizing: border-box; font-family: inherit; }
.replyOptions { display: flex; gap: 1rem; align-items: center; flex-wrap: wrap; font-size: 0.8rem; color: var(--text-secondary); }
.replyOptions label { display: flex; align-items: center; gap: 0.3rem; cursor: pointer; }
.targetToggle { display: flex; border: 1px solid var(--border); border-radius: 5px; overflow: hidden; }
.targetBtn { padding: 0.25rem 0.6rem; border: none; background: var(--bg-secondary); color: var(--text-secondary); font-size: 0.8rem; cursor: pointer; }
.targetBtn.active { background: var(--accent); color: white; }
.replyActions { display: flex; gap: 0.5rem; align-items: center; }
.replySuccess { color: var(--badge-green-text, #16a34a); font-size: 0.85rem; }
.replyError { color: var(--badge-red-text); font-size: 0.85rem; }
.accountSelect { padding: 0.3rem 0.5rem; border: 1px solid var(--border); border-radius: 5px; background: var(--bg-secondary); color: var(--text-primary); font-size: 0.8rem; }
```

- [ ] **Step 3: Add reply panel to FlagDetailModal**

In `Flags.jsx`, update the `FlagDetailModal` component. Add state and the reply section after the error display (line 108) and before the closing `</div>` of `modalBody`:

Add these state variables after line 36 (`const [error, setError] = useState('')`):

```javascript
const [showReply, setShowReply] = useState(false)
const [replyText, setReplyText] = useState('')
const [replyTarget, setReplyTarget] = useState('GROUP')
const [replyToOriginal, setReplyToOriginal] = useState(true)
const [prefixModerator, setPrefixModerator] = useState(false)
const [replySending, setReplySending] = useState(false)
const [replyError, setReplyError] = useState('')
const [replySuccess, setReplySuccess] = useState(false)
const [accounts, setAccounts] = useState(null)
const [selectedAccountId, setSelectedAccountId] = useState(null)
const [promptActioned, setPromptActioned] = useState(false)
```

The `FlagDetailModal` needs access to the API, so update the component signature to accept an `api` prop:

```javascript
function FlagDetailModal({ flag, onClose, onStatusChange, api }) {
```

Add the reply handler:

```javascript
const handleReply = async () => {
  setReplySending(true)
  setReplyError('')
  setReplySuccess(false)
  try {
    const body = {
      text: replyText,
      target: replyTarget,
      replyToOriginal,
      prefixModerator,
      accountId: selectedAccountId,
    }
    const resp = await api.reply(flag.id, body)
    setReplySuccess(true)
    setPromptActioned(true)
    setReplyText('')
  } catch (e) {
    // Check for 409 — multiple accounts
    if (e.status === 409 && e.body?.accounts) {
      setAccounts(e.body.accounts)
      setReplyError('Multiple accounts watch this chat — select one below.')
    } else {
      setReplyError(e.message || 'Failed to send reply')
    }
  } finally {
    setReplySending(false)
  }
}

const handleMarkActioned = async () => {
  try {
    await onStatusChange(flag.id, 'ACTIONED')
    setStatus('ACTIONED')
    setPromptActioned(false)
  } catch (e) {
    setReplyError(e.message)
  }
}
```

Add the reply section JSX after the `{error && ...}` block (after line 108):

```jsx
<button className={styles.replyToggle} onClick={() => setShowReply(s => !s)}>
  {showReply ? '▾ Reply' : '▸ Reply'}
</button>

{showReply && (
  <div className={styles.replySection}>
    <textarea
      className={styles.replyTextarea}
      placeholder="Type your response..."
      value={replyText}
      onChange={e => setReplyText(e.target.value)}
      maxLength={4096}
    />

    <div className={styles.replyOptions}>
      <div className={styles.targetToggle}>
        <button className={`${styles.targetBtn}${replyTarget === 'GROUP' ? ' ' + styles.active : ''}`}
          onClick={() => setReplyTarget('GROUP')}>Group</button>
        <button className={`${styles.targetBtn}${replyTarget === 'DM' ? ' ' + styles.active : ''}`}
          onClick={() => setReplyTarget('DM')}>DM</button>
      </div>
      <label>
        <input type="checkbox" checked={replyToOriginal} onChange={e => setReplyToOriginal(e.target.checked)} />
        Reply to original
      </label>
      <label>
        <input type="checkbox" checked={prefixModerator} onChange={e => setPrefixModerator(e.target.checked)} />
        Prefix [Moderator]
      </label>
    </div>

    {accounts && (
      <select className={styles.accountSelect} value={selectedAccountId ?? ''}
        onChange={e => setSelectedAccountId(e.target.value || null)}>
        <option value="">Select account...</option>
        {accounts.map(a => (
          <option key={a.id} value={a.id}>{a.displayName} ({a.phoneNumber})</option>
        ))}
      </select>
    )}

    <div className={styles.replyActions}>
      <Button onClick={handleReply} disabled={replySending || !replyText.trim()}>
        {replySending ? 'Sending...' : 'Send'}
      </Button>
      {replySuccess && !promptActioned && (
        <span className={styles.replySuccess}>Sent!</span>
      )}
      {promptActioned && (
        <>
          <span className={styles.replySuccess}>Sent! Mark as actioned?</span>
          <Button variant="secondary" onClick={handleMarkActioned}>Yes</Button>
          <Button variant="secondary" onClick={() => setPromptActioned(false)}>No</Button>
        </>
      )}
    </div>

    {replyError && <p className={styles.replyError}>{replyError}</p>}
  </div>
)}
```

- [ ] **Step 4: Pass api prop to FlagDetailModal**

In the `Flags` component, update the `FlagDetailModal` usage (around line 207) to pass the API:

```jsx
<FlagDetailModal
  flag={selected}
  onClose={() => setSelected(null)}
  onStatusChange={updateStatus}
  api={api}
/>
```

- [ ] **Step 5: Handle 409 responses in the API layer**

The `useAuthRequest` hook's `request` function may not expose the response status on errors. Check how errors are thrown in `auth/AuthContext.jsx`. If the request function throws a plain `Error` without status, we need to enhance the error handling for the reply call. The reply handler should detect 409 specifically.

Update `flags.js` reply method to handle 409:

```javascript
reply: async (id, body) => {
  const resp = await request(`/api/flags/${encodeURIComponent(id)}/reply`, {
    method: 'POST',
    body: JSON.stringify(body),
    rawResponse: true,  // if supported, get the raw response
  })
  return resp
},
```

**Note:** The exact approach depends on how `useAuthRequest` works. The implementer should check `src/auth/AuthContext.jsx` to see how errors propagate and adjust accordingly. The key requirement: when the backend returns 409, the UI must be able to detect this and extract the `accounts` array from the response body.

- [ ] **Step 6: Commit**

```bash
git add emcip-admin-ui/
git commit -m "feat(admin-ui): reply panel in flag detail modal"
```

---

### Task 6: Install emcip-core and run full test suite

**Files:** None (validation only)

- [ ] **Step 1: Install emcip-core**

```bash
mvn install -pl emcip-core -DskipTests -q
```

- [ ] **Step 2: Run Spotless across all modules**

```bash
mvn spotless:apply
```

If any files changed:
```bash
git add -A && git commit -m "style: apply spotless"
```

- [ ] **Step 3: Run full test suite**

```bash
mvn test
```

Expected: BUILD SUCCESS with 0 failures across all modules.

- [ ] **Step 4: Create PR**

```bash
git push -u origin feat/flag-detail-operator-reply
gh pr create --title "feat: flag-detail operator reply (backlog #23 Phase 1)" --body "..."
```

---

## Self-Review Checklist

| Spec Section | Covered By |
|--------------|------------|
| 1. TDLib send-message endpoint | Task 3 |
| 2. Admin API reply endpoint | Task 4 |
| 3. Admin UI reply panel | Task 5 |
| 4. telegramMessageId in metadata | Task 1 (intent-classifier) + Task 2 (policy-engine) |
| 5. Audit trail | Task 4 (publishAuditEvent in FlagService) |
| Account selection (single/multiple) | Task 4 (FlagService.reply) + Task 5 (409 handling) |
| Operator chooses Group/DM | Task 5 (target toggle) |
| Operator chooses reply-to | Task 5 (checkbox) |
| Operator chooses prefix | Task 5 (checkbox) |
| Mark as actioned prompt | Task 5 (promptActioned state) |
