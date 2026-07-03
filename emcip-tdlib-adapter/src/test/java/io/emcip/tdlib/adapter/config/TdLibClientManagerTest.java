package io.emcip.tdlib.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.emcip.tdlib.adapter.service.TelegramUpdateHandler;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TdLibClientManagerTest {

    private TdLibProperties properties;
    private TdLibClientManager manager;

    @BeforeEach
    void setUp() {
        properties = new TdLibProperties("tdlib-test", true, true, true, false, 1);
        manager =
                new TdLibClientManager(
                        properties,
                        mock(TelegramUpdateHandler.class),
                        new ConcurrentHashMap<>(),
                        30);
    }

    @Test
    void getClient_unknownId_throwsException() {
        assertThatThrownBy(() -> manager.getClient(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No TdLibClient");
    }

    @Test
    void removeClient_nonExistent_doesNotThrow() {
        manager.removeClient(UUID.randomUUID()); // should be a no-op
    }

    @Test
    void hasClient_afterRegister_returnsTrue() {
        UUID id = UUID.randomUUID();
        manager.registerClient(id, stubClient(id));
        assertThat(manager.hasClient(id)).isTrue();
    }

    @Test
    void hasClient_afterRemove_returnsFalse() {
        UUID id = UUID.randomUUID();
        manager.registerClient(id, stubClient(id));
        manager.removeClient(id);
        assertThat(manager.hasClient(id)).isFalse();
    }

    @Test
    void updateWatchedChats_storesSetForAccount() {
        UUID id = UUID.randomUUID();
        manager.updateWatchedChats(id, Set.of(111L, 222L), Set.of(), null);
        assertThat(manager.getWatchedChatIds(id)).containsExactlyInAnyOrder(111L, 222L);
    }

    @Test
    void updateWatchedChats_replacesExistingSet() {
        UUID id = UUID.randomUUID();
        manager.updateWatchedChats(id, Set.of(111L), Set.of(), null);
        manager.updateWatchedChats(id, Set.of(999L), Set.of(), null);
        assertThat(manager.getWatchedChatIds(id)).containsExactly(999L);
    }

    @Test
    void removeClient_clearsWatchedSet() {
        UUID id = UUID.randomUUID();
        manager.registerClient(id, stubClient(id));
        manager.updateWatchedChats(id, Set.of(111L), Set.of(), null);
        manager.removeClient(id);
        assertThat(manager.getWatchedChatIds(id)).isEmpty();
    }

    @Test
    void getWatchedChatIds_unknownAccount_returnsEmptySet() {
        assertThat(manager.getWatchedChatIds(UUID.randomUUID())).isEmpty();
    }

    @Test
    void getRateLimiter_sameApiId_returnsSameInstance() {
        var rl1 = manager.getOrCreateRateLimiter(12345);
        var rl2 = manager.getOrCreateRateLimiter(12345);
        assertThat(rl1).isSameAs(rl2);
    }

    @Test
    void getRateLimiter_differentApiId_returnsDifferentInstances() {
        var rl1 = manager.getOrCreateRateLimiter(11111);
        var rl2 = manager.getOrCreateRateLimiter(22222);
        assertThat(rl1).isNotSameAs(rl2);
    }

    private TdLibClient stubClient(UUID id) {
        // Construct without initialising (no TDLib native library needed)
        return new TdLibClient(
                id, 0, "hash", "+49000", "tdlib-test/" + id, properties, (a, s) -> {}, null);
    }
}
