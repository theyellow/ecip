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
    private final ProfileCacheService profileCache;

    public TelegramUpdateHandler(
            TelegramEventPublisher eventPublisher,
            ConcurrentMap<UUID, Set<Long>> watchedChatIds,
            TdLibClientManager manager,
            ProfileCacheService profileCache) {
        this.eventPublisher = eventPublisher;
        this.watchedChatIds = watchedChatIds;
        this.manager = manager;
        this.profileCache = profileCache;
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
        boolean knowledgeFork = manager.isKnowledgeForkEnabled(accountId, chatId);
        eventPublisher
                .publishMessage(newMessage.message, newMessage, tenantId, knowledgeFork)
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

    void handleChatTitle(TdApi.Update update) {
        if (update instanceof TdApi.UpdateChatTitle title) {
            profileCache.putChat(title.chatId, title.title);
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

    void handleUserUpdate(TdApi.Update update) {
        if (update instanceof TdApi.UpdateUser userUpdate) {
            TdApi.User user = userUpdate.user;
            String displayName =
                    (user.firstName + " " + (user.lastName != null ? user.lastName : "")).trim();
            String username = resolveUsername(user);
            profileCache.putUser(user.id, displayName, username);
            log.debug("User {} updated: {} (@{})", user.id, displayName, username);

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

    /**
     * Resolve username from TdApi.User — the real TDLib API uses {@code
     * user.usernames.editableUsername} while the local development stub uses {@code user.username}.
     * This method tries the real API field first via reflection, falling back to the stub field.
     */
    private static String resolveUsername(TdApi.User user) {
        try {
            var field = TdApi.User.class.getField("usernames");
            Object usernames = field.get(user);
            if (usernames != null) {
                var editableField = usernames.getClass().getField("editableUsername");
                return (String) editableField.get(usernames);
            }
            return null;
        } catch (NoSuchFieldException e) {
            // Stub TdApi — fall back to direct username field
            try {
                var fallback = TdApi.User.class.getField("username");
                return (String) fallback.get(user);
            } catch (ReflectiveOperationException ex) {
                return null;
            }
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
