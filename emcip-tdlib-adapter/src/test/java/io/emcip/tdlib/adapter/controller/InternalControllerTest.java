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

        StepVerifier.create(controller.updateWatchedGroups(accountId, req)).verifyComplete();

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
