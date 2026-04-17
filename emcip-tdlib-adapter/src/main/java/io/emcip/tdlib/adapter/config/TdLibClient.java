package io.emcip.tdlib.adapter.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TdLibClient {

    private static final Logger log = LoggerFactory.getLogger(TdLibClient.class);

    private final TdLibProperties properties;
    private Client client;
    private volatile boolean isInitialized = false;
    private volatile boolean isAuthorized = false;
    private final ConcurrentMap<Long, CompletableFuture<TdApi.Object>> pendingRequests =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Consumer<TdApi.Update>> updateHandlers =
            new ConcurrentHashMap<>();

    private final Client.ResultHandler defaultHandler = this::handleResponse;
    private final Client.ExceptionHandler exceptionHandler = new DefaultExceptionHandler();

    public TdLibClient(TdLibProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        try {
            System.loadLibrary("tdjni");
        } catch (UnsatisfiedLinkError e) {
            log.warn("TDLib native library not found in java.library.path: {}", e.getMessage());
        }

        Client.execute(new TdApi.SetLogVerbosityLevel(properties.logVerbosityLevel()));

        client = Client.create(defaultHandler, exceptionHandler, null);
        isInitialized = true;

        log.info("TDLib client initialized");

        sendInitialParameters();
    }

    private void sendInitialParameters() {
        TdApi.SetTdlibParameters params = new TdApi.SetTdlibParameters();
        params.useTestDc = false;
        params.databaseDirectory = properties.databaseDirectory();
        params.filesDirectory = properties.filesDirectory();
        params.useFileDatabase = properties.useFileDatabase();
        params.useChatInfoDatabase = properties.useChatInfoDatabase();
        params.useMessageDatabase = properties.useMessageDatabase();
        params.useSecretChats = properties.useSecretChats();
        params.apiId = properties.apiId();
        params.apiHash = properties.apiHash();
        params.systemLanguageCode = "en";
        params.deviceModel = "Desktop";
        params.systemVersion = "Unknown";
        params.applicationVersion = "0.1.0";

        client.send(params, this::handleAuthorizationState);
    }

    private void handleAuthorizationState(TdApi.Object object) {
        if (object instanceof TdApi.AuthorizationState state) {
            handleAuthorizationStateUpdate(state);
        }
    }

    public void handleAuthorizationStateUpdate(TdApi.AuthorizationState state) {
        switch (state.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:
                sendInitialParameters();
                break;

            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR:
                log.info("Waiting for phone number...");
                if (properties.phoneNumber() != null && !properties.phoneNumber().isBlank()) {
                    setPhoneNumber(properties.phoneNumber());
                }
                break;

            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR:
                log.info("Waiting for authentication code...");
                break;

            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR:
                log.info("Waiting for 2FA password...");
                break;

            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                log.info("Authorization completed successfully");
                isAuthorized = true;
                break;

            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                log.info("Logging out...");
                isAuthorized = false;
                break;

            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                log.info("Closing...");
                break;

            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                log.info("Closed");
                isInitialized = false;
                break;

            default:
                log.warn("Unknown authorization state: {}", state.getClass().getSimpleName());
        }
    }

    public void setPhoneNumber(String phoneNumber) {
        client.send(
                new TdApi.SetAuthenticationPhoneNumber(phoneNumber, null),
                result -> {
                    if (result instanceof TdApi.Error error) {
                        log.error("Failed to set phone number: {} - {}", error.code, error.message);
                    } else {
                        log.info("Phone number set successfully");
                    }
                });
    }

    public void setAuthenticationCode(String code) {
        client.send(
                new TdApi.CheckAuthenticationCode(code),
                result -> {
                    if (result instanceof TdApi.Error error) {
                        log.error("Failed to check code: {} - {}", error.code, error.message);
                    } else {
                        log.info("Code verified successfully");
                    }
                });
    }

    public void setPassword(String password) {
        client.send(
                new TdApi.CheckAuthenticationPassword(password),
                result -> {
                    if (result instanceof TdApi.Error error) {
                        log.error("Failed to check password: {} - {}", error.code, error.message);
                    } else {
                        log.info("Password verified successfully");
                    }
                });
    }

    public void logout() {
        client.send(new TdApi.LogOut(), result -> log.info("Logout request sent"));
    }

    private void handleResponse(TdApi.Object object) {
        if (object instanceof TdApi.Update update) {
            handleUpdate(update);
        } else {
            log.debug("Received non-update response: {}", object.getClass().getSimpleName());
        }
    }

    private void handleUpdate(TdApi.Update update) {
        String updateType = update.getClass().getSimpleName();

        if (update instanceof TdApi.UpdateAuthorizationState authState) {
            handleAuthorizationStateUpdate(authState.authorizationState);
        }

        Consumer<TdApi.Update> handler = updateHandlers.get(updateType);
        if (handler != null) {
            try {
                handler.accept(update);
            } catch (Exception e) {
                log.error("Error handling update {}: {}", updateType, e.getMessage(), e);
            }
        } else {
            log.debug("No handler for update type: {}", updateType);
        }
    }

    public void registerUpdateHandler(String updateType, Consumer<TdApi.Update> handler) {
        updateHandlers.put(updateType, handler);
        log.info("Registered handler for update type: {}", updateType);
    }

    public void unregisterUpdateHandler(String updateType) {
        updateHandlers.remove(updateType);
        log.info("Unregistered handler for update type: {}", updateType);
    }

    public void sendRequest(TdApi.Function query, Client.ResultHandler handler) {
        if (!isInitialized || client == null) {
            throw new IllegalStateException("TDLib client not initialized");
        }
        client.send(query, handler);
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public boolean isAuthorized() {
        return isAuthorized;
    }

    @PreDestroy
    public void destroy() {
        log.info("Shutting down TDLib client...");
        if (isAuthorized) {
            logout();
        }
        if (client != null) {
            client.close();
        }
    }

    private static class DefaultExceptionHandler implements Client.ExceptionHandler {
        @Override
        public void onException(Throwable e) {
            LoggerFactory.getLogger(TdLibClient.class)
                    .error("TDLib exception: {}", e.getMessage(), e);
        }
    }
}
