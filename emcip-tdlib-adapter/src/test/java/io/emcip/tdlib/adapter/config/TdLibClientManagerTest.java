package io.emcip.tdlib.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.emcip.tdlib.adapter.service.TelegramUpdateHandler;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TdLibClientManagerTest {

    private TdLibProperties properties;
    private TdLibClientManager manager;

    @BeforeEach
    void setUp() {
        properties = new TdLibProperties("tdlib-test", true, true, true, false, 1);
        manager = new TdLibClientManager(properties, mock(TelegramUpdateHandler.class));
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

    private TdLibClient stubClient(UUID id) {
        // Construct without initialising (no TDLib native library needed)
        return new TdLibClient(
                id, 0, "hash", "+49000", "tdlib-test/" + id, properties, (a, s) -> {});
    }
}
