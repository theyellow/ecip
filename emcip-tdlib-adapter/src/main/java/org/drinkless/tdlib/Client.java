// Stub implementation - Replace with real TDLib Java bindings
// Download from: https://github.com/tdlib/td/tree/master/example/java
// Build instructions in TDLIB_SETUP.md

package org.drinkless.tdlib;

public final class Client {

    public static Client create(
            ResultHandler updateHandler,
            ExceptionHandler exceptionHandler,
            ExceptionHandler defaultExceptionHandler) {
        return new Client();
    }

    public void send(TdApi.Function query, ResultHandler handler) {
        // Stub - implement with real TDLib
    }

    public void close() {
        // Stub - implement with real TDLib
    }

    public static TdApi.Object execute(TdApi.Function query) {
        return new TdApi.Ok();
    }

    public interface ResultHandler {
        void onResult(TdApi.Object object);
    }

    public interface ExceptionHandler {
        void onException(Throwable e);
    }
}
