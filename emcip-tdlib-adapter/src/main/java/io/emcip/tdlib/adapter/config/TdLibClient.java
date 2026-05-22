package io.emcip.tdlib.adapter.config;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TdLibClient {

    private static final Logger log = LoggerFactory.getLogger(TdLibClient.class);

    private final UUID accountId;
    private final int apiId;
    private final String apiHash;
    private final String phoneNumber;
    private final String databaseDirectory;
    private final TdLibProperties properties;
    private final BiConsumer<UUID, TdApi.AuthorizationState> authStateCallback;

    private Client client;
    private volatile boolean initialized = false;
    private volatile boolean authorized = false;
    private volatile boolean awaitingPassword = false;
    private volatile String lastError = null;

    private final ConcurrentMap<String, Consumer<TdApi.Update>> updateHandlers =
            new ConcurrentHashMap<>();

    public TdLibClient(
            UUID accountId,
            int apiId,
            String apiHash,
            String phoneNumber,
            String databaseDirectory,
            TdLibProperties properties,
            BiConsumer<UUID, TdApi.AuthorizationState> authStateCallback) {
        this.accountId = accountId;
        this.apiId = apiId;
        this.apiHash = apiHash;
        this.phoneNumber = phoneNumber;
        this.databaseDirectory = databaseDirectory;
        this.properties = properties;
        this.authStateCallback = authStateCallback;
    }

    public void initialize() {
        try {
            System.loadLibrary("tdjni");
        } catch (UnsatisfiedLinkError e) {
            log.warn("[{}] TDLib native library not found: {}", accountId, e.getMessage());
        }

        try {
            Client.execute(new TdApi.SetLogVerbosityLevel(properties.logVerbosityLevel()));
        } catch (Client.ExecutionException e) {
            log.warn("[{}] Failed to set TDLib log verbosity: {}", accountId, e.getMessage());
        }

        client = Client.create(this::handleResponse, new DefaultExceptionHandler(accountId), null);
        initialized = true;
        log.info("[{}] TDLib client initialized", accountId);
    }

    private void sendInitialParameters() {
        TdApi.SetTdlibParameters params = new TdApi.SetTdlibParameters();
        params.useTestDc = false;
        params.databaseDirectory = databaseDirectory;
        params.filesDirectory = databaseDirectory + "/files";
        params.useFileDatabase = properties.useFileDatabase();
        params.useChatInfoDatabase = properties.useChatInfoDatabase();
        params.useMessageDatabase = properties.useMessageDatabase();
        params.useSecretChats = properties.useSecretChats();
        params.apiId = apiId;
        params.apiHash = apiHash;
        params.systemLanguageCode = "en";
        params.deviceModel = "Desktop";
        params.systemVersion = "Unknown";
        params.applicationVersion = "0.1.0";
        client.send(params, result -> log.debug("[{}] TDLib params sent", accountId));
    }

    public void handleAuthorizationStateUpdate(TdApi.AuthorizationState state) {
        log.info("[{}] Auth state: {}", accountId, state.getClass().getSimpleName());
        switch (state.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> sendInitialParameters();
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                if (phoneNumber != null && !phoneNumber.isBlank()) {
                    setPhoneNumber(phoneNumber);
                }
            }
            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> awaitingPassword = false;
            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> awaitingPassword = true;
            case TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                authorized = true;
                awaitingPassword = false;
                lastError = null;
            }
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR -> authorized = false;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
                initialized = false;
                authorized = false;
                awaitingPassword = false;
            }
            default ->
                    log.debug(
                            "[{}] Unhandled auth state: {}",
                            accountId,
                            state.getClass().getSimpleName());
        }
        authStateCallback.accept(accountId, state);
    }

    public void setPhoneNumber(String phone) {
        client.send(
                new TdApi.SetAuthenticationPhoneNumber(phone, null),
                result -> {
                    if (result instanceof TdApi.Error error) {
                        lastError = error.message;
                        log.error("[{}] Phone error {}: {}", accountId, error.code, error.message);
                    }
                });
    }

    public void setAuthenticationCode(String code) {
        client.send(
                new TdApi.CheckAuthenticationCode(code),
                result -> {
                    if (result instanceof TdApi.Error error) {
                        lastError = error.message;
                        log.error("[{}] Code error {}: {}", accountId, error.code, error.message);
                    } else {
                        lastError = null;
                    }
                });
    }

    public void setPassword(String password) {
        client.send(
                new TdApi.CheckAuthenticationPassword(password),
                result -> {
                    if (result instanceof TdApi.Error error) {
                        lastError = error.message;
                        log.error(
                                "[{}] Password error {}: {}", accountId, error.code, error.message);
                    } else {
                        lastError = null;
                    }
                });
    }

    public void logout() {
        client.send(new TdApi.LogOut(), result -> log.info("[{}] Logout sent", accountId));
    }

    public void registerUpdateHandler(String updateType, Consumer<TdApi.Update> handler) {
        updateHandlers.put(updateType, handler);
    }

    public void unregisterUpdateHandler(String updateType) {
        updateHandlers.remove(updateType);
    }

    public void sendRequest(TdApi.Function<?> query, Client.ResultHandler handler) {
        if (!initialized || client == null) {
            throw new IllegalStateException(
                    "TDLib client not initialized for account " + accountId);
        }
        client.send(query, handler);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isAuthorized() {
        return authorized;
    }

    public boolean isAwaitingPassword() {
        return awaitingPassword;
    }

    public String getLastError() {
        return lastError;
    }

    public UUID getAccountId() {
        return accountId;
    }

    private void handleResponse(TdApi.Object object) {
        if (object instanceof TdApi.Update update) {
            if (update instanceof TdApi.UpdateAuthorizationState s) {
                handleAuthorizationStateUpdate(s.authorizationState);
            }
            Consumer<TdApi.Update> handler = updateHandlers.get(update.getClass().getSimpleName());
            if (handler != null) {
                try {
                    handler.accept(update);
                } catch (Exception e) {
                    log.error("[{}] Handler error: {}", accountId, e.getMessage(), e);
                }
            }
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("[{}] Destroying TDLib client", accountId);
        if (client != null) client.send(new TdApi.Close(), null);
    }

    private record DefaultExceptionHandler(UUID accountId) implements Client.ExceptionHandler {
        @Override
        public void onException(Throwable e) {
            LoggerFactory.getLogger(TdLibClient.class)
                    .error("[{}] TDLib exception: {}", accountId, e.getMessage(), e);
        }
    }
}
