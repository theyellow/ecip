package io.emcip.tdlib.adapter.service;

import io.emcip.tdlib.adapter.config.TdLibClient;
import io.emcip.tdlib.adapter.config.TdLibClientManager;
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
    private final TdLibClientManager manager;

    public TelegramUpdateHandler(
            TelegramEventPublisher eventPublisher,
            ConcurrentMap<UUID, Set<Long>> watchedChatIds,
            TdLibClientManager manager) {
        this.eventPublisher = eventPublisher;
        this.watchedChatIds = watchedChatIds;
        this.manager = manager;
    }

    /**
     * Register all update handlers on the given client. Called by TdLibClientManager after a new
     * TdLibClient is created and initialized.
     */
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

        String tenantId = manager.getTenantId(accountId);
        eventPublisher
                .publishMessage(newMessage.message, newMessage, tenantId)
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
