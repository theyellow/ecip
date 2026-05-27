package io.emcip.tdlib.adapter.controller;

import io.emcip.tdlib.adapter.config.TdLibClient;
import io.emcip.tdlib.adapter.config.TdLibClientManager;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final TdLibClientManager manager;

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
        manager.updateWatchedChats(accountId, new HashSet<>(req.chatIds()));
        log.info("[{}] Watched chat IDs updated: {}", accountId, req.chatIds());
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
                .map(chat -> new ChatInfo(chat.id, chat.title, chatType(chat.type)))
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
