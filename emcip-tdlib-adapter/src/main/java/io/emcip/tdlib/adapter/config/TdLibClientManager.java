package io.emcip.tdlib.adapter.config;

import io.emcip.tdlib.adapter.service.TelegramUpdateHandler;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.drinkless.tdlib.TdApi;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TdLibClientManager {

    private final TdLibProperties properties;
    private final TelegramUpdateHandler updateHandler;
    private final ConcurrentMap<UUID, TdLibClient> clients = new ConcurrentHashMap<>();

    public TdLibClientManager(
            TdLibProperties properties, @Lazy TelegramUpdateHandler updateHandler) {
        this.properties = properties;
        this.updateHandler = updateHandler;
    }

    /**
     * Create and initialise a new TdLibClient for the given account. If a client already exists for
     * this account it is destroyed first. If {@code sessionString} is non-null and non-empty, TDLib
     * will attempt a silent session resume from its database directory.
     */
    public TdLibClient createAndInitialize(
            UUID accountId, int apiId, String apiHash, String phoneNumber, String sessionString) {
        log.debug(
                "[{}] Session string present: {}",
                accountId,
                sessionString != null && !sessionString.isEmpty());
        removeClient(accountId);
        String dbDir = properties.baseDirectory() + "/" + accountId;
        TdLibClient client =
                new TdLibClient(
                        accountId,
                        apiId,
                        apiHash,
                        phoneNumber,
                        dbDir,
                        properties,
                        this::onAuthStateChange);
        clients.put(accountId, client);
        client.initialize();
        updateHandler.registerOn(client);
        return client;
    }

    /** Register a pre-constructed client (used in tests). */
    public void registerClient(UUID accountId, TdLibClient client) {
        clients.put(accountId, client);
    }

    public TdLibClient getClient(UUID accountId) {
        TdLibClient client = clients.get(accountId);
        if (client == null) {
            throw new IllegalArgumentException(
                    "No TdLibClient registered for account " + accountId);
        }
        return client;
    }

    public boolean hasClient(UUID accountId) {
        return clients.containsKey(accountId);
    }

    public Map<UUID, TdLibClient> getClients() {
        return Collections.unmodifiableMap(clients);
    }

    public void removeClient(UUID accountId) {
        TdLibClient existing = clients.remove(accountId);
        if (existing != null) {
            try {
                existing.destroy();
            } catch (Exception e) {
                log.warn("[{}] Error destroying client: {}", accountId, e.getMessage());
            }
        }
    }

    private void onAuthStateChange(UUID accountId, TdApi.AuthorizationState state) {
        log.info("[{}] Auth state changed to: {}", accountId, state.getClass().getSimpleName());
        // Status persistence is handled by admin-api polling /status
        // Future: push status updates via Kafka or SSE
    }
}
