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
                            if (messages.messages == null || messages.messages.length == 0) {
                                return new ChatHistoryResponse(List.of(), false, 0L);
                            }
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
                null,
                null,
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
                Map.of(
                        "contentType",
                        contentTypeOf(message),
                        "isChannelPost",
                        message.isChannelPost),
                Instant.now().toString(),
                null,
                null,
                null);
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

    public record ChatHistoryResponse(List<String> messages, boolean hasMore, long lastMessageId) {}
}
