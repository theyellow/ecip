package io.emcip.tdlib.adapter.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.emcip.tdlib.adapter.config.TdLibClientManager;
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
    @Mock TdLibClientManager manager;
    @Mock ProfileCacheService profileCache;

    ConcurrentMap<UUID, Set<Long>> watchedChatIds;
    TelegramUpdateHandler handler;

    @BeforeEach
    void setUp() {
        watchedChatIds = new ConcurrentHashMap<>();
        handler = new TelegramUpdateHandler(publisher, watchedChatIds, manager, profileCache);
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
        when(publisher.publishMessage(any(), any(), any())).thenReturn(Mono.empty());

        handler.handleNewMessage(accountId, makeUpdate(111L, 1L));

        verify(publisher).publishMessage(any(), any(), any());
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

    @Test
    void handleUserUpdate_populatesProfileCache() {
        TdApi.User user = new TdApi.User();
        user.id = 42L;
        user.firstName = "John";
        user.lastName = "Doe";
        setUsername(user, "johndoe");
        TdApi.UpdateUser update = new TdApi.UpdateUser();
        update.user = user;
        when(publisher.publishUpdate(any())).thenReturn(Mono.empty());

        handler.handleUserUpdate(update);

        verify(profileCache).putUser(42L, "John Doe", "johndoe");
    }

    @Test
    void handleChatTitle_populatesProfileCache() {
        TdApi.UpdateChatTitle update = new TdApi.UpdateChatTitle();
        update.chatId = -100123L;
        update.title = "New Group Name";
        when(publisher.publishUpdate(any())).thenReturn(Mono.empty());

        handler.handleChatTitle(update);

        verify(profileCache).putChat(-100123L, "New Group Name");
    }

    /** Set username on TdApi.User — works with both the local stub and real TDLib API. */
    private static void setUsername(TdApi.User user, String username) {
        try {
            // Stub: user.username
            var field = TdApi.User.class.getField("username");
            field.set(user, username);
        } catch (NoSuchFieldException e) {
            // Real TDLib: user.usernames.editableUsername
            try {
                var unField = TdApi.User.class.getField("usernames");
                var usernamesClass = Class.forName("org.drinkless.tdlib.TdApi$Usernames");
                Object usernames = usernamesClass.getDeclaredConstructor().newInstance();
                usernamesClass.getField("editableUsername").set(usernames, username);
                unField.set(user, usernames);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException("Cannot set username on TdApi.User", ex);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot set username on TdApi.User", e);
        }
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
