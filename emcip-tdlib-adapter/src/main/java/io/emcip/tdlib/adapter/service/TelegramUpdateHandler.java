package io.emcip.tdlib.adapter.service;

import io.emcip.tdlib.adapter.config.TdLibClient;
import jakarta.annotation.PostConstruct;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class TelegramUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateHandler.class);

    private final TdLibClient tdLibClient;
    private final TelegramEventPublisher eventPublisher;

    public TelegramUpdateHandler(TdLibClient tdLibClient, TelegramEventPublisher eventPublisher) {
        this.tdLibClient = tdLibClient;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    public void initializeHandlers() {
        tdLibClient.registerUpdateHandler("UpdateNewMessage", this::handleNewMessage);
        tdLibClient.registerUpdateHandler("UpdateMessageEdited", this::handleMessageEdited);
        tdLibClient.registerUpdateHandler("UpdateMessageDeleted", this::handleMessageDeleted);
        tdLibClient.registerUpdateHandler("UpdateChatTitle", this::handleChatTitle);
        tdLibClient.registerUpdateHandler("UpdateUser", this::handleUserUpdate);

        log.info("Telegram update handlers registered");
    }

    private void handleNewMessage(TdApi.Update update) {
        if (update instanceof TdApi.UpdateNewMessage newMessage) {
            TdApi.Message message = newMessage.message;

            log.debug(
                    "Received new message from chat {}: {}",
                    message.chatId,
                    message.content instanceof TdApi.MessageText text
                            ? text.text.text
                            : "[non-text]");

            Mono.fromRunnable(
                            () -> {
                                eventPublisher
                                        .publishMessage(message, newMessage)
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe(
                                                null,
                                                error ->
                                                        log.error(
                                                                "Error publishing message: {}",
                                                                error.getMessage()));
                            })
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
        }
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
        if (update instanceof TdApi.UpdateMessageDeleted deleted) {
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
