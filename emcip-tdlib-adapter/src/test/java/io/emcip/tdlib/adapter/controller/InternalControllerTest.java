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

@ExtendWith(MockitoExtension.class)
class InternalControllerTest {

    @Mock TdLibClientManager manager;
    @Mock TdLibClient client;
    InternalController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalController(manager);
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
}
